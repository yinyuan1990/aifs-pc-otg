#ifndef WINSHAPER_RULES_H
#define WINSHAPER_RULES_H

#include <stdint.h>
#include <wchar.h>

#define WINSHAPER_MAX_RULES 64

typedef struct {
    wchar_t  exe[260];     /* basename match, e.g. chrome.exe */
    int64_t  up_bps;       /* 0 = unlimited */
    int64_t  dn_bps;
} ShaperRule;

extern ShaperRule g_rules[WINSHAPER_MAX_RULES];
extern int        g_rule_count;

/* Load rules file: each line: exe_name upload_bps download_bps (bytes/sec, ASCII) */
int rules_load(const char *path);

#endif
