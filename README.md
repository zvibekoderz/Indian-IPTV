# Indian IPTV

[![Android build](https://github.com/zvibekoderz/Indian-IPTV/actions/workflows/android-build.yml/badge.svg)](https://github.com/zvibekoderz/Indian-IPTV/actions/workflows/android-build.yml)

A lightweight, open-source IPTV player for Android TV 10+, built natively in
Kotlin with Compose for TV and Media3/ExoPlayer. Pre-loaded with **Indian
channels** from the [iptv-org](https://github.com/iptv-org/iptv) curated
playlist, and supports **M3U** playlists and **Xtream Codes** providers with
custom HTTP headers via `#EXTVLCOPT`.

Originally tuned for the **Formuler Z10 Pro Max**, but runs on any Android TV
device with API 29 or higher.

### TV and phone are separate builds

The project uses product flavours instead of one universal APK:

| Flavour | Package | Min Android | Launcher |
|---|---|---|---|
| `tv` | `com.indian.iptv` | 10 (API 29) | Leanback launcher |
| `mobile` | `com.indian.iptv.mobile` | 9 (API 28) | Phone app drawer, portrait allowed |

Build them with `./gradlew :app:assembleTvDebug` and `:app:assembleMobileDebug`.
Both can be installed on the same device; the phone UI is the same D-pad-first
interface — cards, tabs and search respond to touch.

> **Disclaimers.** This project is an independent community app, **not
> affiliated with or endorsed by Formuler**. "Formuler" and "Z10 Pro Max" are
> trademarks of their respective owners. The app is a media player — it ships
> no channels, no streams and no credentials. You are responsible for the
> legality of the sources you connect to it. Piracy is not supported.

## Features

### Indian Channels Ready Out of the Box
- **Default playlist**: pre-filled with iptv-org's India list (`countries/in.m3u`, ~220 KB) — just press **Save** in the wizard
- **100+ Indian channels** across news, entertainment, sports, movies, music, kids, and regional languages
- Works immediately on first launch — just tap "Save" in the wizard

### Sources
- **Multiple playlists at once** — add as many M3U URLs as you like; every channel is merged into one catalogue. Ids from the first playlist stay stable, so favourites survive; a clash in a later playlist gets a `@s<index>` suffix
- **DNS-over-HTTPS fallback** — if the device resolver answers `0.0.0.0` (ISP or ad-blocker DNS filtering), downloads resolve through `1.1.1.1` / `8.8.8.8` over HTTPS instead of failing
- **Type the source on your phone** — Settings shows a QR code; scanning it opens a small form in the phone's browser (a token-protected LAN server) and submits the playlist URL or Xtream credentials straight to the TV
- **M3U / M3U8** playlists (extended format with `tvg-*`, `group-title`, and **`#EXTVLCOPT`** custom headers)
- **Xtream Codes** accounts (live, VOD, series) via a typed Retrofit client
- **Custom HTTP headers** per channel: `#EXTVLCOPT:http-user-agent=...` and `#EXTVLCOPT:http-referrer=...` — essential for providers that require specific User-Agent or Referrer
- Unified `Channel` model (`TV`, `MOVIE`, `SERIES`) so the rest of the app doesn't care where the data came from
- Local Room cache with ETag / Last-Modified awareness — refreshes fire only when the upstream playlist actually changed
- Manual refresh and optional scheduled nightly refresh via WorkManager

### Live TV
- Category rails and an "All channels" grid
- **Now-on progress** — every live card shows the current programme plus a thin bar for how far through it is
- **HD / NEW badges** on cards so quality and freshly-added channels are visible at a glance
- D-pad navigation, Play/Pause, Channel Up/Down, numeric channel entry
- **Programme reminders** — OK on an upcoming programme in the guide sets a reminder; the app prompts you when it starts
- **Catch-up / timeshift** on Xtream sources — watch a programme from earlier in the day (start-over and archive replay where the provider allows it)
- Audio track and subtitle selection, with adjustable subtitle delay
- Stream stats overlay (resolution, bitrate, codec, buffer, dropped frames)
- Now/Next from XMLTV EPG when the provider exposes it

### Movies & Series (VOD)
- Dedicated detail screens for movies and for series (seasons → episodes)
- Resume playback per profile, with a "Continue watching" rail on the home screen
- "My list" favourites and a "Related" rail on detail pages
- **Watchlist** ("save for later") per profile, separate from favourites — add from any card's context menu or a detail screen, with its own home rail
- Recent-search history and on-screen voice input (where the device supports it)

### Profiles
- Multiple user profiles, each with its own favourites, watch progress and recent searches
- **Kids profile** mode — hides adult categories, requires PIN to switch
- A `default` profile is seeded so single-user setups need no configuration

### TMDB enrichment (optional)
- Posters, overviews, popularity ranking and a "Popular now" rail
- Hero carousel on the Movies/Series tabs with a muted **trailer autoplaying** in the background
- Entirely optional — without a TMDB token the feature silently disables and the rest of the app keeps working

### Player
- Media3 / ExoPlayer with HLS, DASH and progressive TS support
- Adaptive playback with explicit audio-capability detection for the set-top box's HDMI audio path
- **Automatic frame-rate matching** — switches the display to the content's refresh rate (24/25/30/50/60 fps) to remove judder, with a render-rate fallback for tunneled TS
- Configurable aspect ratio (Fit / Fill / Zoom)
- Remote-friendly overlay panels for audio, subtitles (with adjustable delay) and info, all reachable through the coloured remote buttons

## Requirements

| | |
|---|---|
| Device | Android TV 10+ (API 29), D-pad remote |
| JDK to build | 17 or 21 (AGP 8.5.2 does not support JDK 25) |
| An IPTV source | An M3U URL **or** Xtream Codes credentials (optional — default Indian playlist works out of the box) |
| TMDB token | Optional — only for posters & metadata |

## Building

Copy `local.properties.example` to `local.properties` and point `sdk.dir` at your Android SDK (the file is git-ignored, so secrets stay local). Then:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests:

```sh
./gradlew test
```

### TMDB token (optional)

To enable poster and metadata enrichment, get a TMDB **v4 API Read Access Token** from <https://www.themoviedb.org/settings/api> and add it to `local.properties` (git-ignored):

```
TMDB_BEARER_TOKEN=eyJhbGciOi...
```

Without a token the app behaves identically except TMDB-powered rails and artwork are hidden.

## Installing on an Android TV device

1. Enable **Developer options** on the TV (Settings → Device Preferences → About → tap Build seven times) and turn on **ADB debugging** under Developer options.
2. Note the device IP (Settings → Network).
3. From your dev machine:

   ```sh
   adb connect <tv-ip>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.indian.iptv/.MainActivity
   ```

## First run

On first launch a welcome wizard opens with **iptv-org's India playlist pre-filled** (`countries/in.m3u`). Just press **Save** to start watching Indian channels immediately, then add more playlist URLs under Settings → Set Source → **Add playlist** (sports, movies, regional…). All of them merge into one catalogue.

Useful tips:

- A USB keyboard paired to the TV saves a lot of D-pad typing when entering URLs or credentials — or skip typing entirely: Settings shows a **QR code** that opens the source form in your phone's browser.
- On-screen text fields also accept `adb shell input text "..."` from a connected dev machine.
- Additional profiles can be created under Settings → Profiles.

## M3U EXTVLCOPT Custom Headers

If your provider requires specific headers, add them to your M3U file:

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="starplus" tvg-logo="https://..." group-title="Entertainment",Star Plus
#EXTVLCOPT:http-user-agent=Mozilla/5.0 (Linux; Android 10) CustomUA
#EXTVLCOPT:http-referrer=https://example.com/
http://stream.example.com/starplus.m3u8
```

The parser extracts `http-user-agent` and `http-referrer` and applies them automatically during playback via OkHttp interceptor.

## Tech stack

- **Kotlin** + **Coroutines** / **Flow**
- **Jetpack Compose** + **Compose for TV** (`androidx.tv.foundation`, `androidx.tv.material`)
- **Media3 / ExoPlayer** (HLS, DASH, TS)
- **Room** for the catalogue and watch-progress cache
- **Retrofit** + **kotlinx.serialization** for Xtream and TMDB APIs
- **OkHttp** (shared across Retrofit and Coil) with custom header interceptor and a DNS-over-HTTPS fallback resolver
- **WorkManager** for the scheduled refresh
- **DataStore (Preferences)** for settings
- **Coil** for image loading

The app intentionally avoids dependency-injection frameworks — `IptvApp` doubles as a small service locator.

## Project structure

```
app/src/main/java/com/indian/iptv/
  IptvApp.kt                    Application class: service locator, Coil
                                config, refresh scheduler wiring
  MainActivity.kt               Compose root + route host (Splash / Welcome /
                                Channels / Settings / Profiles /
                                MovieDetail / SeriesDetail)
  player/
    PlayerActivity.kt           ExoPlayer + PlayerView, D-pad key handling,
                                resume persistence, custom header injection
    PlayerScreen.kt             Compose layer around PlayerView
  data/
    Channel.kt                  Unified domain model (TV / MOVIE / SERIES)
                                with userAgent / referrer fields
    m3u/M3uParser.kt            Extended M3U / M3U8 parser with EXTVLCOPT
                                support (unit-tested)
    xtream/                     Retrofit API + DTOs (live, VOD, series)
    epg/XmltvParser.kt          XMLTV parser for now/next
    tmdb/                       TMDB API client, matchers, popular repo
    remote/HttpClient.kt        Shared OkHttp + Retrofit + custom header interceptor
    remote/DohDns.kt            DNS-over-HTTPS fallback resolver (1.1.1.1 / 8.8.8.8)
    db/                         Room entities, DAOs, mappers
    settings/                   DataStore-backed settings, SourceConfig and the
                                QR-code phone setup server
    reminders/                  EPG programme reminders
    repo/                       PlaylistRepository abstraction,
                                refresh use case, scheduler and WorkManager
                                worker
    catchup/Catchup.kt          Xtream catch-up with header preservation
  ui/
    theme/                      Compose theme
    splash/                     Splash screen
    wizard/                     First-run welcome flow (Indian playlist pre-filled)
    settings/                   Settings screen + VM
    profiles/                   Profiles screen + VM
    channels/                   Home screen: rails, search, favourites
    detail/                     Movie detail screen + VM
    seriesdetail/               Series detail screen + VM
```

Unit tests live under `app/src/test/`. The M3U parser has the most coverage; the rest of the codebase leans on manual exploratory testing on the device.

## Known limitations

- **Text entry on a remote is painful.** Use a USB keyboard or `adb shell input text`. A leanback-friendly input dialog is not yet implemented.
- **Cleartext HTTP is enabled app-wide** because many IPTV providers still serve plain HTTP. See [`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml).
- **Compose for TV is on `1.0.0-alpha10`.** A known focus-search crash inside the episode list is swallowed in `MainActivity.dispatchKeyEvent`; it goes away when the Compose BOM can be bumped.
- **TMDB matching is best-effort** based on title and year; uncommon releases may stay without artwork.
- **No DRM.** Providers don't serve DRM streams over M3U/Xtream. Catch-up is supported on Xtream sources; there is no PVR and no Chromecast.

## Contributing

Issues and pull requests are welcome. Because this started as a personal project:

- Keep dependencies light — the service-locator approach is intentional.
- Prefer small, focused PRs with a short description of the user-visible behaviour.
- Changes that touch the player or the catalogue refresh logic should be tested on real hardware; the emulator does not faithfully reproduce Android TV's D-pad focus or HDMI audio behaviour.
- New parser logic should ship with unit tests; see `app/src/test/.../M3uParserTest.kt` for the style.

## Acknowledgements

- [Media3 / ExoPlayer](https://developer.android.com/media/media3) for the playback stack.
- [iptv-org](https://github.com/iptv-org/iptv) for the curated Indian channel playlist used as the default source.
- [The Movie Database (TMDB)](https://www.themoviedb.org/) for metadata and artwork — used under their API terms of use. This product uses the TMDB API but is not endorsed or certified by TMDB.
- The Android TV and Compose for TV teams for the underlying UI primitives.

## License

Licensed under the [Apache License 2.0](LICENSE). You may use, modify and distribute this code within the terms of that license.