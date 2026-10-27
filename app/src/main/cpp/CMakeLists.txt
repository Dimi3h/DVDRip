# Native code of DiscRip.
#
# Third-party sources are not in git: native/scripts/fetch-sources.sh downloads them, pinned by
# version and checksum, and native/scripts/build-ffmpeg.sh builds FFmpeg (Gradle runs both before
# every build). libusb, libdvdcss and libdvdread are compiled from source here.
#
# For Android this builds libdiscrip.so (JNI bridge and rip engine) and libusb-1.0.so. On the
# build machine (no ANDROID) it builds discrip_host, a command-line driver of the same engine:
#   cmake -S app/src/main/cpp -B native/build/host && cmake --build native/build/host
cmake_minimum_required(VERSION 3.22.1)
project(discrip LANGUAGES C)

set(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")
set(THIRD_PARTY "${NATIVE}/third_party")
set(LIBUSB "${THIRD_PARTY}/libusb-1.0.30")
set(LIBDVDCSS "${THIRD_PARTY}/libdvdcss-1.6.0")
set(LIBDVDREAD "${THIRD_PARTY}/libdvdread-7.1.1")
if(ANDROID)
    set(FFMPEG "${NATIVE}/build/ffmpeg/android" CACHE PATH "FFmpeg install prefix")
else()
    set(FFMPEG "${NATIVE}/build/ffmpeg/host" CACHE PATH "FFmpeg install prefix")
endif()

foreach(required "${LIBUSB}/libusb/core.c" "${LIBDVDCSS}/src/libdvdcss.c" "${LIBDVDREAD}/src/dvd_reader.c")
    if(NOT EXISTS "${required}")
        message(FATAL_ERROR "Third-party sources missing (${required}); run native/scripts/fetch-sources.sh")
    endif()
endforeach()
if(NOT EXISTS "${FFMPEG}/include/libavformat/avformat.h")
    message(FATAL_ERROR "FFmpeg is not built in ${FFMPEG}; run native/scripts/build-ffmpeg.sh")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(ANDROID)
    # 16 KB page alignment for Android 15+ devices with 16 KB pages (NDK r28+ default, made explicit).
    add_link_options("LINKER:-z,max-page-size=16384")
endif()

set(GENERATED "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(DVDCSS_VERSION_MAJOR 1)
set(DVDCSS_VERSION_MINOR 6)
set(DVDCSS_VERSION_MICRO 0)
configure_file("${LIBDVDCSS}/src/dvdcss/version.h.in" "${GENERATED}/dvdcss/version.h" @ONLY)
set(DVDREAD_VERSION_MAJOR 7)
set(DVDREAD_VERSION_MINOR 1)
set(DVDREAD_VERSION_MICRO 1)
configure_file("${LIBDVDREAD}/src/dvdread/version.h.in" "${GENERATED}/dvdread/version.h" @ONLY)

# libdvdcss (GPL-2.0-or-later): CSS decryption. Linked statically into the engine.
add_library(dvdcss STATIC
    ${LIBDVDCSS}/src/cpxm.c
    ${LIBDVDCSS}/src/css.c
    ${LIBDVDCSS}/src/device.c
    ${LIBDVDCSS}/src/error.c
    ${LIBDVDCSS}/src/ioctl.c
    ${LIBDVDCSS}/src/libdvdcpxm.c
    ${LIBDVDCSS}/src/libdvdcss.c)
target_include_directories(dvdcss
    PRIVATE config/libdvdcss ${LIBDVDCSS}/src
    PUBLIC ${LIBDVDCSS}/src ${GENERATED})
target_compile_definitions(dvdcss PRIVATE _DEFAULT_SOURCE)
target_compile_options(dvdcss PRIVATE -w)

# libdvdread (GPL-2.0-or-later): IFO parsing, UDF, title VOB access.
add_library(dvdread STATIC
    ${LIBDVDREAD}/src/bitreader.c
    ${LIBDVDREAD}/src/dvd_input.c
    ${LIBDVDREAD}/src/dvd_reader.c
    ${LIBDVDREAD}/src/dvd_udf.c
    ${LIBDVDREAD}/src/ifo_print.c
    ${LIBDVDREAD}/src/ifo_read.c
    ${LIBDVDREAD}/src/logger.c
    ${LIBDVDREAD}/src/md5.c
    ${LIBDVDREAD}/src/nav_print.c
    ${LIBDVDREAD}/src/nav_read.c
    ${LIBDVDREAD}/src/file/file_posix.c)
target_include_directories(dvdread
    PRIVATE config/libdvdread ${LIBDVDREAD} ${LIBDVDREAD}/src
    PUBLIC ${LIBDVDREAD}/src ${GENERATED})
target_compile_definitions(dvdread PRIVATE _DEFAULT_SOURCE _LARGEFILE64_SOURCE)
target_compile_options(dvdread PRIVATE -w)
target_link_libraries(dvdread PUBLIC dvdcss)

# FFmpeg (LGPL build): shared libraries on Android, static ones for the host driver.
set(FFMPEG_LIBRARIES avformat avfilter avcodec swscale avutil)
foreach(name ${FFMPEG_LIBRARIES})
    if(ANDROID)
        add_library(ff_${name} SHARED IMPORTED)
        set_target_properties(ff_${name} PROPERTIES IMPORTED_LOCATION "${FFMPEG}/lib/lib${name}.so")
    else()
        add_library(ff_${name} STATIC IMPORTED)
        set_target_properties(ff_${name} PROPERTIES IMPORTED_LOCATION "${FFMPEG}/lib/lib${name}.a")
    endif()
    set_target_properties(ff_${name} PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${FFMPEG}/include")
endforeach()
if(NOT ANDROID)
    # Static FFmpeg needs libm and pthreads after it on the link line.
    find_package(Threads REQUIRED)
    set_target_properties(ff_avutil PROPERTIES INTERFACE_LINK_LIBRARIES "m;Threads::Threads")
endif()

# The rip engine, shared by the app and the host driver.
add_library(engine STATIC
    engine/dr_common.c
    engine/dr_fd.c
    engine/dr_json.c
    engine/dr_order.c
    engine/dr_plan.c
    engine/dr_probe.c
    engine/dr_reader.c
    engine/dr_rip.c
    engine/dr_source.c
    engine/dr_video.c)
target_include_directories(engine PUBLIC engine)
target_compile_options(engine PRIVATE -Wall -Wextra -Werror)
target_link_libraries(engine PUBLIC dvdread ff_avformat ff_avfilter ff_avcodec ff_swscale ff_avutil)
if(ANDROID)
    # MediaCodec through the NDK, which the engine drives itself for the lossy presets.
    target_sources(engine PRIVATE engine/dr_mediacodec.c)
    target_link_libraries(engine PUBLIC mediandk)
endif()

if(ANDROID)
    # libusb with its Linux usbfs backend and the Android configuration it ships. A separate
    # shared library, as befits an LGPL component.
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

    add_library(discrip SHARED usb_jni.c jni/rip_jni.c)
    target_compile_options(discrip PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(discrip PRIVATE usb-1.0 engine log)
else()
    add_executable(discrip_host host/discrip_host.c)
    target_compile_options(discrip_host PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(discrip_host PRIVATE engine)

    # Unit tests of engine parts the test DVD cannot reach, such as reordered MediaCodec output.
    add_executable(engine_unit_tests host/engine_unit_tests.c)
    target_compile_options(engine_unit_tests PRIVATE -Wall -Wextra -Werror)
    target_link_libraries(engine_unit_tests PRIVATE engine)

    # CSS test helpers from libdvdcss's own code: test vectors for the Kotlin port of the
    # authentication cipher, and scrambling a test image like a CSS-protected disc.
    add_executable(css_tool host/css_tool.c)
    target_include_directories(css_tool PRIVATE config/libdvdcss ${LIBDVDCSS}/src)
    target_compile_definitions(css_tool PRIVATE _DEFAULT_SOURCE)
    target_compile_options(css_tool PRIVATE -w)
    target_link_libraries(css_tool PRIVATE dvdcss)
endif()
