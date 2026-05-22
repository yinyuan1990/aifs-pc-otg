/*
 * Tuple -> PID map built by periodically scanning GetExtendedTcpTable/UdpTable.
 * Works from any session (including SYSTEM service in Session 0).
 *
 * TCP:  full 5-tuple match (local/remote addr+port, proto)
 * UDP:  local-port-only match (system UDP table has no remote info)
 */

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#include <tcpmib.h>
#include <udpmib.h>

#include "flowmap.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#pragma comment(lib, "iphlpapi.lib")

#define FLOWMAP_BUCKETS 8192

/* --- TCP: full 5-tuple map --- */

typedef struct FNode {
    UINT32            local[4];
    UINT32            remote[4];
    UINT16            local_port;
    UINT16            remote_port;
    UINT8             proto;
    DWORD             pid;
    struct FNode     *next;
} FNode;

static FNode         *g_buckets[FLOWMAP_BUCKETS];

/* --- UDP: local-port-only map --- */

#define UDP_PORT_BUCKETS 4096

typedef struct UNode {
    UINT16            local_port;
    DWORD             pid;
    struct UNode     *next;
} UNode;

static UNode         *g_udp_buckets[UDP_PORT_BUCKETS];

static CRITICAL_SECTION g_lock;
static volatile LONG    g_stop = 0;

/* --- Hash functions --- */

static UINT32 hash_tuple(const UINT32 local[4], const UINT32 remote[4],
                         UINT16 lp, UINT16 rp, UINT8 proto)
{
    UINT32 h = (UINT32)proto * 0x9e3779b9u;
    for (int i = 0; i < 4; i++) {
        h ^= local[i];
        h = (h << 13) | (h >> 19);
        h ^= remote[i];
        h = (h << 7) | (h >> 25);
    }
    h ^= ((UINT32)lp << 16) ^ (UINT32)rp;
    return h % FLOWMAP_BUCKETS;
}

static UINT32 hash_udp_port(UINT16 port)
{
    return ((UINT32)port * 0x9e3779b9u) % UDP_PORT_BUCKETS;
}

/* --- IPv4-mapped-IPv6 helper --- */

static void v4_netword_to_mapped(UINT32 addr_be, UINT32 out[4])
{
    UINT8 *b = (UINT8 *)out;
    memset(out, 0, 16);
    b[10] = 0xff;
    b[11] = 0xff;
    memcpy(b + 12, &addr_be, 4);
}

/* --- Packet -> tuple helpers --- */

static void key_from_pkt_v4(int outbound, const WINDIVERT_IPHDR *ip,
                            UINT16 src_port, UINT16 dst_port,
                            UINT32 local[4], UINT32 remote[4],
                            UINT16 *lp, UINT16 *rp)
{
    if (outbound) {
        v4_netword_to_mapped(ip->SrcAddr, local);
        v4_netword_to_mapped(ip->DstAddr, remote);
        *lp = src_port;
        *rp = dst_port;
    } else {
        v4_netword_to_mapped(ip->DstAddr, local);
        v4_netword_to_mapped(ip->SrcAddr, remote);
        *lp = dst_port;
        *rp = src_port;
    }
}

static void key_from_pkt_v6(int outbound, const WINDIVERT_IPV6HDR *ip6,
                            UINT16 src_port, UINT16 dst_port,
                            UINT32 local[4], UINT32 remote[4],
                            UINT16 *lp, UINT16 *rp)
{
    if (outbound) {
        memcpy(local,  ip6->SrcAddr, 16);
        memcpy(remote, ip6->DstAddr, 16);
        *lp = src_port;
        *rp = dst_port;
    } else {
        memcpy(local,  ip6->DstAddr, 16);
        memcpy(remote, ip6->SrcAddr, 16);
        *lp = dst_port;
        *rp = src_port;
    }
}

/* --- TCP map operations --- */

static FNode *find_tcp_node(UINT32 h, const UINT32 local[4], const UINT32 remote[4],
                            UINT16 lp, UINT16 rp)
{
    for (FNode *n = g_buckets[h]; n; n = n->next) {
        if (n->local_port != lp || n->remote_port != rp) continue;
        if (memcmp(n->local, local, 16) != 0) continue;
        if (memcmp(n->remote, remote, 16) != 0) continue;
        return n;
    }
    return NULL;
}

static void tcp_upsert(UINT32 local[4], UINT32 remote[4],
                       UINT16 lp, UINT16 rp, DWORD pid)
{
    UINT32 h = hash_tuple(local, remote, lp, rp, IPPROTO_TCP);
    FNode *ex = find_tcp_node(h, local, remote, lp, rp);
    if (ex) { ex->pid = pid; return; }
    FNode *n = (FNode *)calloc(1, sizeof(FNode));
    if (!n) return;
    memcpy(n->local, local, 16);
    memcpy(n->remote, remote, 16);
    n->local_port = lp;
    n->remote_port = rp;
    n->proto = IPPROTO_TCP;
    n->pid = pid;
    n->next = g_buckets[h];
    g_buckets[h] = n;
}

/* --- UDP map operations (local port only) --- */

static UNode *find_udp_node(UINT32 h, UINT16 lp)
{
    for (UNode *n = g_udp_buckets[h]; n; n = n->next) {
        if (n->local_port == lp) return n;
    }
    return NULL;
}

static void udp_upsert(UINT16 lp, DWORD pid)
{
    UINT32 h = hash_udp_port(lp);
    UNode *ex = find_udp_node(h, lp);
    if (ex) { ex->pid = pid; return; }
    UNode *n = (UNode *)calloc(1, sizeof(UNode));
    if (!n) return;
    n->local_port = lp;
    n->pid = pid;
    n->next = g_udp_buckets[h];
    g_udp_buckets[h] = n;
}

/* --- Clear all --- */

static void map_clear_all(void)
{
    for (int i = 0; i < FLOWMAP_BUCKETS; i++) {
        FNode *n = g_buckets[i];
        while (n) { FNode *nx = n->next; free(n); n = nx; }
        g_buckets[i] = NULL;
    }
    for (int i = 0; i < UDP_PORT_BUCKETS; i++) {
        UNode *n = g_udp_buckets[i];
        while (n) { UNode *nx = n->next; free(n); n = nx; }
        g_udp_buckets[i] = NULL;
    }
}

/* Full refresh: clear old map, rebuild from system TCP/UDP tables. */
static void refresh_from_system_tables(void)
{
    EnterCriticalSection(&g_lock);
    map_clear_all();

    /* IPv4 TCP (ESTABLISHED only) */
    {
        ULONG sz = 0;
        GetExtendedTcpTable(NULL, &sz, FALSE, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
        if (sz) {
            MIB_TCPTABLE_OWNER_PID *tbl = (MIB_TCPTABLE_OWNER_PID *)malloc(sz);
            if (tbl && GetExtendedTcpTable(tbl, &sz, FALSE, AF_INET,
                    TCP_TABLE_OWNER_PID_ALL, 0) == NO_ERROR) {
                for (DWORD i = 0; i < tbl->dwNumEntries; i++) {
                    MIB_TCPROW_OWNER_PID *r = &tbl->table[i];
                    if (r->dwState != MIB_TCP_STATE_ESTAB) continue;
                    UINT32 local[4], remote[4];
                    v4_netword_to_mapped(r->dwLocalAddr, local);
                    v4_netword_to_mapped(r->dwRemoteAddr, remote);
                    UINT16 lp = (UINT16)ntohs((u_short)r->dwLocalPort);
                    UINT16 rp = (UINT16)ntohs((u_short)r->dwRemotePort);
                    tcp_upsert(local, remote, lp, rp, r->dwOwningPid);
                }
            }
            free(tbl);
        }
    }

    /* IPv4 UDP (local port → PID, no remote info available) */
    {
        ULONG sz = 0;
        GetExtendedUdpTable(NULL, &sz, FALSE, AF_INET, UDP_TABLE_OWNER_PID, 0);
        if (sz) {
            MIB_UDPTABLE_OWNER_PID *tbl = (MIB_UDPTABLE_OWNER_PID *)malloc(sz);
            if (tbl && GetExtendedUdpTable(tbl, &sz, FALSE, AF_INET,
                    UDP_TABLE_OWNER_PID, 0) == NO_ERROR) {
                for (DWORD i = 0; i < tbl->dwNumEntries; i++) {
                    MIB_UDPROW_OWNER_PID *r = &tbl->table[i];
                    UINT16 lp = (UINT16)ntohs((u_short)r->dwLocalPort);
                    udp_upsert(lp, r->dwOwningPid);
                }
            }
            free(tbl);
        }
    }

    /* IPv6 TCP (ESTABLISHED only) */
    {
        ULONG sz = 0;
        GetExtendedTcpTable(NULL, &sz, FALSE, AF_INET6, TCP_TABLE_OWNER_PID_ALL, 0);
        if (sz) {
            MIB_TCP6TABLE_OWNER_PID *tbl = (MIB_TCP6TABLE_OWNER_PID *)malloc(sz);
            if (tbl && GetExtendedTcpTable(tbl, &sz, FALSE, AF_INET6,
                    TCP_TABLE_OWNER_PID_ALL, 0) == NO_ERROR) {
                for (DWORD i = 0; i < tbl->dwNumEntries; i++) {
                    MIB_TCP6ROW_OWNER_PID *r = &tbl->table[i];
                    if (r->dwState != MIB_TCP_STATE_ESTAB) continue;
                    UINT32 local[4], remote[4];
                    memcpy(local, r->ucLocalAddr, 16);
                    memcpy(remote, r->ucRemoteAddr, 16);
                    UINT16 lp = (UINT16)ntohs((u_short)r->dwLocalPort);
                    UINT16 rp = (UINT16)ntohs((u_short)r->dwRemotePort);
                    tcp_upsert(local, remote, lp, rp, r->dwOwningPid);
                }
            }
            free(tbl);
        }
    }

    /* IPv6 UDP */
    {
        ULONG sz = 0;
        GetExtendedUdpTable(NULL, &sz, FALSE, AF_INET6, UDP_TABLE_OWNER_PID, 0);
        if (sz) {
            MIB_UDP6TABLE_OWNER_PID *tbl = (MIB_UDP6TABLE_OWNER_PID *)malloc(sz);
            if (tbl && GetExtendedUdpTable(tbl, &sz, FALSE, AF_INET6,
                    UDP_TABLE_OWNER_PID, 0) == NO_ERROR) {
                for (DWORD i = 0; i < tbl->dwNumEntries; i++) {
                    MIB_UDP6ROW_OWNER_PID *r = &tbl->table[i];
                    UINT16 lp = (UINT16)ntohs((u_short)r->dwLocalPort);
                    udp_upsert(lp, r->dwOwningPid);
                }
            }
            free(tbl);
        }
    }

    LeaveCriticalSection(&g_lock);
}

/* --- Public API --- */

void flowmap_init(void)
{
    memset(g_buckets, 0, sizeof(g_buckets));
    memset(g_udp_buckets, 0, sizeof(g_udp_buckets));
    InitializeCriticalSection(&g_lock);
    InterlockedExchange(&g_stop, 0);
}

void flowmap_shutdown(void)
{
    EnterCriticalSection(&g_lock);
    map_clear_all();
    LeaveCriticalSection(&g_lock);
    DeleteCriticalSection(&g_lock);
}

void flowmap_stop(void)
{
    InterlockedExchange(&g_stop, 1);
}

BOOL flowmap_lookup_ipv4(int outbound, const WINDIVERT_IPHDR *ip,
                         UINT16 src_port, UINT16 dst_port, UINT8 proto,
                         DWORD *out_pid)
{
    UINT32 local[4], remote[4];
    UINT16 lp, rp;
    key_from_pkt_v4(outbound, ip, src_port, dst_port, local, remote, &lp, &rp);

    EnterCriticalSection(&g_lock);

    if (proto == IPPROTO_TCP) {
        UINT32 h = hash_tuple(local, remote, lp, rp, IPPROTO_TCP);
        FNode *n = find_tcp_node(h, local, remote, lp, rp);
        DWORD pid = n ? n->pid : 0;
        LeaveCriticalSection(&g_lock);
        if (!pid) return FALSE;
        *out_pid = pid;
        return TRUE;
    }

    /* UDP: match by local port only */
    UINT32 h = hash_udp_port(lp);
    UNode *u = find_udp_node(h, lp);
    DWORD pid = u ? u->pid : 0;
    LeaveCriticalSection(&g_lock);
    if (!pid) return FALSE;
    *out_pid = pid;
    return TRUE;
}

BOOL flowmap_lookup_ipv6(int outbound, const WINDIVERT_IPV6HDR *ip6,
                         UINT16 src_port, UINT16 dst_port, UINT8 proto,
                         DWORD *out_pid)
{
    UINT32 local[4], remote[4];
    UINT16 lp, rp;
    key_from_pkt_v6(outbound, ip6, src_port, dst_port, local, remote, &lp, &rp);

    EnterCriticalSection(&g_lock);

    if (proto == IPPROTO_TCP) {
        UINT32 h = hash_tuple(local, remote, lp, rp, IPPROTO_TCP);
        FNode *n = find_tcp_node(h, local, remote, lp, rp);
        DWORD pid = n ? n->pid : 0;
        LeaveCriticalSection(&g_lock);
        if (!pid) return FALSE;
        *out_pid = pid;
        return TRUE;
    }

    UINT32 h = hash_udp_port(lp);
    UNode *u = find_udp_node(h, lp);
    DWORD pid = u ? u->pid : 0;
    LeaveCriticalSection(&g_lock);
    if (!pid) return FALSE;
    *out_pid = pid;
    return TRUE;
}

void flowmap_seed_ipv4_tcp(UINT32 localAddr, UINT16 localPort,
                           UINT32 remoteAddr, UINT16 remotePort, DWORD pid)
{
    (void)localAddr; (void)localPort; (void)remoteAddr; (void)remotePort; (void)pid;
}

void flowmap_seed_ipv4_udp(UINT32 localAddr, UINT16 localPort, DWORD pid)
{
    (void)localAddr; (void)localPort; (void)pid;
}

/* Periodically refresh the flowmap from system TCP/UDP tables (every 1s). */
DWORD WINAPI flowmap_socket_thread(LPVOID arg)
{
    (void)arg;
    while (InterlockedCompareExchange(&g_stop, 0, 0) == 0) {
        refresh_from_system_tables();
        for (int i = 0; i < 10 && InterlockedCompareExchange(&g_stop, 0, 0) == 0; i++)
            Sleep(100);
    }
    return 0;
}
