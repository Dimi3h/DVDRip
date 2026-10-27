/*
 * Unit tests of engine parts the test DVD cannot reach: the decoding times dr_order derives for
 * MediaCodec's frames, for encoders that reorder frames (B-frames), drop frames or change their
 * times, which the emulator's encoder never does. Exits non-zero on a failure.
 */
#include "dr_order.h"

#include <inttypes.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

static int failures;

__attribute__((format(printf, 2, 3)))
static void fail(const char *test, const char *format, ...) {
    printf("FAIL %s: ", test);
    va_list arguments;
    va_start(arguments, format);
    vprintf(format, arguments);
    va_end(arguments);
    printf("\n");
    failures++;
}

/* The frames that came out so far, whose decoding times must never decrease. */
typedef struct timeline {
    const char *test;
    int64_t last_dts;
    int first;
} timeline;

/* A frame comes out: its decoding time, which must not lie after its own time or before the last. */
static int64_t out(dr_order *order, timeline *line, int64_t pts) {
    int64_t dts = dr_order_out(order, pts);
    if (dts > pts) fail(line->test, "the frame at %" PRId64 " decodes later, at %" PRId64, pts, dts);
    if (!line->first && dts < line->last_dts) {
        fail(line->test, "the frame at %" PRId64 " decodes at %" PRId64 ", before the one before at %" PRId64, pts, dts,
             line->last_dts);
    }
    line->first = 0;
    line->last_dts = dts;
    return dts;
}

static void comes_out(dr_order *order, timeline *line, int64_t pts, int64_t expected) {
    int64_t dts = out(order, line, pts);
    if (dts != expected) fail(line->test, "the frame at %" PRId64 " decodes at %" PRId64 ", not at %" PRId64, pts, dts, expected);
}

static void in(dr_order *order, int64_t pts) {
    if (dr_order_in(order, pts) < 0) {
        printf("FAIL out of memory\n");
        exit(1);
    }
}

static void at_most_waiting(const timeline *line, size_t most, size_t limit) {
    if (most > limit) fail(line->test, "%zu frames waited at once, more than %zu", most, limit);
}

/* Frames come out in the order they went in: each decodes at its own time. */
static void in_order(void) {
    dr_order order = {0};
    timeline line = {"in order", 0, 1};
    in(&order, 0);
    in(&order, 40);
    comes_out(&order, &line, 0, 0);
    in(&order, 80);
    comes_out(&order, &line, 40, 40);
    comes_out(&order, &line, 80, 80);
    at_most_waiting(&line, order.count, 0);
    dr_order_free(&order);
}

/* I0 B40 B80 P120 go in, I0 P120 B40 B80 come out: P decodes before the B-frames it precedes. */
static void b_frames(void) {
    dr_order order = {0};
    timeline line = {"B-frames", 0, 1};
    for (int64_t pts = 0; pts <= 120; pts += 40) in(&order, pts);
    comes_out(&order, &line, 0, 0);
    comes_out(&order, &line, 120, 40);
    comes_out(&order, &line, 40, 40);
    comes_out(&order, &line, 80, 80);
    /* And the next group the same way, while frames keep going in. */
    in(&order, 160);
    in(&order, 200);
    in(&order, 240);
    comes_out(&order, &line, 240, 160);
    in(&order, 280);
    comes_out(&order, &line, 160, 160);
    comes_out(&order, &line, 200, 200);
    comes_out(&order, &line, 280, 280);
    at_most_waiting(&line, order.count, 0);
    dr_order_free(&order);
}

/* An encoder that drops every tenth frame: the times hold, and the dropped frames are let go. */
static void dropped_frames(void) {
    dr_order order = {0};
    timeline line = {"dropped frames", 0, 1};
    size_t most = 0;
    for (int64_t frame = 0; frame < 10000; frame++) {
        in(&order, frame * 40);
        /* An encoder keeps a few frames before the first comes out. */
        int64_t leaving = frame - 4;
        if (leaving >= 0 && leaving % 10 != 3) out(&order, &line, leaving * 40);
        if (order.count > most) most = order.count;
    }
    at_most_waiting(&line, most, 40);
    dr_order_free(&order);
}

/* An encoder that shifts every time by 5: the frames up to the time that came out are let go. */
static void changed_times(void) {
    dr_order order = {0};
    timeline line = {"changed times", 0, 1};
    size_t most = 0;
    for (int64_t frame = 0; frame < 10000; frame++) {
        in(&order, frame * 40);
        if (frame >= 2) out(&order, &line, (frame - 2) * 40 + 5);
        if (order.count > most) most = order.count;
    }
    at_most_waiting(&line, most, 3);
    dr_order_free(&order);
}

int main(void) {
    in_order();
    b_frames();
    dropped_frames();
    changed_times();
    if (failures > 0) return 1;
    printf("ok   engine unit tests\n");
    return 0;
}
