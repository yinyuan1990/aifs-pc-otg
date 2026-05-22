#ifndef WINSHAPER_PROCUTIL_H
#define WINSHAPER_PROCUTIL_H

#include <windows.h>
#include <wchar.h>

#include "rules.h"

BOOL proc_get_basename(DWORD pid, wchar_t *out, size_t max_chars);

/* Returns rule index or -1 */
int rules_match_pid(DWORD pid);

#endif
