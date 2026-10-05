<div align="center">

<img src="app/src/main/res/drawable/lunara_logo.jpg" alt="Lunara logo" width="120" height="120" />

# Lunara

### Free and open source music player for Android

Streams the YouTube Music catalogue, plays audio through Media3, and shows
time-synced lyrics. No advertisements, no tracking, no account required.

<br />

[![Build](https://img.shields.io/badge/Build-GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white)](https://github.com/ankitkhatrik6/lunara-android/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ankitkhatrik6/lunara-android?sort=semver&style=flat-square&logo=github&logoColor=white&color=181717)](https://github.com/ankitkhatrik6/lunara-android/releases)
[![License](https://img.shields.io/badge/License-GPLv3-A42E2B?style=flat-square&logo=gnu&logoColor=white)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![API](https://img.shields.io/badge/API-24%2B-34A853?style=flat-square&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](#)

<br />

[Download](#installation) &nbsp;|&nbsp; [Features](#features) &nbsp;|&nbsp; [Architecture](#architecture) &nbsp;|&nbsp; [Building](#building) &nbsp;|&nbsp; [Troubleshooting](#troubleshooting)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Installation](#installation)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Building](#building)
- [Signing](#signing)
- [Troubleshooting](#troubleshooting)
- [License](#license)
- [Author](#author)

---

## Overview

Lunara is a free and open source music player for Android. It streams the YouTube Music catalogue through the InnerTube API, plays audio with the AndroidX Media3 engine, and displays time-synced lyrics. There are no advertisements, no tracking, and no account required.

## Features

| Feature | Description |
|---------|-------------|
| **YouTube Music catalogue** | Search, live suggestions, browse, artists, albums, and playlists through the InnerTube `WEB_REMIX` client. |
| **Reliable playback** | Each track is resolved against the InnerTube player endpoint with a rotating set of public client identities, and every candidate URL is **proven playable before it reaches the player** — Lunara probes past the 1 MiB Google Video Server throttle boundary so a truncated stream is rejected and re-resolved instead of stalling in the buffer. Resolved streams carry the minting client's identity headers, the `n` throttling parameter is transformed from `player.js`, and a stall watchdog re-resolves a dead stream automatically. Playback runs through Media3 with lock screen and notification controls. |
| **Synced lyrics** | Time-synced and plain lyrics with a priority chain of providers: Paxsenix, LRCLIB, Better Lyrics, KuGou, and LyricsPlus. |
| **Queue management** | Play next, add to queue, reorder, remove, and clear. |
| **Mini player and full player** | Scrubbable seek bar, repeat (all, one, off), shuffle, and a sleep timer. |
| **Spotify import** | Paste a public playlist link and Lunara matches the tracks into an offline-ready playlist. |
| **Offline downloads** | Save high-bitrate audio locally for offline playback. |
| **Local device music** | The MediaStore scanner integrates files already on the device. |
| **Room persistence** | Liked songs, playlists, search history, profile, and listening statistics are stored locally. |
| **Personalised home** | Time-based greeting, listening history, trending, popular albums, recommended playlists, and a local Top 20. |

## Installation

Download the latest signed APK from the [Releases](https://github.com/ankitkhatrik6/lunara-android/releases) page and install it.

> [!NOTE]
> If you previously installed an earlier or unofficial build of Lunara, uninstall it once before installing this release. See [Troubleshooting](#troubleshooting) for details.

## Architecture

```text
app/src/main/java/com/lunara/music/
|-- LunaraApplication.kt            Application entry point and service bootstrap
|-- MainActivity.kt                 Single activity, edge-to-edge Compose
|-- data/models/                    Song, Album, Artist, Playlist, Lyrics models
|-- database/                       Room entities, DAOs, LunaraDatabase
|-- service/
|   |-- audio/                      MediaSessionService and LunaraPlayerManager
|   |-- innertube/                  InnerTubeService, StreamResolver, LunaraDownloader
|   |-- download/                   Offline media DownloadManager
|   |-- local/                      MediaStore local audio scanner
|   |-- lyrics/                     LRCLIB synced and plain lyrics service
|   `-- spotify/                    Spotify playlist parser and track matcher
`-- ui/
    |-- components/                 Player bar, artwork, rows, cards, time bar
    |-- navigation/                 LunaraNavHost and bottom navigation
    |-- screens/                    Home, Search, Library, Player, Settings, Details
    `-- theme/                      Obsidian dark theme palette and typography
```

## Technology Stack

| Area | Choice |
|------|--------|
| Platform | Android, Kotlin, Jetpack Compose, Material 3 |
| Audio | AndroidX Media3 (ExoPlayer and MediaSession) with an OkHttp data source |
| Extraction | In-app InnerTube client (player endpoint, client rotation, signature + `n` throttling decipher) and deep stream validation |
| Lyrics | Paxsenix, LRCLIB, Better Lyrics, KuGou, and LyricsPlus |
| Database | AndroidX Room |
| Images | Coil |
| Networking | OkHttp 4 |
| Testing | Robolectric, JUnit 4, Kotlinx Coroutines Test |

## Building

### Requirements

- JDK 17 or newer
- Android SDK (API 34 or newer)

### Build commands

```bash
git clone https://github.com/ankitkhatrik6/lunara-android.git
cd lunara-android

# Unit tests
./gradlew testDebugUnitTest

# Debug build
./gradlew assembleDebug

# Signed release build
./gradlew assembleRelease
```

### Build outputs

| Build type | Path |
|------------|------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

## Signing

Release and debug builds are both signed with the stable keystore committed at the repository root (`lunara-upload-key.jks`). Using one key for every build guarantees that updates install over existing builds and that the package is not reported as untrusted.

| Property | Value |
|----------|-------|
| Keystore | `lunara-upload-key.jks` |
| Alias | `lunara` |
| Store password | `lunara-upload` |
| Key password | `lunara-upload` |

The values can be overridden with the environment variables `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`, or with entries in `local.properties`:

```properties
lunara.storeFile=/absolute/path/to/keystore.jks
lunara.storePassword=your-password
lunara.keyAlias=your-alias
lunara.keyPassword=your-key-password
```

> [!WARNING]
> Keep this keystore safe. Every future release must be signed with the same key, otherwise Android will refuse to install the update.

## Troubleshooting

<details>
<summary><b>"App not installed" or "package conflict"</b></summary>

<br />

This happens when a build is installed over a previous build that has the same package name but a different signing certificate, or when the version code is not increased. To resolve it:

1. Uninstall the previously installed Lunara build.
2. Install the latest release APK.

All builds in this repository share the package `com.lunara.music` and the same signing key, so subsequent updates install without conflict.

</details>

<details>
<summary><b>"Play Protect blocked this app because the developer is unknown"</b></summary>

<br />

Play Protect shows this warning for applications that are not distributed through Google Play. Lunara is signed with a real release key and is not debuggable, which is what keeps it from being blocked as an untrusted debug package. When the warning appears, choose to install anyway, or add Lunara to the allowed list in the Play Store settings under Play Protect.

</details>

## License

Lunara is released under the GNU General Public License v3.0. See [LICENSE](LICENSE) for the full text.

Lunara is an independent project. It is not affiliated with, endorsed by, or sponsored by Google LLC, YouTube, or Spotify AB.

## Author

Designed and developed by **Ankit**, a BSc CSIT student and full-stack developer based in Kathmandu, Nepal.

[![GitHub](https://img.shields.io/badge/GitHub-ankitkhatrik6-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/ankitkhatrik6)
[![Portfolio](https://img.shields.io/badge/Portfolio-ankitkhatri.me-FF6D00?style=flat-square&logo=googlechrome&logoColor=white)](https://ankitkhatri.me)
[![Instagram](https://img.shields.io/badge/Instagram-21ank1t-E4405F?style=flat-square&logo=instagram&logoColor=white)](https://instagram.com/21ank1t)

---

<div align="center">

Built with Kotlin and Jetpack Compose.

</div>
