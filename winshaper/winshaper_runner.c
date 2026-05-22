/*
 * Shared entry for standalone winshaper.exe and zjc_worker --zjc-shaper mode.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <windows.h>
#include <iphlpapi.h>
#include <tcpmib.h>
#include <udpmib.h>

#include "flowmap.h"
#include "rules.h"
#include "shape.h"
#include "procutil.h"
#include "winshaper_main.h"

#pragma comment(lib, "iphlpapi.lib")

static wchar_t g_shaperLogPath[MAX_PATH];

static void shaper_log(const char *fmt, ...)
{
    HANDLE hf = CreateFileW(g_shaperLogPath, FILE_APPEND_DATA, FILE_SHARE_READ,
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

static void build_log_path(const char *rulesPath)
{
    wchar_t wpath[MAX_PATH];
    MultiByteToWideChar(CP_UTF8, 0, rulesPath, -1, wpath, MAX_PATH);
    lstrcpyW(g_shaperLogPath, wpath);
    wchar_t *sl = wcsrchr(g_shaperLogPath, L'\\');
    if (!sl) sl = wcsrchr(g_shaperLogPath, L'/');
    if (sl) sl[1] = L'\0';
    else    g_shaperLogPath[0] = L'\0';
    lstrcatW(g_shaperLogPath, L"zjc_shaper.log");
}

/* Pre-populate flowmap with existing TCP/UDP connections so we can shape
   traffic on connections established before the shaper started. */
static void seed_existing_connections(void)
{
    int seeded = 0;

    /* IPv4 TCP */
    {
        ULONG sz = 0;
        GetExtendedTcpTable(NULL, &sz, FALSE, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
        MIB_TCPTABLE_OWNER_PID *tbl = (MIB_TCPTABLE_OWNER_PID *)malloc(sz);
        if (tbl && GetExtendedTcpTable(tbl, &sz, FALSE, AF_INET,
                TCP_TABLE_OWNER_PID_ALL, 0) == NO_ERROR) {
            for (DWORD i = 0; i < tbl->dwNumEntries; i++) {
                MIB_TCPROW_OWNER_PID *r = &tbl->table[i];
                if (r->dwState != MIB_TCP_STATE_ESTAB) continue;
                if (rules_match_pid(r->dwOwningPid) >= 0) {
                    flowmap_seed_ipv4_tcp(
                        r->dwLocalAddr, (UINT16)ntohs((u_short)r->dwLocalPort),
                        r->dwRemoteAddr, (UINT16)ntohs((u_short)r->dwRemotePort),
                        r->dwOwningPid);
                    seeded++;
                }
            }
        }
        free(tbl);
    }

    /* IPv4 UDP */
    {
        ULONG sz = 0;
        GetExtendedUdpTable(NULL, &sz, FALSE, AF_INET, UDP_TABLE_OWNER_PID, 0);
        MIB_UDPTABLE_OWNER_PID *tbl = (MIB_UDPTABLE_OWNER_PID *)malloc(sz);
        if (tbl && GetExtendedUdpTable(tbl, &sz, FALSE, AF_INET,
                UDP_TABLE_OWNER_PID, 0) == NO_ERROR) {
            for (DWORD i = 0; i < tbl->dwNumEntries; i++) {
                MIB_UDPROW_OWNER_PID *r = &tbl->table[i];
                if (rules_match_pid(r->dwOwningPid) >= 0) {
                    flowmap_seed_ipv4_udp(
                        r->dwLocalAddr, (UINT16)ntohs((u_short)r->dwLocalPort),
                        r->dwOwningPid);
                    seeded++;
                }
            }
        }
        free(tbl);
    }

    shaper_log("seed: pre-populated %d existing connections", seeded);
}

static BOOL WINAPI console_handler(DWORD t)
{
    if (t == CTRL_C_EVENT || t == CTRL_BREAK_EVENT) {
        shape_stop();
        flowmap_stop();
        return TRUE;
    }
    return FALSE;
}

int winshaper_main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr,
            "usage: winshaper <rules.txt>\n"
            "Each line: exe_basename upload_bytes_per_sec download_bytes_per_sec\n"
            "Example: chrome.exe 102400 512000\n"
            "Use 0 for unlimited on that direction.\n");
        return 1;
    }

    build_log_path(argv[1]);
    shaper_log("=== winshaper started, rules=%s ===", argv[1]);

    if (rules_load(argv[1]) != 0) {
        shaper_log("rules_load FAILED");
        return 1;
    }
    for (int i = 0; i < g_rule_count; i++) {
        char exeA[260];
        WideCharToMultiByte(CP_UTF8, 0, g_rules[i].exe, -1, exeA, 260, NULL, NULL);
        shaper_log("rule[%d]: exe=%s up=%I64d dn=%I64d", i, exeA,
            g_rules[i].up_bps, g_rules[i].dn_bps);
    }

    SetConsoleCtrlHandler(console_handler, TRUE);

    flowmap_init();
    HANDLE sock_th = CreateThread(NULL, 0, flowmap_socket_thread, NULL, 0, NULL);
    if (!sock_th) {
        shaper_log("CreateThread(socket) FAILED");
        flowmap_shutdown();
        return 1;
    }

    Sleep(400);

    seed_existing_connections();

    shape_set_log_path(g_shaperLogPath);
    shaper_log("entering shape_run ...");
    int rc = shape_run();
    shaper_log("shape_run returned %d", rc);

    if (rc != 0) {
        flowmap_stop();
        WaitForSingleObject(sock_th, 15000);
        CloseHandle(sock_th);
        shape_free_queues();
        flowmap_shutdown();
        return 1;
    }

    flowmap_stop();
    WaitForSingleObject(sock_th, INFINITE);
    CloseHandle(sock_th);

    shape_free_queues();
    flowmap_shutdown();
    shaper_log("=== winshaper exited normally ===");
    return 0;
}
