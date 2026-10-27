#include "dr_order.h"

#include <libavutil/error.h>
#include <stdlib.h>
#include <string.h>

/* How many later frames can overtake one, for B-frames: at most 16 in H.264 and HEVC. A frame
   overtaken by more was dropped by the encoder. */
#define MAX_REORDER 16

int dr_order_in(dr_order *order, int64_t pts) {
    if (order->count == order->capacity) {
        size_t capacity = order->capacity > 0 ? order->capacity * 2 : 64;
        dr_order_frame *grown = realloc(order->frames, capacity * sizeof(dr_order_frame));
        if (grown == NULL) return AVERROR(ENOMEM);
        order->frames = grown;
        order->capacity = capacity;
    }
    order->frames[order->count++] = (dr_order_frame) {pts, 0};
    return 0;
}

int64_t dr_order_out(dr_order *order, int64_t pts) {
    if (order->count == 0) return pts;
    int64_t dts = order->frames[0].pts;
    size_t found = 0;
    while (found < order->count && order->frames[found].pts != pts) found++;
    for (size_t i = 0; i < order->count; i++) {
        /* Frames overtaken by too many later ones were dropped, and an encoder that changed the
           time leaves the frames up to it behind: either way they will not come out any more. */
        int gone = found < order->count ? i == found || i + MAX_REORDER < found : order->frames[i].pts <= pts;
        if (gone) order->frames[i].out = 1;
    }
    size_t done = 0;
    while (done < order->count && order->frames[done].out) done++;
    memmove(order->frames, order->frames + done, (order->count - done) * sizeof(dr_order_frame));
    order->count -= done;
    return dts < pts ? dts : pts;
}

void dr_order_free(dr_order *order) {
    free(order->frames);
    *order = (dr_order) {0};
}
