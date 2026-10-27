/*
 * Which sectors make up a title, in playback order: the cells of its program chain(s), one angle
 * of each angle block, with the cells where chapters start. Also the title list as JSON.
 */
#ifndef DR_PLAN_H
#define DR_PLAN_H

#include "dr_common.h"

#include <dvdread/dvd_reader.h>
#include <dvdread/ifo_types.h>

#define DR_TICKS_PER_SECOND 90000

typedef struct dr_plan_cell {
    uint32_t first_sector;
    uint32_t last_sector;
    int interleaved;
    /* 1-based chapter that starts with this cell, or 0. */
    int chapter;
    /* Playback time from the IFO, in 90 kHz ticks. */
    int64_t duration;
} dr_plan_cell;

typedef struct dr_plan {
    int title;
    int title_set;
    int angle;
    int chapter_count;
    int cell_count;
    dr_plan_cell *cells;
    uint64_t sector_count;
    int64_t duration;
    /* The VTS IFO and the first program chain, for stream attributes and the subpicture palette. */
    ifo_handle_t *vts;
    pgc_t *pgc;
} dr_plan;

/* Builds the plan of a 1-based title and angle. Returns 0, or -1 with error filled in. */
int dr_plan_build(dvd_reader_t *dvd, ifo_handle_t *vmg, int title, int angle, dr_plan *plan,
                  char *error, size_t error_size);

void dr_plan_free(dr_plan *plan);

/* The picture size the IFO gives a title set: 720, 704 or 352 wide, PAL or NTSC high. */
void dr_picture_size(const video_attr_t *video, int *width, int *height);

/* 90 kHz ticks of a BCD dvd_time_t; frame rate from its two high bits. */
int64_t dr_dvd_time_ticks(const dvd_time_t *time);

/* MPEG-PS stream id FFmpeg's demuxer gives an audio stream (AVStream.id), or -1. */
int dr_audio_stream_id(const audio_attr_t *attr, uint16_t control);

/* Subpicture stream id (0x20 + number) for the title's display aspect, or -1. */
int dr_subtitle_stream_id(const video_attr_t *video, uint32_t control);

/* The titles of the disc as JSON (caller frees), or NULL with error filled in. */
char *dr_titles_json(dvd_reader_t *dvd, const dr_logger *logger, char *error, size_t error_size);

#endif
