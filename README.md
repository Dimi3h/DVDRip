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
| 2 | USB layer: enumerate the drive, INQUIRY, detect the disc, read sector 16 and show the volume label; fake block device backed by an ISO | planned |
| 3 | Native build (libdvdread, libdvdcss, FFmpeg), JNI bridge, ISO to MKV end to end with the lossless preset | planned |
| 4 | Main-feature detection, audio and subtitle selection, chapters, tags, size estimates, free-space check | planned |
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

USB, disc reading and ripping arrive in phases 2 and 3; the home screen does not pretend otherwise.

## Building

Requirements:

- JDK 17 or newer (CI uses Temurin 21). The Gradle wrapper (9.8.2) downloads Gradle itself.
- Android SDK with the API 37 platform and current build tools (Android Studio installs them;
  otherwise the Android Gradle plugin downloads missing ones when the SDK licenses are accepted),
  and `local.properties` with `sdk.dir` or the `ANDROID_HOME` environment variable.
- From phase 3: NDK r27 or newer and CMake (installed through the SDK manager).

Toolchain: Android Gradle plugin 9.4 with its built-in Kotlin support, Kotlin 2.4, Compose BOM
2026.09. `compileSdk` is 37 because current AndroidX, Compose and OkHttp releases require it;
`targetSdk` stays 35 and `minSdk` is 29 (Android 10).

Commands (from the repository root):

```sh
./gradlew -p core test                  # platform-independent logic; needs no Android SDK
./gradlew :app:testDebugUnitTest        # app unit tests on the JVM
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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

The wrapper has no `distributionSha256Sum` yet: the official checksum host was not reachable from
the environment this was written in. To pin it, run
`./gradlew wrapper --gradle-version 9.8.2 --gradle-distribution-sha256-sum <sum>` with the sum from
https://gradle.org/release-checksums/.

**CI.** `.github/workflows/ci.yml` runs on every push: core tests, app unit tests, the debug APK
(uploaded as the `discrip-debug-apk` artifact) and lint. A third job prints which pinned
dependencies have newer stable releases (`python3 scripts/dependency_report.py` does the same
locally).

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
app/                      Android app
  data/                   DataStore settings, encrypted secrets, local folder grants
  ui/theme, ui/components HIG theme and components
  ui/home, ui/metadata, ui/settings, ui/navigation
scripts/dependency_report.py
```

## Decisions

These were made without asking because they do not change the architecture; each can be revisited.

1. **`core` is a separate Gradle build** included with `includeBuild("core")`. It holds all logic
   that does not need Android (naming, presets, estimates, TMDB and Plex clients; later the SCSI
   command builders, CBW/CSW parsing and title scoring) and depends only on Maven Central, so its
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

## Hardware checklist (phases 2 and 6)

- A phone with USB host (OTG) support; Android 10 or newer.
- A USB DVD drive. Slim drives draw up to about 1 A at 5 V when spinning up, more than many phones
  supply over OTG: use a drive with its own power supply, a Y-cable, or a powered USB hub.
- An OTG adapter or USB-C hub that passes data (and charges the phone during long rips, if it can).
- The drive's region set to 2 (RPC-2 drives refuse to read CSS-protected discs from other regions;
  the region can only be changed on a PC, a limited number of times).
- Test material: an unencrypted DVD-Video ISO (generated in CI from phase 3) and a few real discs,
  including one with many near-identical titles.

## Privacy

No analytics, no crash reporting, no accounts. Network traffic goes only to TMDB (when a key is
set), your NAS and your Plex server.
