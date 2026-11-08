# DiscRip voor macOS

Persoonlijke Mac-app die DVD-Video ript naar Matroska (`.mkv`), metadata invult (TMDB of zelf),
lokaal en/of op een Synology NAS bewaart en Plex de film laat oppikken. Het is de macOS-versie
van de Android-app "DiscRip". De vereisten zijn dezelfde, maar het ontwerp is op macOS afgestemd
en geen letterlijke port.

## Van Android naar macOS: keuzes

| Android-ontwerp | macOS-ontwerp | Waarom |
|---|---|---|
| Eigen USB-driver (BOT/SCSI/MMC via USB Host API) | macOS-kernel stuurt de drive aan (IOKit); DiscRip leest `/dev/rdiskN`, DiskArbitration voor insert/eject/label | macOS ondersteunt optische drives zelf. Geen eigen driver nodig, geen root, geen kernel-extensie. |
| `DVDCSS_METHOD=title` via stream-API | `DVDCSS_METHOD=key`: echte CSS-authenticatie met de drive (libdvdcss valt zelf terug op disc/title cracking) | Op macOS kan libdvdcss de drive aanspreken via de DVD-ioctls. Dat is betrouwbaarder. |
| `hevc_mediacodec` / `h264_mediacodec` | `hevc_videotoolbox` / `h264_videotoolbox` | Hardware-encoders van Apple Silicon. HEVC valt terug op H.264 als hij ontbreekt. |
| SAF document tree | `NSOpenPanel` + bookmark | Werkt ook voor USB-sticks en externe schijven. |
| smbj | macOS-SMB-client (NetFS-mount), kopie met controle van de bestandsgrootte | Systeemcomponent, geen extra afhankelijkheid. |
| EncryptedSharedPreferences | Sleutelhanger van macOS | |
| Foreground service + wake lock | `ProcessInfo` activity (Mac blijft wakker), Dock-badge, meldingen; de rip loopt door als het venster dicht is | |
| Jetpack Compose in iOS-stijl | SwiftUI volgens de **macOS**-HIG: zijbalk, gegroepeerde formulieren, segmented controls, Instellingen-venster (⌘,), licht/donker, haptiek op trackpads | Een Mac-app hoort als een Mac-app aan te voelen. |
| Regio: sense key 5 / ASC 0x6F | Regiostatus via `DKIOCDVDREPORTKEY` (RPC-2), vergeleken met het regiomasker van de disc vóór het rippen | |

Niet overgenomen, omdat het op macOS overbodig is: de unit tests voor SCSI-commandobuilders en
CBW/CSW-parsing (er is geen eigen USB-laag). In de plaats daarvan zijn er tests voor de regiologica.

## Architectuur

- `native/scripts/build-native.sh`: reproduceerbare statische arm64-build van **libdvdcss 1.6.0**,
  **libdvdread 7.1.1** (VideoLAN) en **FFmpeg 9.0.2** (LGPL-configuratie met VideoToolbox), met controle
  van de SHA-256-checksums.
- `Sources/CDiscRip` (C): opent de disc, beschrijft titels, cellen, hoofdstukken en streams als JSON,
  en voert de rip-pipeline uit. Die loopt de PGC-cellen af met `DVDReadBlocks` (32 sectoren per
  oproep, 3 retries per bereik, onleesbare bereiken markeren en overslaan), zet de tijdstempels per
  cell recht (naadloze overgangen), demuxt MPEG-PS via een eigen AVIOContext, kopieert of encodeert
  (MPEG-2 software-decode → yadif alleen op interlaced frames → setsar → VideoToolbox) en muxt naar
  MKV met hoofdstukken, tags, taalcodes en VobSub met palet. Er wordt rechtstreeks naar het doelbestand
  geschreven, zonder tijdelijke kopie van meerdere GB.
- `Sources/DiscRipCore` (Swift): modellen, hoofdfilmdetectie, Plex-naamgeving, presets en schattingen,
  instellingen, TMDB-, Plex- en NAS-clients, drive-monitor, geschiedenis en `RipJob` (controles →
  rip → upload met controle → Plex-scan).
- `Sources/DiscRip` (SwiftUI): de app.

LPCM: Matroska kan DVD-LPCM niet rechtstreeks opslaan. DiscRip bewaart het daarom bit-exact als
PCM (`A_PCM/INT/LIT`), of als FLAC als de schakelaar aan staat. Beide zijn verliesvrij.

## Bouwen

Vereist: macOS 14 of nieuwer, Apple Silicon, de Xcode Command Line Tools (`xcode-select --install`)
en internet voor de bronnen. Xcode is niet nodig.

```bash
native/scripts/build-native.sh        # libdvdcss, libdvdread, FFmpeg → native/out (± 5 min)
native/scripts/build-test-tools.sh    # dvdauthor (alleen voor de test-ISO)
scripts/build-app.sh                  # → dist/DiscRip.app (ad-hoc ondertekend)
open dist/DiscRip.app
```

Tests (40, waaronder end-to-end-rips van de test-ISO, lossless en Gebalanceerd):

```bash
swift run --build-system native DiscRipTestRunner
```

Zonder Xcode vindt `swift test` geen Swift Testing-tests. Daarom roept `DiscRipTestRunner` het
entrypoint van Swift Testing zelf aan.

De test-disc `testdata/DISCRIP_TEST_D1.iso` (10 MB, PAL 16:9, 2 titels, 3 hoofdstukken, AC-3 en
LPCM, twee VobSub-sporen, tijdstempelbreuk tussen twee VOB's) maak je opnieuw met
`scripts/make-test-iso.sh`.

Opmerkingen bij de build zonder Xcode:
- In SDK 27 is `@State` een macro waarvan de plugin alleen met Xcode meekomt. De app gebruikt
  daarom `@ViewState`, een typealias naar dezelfde property wrapper.
- De scripts gebruiken `--build-system native`. Met `SCRATCH=/pad` kies je een andere build-map.
- Debug-uitvoer van de pipeline per pakket: `DISCRIP_DEBUG=1`.
- `DiscRip --snapshot <map> --open <iso>` rendert de schermen offscreen naar PNG.

## Instellingen en gegevens

| Wat | Waar |
|---|---|
| TMDB-API-sleutel (v3) of leestoken (v4) | Instellingen › TMDB (sleutelhanger) |
| NAS-host, share, mappen, gebruiker, wachtwoord | Instellingen › NAS (wachtwoord in de sleutelhanger) |
| Plex-URL, token, bibliotheken | Instellingen › Plex (token in de sleutelhanger) |
| Geschiedenis | `~/Library/Application Support/DiscRip/history.json` |
| Tijdelijke opslag voor NAS-only | `~/Library/Application Support/DiscRip/Staging` |
| Logs per rip | `~/Library/Logs/DiscRip` |
| CSS-sleutelcache | `~/Library/Caches/DiscRip/dvdcss` |

De app is ad-hoc ondertekend. Na elke nieuwe build kan macOS dus één keer vragen of DiscRip de
sleutelhanger mag gebruiken. Bij de eerste NAS- of Plex-verbinding vraagt macOS toestemming voor
het lokale netwerk.

Plex: de map die Plex scant, is de locatie van de gekozen bibliotheek (zoals Plex die zelf ziet,
bv. `/volume1/video/Films`) plus de filmmap. De laatste component van de NAS-filmmap moet daarom
overeenkomen met die bibliotheeklocatie.

## Hardware-checklist

1. Sluit de USB-dvd-drive aan. Op Home staat de naam van de drive met "USB".
2. Steek een DVD-Video in. DiscRip opent hem vanzelf; het lezen van de CSS-sleutels duurt soms
   een minuut.
3. Verschijnt er een regiowaarschuwing, zet de drive dan eenmalig op regio 2 (de teller is beperkt).
4. Controleer de voorgestelde hoofdfilm, de audio, ondertitels, kwaliteit en "Bewaren op".
5. Kies op het metadata-scherm een TMDB-resultaat of vul zelf in, en start de rip.
6. Na een geslaagde rip wordt de disc uitgeworpen ("Steek de volgende disc in"); de volgende disc
   opent vanzelf.
7. Test de foutgevallen één keer: trek de USB-kabel uit tijdens een rip (duidelijke fout, geen half
   bestand), haal de NAS van het netwerk, gebruik een fout Plex-token, en gebruik een volle USB-stick.

## Licenties

FFmpeg is LGPL-geconfigureerd. libdvdcss en libdvdread zijn GPL-2.0-or-later, waardoor de app als
geheel onder de GPL valt. Dat is geen probleem voor persoonlijk gebruik zonder verspreiding.
dvdauthor (GPL) wordt alleen gebruikt om de test-ISO te maken en zit niet in de app.
