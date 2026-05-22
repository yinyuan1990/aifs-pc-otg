#ifndef WINSHAPER_FLOWMAP_H
#define WINSHAPER_FLOWMAP_H

#include <windows.h>
#include <stdint.h>

#include "windivert.h"

void flowmap_init(void);
void flowmap_shutdown(void);
void flowmap_stop(void);

DWORD WINAPI flowmap_socket_thread(LPVOID arg);

/* Generic packet -> PID lookup (TCP or UDP, IPv4 or IPv6).
 * src_port / dst_port are in HOST byte order. */
BOOL flowmap_lookup_ipv4(int outbound, const WINDIVERT_IPHDR *ip,
                         UINT16 src_port, UINT16 dst_port, UINT8 proto,
                         DWORD *out_pid);

BOOL flowmap_lookup_ipv6(int outbound, const WINDIVERT_IPV6HDR *ip6,
                         UINT16 src_port, UINT16 dst_port, UINT8 proto,
                         DWORD *out_pid);

/* Legacy seed functions (no-op, kept for API compatibility) */
void flowmap_seed_ipv4_tcp(UINT32 localAddr, UINT16 localPort,
                           UINT32 remoteAddr, UINT16 remotePort, DWORD pid);
void flowmap_seed_ipv4_udp(UINT32 localAddr, UINT16 localPort, DWORD pid);

#endif
