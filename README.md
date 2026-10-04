# Lunara

[![Build](https://github.com/ankitkhatrik6/lunara-android/actions/workflows/build.yml/badge.svg)](https://github.com/ankitkhatrik6/lunara-android/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/ankitkhatrik6/lunara-android?sort=semver)](https://github.com/ankitkhatrik6/lunara-android/releases)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](#)

Lunara is a free and open source music player for Android. It streams the
YouTube Music catalogue through the InnerTube API, plays audio with the AndroidX
Media3 engine, and displays time synced lyrics. There are no advertisements, no
tracking, and no account required.

## Features

- YouTube Music catalogue: search, live suggestions, browse, artists, albums,
  and playlists through the InnerTube `WEB_REMIX` client.
- Reliable playback: the playable audio stream is resolved per track directly
  against the InnerTube player endpoint using several public client identities
  (Android VR, iOS, Android Creator, TV embedded and Web Music), so no external
  extractor library and no JavaScript engine are required. Playback runs through
  Media3 with lock screen and notification controls.
- Time synced and plain lyrics with a priority chain of providers: Paxsenix,
  LRCLIB, Better Lyrics, KuGou and LyricsPlus.
- Queue management: play next, add to queue, reorder, remove, and clear.
- Mini player and full player: scrubbable seek bar, repeat (all, one, off),
  shuffle, and a sleep timer.
- Spotify import: paste a public playlist link and Lunara matches the tracks into
  an offline ready playlist.
- Offline downloads: save high bitrate audio locally for offline playback.
- Local device music: the MediaStore scanner integrates files already on the
  device.
- Room persistence: liked songs, playlists, search history, profile, and
  listening statistics are stored locally.
- Personalised home: time based greeting, listening history, trending, popular
  albums, recommended playlists, and a local Top 20.

## Installation

Download the latest signed APK from the
[Releases](https://github.com/ankitkhatrik6/lunara-android/releases) page and
install it.

If you previously installed an earlier or unofficial build of Lunara, uninstall
it once before installing this release. See [Troubleshooting](#troubleshooting)
for details.

## Architecture

```
app/src/main/java/com/lunara/music/
  LunaraApplication.kt            Application entry point and service bootstrap
  MainActivity.kt                 Single activity, edge to edge Compose
  data/models/                    Song, Album, Artist, Playlist, Lyrics models
  database/                       Room entities, DAOs, LunaraDatabase
  service/
    audio/                        MediaSessionService and LunaraPlayerManager
    innertube/                    InnerTubeService, StreamResolver, LunaraDownloader
    download/                     Offline media DownloadManager
    local/                        MediaStore local audio scanner
    lyrics/                       LRCLIB synced and plain lyrics service
    spotify/                      Spotify playlist parser and track matcher
  ui/
    components/                   Player bar, artwork, rows, cards, time bar
    navigation/                   LunaraNavHost and bottom navigation
    screens/                      Home, Search, Library, Player, Settings, Details
    theme/                        Obsidian dark theme palette and typography
```

## Technology

- Platform: Android, Kotlin, Jetpack Compose, Material 3
- Audio: AndroidX Media3 (ExoPlayer and MediaSession) with an OkHttp data source
- Extraction: in-app InnerTube client (player endpoint with client rotation)
- Lyrics: Paxsenix, LRCLIB, Better Lyrics, KuGou and LyricsPlus
- Database: AndroidX Room
- Images: Coil
- Networking: OkHttp 4
- Testing: Robolectric, JUnit 4, Kotlinx Coroutines Test

## Building

Requirements:

- JDK 17 or newer
- Android SDK (API 34 or newer)

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

Build outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## Signing

Release and debug builds are both signed with the stable keystore committed at
the repository root (`lunara-upload-key.jks`). Using one key for every build
guarantees that updates install over existing builds and that the package is not
reported as untrusted.

| Property | Value |
| --- | --- |
| Keystore | `lunara-upload-key.jks` |
| Alias | `lunara` |
| Store password | `lunara-upload` |
| Key password | `lunara-upload` |

The values can be overridden with the environment variables `KEYSTORE_PATH`,
`STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`, or with entries in
`local.properties`:

```properties
lunara.storeFile=/absolute/path/to/keystore.jks
lunara.storePassword=your-password
lunara.keyAlias=your-alias
lunara.keyPassword=your-key-password
```

Keep this keystore safe. Every future release must be signed with the same key,
otherwise Android will refuse to install the update.

## Troubleshooting

**"App not installed" or "package conflict"**

This happens when a build is installed over a previous build that has the same
package name but a different signing certificate, or when the version code is not
increased. To resolve it:

1. Uninstall the previously installed Lunara build.
2. Install the latest release APK.

All builds in this repository share the package `com.lunara.music` and the same
signing key, so subsequent updates install without conflict.

**"Play Protect blocked this app because the developer is unknown"**

Play Protect shows this warning for applications that are not distributed
through Google Play. Lunara is signed with a real release key and is not
debuggable, which is what keeps it from being blocked as an untrusted debug
package. When the warning appears, choose to install anyway, or add Lunara to the
allowed list in the Play Store settings under Play Protect.

## License

Lunara is released under the GNU General Public License v3.0. See
[LICENSE](LICENSE) for the full text.

Lunara is an independent project. It is not affiliated with, endorsed by, or
sponsored by Google LLC, YouTube, or Spotify AB.