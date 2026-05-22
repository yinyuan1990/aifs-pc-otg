#ifndef WINSHAPER_SHAPE_H
#define WINSHAPER_SHAPE_H

#include <wchar.h>

void shape_set_log_path(const wchar_t *path);

/* Blocks until shape_stop(); frees driver queue on return. */
int shape_run(void);

void shape_stop(void);
void shape_free_queues(void);

#endif
