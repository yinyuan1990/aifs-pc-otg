/*
 * TCP + UDP bandwidth shaping at WinDivert NETWORK layer + SOCKET layer PID map (flowmap).
 * Supports IPv4 and IPv6.
 */

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

#include "windivert.h"
#include "flowmap.h"
#include "rules.h"
#include "procutil.h"
#include "shape.h"

#define MAXBUF          WINDIVERT_MTU_MAX
#define MAX_QUEUE_BYTES (4 * 1024 * 1024)

/* Diagnostic logging — writes to zjc_shaper.log next to the exe */
static wchar_t g_shapeLogPath[MAX_PATH];
static LONG    g_shapeLogReady = 0;

void shape_set_log_path(const wchar_t *path)
{
    lstrcpyW(g_shapeLogPath, path);
    InterlockedExchange(&g_shapeLogReady, 1);
}

static void shape_log(const char *fmt, ...)
{
    if (!g_shapeLogReady) return;
    HANDLE hf = CreateFileW(g_shapeLogPath, FILE_APPEND_DATA, FILE_SHARE_READ,
        NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) return;
    SYSTEMTIME st;
    GetLocalTime(&st);
    char line[1200];
    int off = wsprintfA(line, "[%04d-%02d-%02d %02d:%02d:%02d] ",
        st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
    va_list ap;
    va_start(ap, fmt);
    off += wvsprintfA(line + off, fmt, ap);
    va_end(ap);
    lstrcpyA(line + off, "\r\n");
    DWORD w;
    WriteFile(hf, line, (DWORD)lstrlenA(line), &w, NULL);
    CloseHandle(hf);
}

typedef struct PktQ {
    UINT8            *data;
    UINT              plen;
    WINDIVERT_ADDRESS addr;
    struct PktQ      *next;
} PktQ;

typedef struct {
    double            tokens;
    LARGE_INTEGER     last_qpc;
} Bucket;

typedef struct {
    Bucket  up, dn;
    PktQ   *uq_head, *uq_tail;
    PktQ   *dq_head, *dq_tail;
    size_t  uq_bytes, dq_bytes;
} RuleState;

static LARGE_INTEGER  g_freq;
static RuleState      g_st[WINSHAPER_MAX_RULES];
static HANDLE         g_net = INVALID_HANDLE_VALUE;
static volatile LONG  g_stop_net = 0;

static void bucket_refill(Bucket *b, int64_t rate_bps)
{
    if (rate_bps <= 0)
        return;
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    if (b->last_qpc.QuadPart == 0) {
        b->last_qpc = now;
        b->tokens = (double)rate_bps;
        return;
    }
    double dt = (double)(now.QuadPart - b->last_qpc.QuadPart) / (double)g_freq.QuadPart;
    b->last_qpc = now;
    b->tokens += dt * (double)rate_bps;
    double cap = (double)rate_bps * 2.0;
    if (b->tokens > cap)
        b->tokens = cap;
}

static BOOL bucket_try(Bucket *b, int64_t rate_bps, UINT nbytes)
{
    if (rate_bps <= 0)
        return TRUE;
    bucket_refill(b, rate_bps);
    if (b->tokens >= (double)nbytes) {
        b->tokens -= (double)nbytes;
        return TRUE;
    }
    return FALSE;
}

static void pkt_forward(HANDLE h, UINT8 *pkt, UINT plen, WINDIVERT_ADDRESS *addr)
{
    WinDivertSend(h, pkt, plen, NULL, addr);
}

static BOOL pkt_enqueue(RuleState *s, int outbound, UINT8 *pkt, UINT plen, WINDIVERT_ADDRESS *addr)
{
    size_t *acc = outbound ? &s->uq_bytes : &s->dq_bytes;
    PktQ **head = outbound ? &s->uq_head : &s->dq_head;
    PktQ **tail = outbound ? &s->uq_tail : &s->dq_tail;

    if (*acc + plen > MAX_QUEUE_BYTES)
        return FALSE;

    PktQ *q = (PktQ *)malloc(sizeof(PktQ));
    UINT8 *copy = (UINT8 *)malloc(plen);
    if (!q || !copy) {
        free(copy);
        free(q);
        return FALSE;
    }
    memcpy(copy, pkt, plen);
    q->data = copy;
    q->plen = plen;
    memcpy(&q->addr, addr, sizeof(*addr));
    q->next = NULL;
    *acc += plen;
    if (*tail) {
        (*tail)->next = q;
        *tail = q;
    } else {
        *head = *tail = q;
    }
    return TRUE;
}

static void drain_rule(HANDLE h, int ri, int budget)
{
    RuleState *s = &g_st[ri];
    int64_t up = g_rules[ri].up_bps;
    int64_t dn = g_rules[ri].dn_bps;

    while (budget-- > 0) {
        if (s->uq_head && bucket_try(&s->up, up, s->uq_head->plen)) {
            PktQ *q = s->uq_head;
            s->uq_head = q->next;
            if (!s->uq_head)
                s->uq_tail = NULL;
            s->uq_bytes -= q->plen;
            WinDivertSend(h, q->data, q->plen, NULL, &q->addr);
            free(q->data);
            free(q);
            continue;
        }
        if (s->dq_head && bucket_try(&s->dn, dn, s->dq_head->plen)) {
            PktQ *q = s->dq_head;
            s->dq_head = q->next;
            if (!s->dq_head)
                s->dq_tail = NULL;
            s->dq_bytes -= q->plen;
            WinDivertSend(h, q->data, q->plen, NULL, &q->addr);
            free(q->data);
            free(q);
            continue;
        }
        break;
    }
}

static void drain_all(HANDLE h, int budget_per_rule)
{
    for (int i = 0; i < g_rule_count; i++)
        drain_rule(h, i, budget_per_rule);
}

void shape_stop(void)
{
    InterlockedExchange(&g_stop_net, 1);
    if (g_net != INVALID_HANDLE_VALUE)
        WinDivertShutdown(g_net, WINDIVERT_SHUTDOWN_BOTH);
}

void shape_free_queues(void)
{
    for (int i = 0; i < WINSHAPER_MAX_RULES; i++) {
        RuleState *s = &g_st[i];
        PktQ *q;
        while ((q = s->uq_head) != NULL) {
            s->uq_head = q->next;
            free(q->data);
            free(q);
        }
        s->uq_head = s->uq_tail = NULL;
        s->uq_bytes = 0;
        while ((q = s->dq_head) != NULL) {
            s->dq_head = q->next;
            free(q->data);
            free(q);
        }
        s->dq_head = s->dq_tail = NULL;
        s->dq_bytes = 0;
    }
}

int shape_run(void)
{
    QueryPerformanceFrequency(&g_freq);
    memset(g_st, 0, sizeof(g_st));
    InterlockedExchange(&g_stop_net, 0);

    g_net = WinDivertOpen("tcp or udp", WINDIVERT_LAYER_NETWORK, 0, 0);
    if (g_net == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        shape_log("WinDivertOpen(NETWORK) FAILED err=%lu", err);
        return -1;
    }
    shape_log("WinDivertOpen(NETWORK) OK");

    WinDivertSetParam(g_net, WINDIVERT_PARAM_QUEUE_LENGTH, 8192);
    WinDivertSetParam(g_net, WINDIVERT_PARAM_QUEUE_TIME, 8000);

    UINT64 stat_total = 0, stat_no_pid = 0, stat_no_rule = 0, stat_shaped = 0, stat_passed = 0;
    DWORD  stat_tick = GetTickCount();

    UINT8 pkt[MAXBUF];
    while (InterlockedCompareExchange(&g_stop_net, 0, 0) == 0) {
        UINT plen = 0;
        WINDIVERT_ADDRESS addr;

        if (!WinDivertRecv(g_net, pkt, sizeof(pkt), &plen, &addr)) {
            DWORD e = GetLastError();
            if (e == ERROR_INVALID_HANDLE || e == ERROR_OPERATION_ABORTED)
                break;
            continue;
        }

        if (plen == 0)
            continue;

        stat_total++;

        /* Periodic stats every 5 seconds */
        DWORD now_tick = GetTickCount();
        if (now_tick - stat_tick >= 5000) {
            shape_log("STATS: total=%I64u no_pid=%I64u no_rule=%I64u shaped=%I64u passed=%I64u",
                stat_total, stat_no_pid, stat_no_rule, stat_shaped, stat_passed);
            stat_tick = now_tick;
        }

        PWINDIVERT_IPHDR   ip  = NULL;
        PWINDIVERT_IPV6HDR ip6 = NULL;
        PWINDIVERT_TCPHDR  tcp = NULL;
        PWINDIVERT_UDPHDR  udp = NULL;
        UINT8 proto = 0;

        if (!WinDivertHelperParsePacket(pkt, plen, &ip, &ip6, &proto,
                NULL, NULL, &tcp, &udp, NULL, NULL, NULL, NULL)) {
            pkt_forward(g_net, pkt, plen, &addr);
            drain_all(g_net, 32);
            continue;
        }

        if (!tcp && !udp) {
            pkt_forward(g_net, pkt, plen, &addr);
            drain_all(g_net, 32);
            continue;
        }

        UINT16 src_port, dst_port;
        if (tcp) {
            src_port = WinDivertHelperNtohs(tcp->SrcPort);
            dst_port = WinDivertHelperNtohs(tcp->DstPort);
        } else {
            src_port = WinDivertHelperNtohs(udp->SrcPort);
            dst_port = WinDivertHelperNtohs(udp->DstPort);
        }

        int outbound = addr.Outbound ? 1 : 0;
        DWORD pid = 0;
        BOOL found = FALSE;

        if (ip) {
            found = flowmap_lookup_ipv4(outbound, ip, src_port, dst_port, proto, &pid);
        } else if (ip6) {
            found = flowmap_lookup_ipv6(outbound, ip6, src_port, dst_port, proto, &pid);
        }

        if (!found) {
            stat_no_pid++;
            pkt_forward(g_net, pkt, plen, &addr);
            drain_all(g_net, 32);
            continue;
        }

        int ri = rules_match_pid(pid);
        if (ri < 0) {
            stat_no_rule++;
            pkt_forward(g_net, pkt, plen, &addr);
            drain_all(g_net, 32);
            continue;
        }

        int64_t lim = outbound ? g_rules[ri].up_bps : g_rules[ri].dn_bps;
        RuleState *rs = &g_st[ri];

        if (lim <= 0) {
            stat_passed++;
            pkt_forward(g_net, pkt, plen, &addr);
            drain_all(g_net, 32);
            continue;
        }

        stat_shaped++;
        Bucket *B = outbound ? &rs->up : &rs->dn;
        if (bucket_try(B, lim, plen)) {
            pkt_forward(g_net, pkt, plen, &addr);
        } else {
            if (!pkt_enqueue(rs, outbound, pkt, plen, &addr))
                pkt_forward(g_net, pkt, plen, &addr);
        }
        drain_all(g_net, 64);
    }

    shape_log("FINAL: total=%I64u no_pid=%I64u no_rule=%I64u shaped=%I64u passed=%I64u",
        stat_total, stat_no_pid, stat_no_rule, stat_shaped, stat_passed);

    WinDivertClose(g_net);
    g_net = INVALID_HANDLE_VALUE;
    return 0;
}
