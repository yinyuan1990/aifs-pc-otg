#include "rules.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

ShaperRule g_rules[WINSHAPER_MAX_RULES];
int        g_rule_count;

static void trim_crlf(char *s)
{
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r')) {
        s[n - 1] = '\0';
        n--;
    }
}

int rules_load(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "rules: cannot open %s\n", path);
        return -1;
    }

    /* Skip UTF-8 BOM if present */
    unsigned char bom[3];
    if (fread(bom, 1, 3, f) != 3 ||
        bom[0] != 0xEF || bom[1] != 0xBB || bom[2] != 0xBF)
        fseek(f, 0, SEEK_SET);

    char line[512];
    g_rule_count = 0;
    while (fgets(line, sizeof(line), f) && g_rule_count < WINSHAPER_MAX_RULES) {
        trim_crlf(line);
        if (line[0] == '\0' || line[0] == '#')
            continue;

        char exe[260];
        long long up = 0, dn = 0;
        if (sscanf_s(line, "%259s %I64d %I64d", exe, (unsigned)sizeof(exe), &up, &dn) != 3) {
            fprintf(stderr, "rules: bad line: %s\n", line);
            continue;
        }

        wchar_t wexe[260];
        int conv = MultiByteToWideChar(CP_UTF8, 0, exe, -1, wexe, 260);
        if (conv == 0) {
            fprintf(stderr, "rules: exe encoding: %s\n", exe);
            continue;
        }

        g_rules[g_rule_count].up_bps = up;
        g_rules[g_rule_count].dn_bps = dn;
        wcsncpy_s(g_rules[g_rule_count].exe, 260, wexe, _TRUNCATE);
        g_rule_count++;
    }

    fclose(f);
    if (g_rule_count == 0) {
        fprintf(stderr, "rules: no valid rules in %s\n", path);
        return -1;
    }
    return 0;
}
