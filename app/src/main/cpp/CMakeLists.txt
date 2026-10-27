# Native code of DiscRip. Third-party sources are not in git: native/scripts/fetch-sources.sh
# downloads them, pinned by version and checksum (Gradle runs it before every build), and they
# are compiled from source here.
cmake_minimum_required(VERSION 3.22.1)
project(discrip LANGUAGES C)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/third_party")
set(LIBUSB "${THIRD_PARTY}/libusb-1.0.30")
if(NOT EXISTS "${LIBUSB}/libusb/core.c")
    message(FATAL_ERROR "libusb sources not found in ${LIBUSB}; run native/scripts/fetch-sources.sh")
endif()

# 16 KB page alignment for Android 15+ devices with 16 KB pages (NDK r28+ default, made explicit).
add_link_options("LINKER:-z,max-page-size=16384")

# libusb with its Linux usbfs backend and the Android configuration it ships. A separate shared
# library, as befits an LGPL component.
add_library(usb-1.0 SHARED
    ${LIBUSB}/libusb/core.c
    ${LIBUSB}/libusb/descriptor.c
    ${LIBUSB}/libusb/hotplug.c
    ${LIBUSB}/libusb/io.c
    ${LIBUSB}/libusb/sync.c
    ${LIBUSB}/libusb/strerror.c
    ${LIBUSB}/libusb/os/linux_usbfs.c
    ${LIBUSB}/libusb/os/linux_netlink.c
    ${LIBUSB}/libusb/os/events_posix.c
    ${LIBUSB}/libusb/os/threads_posix.c)
target_include_directories(usb-1.0
    PRIVATE ${LIBUSB}/android ${LIBUSB}/libusb/os
    PUBLIC ${LIBUSB}/libusb)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 PRIVATE log)

add_library(discrip SHARED usb_jni.c)
target_compile_options(discrip PRIVATE -Wall -Wextra -Werror)
target_link_libraries(discrip PRIVATE usb-1.0)
