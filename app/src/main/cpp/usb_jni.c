/*
 * JNI bridge between be.discrip.usb.NativeUsb and libusb. A session wraps the file descriptor of
 * an Android UsbDeviceConnection with libusb_wrap_sys_device(); the Java side keeps owning the
 * descriptor and the interface claim, libusb only moves data on it.
 *
 * Functions return 0 or a byte count on success and a negative libusb error code on failure.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include <libusb.h>

typedef struct {
    libusb_context *context;
    libusb_device_handle *handle;
    unsigned char *buffer;
    int capacity;
} session_t;

static session_t *session_of(jlong value) {
    return (session_t *) (intptr_t) value;
}

/* One reusable transfer buffer per session; the Kotlin side never runs two transfers at once. */
static unsigned char *buffer_of(session_t *session, int length) {
    if (length > session->capacity) {
        unsigned char *grown = realloc(session->buffer, (size_t) length);
        if (grown == NULL) return NULL;
        session->buffer = grown;
        session->capacity = length;
    }
    return session->buffer;
}

JNIEXPORT jlong JNICALL
Java_be_discrip_usb_NativeUsb_open(JNIEnv *env, jclass clazz, jint fd, jintArray error) {
    (void) clazz;
    jint code = LIBUSB_ERROR_NO_MEM;
    session_t *session = calloc(1, sizeof(session_t));
    if (session != NULL) {
        /* Apps may not scan /dev/bus/usb; the device comes from the Java USB API instead. */
        const struct libusb_init_option options[] = {{.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY}};
        code = libusb_init_context(&session->context, options, 1);
        if (code == LIBUSB_SUCCESS) {
            code = libusb_wrap_sys_device(session->context, (intptr_t) fd, &session->handle);
            if (code != LIBUSB_SUCCESS) libusb_exit(session->context);
        }
        if (code != LIBUSB_SUCCESS) {
            free(session);
            session = NULL;
        }
    }
    (*env)->SetIntArrayRegion(env, error, 0, 1, &code);
    return (jlong) (intptr_t) session;
}

JNIEXPORT jint JNICALL
Java_be_discrip_usb_NativeUsb_bulkTransfer(JNIEnv *env, jclass clazz, jlong handle, jint endpoint,
                                           jbyteArray data, jint offset, jint length, jint timeout,
                                           jintArray transferred) {
    (void) clazz;
    session_t *session = session_of(handle);
    unsigned char *buffer = buffer_of(session, length > 0 ? length : 1);
    if (buffer == NULL) return LIBUSB_ERROR_NO_MEM;

    int in = (endpoint & LIBUSB_ENDPOINT_IN) != 0;
    if (!in && length > 0) (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *) buffer);
    int count = 0;
    int result = libusb_bulk_transfer(session->handle, (unsigned char) endpoint, buffer, length, &count,
                                      (unsigned int) timeout);
    /* A failed transfer can still have moved data, for example before a stall. */
    if (in && count > 0) (*env)->SetByteArrayRegion(env, data, offset, count, (const jbyte *) buffer);
    jint moved = count;
    (*env)->SetIntArrayRegion(env, transferred, 0, 1, &moved);
    return result;
}

JNIEXPORT jint JNICALL
Java_be_discrip_usb_NativeUsb_controlTransfer(JNIEnv *env, jclass clazz, jlong handle, jint requestType,
                                              jint request, jint value, jint index, jbyteArray data,
                                              jint length, jint timeout) {
    (void) clazz;
    session_t *session = session_of(handle);
    unsigned char *buffer = buffer_of(session, length > 0 ? length : 1);
    if (buffer == NULL) return LIBUSB_ERROR_NO_MEM;

    int in = (requestType & LIBUSB_ENDPOINT_IN) != 0;
    if (!in && length > 0) (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *) buffer);
    int result = libusb_control_transfer(session->handle, (uint8_t) requestType, (uint8_t) request,
                                         (uint16_t) value, (uint16_t) index, buffer, (uint16_t) length,
                                         (unsigned int) timeout);
    if (in && result > 0) (*env)->SetByteArrayRegion(env, data, 0, result, (const jbyte *) buffer);
    return result;
}

/* USBDEVFS_CLEAR_HALT: clears the halt on the device and resets the host side of the endpoint. */
JNIEXPORT jint JNICALL
Java_be_discrip_usb_NativeUsb_clearHalt(JNIEnv *env, jclass clazz, jlong handle, jint endpoint) {
    (void) env;
    (void) clazz;
    return libusb_clear_halt(session_of(handle)->handle, (unsigned char) endpoint);
}

/* Leaves the file descriptor open: libusb marks wrapped descriptors as not its own. */
JNIEXPORT void JNICALL
Java_be_discrip_usb_NativeUsb_close(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    session_t *session = session_of(handle);
    if (session == NULL) return;
    libusb_close(session->handle);
    libusb_exit(session->context);
    free(session->buffer);
    free(session);
}

JNIEXPORT jstring JNICALL
Java_be_discrip_usb_NativeUsb_errorName(JNIEnv *env, jclass clazz, jint code) {
    (void) clazz;
    return (*env)->NewStringUTF(env, libusb_error_name(code));
}

JNIEXPORT jstring JNICALL
Java_be_discrip_usb_NativeUsb_version(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char text[32];
    const struct libusb_version *version = libusb_get_version();
    snprintf(text, sizeof(text), "%u.%u.%u", version->major, version->minor, version->micro);
    return (*env)->NewStringUTF(env, text);
}
