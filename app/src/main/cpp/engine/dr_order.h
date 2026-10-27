/*
 * Decoding times for an encoder that tells only presentation times, as MediaCodec does. A frame
 * that comes out decodes at the time of the earliest frame still to come out: without B-frames
 * that is its own time; with them it never lies after it, nor before the decoding time of the
 * frame before, as the muxer needs. Frames go in at increasing times.
 */
#ifndef DR_ORDER_H
#define DR_ORDER_H

#include <stddef.h>
#include <stdint.h>

typedef struct dr_order_frame {
    int64_t pts;
    int out;
} dr_order_frame;

/* Zeroed, it is empty. */
typedef struct dr_order {
    /* The frames in the encoder, in the order they went in, which is the order of their times. */
    dr_order_frame *frames;
    size_t count;
    size_t capacity;
} dr_order;

/* A frame goes into the encoder. 0, or AVERROR(ENOMEM). */
int dr_order_in(dr_order *order, int64_t pts);

/* The decoding time of the frame at pts, which just came out. */
int64_t dr_order_out(dr_order *order, int64_t pts);

void dr_order_free(dr_order *order);

#endif
