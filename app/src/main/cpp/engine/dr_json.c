#include "dr_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void dr_json_init(dr_json *json) {
    memset(json, 0, sizeof(*json));
}

void dr_json_free(dr_json *json) {
    free(json->data);
    memset(json, 0, sizeof(*json));
}

static void append(dr_json *json, const char *text, size_t length) {
    if (json->failed) return;
    if (json->length + length + 1 > json->capacity) {
        size_t capacity = json->capacity ? json->capacity * 2 : 1024;
        while (capacity < json->length + length + 1) capacity *= 2;
        char *grown = realloc(json->data, capacity);
        if (grown == NULL) {
            json->failed = 1;
            return;
        }
        json->data = grown;
        json->capacity = capacity;
    }
    memcpy(json->data + json->length, text, length);
    json->length += length;
    json->data[json->length] = '\0';
}

static void append_text(dr_json *json, const char *text) {
    append(json, text, strlen(text));
}

/* Separator before a value or key inside an object or array. */
static void before_value(dr_json *json) {
    if (json->after_key) {
        json->after_key = 0;
        return;
    }
    if (json->depth > 0) {
        if (json->has_items[json->depth - 1]) append_text(json, ",");
        json->has_items[json->depth - 1] = 1;
    }
}

static void open(dr_json *json, const char *bracket) {
    before_value(json);
    append_text(json, bracket);
    if (json->depth >= DR_JSON_MAX_DEPTH) {
        json->failed = 1;
        return;
    }
    json->has_items[json->depth++] = 0;
}

static void close_(dr_json *json, const char *bracket) {
    if (json->depth > 0) json->depth--;
    append_text(json, bracket);
}

void dr_json_begin_object(dr_json *json) { open(json, "{"); }
void dr_json_end_object(dr_json *json) { close_(json, "}"); }
void dr_json_begin_array(dr_json *json) { open(json, "["); }
void dr_json_end_array(dr_json *json) { close_(json, "]"); }

/* Length of the valid UTF-8 sequence at text, or 0 when it is not valid. */
static int utf8_sequence(const unsigned char *text) {
    unsigned char c = text[0];
    int length;
    if (c < 0x80) return 1;
    if (c >= 0xC2 && c <= 0xDF) length = 2;
    else if (c >= 0xE0 && c <= 0xEF) length = 3;
    else if (c >= 0xF0 && c <= 0xF4) length = 4;
    else return 0;
    for (int i = 1; i < length; i++) {
        if ((text[i] & 0xC0) != 0x80) return 0;
    }
    return length;
}

static void append_quoted(dr_json *json, const char *value) {
    append_text(json, "\"");
    const unsigned char *p = (const unsigned char *) value;
    while (*p) {
        char escape[8];
        if (*p == '"' || *p == '\\') {
            escape[0] = '\\';
            escape[1] = (char) *p;
            append(json, escape, 2);
            p++;
        } else if (*p < 0x20) {
            snprintf(escape, sizeof(escape), "\\u%04x", *p);
            append_text(json, escape);
            p++;
        } else {
            int length = utf8_sequence(p);
            if (length == 0) {
                append_text(json, "\xEF\xBF\xBD"); /* U+FFFD */
                p++;
            } else {
                append(json, (const char *) p, (size_t) length);
                p += length;
            }
        }
    }
    append_text(json, "\"");
}

void dr_json_key(dr_json *json, const char *key) {
    before_value(json);
    append_quoted(json, key);
    append_text(json, ":");
    json->after_key = 1;
}

void dr_json_string(dr_json *json, const char *value) {
    if (value == NULL) {
        dr_json_null(json);
        return;
    }
    before_value(json);
    append_quoted(json, value);
}

void dr_json_int(dr_json *json, long long value) {
    char text[32];
    snprintf(text, sizeof(text), "%lld", value);
    before_value(json);
    append_text(json, text);
}

void dr_json_double(dr_json *json, double value) {
    char text[64];
    snprintf(text, sizeof(text), "%.6g", value);
    before_value(json);
    append_text(json, text);
}

void dr_json_bool(dr_json *json, int value) {
    before_value(json);
    append_text(json, value ? "true" : "false");
}

void dr_json_null(dr_json *json) {
    before_value(json);
    append_text(json, "null");
}

char *dr_json_take(dr_json *json) {
    if (json->failed) {
        dr_json_free(json);
        return NULL;
    }
    char *text = json->data;
    if (text == NULL) text = strdup("");
    json->data = NULL;
    dr_json_free(json);
    return text;
}
