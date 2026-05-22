#include "procutil.h"

#include <string.h>
#include <stdio.h>
#include <wchar.h>

BOOL proc_get_basename(DWORD pid, wchar_t *out, size_t max_chars)
{
    if (!out || max_chars < 2)
        return FALSE;
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h)
        return FALSE;
    DWORD sz = (DWORD)max_chars;
    BOOL ok = QueryFullProcessImageNameW(h, 0, out, &sz);
    CloseHandle(h);
    if (!ok)
        return FALSE;
    wchar_t *slash = wcsrchr(out, L'\\');
    if (slash)
        wmemmove(out, slash + 1, wcslen(slash + 1) + 1);
    return TRUE;
}

static BOOL exe_name_match(const wchar_t *sysName, const wchar_t *rule)
{
    if (_wcsicmp(sysName, rule) == 0)
        return TRUE;

    size_t rlen = wcslen(rule);
    if (rlen < 5 || _wcsicmp(rule + rlen - 4, L".exe") != 0) {
        wchar_t withExe[264];
        _snwprintf_s(withExe, 264, _TRUNCATE, L"%s.exe", rule);
        if (_wcsicmp(sysName, withExe) == 0)
            return TRUE;
    }

    size_t slen = wcslen(sysName);
    if (slen > 4 && _wcsicmp(sysName + slen - 4, L".exe") == 0) {
        wchar_t stripped[260];
        wcsncpy_s(stripped, 260, sysName, slen - 4);
        if (_wcsicmp(stripped, rule) == 0)
            return TRUE;
    }
    return FALSE;
}

int rules_match_pid(DWORD pid)
{
    wchar_t base[260];
    if (!proc_get_basename(pid, base, 260))
        return -1;
    for (int i = 0; i < g_rule_count; i++) {
        if (exe_name_match(base, g_rules[i].exe))
            return i;
    }
    return -1;
}
