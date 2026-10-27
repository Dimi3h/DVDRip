# DiscRip

A personal Android app (single user, sideloaded, never published) that rips DVD-Video discs from a
USB optical drive connected over USB OTG into Matroska files, names them the way Plex expects, and
saves them locally and/or on a Synology NAS over SMB, followed by a Plex library scan.

The UI is in Dutch (nl-BE) with an English fallback and follows Apple's Human Interface Guidelines
(large titles, inset grouped lists, iOS switches and segmented controls) on top of Jetpack Compose.

## Status

The app is built in phases; every phase ends with a buildable app and passing tests.

| Phase | Scope | State |
|---|---|---|
| 1 | Project skeleton, HIG theme, Settings (presets, storage, NAS, Plex, TMDB key), TMDB client, manual metadata form | done |
| 2 | USB layer: enumerate the drive, INQUIRY, detect the disc, read sector 16 and show the volume label; fake block device backed by an ISO | done |
| 3 | Native build (libdvdread, libdvdcss, FFmpeg), JNI bridge, ISO to MKV end to end with the lossless preset | done |
| 4 | Main-feature detection, audio and subtitle selection, chapters, tags, size estimates, free-space check | done |
| 5 | SMB upload, Plex refresh and verification, "Beide" mode, history | planned |
| 6 | Real USB drive, foreground service, eject, batch flow | planned |
| 7 | Lossy presets: MediaCodec encoders, yadif, aspect ratio, H.264 fallback, LPCM to FLAC | planned |

### What phase 1 contains

- **Settings** (tab "Instellingen"):
  - *Kwaliteit*: the four presets with label, one-line description and the size of a two-hour film
    at the configured bitrate; default preset; adjustable bitrates for the lossy presets
    (300 to 12 000 kbit/s, steps of 100); LPCM to FLAC toggle.
  - *Metadata*: default path (Automatisch (TMDB) or Zelf invullen), TMDB API key with a test button.
  - *Bewaren*: default target (Lokaal, NAS, Beide), local folder picked with
    `ACTION_OPEN_DOCUMENT_TREE` (persisted permission, revocation detected), sub folders for films and
    home videos.
  - *NAS*: host, share, user, password, domain, folders for films and home videos, "write directly
    to the NAS" (default off).
  - *Plex*: server address, token, and the library sections for films and home videos, fetched from
    `/library/sections`.
- **Metadata screen**: automatic path (TMDB search in nl-BE and en-US with posters, year filter,
  editable result) and manual path (Titel, Jaar, Soort, optional TMDB or IMDb id), validation, and a
  live preview of the Plex folder and file name.
- **Home**: the metadata chosen for the next rip and a configuration checklist (TMDB, local folder,
  NAS, Plex).

### What phase 2 contains

- **USB drive on Home** (section "Station"): finds USB mass-storage devices (class 8, subclass 2 or
  6, Bulk-Only protocol 0x50), asks for permission, claims the interface with `force = true`, runs
  INQUIRY and keeps only CD/DVD drives (device type 5). Android also offers DiscRip when such a
  device is plugged in.
- **Disc detection**: tray open, no disc, spinning up, ready, unusable. Polled once a second while
  the app is on screen with GET EVENT STATUS NOTIFICATION and TEST UNIT READY; unit attentions
  (disc changed, reset) trigger a new probe.
- **Disc information**: volume label (UDF, else ISO 9660), size, DVD-Video or not (VIDEO_TS in the
  root), CSS or not, and the region check between disc and drive with advice when they do not
  match. "Metadata voor deze disc" opens the metadata screen with the label as the search.
- **Eject and close tray**, with a clear message when a slim drive cannot pull its tray in.
- **Errors**: no USB host, permission denied, not an optical drive, claim failed, no answer, drive
  stopped responding, unplugged, CD or Blu-ray inserted, blank disc, read errors with the drive's
  sense code and a retry.
- **Under the hood** (all in `core`, tested on the JVM): Bulk-Only Transport with CBW/CSW and the
  reset recovery of the specification, SCSI/MMC command builders and parsers, reads of 32 blocks
  per READ(10) with three retries on transient errors, ISO 9660 and UDF 1.02 readers, a disc probe
  and the drive monitor. The tests run against a simulated drive at the USB endpoint level
  (tray, spin-up, unit attentions, stalls, timeouts, bad status wrappers, unplugging, read errors)
  backed by disc images: synthetic ones and real ones made by `genisoimage` and `mkudffs`.

### What phase 3 contains

- **Sources on Home** ("Nieuwe rip"): *ISO-bestand* opens the document picker for a DVD image,
  *VIDEO_TS-map* the folder picker for a copied DVD (the VIDEO_TS folder itself or the folder above
  it). Both work on internal storage, SD cards and USB sticks. The image is checked for a VIDEO_TS
  directory (UDF or ISO 9660) before the engine opens it.
- **Source screen**: the titles with duration, chapters, audio tracks and camera angles; the
  longest title is chosen as the main feature (proper scoring comes in phase 4); the metadata for
  the rip; the audio tracks in output order with their format; where the file goes.
- **Rip screen**: percentage, read speed in MB/s, time left, bytes written, position in the title
  and elapsed time; cancel with confirmation. The screen stays on while ripping. The result shows
  size, duration, chapters and audio languages as read back from the finished file, unreadable
  sectors and the engine's notes. Home shows the running or last rip.
- **Lossless MKV**: MPEG-2 video and AC-3, DTS and MPEG audio copied bit-exact; LPCM rewritten as
  PCM, or as FLAC when "LPCM omzetten naar FLAC" is on. All audio tracks, Dutch and English first,
  the first main track as default, commentary and audio description named and never default.
  Languages as ISO 639-2/B codes (`dut`, `eng`, `ger`, …), the film title and year as TITLE and
  DATE_RELEASED tags, chapters at the program starts named "Hoofdstuk 1", "Hoofdstuk 2", …
  The display aspect ratio (4:3 or 16:9) is kept. Subtitles follow in phase 4.
- **Errors**: file or folder cannot be opened, not a DVD-Video, unreadable IFO files, no titles,
  another rip running, CSS key not recoverable, local folder gone or no longer writable, disk full,
  write errors, cancelled. Unfinished files are deleted.
- **Under the hood**: the rip engine in C (`app/src/main/cpp/engine/`), the JNI bridge
  (`app/src/main/cpp/jni/rip_jni.c`), FFmpeg 9.0.2, libdvdread 7.1.1 and libdvdcss 1.6.0 built
  from source. The same engine is built for the build machine as `discrip_host`, a command-line
  driver, and tested there (also under AddressSanitizer and UBSan) against a DVD authored in the
  test run. On the phone, an instrumented test rips the same DVD through the app's rip manager.

The USB drive from phase 2 is not a rip source yet: ripping from the drive, with CSS
authentication, comes in phase 6 (see "CSS on real drives" below).

### What phase 4 contains

- **Main feature detection**, also on discs that hide the film among decoy titles (Disney's
  "X-Project", ARccOS and similar): the main feature is the longest title that plays its cells
  front to back. Titles whose cells jump back, repeat, or claim more data than DVD-Video can play
  in their time are discounted and marked "scènes door elkaar". Titles shorter than two minutes
  and decoys are folded away under "Alle n titels tonen".
- **Audio selection**: a checkmark per track (all on by default, Dutch and English first), and
  "Standaardspoor" to choose which one plays first. Commentary and audio description are named
  and carry Matroska's FlagCommentary and FlagVisualImpaired.
- **Subtitles**: a checkmark per stream (all on), written as VobSub (`S_VOBSUB`) with the frame
  size and the title's own palette (the IFO's YCrCb converted to RGB), the display time of each
  subtitle taken from its stop command, ISO 639-2/B language tags, and FlagForced,
  FlagHearingImpaired or FlagCommentary where the disc says so. None is marked default; Plex picks
  subtitles by the viewer's language settings. Subtitles follow the same timeline correction as
  audio and video.
- **Tags**: besides TITLE and DATE_RELEASED, IMDB (`tt0133093`), TMDB (`movie/603`) and SUMMARY
  (the TMDB overview), which Kodi and Jellyfin read; Plex finds the film by the file name.
- **Size and space**: the estimated size of the MKV (from the title's VOB size and the streams
  kept) and the free space of the local folder on the source screen. A rip only starts with the
  estimate plus 10 % free; the check is repeated on the file system of the file it creates, so
  it also holds for storage that cannot tell its free space in advance. When the space is short,
  the message says how much is needed and how much is free.
- **Read back**: the result screen lists the subtitle tracks found in the finished file next to
  the audio, and the tags are checked in the instrumented test.

## Building

Requirements:

- JDK 17 or newer (CI uses Temurin 21). The Gradle wrapper (9.8.2) downloads Gradle itself.
- Android SDK with the API 37 platform and current build tools (Android Studio installs them;
  otherwise the Android Gradle plugin downloads missing ones when the SDK licenses are accepted),
  and `local.properties` with `sdk.dir` or the `ANDROID_HOME` environment variable.
- NDK r30 (`30.0.16248370`) and CMake 4.1.2 from the SDK manager. The Android Gradle plugin
  installs both when they are missing and the SDK licenses are accepted.
- bash, curl, tar, make and `sha256sum` or `shasum`: before every build Gradle runs
  `native/scripts/fetch-sources.sh`, which downloads the third-party sources the native build
  compiles (libusb 1.0.30, libdvdcss 1.6.0, libdvdread 7.1.1, FFmpeg 9.0.2), checks their SHA-256
  against the pinned value and unpacks them into `native/third_party/` (git-ignored). Then it runs
  `native/scripts/build-ffmpeg.sh android`, which configures and builds FFmpeg with the app's NDK
  into `native/build/ffmpeg/android/` (git-ignored). That takes a few minutes the first time; the
  script keeps a fingerprint of its configuration and returns at once afterwards. Linux and macOS
  work as they are; on Windows build in WSL.
- For the native engine tests on the build machine and for the instrumented tests: ffmpeg,
  dvdauthor and genisoimage, which author the test DVD, and for `test-engine.sh` also CMake, Ninja
  and nasm (Debian/Ubuntu: `apt install ffmpeg dvdauthor genisoimage cmake ninja-build nasm`).

Toolchain: Android Gradle plugin 9.4 with its built-in Kotlin support, Kotlin 2.4, Compose BOM
2026.09. `compileSdk` is 37 because current AndroidX, Compose and OkHttp releases require it;
`targetSdk` stays 35 and `minSdk` is 29 (Android 10).

Commands (from the repository root):

```sh
./gradlew -p core test                  # platform-independent logic; needs no Android SDK
./gradlew :app:testDebugUnitTest        # app unit tests on the JVM
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
native/scripts/test-engine.sh           # rip engine on the build machine, plain and sanitized
./gradlew :app:connectedDebugAndroidTest   # on a connected arm64 phone or an API 30+ emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The native scripts can also be used on their own: `native/scripts/make-test-dvd.sh out.iso folder`
authors the test DVD, and `discrip_host` (in `native/build/host/` after `test-engine.sh`) lists
titles (`discrip_host titles image.iso`), rips (`discrip_host rip image.iso 1 out.mkv --audio
0x80:eng`) and reads a Matroska file back (`discrip_host probe out.mkv`).

Release builds are minified with R8. Put a `keystore.properties` (git-ignored) next to this README
to sign them with your own key:

```properties
storeFile=/path/to/discrip.jks
storePassword=...
keyAlias=discrip
keyPassword=...
```

Without it, release builds are signed with the debug key, which is fine for sideloading on your own
phone.

The wrapper pins the Gradle distribution by its SHA-256 (`distributionSha256Sum`), taken from
services.gradle.org and checked against the downloaded zip.

Native libraries must support 16 KB memory pages (Android 15+ devices may use them). NDK r30 aligns
them that way and the CMake build asks for it explicitly; `python3 scripts/check_native_alignment.py
app/build/outputs/apk/debug/app-debug.apk` checks the ELF segments and their placement in the APK.

**CI.** `.github/workflows/ci.yml` runs on every push, on Ubuntu 24.04 runners (pinned so the
preinstalled NDK and CMake match): core tests; the rip engine tests on the runner
(`native/scripts/test-engine.sh`); app unit tests, the debug and release APKs (the debug one is
uploaded as the `discrip-debug-apk` artifact), lint and the 16 KB alignment check; instrumented
tests on an API 30 x86_64 emulator, whose ARM translation runs the arm64-only app, including the
end-to-end rip of the test DVD; and a report of which pinned dependencies have newer stable
releases (`python3 scripts/dependency_report.py` does the same locally). The third-party sources
and the FFmpeg builds are cached between runs.

## Credentials

All optional; the app works without any of them (manual metadata, local storage). Secrets are kept
in `EncryptedSharedPreferences` with a master key in the Android Keystore, and the app disables
backup and device transfer so they never leave the phone.

| What | Where to get it | Used for |
|---|---|---|
| TMDB API key (v3) or API read access token (v4) | themoviedb.org, account Settings, API | Searching films, localized titles, posters, ids |
| NAS user and password | Synology DSM, a user with write access to the share; SMB service enabled (SMB 2 or 3) | Uploads (phase 5) |
| Plex token | Plex Web, open a film, ⋯, Get Info, View XML: the `X-Plex-Token` value in the address | Fetching library sections, refreshing and verifying (phase 5) |

The Plex server address may be given as `192.168.1.10`, `nas.local:32400` or a full URL; without a
scheme the app uses `http://` and port 32400. Cleartext HTTP is allowed because a Plex server on the
home network is normally reached by IP address over plain HTTP; TMDB always uses HTTPS.

## Project layout

```
core/                     Platform-independent Kotlin (separate Gradle build, Maven Central only)
  model/                  MediaKind, MovieMetadata, QualityPreset, StorageTarget, validation
  naming/                 Plex naming and file name sanitizing
  estimate/               Size estimates and the free-space margin
  tmdb/                   TMDB client and volume label normalizer
  plex/                   Plex client
  settings/               AppSettings (everything except secrets)
  usb/                    Bulk-Only Transport: CBW/CSW, reset recovery, the BulkTransport interface
  scsi/                   SCSI/MMC command builders, response parsers, OpticalDrive
  disc/                   Block devices (drive, ISO file), ISO 9660/UDF reader, disc probe, monitor
  dvd/                    Title list and rip report models, ISO 639 codes, track order, progress
  src/test/.../testing/   Simulated USB drive and disc images for the tests
  src/test/resources/images/  Disc image fixtures and the script that makes them
app/                      Android app
  data/                   DataStore settings, encrypted secrets, local folder grants
  usb/                    libusb and Java USB transports, mass-storage interface lookup
  drive/                  DriveManager: discovery, permission, claim, polling
  rip/                    RipManager, DVD sessions over JNI, SAF folder reader, output files
  src/main/cpp/           CMake build of libusb, libdvdcss, libdvdread and the engine
    engine/               The rip engine in C: sources, title plans, VOBU reader, remux to MKV
    jni/, usb_jni.c       JNI bridges for the engine and for libusb
    host/                 discrip_host, the engine's command-line driver for the build machine
    config/               Hand-written config.h for libdvdcss and libdvdread
  ui/theme, ui/components HIG theme and components
  ui/home, ui/metadata, ui/settings, ui/source, ui/rip, ui/navigation
native/scripts/           fetch-sources.sh (pinned, checksummed sources), build-ffmpeg.sh,
                          make-test-dvd.sh, test-engine.sh
native/tests/             test_engine.py: engine tests against the authored test DVD
scripts/                  dependency_report.py, check_native_alignment.py, test_summary.py
```

## Decisions

These were made without asking because they do not change the architecture; each can be revisited.

1. **`core` is a separate Gradle build** included with `includeBuild("core")`. It holds all logic
   that does not need Android (naming, presets, estimates, TMDB and Plex clients, the USB and
   SCSI stack, the disc readers; later title scoring) and depends only on Maven Central, so its
   tests run anywhere, fast.
2. **Material 3 themed to the HIG instead of compose-cupertino.** The latest compose-cupertino
   release is 0.1.0-alpha04 from April 2024, built against Compose 1.6.3 and Kotlin 1.9.23. Its
   bytecode calls the Compose 1.6 constructor
   `AnchoredDraggableState(Object, Function1, Function0, AnimationSpec, Function1)` and
   `animateTo(state, value, velocity)`, which no longer exist in current Compose foundation (checked
   against the Compose Multiplatform 1.12.2 class files, which share this code with Jetpack
   Compose), so it would fail at runtime with `NoSuchMethodError`. The app has
   its own small component set instead: large-title navigation bar, inset grouped sections, rows,
   UISwitch, UISegmentedControl, UIStepper, alert, sheet, tab bar, activity indicator and an
   SF-Symbols-like icon set drawn from scratch (SF Symbols may not be used outside Apple platforms).
   Colors and the type scale are the UIKit values; sizes are in sp so they follow the font scale.
3. **Manual dependency injection** (`AppContainer`) instead of Hilt: one Activity, a handful of
   singletons, fewer annotation processors to keep building.
4. **Naming.** `<base>/<Title> (<Year>) {tmdb-<id>}/<Title> (<Year>) {tmdb-<id>}.mkv`. When only an
   IMDb id is known (typed in the manual form) the hint is `{imdb-tt…}`, which Plex also accepts.
   Home videos and "Andere" never carry an id and may omit the year. Titles are sanitized for
   ext4/FAT/exFAT and for SMB shares (`:` becomes ` - `, `/ \ | *` become `-`, `? < >` are dropped,
   braces become parentheses so Plex does not misread them as hints, at most 200 bytes).
5. **TMDB languages.** Searches run in nl-BE and en-US in parallel and are merged per film: the
   Dutch title wins when TMDB has a translation, otherwise the English one. Details for the chosen
   film use TMDB's translation list (nl-BE, then other Dutch, then en-US, then the original title).
   A year from the volume label filters the search; without results the search repeats without it.
6. **Settings storage.** DataStore for normal settings, `EncryptedSharedPreferences`
   (androidx.security:security-crypto) for the TMDB key, NAS password and Plex token, as specified.
   Google has deprecated that library in favour of using the Android Keystore directly; it still
   works, and replacing it later only touches `SecretStore`.
7. **Strings.** English in `values/`, Dutch in `values-nl/` (used for nl-BE and nl-NL), and an
   in-app language list (`locales_config.xml`) so Android 13+ can switch the app language.
8. **Size estimate.** Bytes = duration × (video + audio + 10 kbit/s per subtitle stream) / 8 × 1.01
   for Matroska overhead. The lossless preset uses the disc's measured video bitrate when known
   (5.5 Mbit/s otherwise); FLAC is estimated at 60 % of LPCM. Before a rip the free space must be
   at least 110 % of the estimate.

9. **The SCSI stack is Kotlin, the USB pipes are libusb.** Bulk-Only Transport and SCSI/MMC live
   in `core`, so they are tested on the JVM against a simulated drive. Only the raw transfers are
   Android-specific, with two implementations behind one interface. The preferred one is libusb
   1.0.30, built from source and attached with `libusb_wrap_sys_device()` to the descriptor of the
   `UsbDeviceConnection` (no root needed): it tells a stall from a timeout from an unplugged
   device, keeps the bytes that arrived before a stall, and clears halts with the
   `USBDEVFS_CLEAR_HALT` ioctl. That matters for reset recovery: the plain CLEAR_FEATURE control
   request that the Java API can send does not reset the host's data toggle (checked in the Linux
   usbfs source, `drivers/usb/core/devio.c`). If the native library cannot be loaded, DiscRip
   falls back to `UsbDeviceConnection.bulkTransfer`. The drive screen shows which path is in use.
   libusb is LGPL-2.1 and is built as its own shared library.
10. **Claiming carefully.** `claimInterface(…, force = true)` detaches Android's own driver, which
    for a USB stick means it is unmounted until it is plugged in again. DiscRip therefore only
    claims a device the user gave permission for, lets go as soon as INQUIRY shows it is not a
    CD/DVD drive, and remembers such devices (vendor, product, serial) so it does not claim them
    again unless the user taps "Toch proberen".
11. **Volume label.** The UDF volume identifier comes first, as libdvdread and players use it; the
    ISO 9660 label is the fallback. UDF is read from the anchor at sector 256 (then N−256 and N−1),
    the main volume descriptor sequence and, if that is damaged, the reserve one. VIDEO_TS is looked
    up in both the UDF and the ISO 9660 tree, so UDF-only discs are recognized too.
12. **Region check before ripping.** READ DVD STRUCTURE (copyright information: CSS flag and the
    disc's region mask) and REPORT KEY (the drive's RPC state) say up front whether an RPC-II drive
    will refuse the disc. The rules follow libdvdcss: an RPC-II drive without a region refuses
    scrambled sectors of CSS discs; an RPC-I drive does not enforce regions.
13. **Reading.** 32 blocks (64 KiB) per READ(10). Each command is retried three times on transient
    errors (medium and hardware errors, not ready, unit attention, aborted commands, USB timeouts,
    protocol errors); a region mismatch, a copy protection refusal or an address past the end fails
    at once, as retrying cannot help.

14. **The rip engine is C, shared with a command-line driver.** Reading a title (cells, angle
    blocks, interleaved units), lining up its timeline and remuxing it run in C on top of
    libdvdread, libdvdcss and FFmpeg. The app calls it through JNI; `discrip_host` builds the same
    code for the build machine, so the ripping logic is tested on every push without a phone, also
    under AddressSanitizer and UBSan, and failures such as unreadable blocks or a full disk can be
    injected.
15. **Timestamps across VOBs.** Every VOB of a title can start its own clock (DVD authoring tools
    and seamless branching do this). The reader takes each VOBU's start and end time from its
    navigation pack; where a VOBU does not start where the previous one ended, it records an offset
    by stream position, and the remuxer applies it to every packet. Chapters come from the cells
    where programs start, at the time their first VOBU is written.
16. **How the engine reads.** Images are read with `pread()` on a descriptor the app hands over, so
    no JNI call is made per block. VIDEO_TS folders picked with the Storage Access Framework are
    reached through libdvdread's filesystem callbacks: Kotlin answers `stat`, directory listings
    and `open`, and the engine reads the descriptors it gets. libdvdread chooses its input
    functions through process-wide pointers, so the app opens one DVD at a time.
17. **Read errors.** A failed 32-block read is repeated block by block, each block up to three
    times; blocks that stay unreadable are left out, the rip continues, and the result lists them.
    Scrambled sectors that libdvdcss could not decrypt stop the title instead of producing garbage.
18. **Output.** The MKV is written as `<name>.mkv.part` in its Plex folder and renamed when it is
    complete and read back successfully (streams, duration, chapters); a cancelled or failed rip
    deletes it. An earlier rip of the same film is only replaced after confirmation. Storage that
    cannot rename gets the final name directly.
19. **FFmpeg build.** FFmpeg 9.0.2, configured as LGPL (no `--enable-gpl`, no `--enable-nonfree`)
    with only the demuxers, muxer, decoders, encoders, parsers and filters DiscRip uses (see
    `native/scripts/build-ffmpeg.sh`), as shared libraries with unversioned names. The MediaCodec
    encoders and the filters for the lossy presets are already in the build for phase 7.
20. **Licenses.** libdvdcss and libdvdread are GPL-2.0-or-later and are linked statically into
    `libdiscrip.so`, so that library, and with it the app, falls under the GPL. That is fine for a
    personal app that is not distributed; anyone who passes the APK on must offer this source
    code. FFmpeg (LGPL-2.1-or-later) and libusb (LGPL-2.1-or-later) stay separate shared libraries.
21. **No foreground service yet.** Until phase 6 the rip runs while DiscRip is open: the rip screen
    keeps the display (and with it the CPU) awake. Leaving the app during a rip can get it paused
    or stopped by Android.
22. **Main feature by cell order.** Players reach the film through the disc's menus, which would
    need a DVD virtual machine (libdvdnav) to follow. The cell lists say enough instead: decoy
    titles reuse the film's cells in shuffled or repeated order, or point cells at more data than
    can play in their time (over 10.08 Mbit/s). The score is duration / (1 + anomalies); among
    titles within 1 % of the best, fewer anomalies, fewer sub-second cells, longer, more chapters
    and a lower title number win. Of two clean cuts (theatrical and extended, made with seamless
    branching) the longer one is chosen; the user can pick the other.
23. **Subtitles stay pictures.** DVD subtitles are bitmaps. Turning them into text would need OCR
    and would lose positioning and styling, so they are copied as VobSub, which Plex, VLC and Kodi
    play. The palette has to come from the IFO, because the subtitle stream only holds palette
    indices.
24. **Audio rates for estimates.** The IFO does not record the bit rate of compressed audio, so
    estimates use the rates DVDs use most: AC-3 192 kbit/s for stereo and 448 for 5.1, DTS 768,
    MPEG audio 224; LPCM is calculated exactly. The video rate is what the title's VOB data leaves.

## CSS on real drives (phase 6)

Phase 3 rips images and VIDEO_TS folders. libdvdcss handles CSS in them as specified
(`DVDCSS_METHOD=title`, keys cached in the app's cache directory): it recovers title keys from
the scrambled sectors themselves. Current drives (RPC-II), however, refuse to hand out
CSS-scrambled sectors until the host has gone through CSS authentication (sense 05/6F/03, "read of
scrambled sector without authentication"). libdvdcss's stream API, which DiscRip uses because apps
cannot open `/dev/sr0`, cannot send SCSI commands itself. Phase 6, which makes the USB drive a rip
source, therefore adds the authentication handshake (REPORT KEY and SEND KEY: AGID, challenges,
bus key) to the Kotlin SCSI layer, run before the engine reads, while libdvdcss still recovers the
title keys. The overall design stays as specified.

## Hardware checklist

- A phone with USB host (OTG) support; Android 10 or newer.
- A USB DVD drive. Slim drives draw up to about 1 A at 5 V when spinning up, more than many phones
  supply over OTG: use a drive with its own power supply, a Y-cable, or a powered USB hub.
- An OTG adapter or USB-C hub that passes data (and charges the phone during long rips, if it can).
- The drive's region set to 2 (RPC-2 drives refuse to read CSS-protected discs from other regions;
  the region can only be changed on a PC, a limited number of times).
- Test material: an unencrypted DVD-Video ISO (`native/scripts/make-test-dvd.sh`, also authored in
  CI) and a few real discs, including one with many near-identical titles.

Phase 2 on real hardware:

1. Install the debug APK and connect the drive. Android asks whether to open DiscRip; or open the
   app, see the drive under "Station" with "Toegang nodig" and tap "Verbinden", then allow.
2. The drive's name appears with "USB via libusb 1.0.30" (or "USB via Android" on the fallback).
3. Insert a DVD: "Disc wordt gelezen…", then the label, size, DVD-Video, CSS and region.
4. Try "Uitwerpen", "Lade sluiten" and "Lade openen"; take the disc out and put another one in.
5. Insert a CD or a blank disc: "Geen dvd" or "Lege disc".
6. Pull the cable while a disc is in: the section goes back to "Geen station aangesloten" without a
   crash; plug it in again.
7. Connect a USB stick on its own: DiscRip should report "Geen cd- of dvd-station" and not touch
   it again on the next connection.

Phase 3 on a phone (no drive needed):

1. In Instellingen › Bewaren, choose a local folder.
2. Copy an unencrypted DVD image to the phone (the test DVD, or an ISO of one of your own discs)
   and tap "ISO-bestand" on Home. The titles appear with the longest one marked "Hoofdfilm".
3. Tap "Metadata kiezen", pick the film, then "Rip starten". Watch percentage, speed and time left;
   the screen stays on.
4. Open the MKV from `Films/<Title> (<Year>) {tmdb-<id>}/` in VLC or Plex: chapters, Dutch audio
   first and marked default, the right display aspect ratio.
5. Rip the same disc again: DiscRip asks before replacing the file. Cancel a rip halfway: no file
   is left behind.
6. Copy a VIDEO_TS folder to a USB stick, pick it with "VIDEO_TS-map" and rip it.
7. Take the SD card or USB stick with the local folder out during a rip: the rip stops with a clear
   message.

Phase 4 on a phone:

1. Open an image of a disc with several audio tracks and subtitles. Uncheck a few tracks and pick
   another "Standaardspoor"; the estimated size goes down as tracks are unchecked.
2. Rip and check in VLC or Plex: only the checked tracks, in the order shown, the chosen default,
   subtitles in the right colours and at the right moments, also after the layer change.
3. Open an image of a disc with decoy titles (many titles of the same length): "Hoofdfilm" should
   sit on a title without "scènes door elkaar", and its rip should play the scenes in order.
4. Fill the local folder until less than the estimate is free: the start button stays disabled
   and the footer gives the numbers.

## Privacy

No analytics, no crash reporting, no accounts. Network traffic goes only to TMDB (when a key is
set), your NAS and your Plex server.
