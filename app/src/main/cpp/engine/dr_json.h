/* A small JSON writer for handing structured results to Kotlin (and to the host tests). */
#ifndef DR_JSON_H
#define DR_JSON_H

#include <stddef.h>

#define DR_JSON_MAX_DEPTH 32

typedef struct dr_json {
    char *data;
    size_t length;
    size_t capacity;
    int failed;
    int depth;
    int has_items[DR_JSON_MAX_DEPTH];
    int after_key;
} dr_json;

void dr_json_init(dr_json *json);
void dr_json_free(dr_json *json);
void dr_json_begin_object(dr_json *json);
void dr_json_end_object(dr_json *json);
void dr_json_begin_array(dr_json *json);
void dr_json_end_array(dr_json *json);
void dr_json_key(dr_json *json, const char *key);
/* NULL writes null. Invalid UTF-8 is replaced, so the output always parses. */
void dr_json_string(dr_json *json, const char *value);
void dr_json_int(dr_json *json, long long value);
void dr_json_double(dr_json *json, double value);
void dr_json_bool(dr_json *json, int value);
void dr_json_null(dr_json *json);
/* The finished text (caller frees it), or NULL when memory ran out. */
char *dr_json_take(dr_json *json);

#endif
