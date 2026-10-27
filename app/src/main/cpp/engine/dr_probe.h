/* Reads a finished Matroska file back, to check what a rip produced. */
#ifndef DR_PROBE_H
#define DR_PROBE_H

/*
 * Describes the file on fd (read from its start; the descriptor stays the caller's) as JSON:
 *   {"ok":true,"durationMs":…,"streams":[{"type":"video","codec":"mpeg2video",…}],"chapterStartsMs":[…]}
 *   {"ok":false,"message":"…"}
 */
char *dr_probe(int fd);

#endif
