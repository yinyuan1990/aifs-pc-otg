#ifndef WINSHAPER_MAIN_H
#define WINSHAPER_MAIN_H

#ifdef __cplusplus
extern "C" {
#endif

/* argv[0] ignored; argv[1] = path to rules file. Returns 0 on normal exit. */
int winshaper_main(int argc, char **argv);

#ifdef __cplusplus
}
#endif

#endif
