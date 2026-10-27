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
| 5 | SMB upload, Plex refresh and verification, "Beide" mode, history | done |
| 6 | Real USB drive, foreground service, eject, batch flow | done |
| 7 | Lossy presets: MediaCodec encoders, yadif, aspect ratio, H.264 fallback, LPCM to FLAC | done |

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

### What phase 5 contains

- **"Bewaren op" per rip**: on the source screen, Lokaal, NAS or Beide, starting from the default
  in Instellingen › Bewaren. The screen shows the folder in the local folder and on the share.
- **NAS** (SMB 2 and 3, such as a Synology): a NAS rip goes into the app's own storage first, is
  read back, uploaded as `<name>.mkv.part`, its size on the NAS compared with the size on the phone,
  renamed, and only then removed from the phone. With "Rechtstreeks naar de NAS" on, the engine
  writes to the share itself and reads the file back from there. "Beide" rips into the local folder
  and uploads a copy. The upload shows percentage, speed, time left and bytes, and can be stopped.
- **Checks before the rip**: the start button connects to the NAS, signs in, writes and removes a
  small file in the films folder and compares the free space with the estimate, so a NAS that is
  off, a refused login, an unknown share, a read-only folder or a full share are reported before
  the rip. Instellingen › NAS has the same check as "Verbinding testen". A host may carry a port
  (`nas.local:1445`).
- **Plex**: after the upload, Plex is asked to scan just the film's folder, worked out from the
  library's folders as Plex sees them (`/volume1/video/Films`); the app waits until the library
  lists the file and then shows "Toegevoegd aan Plex" with "Openen in Plex", a link to the film in
  Plex Web on the server. A rejected token, a library that is gone or a film that does not show up
  are named on the result screen; "Niet wachten op Plex" stops waiting.
- **Rip screen**: the phases ("Lezen en muxen › Uploaden › Plex") with the current one marked.
- **Result**: where the file is (local folder, `smb://nas/share/…`) and what Plex made of it.
- **History** (tab "Geschiedenis"): every rip with date, disc, size, target, result, unreadable
  sectors and notes. A rip whose upload failed waits in the app's storage (or, for Beide, in the
  local folder) and can be uploaded again from here, or given up.
- **Under the hood**: the NAS client on smbj in `core` (with errors for each case above), the
  engine's output and read-back through `dr_file` (a descriptor or JNI callbacks), the Plex path
  mapping and scan check, and the history as JSON. Tested against a real Samba server, in `core`
  and on the emulator, with a MockWebServer playing Plex.

### What phase 6 contains

- **The USB drive as a source**: Home's drive section has "Rippen" for a DVD-Video disc, which
  opens its titles like an image; the rip reads the disc over USB. The disc is named by its volume
  label, and the metadata screen searches TMDB with it.
- **CSS on the drive**: a CSS-protected disc is authenticated with the drive when it opens (the
  drive then hands out its scrambled sectors), so a drive whose region does not match is reported
  before the rip, with the advice to set it to the disc's region (2 for European discs) on a PC.
  The engine recovers the title keys from the sectors with libdvdcss and decrypts them.
- **Reading a real drive**: one attempt per read, since the engine retries every block range three
  times and skips what stays unreadable; a drive that lost its authentication (a bus reset) is
  authenticated again, and after a unit attention the disc is compared with the one being read.
  The tray is locked while the drive is read.
- **Errors that end a rip**: the drive unplugged or no longer answering, the disc taken out or
  swapped, a region the drive refuses, a drive that keeps refusing the protected sectors, and a
  title whose key cannot be found. Each is named on the rip screen and in Geschiedenis, and the
  unfinished file is deleted.
- **Batches**: after a good rip the disc comes out and the rip screen says "Steek de volgende disc
  in"; once the drive has read the next one, "Volgende disc rippen" opens it. It can go in while
  the last file is still being uploaded; the next rip starts when the upload is done. The film
  details of the last rip are cleared, so the next disc starts from its own label.
- **Foreground service**: a rip or an upload goes on with the screen off or the app in the
  background. A notification shows the film, the stage and the progress, with "Stoppen", and
  tells how the rip ended. The app asks for permission to show notifications at the first rip
  (Android 13 and later); without it the work runs all the same.

### What phase 7 contains

- **Lossy presets**: "Gebalanceerd" (HEVC, about 2.5 Mbit/s), "Meeste compressie" (HEVC, about
  1.2 Mbit/s) and "Breed compatibel" (H.264, about 3 Mbit/s), chosen per rip under "Kwaliteit" on
  the source screen, each with its size for the chosen title and tracks. The default and the bit
  rates stay in Instellingen › Kwaliteit, which also shows the phone's video encoders.
- **The video path**: MPEG-2 decoded in software; frames flagged interlaced deinterlaced with yadif,
  one frame out per frame in, so the frame rate stays; the pixel aspect set so the disc's 16:9 or
  4:3 shows right (64:45 pixels for a 16:9 PAL disc), in the Matroska track and in the HEVC or H.264
  stream itself; encoded at the title's own size by MediaCodec. Audio and subtitles are copied as
  in the lossless preset, LPCM to PCM or, when asked, FLAC.
- **Encoder choice**: the phone's hardware HEVC encoder; without one, H.264 on the hardware, and
  the source screen says so; without any hardware encoder, the slower software encoder, also said.
  An encoder that will not start makes way for the next before anything is written.
- **Progress**: frames per second and the pace against the film (in orange below 1×, when the rip
  takes longer than the film), the time left, and "Lezen en encoderen" in the notification.
- **Errors**: no encoder that starts (nothing is written), an encoder that fails during the rip
  (the unfinished file is deleted, and the screen says so).
- **History**: every rip records its quality and, for a lossy one, the encoder.

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
- For the NAS tests: Samba (`apt install samba`) and root or sudo, for the throw-away server of
  `scripts/samba-test-server.sh`.

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

The NAS tests need an SMB server. `scripts/samba-test-server.sh start /tmp/discrip-samba` starts
a throw-away Samba server on 127.0.0.1:1445 (shares `video` and `readonly`, user `discriptest`) and
prints the environment the core tests read; `stop` ends it. The folder must be reachable for other
users (Samba writes as `discriptest`), which a private home or temp folder may not be:

```sh
eval "$(scripts/samba-test-server.sh start /tmp/discrip-samba | sed 's/^/export /')"
./gradlew -p core smbTest               # the NAS client against that server
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.smbHost=10.0.2.2 \
    -Pandroid.testInstrumentationRunnerArguments.smbPort=$DISCRIP_SMB_PORT \
    -Pandroid.testInstrumentationRunnerArguments.smbShare=$DISCRIP_SMB_SHARE \
    -Pandroid.testInstrumentationRunnerArguments.smbReadOnlyShare=$DISCRIP_SMB_READONLY_SHARE \
    -Pandroid.testInstrumentationRunnerArguments.smbUser=$DISCRIP_SMB_USER \
    -Pandroid.testInstrumentationRunnerArguments.smbPassword=$DISCRIP_SMB_PASSWORD
```

An emulator reaches the machine it runs on as 10.0.2.2; a phone needs the machine's address on the
network, and the server bound to it (it listens on `lo` only). Without `smbHost` the instrumented
NAS tests are skipped; CI fails on skipped tests, so there they always run.

The native scripts can also be used on their own: `native/scripts/make-test-dvd.sh out.iso folder`
authors the test DVD, and `discrip_host` (in `native/build/host/` after `test-engine.sh`) lists
titles (`discrip_host titles image.iso`), rips (`discrip_host rip image.iso 1 out.mkv --audio
0x80:eng`, with `--encoder mpeg4 --bitrate 2500000` the way the lossy presets do, MPEG-4 Part 2
standing in for MediaCodec) and reads a Matroska file back (`discrip_host probe out.mkv`).

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
preinstalled NDK and CMake match): core tests, and the NAS client against a Samba server on the
runner; the rip engine tests on the runner (`native/scripts/test-engine.sh`); app unit tests, the
debug and release APKs (the debug one is uploaded as the `discrip-debug-apk` artifact), lint and
the 16 KB alignment check; instrumented tests on an API 30 x86_64 emulator, whose ARM translation
runs the arm64-only app, including the end-to-end rips of the test DVD to a local folder (lossless
and "Gebalanceerd", whose file ffprobe then checks: `scripts/check_lossy_output.py`) and to the
runner's Samba server; and a report of which pinned dependencies have newer stable releases
(`python3 scripts/dependency_report.py` does the same locally). The third-party sources and the
FFmpeg builds are cached between runs. The emulator needs 7.4 GB free next to the build, so the
job first removes toolchains it does not use (.NET, GHC, CodeQL, Swift).

## Credentials

All optional; the app works without any of them (manual metadata, local storage). Secrets are kept
in `EncryptedSharedPreferences` with a master key in the Android Keystore, and the app disables
backup and device transfer so they never leave the phone.

| What | Where to get it | Used for |
|---|---|---|
| TMDB API key (v3) or API read access token (v4) | themoviedb.org, account Settings, API | Searching films, localized titles, posters, ids |
| NAS user and password | Synology DSM, a user with read and write access to the share; Control Panel › File Services › SMB enabled (SMB 2 or 3) | Rips to the NAS |
| Plex token | Plex Web, open a film, ⋯, Get Info, View XML: the `X-Plex-Token` value in the address | Fetching library sections, scanning and checking new rips |

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
  plex/                   Plex client, path mapping and the scan-and-check of new rips
  nas/                    SMB client on smbj: folders, uploads, random-access files, errors
  history/                The rip history's records and JSON
  settings/               AppSettings (everything except secrets)
  usb/                    Bulk-Only Transport: CBW/CSW, reset recovery, the BulkTransport interface
  scsi/                   SCSI/MMC command builders, response parsers, OpticalDrive
  css/                    CSS authentication with the drive and its cipher (CryptKey)
  disc/                   Block devices (drive, ISO file), ISO 9660/UDF reader, disc probe,
                          monitor, DriveReader (how the rip engine reads a drive)
  dvd/                    Title list and rip report models, ISO 639 codes, track order, progress
  video/                  Which encoder a lossy preset uses (EncoderChoice)
  src/testFixtures/.../testing/  Simulated USB drive and CSS scrambler, also for the app's tests
  src/test/.../testing/   Disc images for the tests
  src/test/resources/     Disc image fixtures (and the script that makes them), CSS vectors
app/                      Android app
  data/                   DataStore settings, encrypted secrets, local folder grants, history file
  usb/                    libusb and Java USB transports, mass-storage interface lookup
  drive/                  DriveManager: discovery, permission, claim, polling, lending the disc
  rip/                    RipManager (rip, upload, Plex), DVD sessions over JNI, SAF folder
                          reader, outputs (local folder, app storage, NAS), destinations, the
                          device's video encoders (DeviceEncoders)
  work/                   The foreground service and its notifications
  src/main/cpp/           CMake build of libusb, libdvdcss, libdvdread and the engine
    engine/               The rip engine in C: sources, title plans, VOBU reader, remux to MKV,
                          and the lossy presets' video (dr_video: decode, yadif, aspect, encode)
    jni/, usb_jni.c       JNI bridges for the engine and for libusb
    host/                 discrip_host, the engine's command-line driver for the build machine,
                          and css_tool (CSS test vectors and scrambled copies from libdvdcss)
    config/               Hand-written config.h for libdvdcss and libdvdread
  ui/theme, ui/components HIG theme and components
  ui/home, ui/metadata, ui/settings, ui/source, ui/rip, ui/history, ui/navigation
native/scripts/           fetch-sources.sh (pinned, checksummed sources), build-ffmpeg.sh,
                          make-test-dvd.sh, test-engine.sh
native/tests/             test_engine.py: engine tests against the authored test DVD
scripts/                  dependency_report.py, check_native_alignment.py, test_summary.py,
                          samba-test-server.sh, check_lossy_output.py
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
    `native/scripts/build-ffmpeg.sh`), as shared libraries with unversioned names: for the lossy
    presets the MediaCodec encoders, the yadif, scale, setsar and format filters, and the
    `h264_metadata` and `hevc_metadata` bitstream filters (decision 42). The build machine's static
    FFmpeg adds the MPEG-4 Part 2 encoder for the engine tests.
20. **Licenses.** libdvdcss and libdvdread are GPL-2.0-or-later and are linked statically into
    `libdiscrip.so`, so that library, and with it the app, falls under the GPL. That is fine for a
    personal app that is not distributed; anyone who passes the APK on must offer this source
    code. FFmpeg (LGPL-2.1-or-later) and libusb (LGPL-2.1-or-later) stay separate shared libraries.
21. **A foreground service follows the work.** The rip and the upload run in `RipManager`; the
    service (`work/RipService`) starts when either starts, holds a partial wake lock, shows and
    updates the notification and stops when nothing runs. A rip from the drive makes it a
    `connectedDevice` service (the USB permission DiscRip holds is the prerequisite) and `dataSync`
    for the upload that follows, both from the start, while the app is in the foreground, because
    a type changed later, from the background, may be refused. Other work is `dataSync`, which
    Android 15 limits to six hours a day in the background; should that run out, the work goes on
    without the service. A Wi-Fi lock is held while files go
    to the NAS or Plex is asked; on Android 14 and later Android turns it into a low-latency lock
    that only works with the screen on, so with the screen off Wi-Fi may save power and transfer
    more slowly, but stays connected. The rip screen no longer keeps the display on.
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
25. **NAS client: smbj** (0.15.0, Apache 2.0), SMB 2 and 3 in pure Java. jcifs-ng, the other
    candidate, has had no release since 2023. smbj signs in with NTLM, with MD4 from BouncyCastle
    because neither Android nor the JDK offer MD4; it leaves Kerberos out on Android. There is no
    socket read timeout: smbj's reader thread takes one for a dead connection, and the connection
    sits idle while the drive reads. A NAS that does not accept the connection fails after 5
    seconds, a request that gets no answer after a minute (enough for disks that spin up first).
26. **NAS rips go through the phone first**, as specified: rip into the app's own storage, read
    back, upload as `.part`, compare the size on the NAS after a flush, rename, then delete the
    local copy. "Rechtstreeks naar de NAS" (off by default) lets the engine write to the share
    through JNI callbacks (`dr_file`): the muxer's seeks back (sizes, duration, index) and the
    read-back work over SMB. Without a local copy, a connection lost during the rip fails the rip.
27. **Checks before the rip.** Connecting, signing in, writing a test file in the films folder and
    comparing the free space happen when "Rip starten" is tapped and again when the rip starts, so
    the NAS problems the spec lists are reported before the drive spins, not after an hour.
28. **A failed upload is not lost.** When the NAS is gone by the time the upload starts, the rip
    still counts as done: the file stays in the app's storage (NAS) or the local folder (Beide)
    and the history offers "Opnieuw uploaden". Stopping an upload deletes the staged file of a NAS
    rip (stopping means stopping); a Beide rip keeps its local copy. Staged files no history entry
    refers to (left by a crash) are deleted at the next start.
29. **Plex scans only the new folder.** The library's folders as Plex sees them are matched to the
    share and folders on the NAS by their last path segments (`/volume1/video/Films` for share
    `video`, folder `Films`; `D:\Films` or `\\nas\video\Films` for Plex on Windows), and Plex is
    asked to scan just the film's folder (`/library/sections/<key>/refresh?path=…`). When no folder
    matches, or the film does not show up within a minute, the whole library is scanned. A rip
    counts as found when the newest items of the library include a file with its name; after two
    minutes the app stops waiting and says so. Plex problems never undo a rip.
30. **History** as JSON in the app's storage: finished, failed and cancelled rips, the last 500. A
    damaged file is set aside as `history.json.broken` rather than silently dropped.
31. **NAS paths are shown as `smb://host/share/…`**, the form Android and iOS file managers use.
32. **R8 and smbj.** smbj receives connection events through mbassador, which finds the handler
    methods by annotation; those methods and mbassador are kept. Kerberos (`org.ietf.jgss`) and
    mbassador's EL filters (`javax.el`) do not exist on Android and are never used there.
33. **Tests against real servers.** The NAS client is tested against a throw-away Samba server
    (`scripts/samba-test-server.sh`), in `core` and from the emulator, which reaches the runner as
    10.0.2.2; a MockWebServer on the device plays Plex. The instrumented test summary fails on
    skipped tests, so the NAS tests cannot drop out unnoticed.
34. **CSS** is handled as described in "CSS on real drives" below: authentication in Kotlin, the
    engine decrypting with its own libdvdcss instance, no key cache.
35. **One read attempt from the drive.** The engine already retries every block range three times
    and skips what stays unreadable; retries in the drive layer as well would multiply, and on a
    scratched disc every failed read can cost the drive seconds. Conditions that cannot improve end
    the rip at once instead of marking every following block unreadable: the drive gone (a USB
    error, or no answer to TEST UNIT READY after a failed transfer), no disc, a region refusal, and
    protected sectors refused right after a new authentication. After a unit attention the disc's
    size and primary volume descriptor are compared with the disc being read: the same disc reads
    on (re-authenticated if needed), another one ends the rip.
36. **The disc is lent to the engine.** While the source screen or a rip has the disc, the drive
    monitor does not poll: its TEST UNIT READY would take the unit attention of a disc change that
    the rip has to see. Reads hop onto the drive's own thread, where every command to the drive
    runs. When the loan ends the drive is read afresh if the reads saw a disc change or reset.
    Each probe of a disc gets a new number, so a disc put in again, even the same one, is a new
    source.
37. **Locked tray, eject after success.** The tray is locked (PREVENT MEDIUM REMOVAL) while the
    drive is read, so a press on the drive's button cannot end a rip, and unlocked after; a tray an
    earlier run left locked is unlocked when the drive connects. After a good rip the disc comes
    out at once, before the upload, so the next one can go in; after a failed rip it stays in.
38. **The film details go with the rip.** After a good rip the draft (title, year, TMDB id) is
    cleared, so a batch cannot give the next disc the last film's name.
39. **Notification permission at the first rip.** Android 13 and later ask for it when "Rip
    starten" is first tapped, when the reason is clear; the rip starts whatever the answer.
40. **Drive tests without a drive.** core's simulated drive (Bulk-Only Transport, SCSI, CSS
    authentication, region, faults) is a test fixture that the instrumented tests use too.
41. **The lossy video path** (`engine/dr_video.c`). FFmpeg decodes the MPEG-2 video in software
    (slice threads). A filter graph, built for the frames as they come and rebuilt when their size,
    format or colours change, runs `yadif=mode=send_frame:parity=auto:deint=interlaced` (only
    frames flagged interlaced, one frame out per frame in), `scale` to the title's size (nothing
    for frames of that size; titles that change size halfway are rare), `setsar` and `format`
    (NV12, else planar 4:2:0, whichever the encoder starts with). The pixel aspect follows from the
    IFO's display aspect and the full frame, as FFmpeg's MPEG-2 decoder and the lossless copy have
    it: 64:45 for 720×576 at 16:9, 16:15 at 4:3, 32:27 and 8:9 for NTSC. Frame times stay as on the
    disc, so soft-telecined film keeps its 24 frames a second.
42. **The aspect ratio in the stream as well.** MediaCodec has no portable way to put the pixel
    aspect into the SPS, and a player that ignores Matroska's display size (or a Plex remux to
    MPEG-TS for a TV) would show a 16:9 disc squeezed. FFmpeg's `hevc_metadata` and `h264_metadata`
    bitstream filters therefore write it into the parameter sets, in the CodecPrivate and in the
    stream; Matroska's display size carries it too. CI takes the emulator's stream out of Matroska
    and checks it with ffprobe.
43. **One encoder tick per frame**, as ffmpeg sets it. FFmpeg's own encoders (the MPEG-4 stand-in
    on the build machine) count frames in the time base to spread the bit rate; in milliseconds they
    missed the target completely. MediaCodec only converts it to microseconds.
44. **Which encoder.** From MediaCodecList (regular codecs, no aliases): encoders that take the
    title's size from buffers (NV12, planar or flexible 4:2:0). For the HEVC presets: hardware HEVC,
    then hardware H.264, then software HEVC, then software H.264; for "Breed compatibel" hardware,
    then software H.264. The preset's bit rate stays on a fallback, so the file comes out at the size
    shown; H.264 at HEVC's rate looks somewhat worse, and the screen says H.264 is used. VBR where
    the encoder can, else CBR, else its own mode. FFmpeg drives MediaCodec through the NDK (it gets
    no JVM) and opens the component by name (`codec_name`).
45. **Encoder failures.** An encoder that will not open fails the engine before it writes, and the
    next one takes over, in the same file. One that fails during the rip ends it; the unfinished
    file is deleted and the message says so. Starting over with another encoder by itself would
    double the time of a long rip, so that is left to the user ("Breed compatibel" or "Beste
    kwaliteit").
46. **Testing without MediaCodec.** The build machine has no MediaCodec, so its FFmpeg adds the
    MPEG-4 Part 2 encoder and the engine tests run the whole lossy path with it: yadif checked with
    FFmpeg's `idet` on an interlaced title of the test DVD, the bit rate, the 16:9 aspect, audio and
    subtitles packet for packet as in the lossless rip, write failures, cancelling, a missing
    encoder. The emulator rips "Gebalanceerd" through the app with its own (software) encoders,
    also with an encoder that will not start, and leaves the file for CI's ffprobe check.

## CSS on real drives (phase 6)

libdvdcss recovers title keys from the scrambled sectors themselves (`DVDCSS_METHOD=title`), as
specified; that works the same for images and drives. Current drives (RPC-II), however, refuse to
hand out CSS-scrambled sectors until the host has gone through CSS authentication (sense 05/6F/03,
"read of scrambled sector without authentication"), and libdvdcss's stream API, which DiscRip
uses because apps cannot open `/dev/sr0`, cannot send SCSI commands. The authentication therefore
runs in the Kotlin SCSI layer (`core/css`): REPORT KEY and SEND KEY for the grant ID, both
challenges and keys, then the disc key read, after which the drive sets its authentication success
flag (a flag left clear means a region the drive refuses). Its cipher, libdvdcss's CryptKey, is
ported to Kotlin and checked against 288 results of libdvdcss's own code (`css_tool vectors`, also
compared in CI). The drive's title keys are not used: decrypting them would need the player keys
and the disc key cracking of libdvdcss's other methods, while the title method needs neither.

Three things differ from the original plan, all because of how libdvdcss and libdvdread behave:

- **The engine decrypts title VOBs itself**, through its own libdvdcss instance, instead of letting
  libdvdread do it. When libdvdcss finds no key for a title set, libdvdread ignores the error and
  reads on with the previous title set's key, or with one never set (libdvdcss does not clear it):
  the rip would be garbage that nothing notices. The engine recovers the key at the first scrambled
  block it reads, only for the title set being ripped, checks that it got one, and otherwise stops
  the title with a message that its key could not be found, as specified. A block that cannot be read during the
  search does not end it: the search starts again past it. The search reads block by block, on a
  disc without a usable key through the whole title, and can be stopped.
- **libdvdcss's key cache is off** (`DVDCSS_CACHE=off`, not the app's cache directory). Without the
  disc key, which the stream API never sees, libdvdcss names a disc's cache after its volume label
  and date alone. Discs of a box set can share those, and a decrypted copy shares them with its
  original; a key cached for the wrong disc decrypts to garbage, and a cached "unencrypted" makes
  the original fail. Recovering the key of one title set takes a second or two.
- **libdvdread no longer cracks every key** at the first VOB it opens (`DVDREAD_NOKEYS`); the engine
  only needs the title set it rips.

The engine tests rip copies of the test DVD scrambled with libdvdcss's own cipher (`css_tool
scramble`): the output equals the clear rip, also when scrambling starts mid-title; a title whose
key cannot be found is refused; unreadable blocks in the search are skipped. On the emulator, a
simulated drive (BOT, SCSI, CSS gating, region) serves a CSS copy scrambled by a Kotlin port of the
cipher, checked byte for byte against libdvdcss.

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
   from phase 6 on the screen may go off and the notification shows the progress.
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

Phase 5 with a Synology and Plex:

1. In DSM, enable SMB (Control Panel › File Services › SMB) and give a user read and write access
   to the `video` share. In Plex, make sure the film library includes the folder the rips go to
   (for example `/volume1/video/Films`).
2. In Instellingen › NAS, enter the NAS's address, `video`, the user and the password, and tap
   "Verbinding testen": "Verbonden en schrijven mag" with the free space. Try a wrong password and
   a share without write access: each gets its own message.
3. In Instellingen › Plex, enter the address and token, load the libraries and choose the film
   library.
4. Rip an image with "Bewaren op: NAS": the progress shows the rip, then the upload, then "Wachten
   tot Plex de film toont…". The result lists `smb://…` and "In de bibliotheek als …"; the film
   appears in Plex within seconds, without a full library scan (Plex's log shows a scan of one
   folder). Nothing is left in the app's storage.
5. Repeat with "Beide": the MKV is in the local folder and on the NAS. Repeat with "Rechtstreeks
   naar de NAS" on.
6. Switch the NAS off and tap "Rip starten": "De NAS is niet bereikbaar". Switch it off during a NAS
   rip instead: the rip finishes, the upload fails, and Geschiedenis shows "Wacht". Switch the NAS
   on again and tap "Opnieuw uploaden".
7. Change the Plex token to a wrong one: the rip still succeeds and the result says Plex refuses
   the token.

Phase 6 with the drive:

1. Insert an unprotected disc (a home-made DVD, or the test DVD burnt to a DVD±R) and tap
   "Rippen" under "Station": the titles appear. Rip it: during the rip the drive's eject button
   does nothing; afterwards the disc comes out and the rip screen says "Steek de volgende disc in".
2. Insert a commercial CSS-protected disc with the drive set to region 2: the titles appear after
   a moment (authentication). Rip the main feature and check it in VLC: no green blocks or
   garbage, sound in sync. The log (`adb logcat -s DiscRip`) shows "CSS title key found".
3. Batch: while the rip screen says "Steek de volgende disc in", put the next disc in; "Volgende
   disc rippen" appears once the drive has read it. With a NAS rip, do this during the upload.
4. Turn the screen off during a rip and leave the phone for ten minutes: the rip goes on; the
   notification shows progress and "Stoppen", and says how the rip ended.
5. Pull the USB cable during a rip: "Het station werd losgekoppeld of reageert niet meer", no file
   left behind, and Geschiedenis lists it with that reason.
6. Open the tray with the paper-clip hole during a rip (the button is locked): "De disc werd uit
   het station gehaald of vervangen".
7. A disc of another region (or a drive whose region was never set): the source screen says the
   drive refuses the disc and how to set the region on a PC.
8. A scratched disc: the rip goes on past unreadable sectors and the result lists them; on a disc
   where the CSS key search runs into a scratch, the search starts again past it.

Phase 7 on a phone:

1. Instellingen › Kwaliteit, "Video-encoders op dit toestel": HEVC and H.264 should say "Hardware"
   with a component such as `c2.qti.hevc.encoder` or `c2.exynos.hevc.encoder`.
2. Open a disc or an image, tap "Kwaliteit" and choose "Gebalanceerd": the sizes in the list follow
   the title and the tracks, and the footer says the video becomes HEVC on the hardware encoder.
3. Rip: "Lezen en encoderen", frames per second and the pace; on a recent phone the pace should be
   well above 1×. Turn the screen off; the notification keeps counting.
4. Play the MKV in Plex (also in a TV app) and in VLC: a 16:9 disc fills the width without being
   squeezed, a 4:3 disc stays 4:3, an interlaced disc (TV series, concerts) shows no combing on
   moving edges, and chapters and audio tracks are as in a lossless rip.
5. Sizes: a two-hour film should come to about 2 to 3 GB with "Gebalanceerd" and about 1 GB with
   "Meeste compressie".
6. On a phone without a hardware HEVC encoder (older or budget models) the source screen says the
   rip uses H.264.

## Privacy

No analytics, no crash reporting, no accounts. Network traffic goes only to TMDB (when a key is
set), your NAS and your Plex server.
