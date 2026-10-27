/* Reads a finished Matroska file back, to check what a rip produced. */
#ifndef DR_PROBE_H
#define DR_PROBE_H

#include "dr_file.h"

/*
 * Describes the file (read through its read and size callbacks; it stays the caller's) as JSON:
 *   {"ok":true,"durationMs":…,"streams":[{"type":"video","codec":"mpeg2video",…}],"chapterStartsMs":[…],
 *    "tags":{"title":"…","DATE_RELEASED":"…",…}}
 *   {"ok":false,"message":"…"}
 */
char *dr_probe(const dr_file *file);

#endif
