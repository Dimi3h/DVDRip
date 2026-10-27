/* libdvdread build configuration for Linux and Android (what its meson.build would detect). */
#define PACKAGE_VERSION "7.1.1"
#define HAVE_SYS_PARAM_H 1
#define HAVE_LIMITS_H 1
#define HAVE_DIRENT_H 1
#define UNUSED __attribute__((unused))
#define HAVE_STRERROR_R 1
#define HAVE_DECL_STRERROR_R 1
#define HAVE_STATIC_ASSERT 1
/* libdvdcss is linked in, not loaded with dlopen (an APK cannot ship libdvdcss.so.2). */
#define HAVE_DVDCSS_DVDCSS_H 1
