# CachePipe

CachePipe is a personal-use Android music/audio app based on
[NewPipe](https://github.com/TeamNewPipe/NewPipe). It keeps NewPipe's streaming, download,
extractor, and player foundations and adds playlist-level audio caching for offline playback.

This project was developed with substantial AI assistance. It was created primarily for personal
use, so it may contain bugs, rough edges, limitations, and occasional finicky behavior. It is not
an official NewPipe release and is not affiliated with NewPipe e.V. or TeamNewPipe.

## How playlist audio caching works

Caching is started from a playlist's overflow menu with **Cache playlist**. This works for both
remote playlists (for example YouTube playlists) and local NewPipe playlists.

When a cache job starts:

1. CachePipe builds an ordered snapshot of the playlist:
   - Remote playlists are read page by page through NewPipe Extractor and de-duplicated by URL.
   - Local playlists are read from NewPipe's local playlist database.
2. A unique `WorkManager` job tracks the playlist. It requires a network connection and shows a
   foreground progress notification with a cancel action.
3. For each track, CachePipe resolves `StreamInfo`, selects the audio stream that NewPipe's normal
   audio filtering would use, and requires that stream to be a direct progressive HTTP audio
   stream.
4. It computes a stable playback cache key from the stream's service, stream IDs, format, bitrate,
   and audio-track information. The URL is only included when the format and bitrate are unknown,
   so refreshed stream URLs still map to the same cached file.
5. The `StreamInfo` used for offline playback is serialized and written before the download starts.
6. Audio is downloaded through NewPipe's existing Giga downloader
   (`DownloadManagerService`), using the same audio post-processing choices as normal manual audio
   downloads (`M4A_NO_DASH` for M4A and `OGG_FROM_WEBM_DEMUXER` for WebM Opus).
7. Giga downloads into a temporary `.part` file. When the mission finishes, CachePipe atomically
   promotes it to its final file and records a shared cached-audio entry.
8. The worker polls mission progress roughly every 1.5 seconds, updates the database and foreground
   notification, and finishes when every track reaches a terminal state.

**Cancel caching** cancels the unique work and the matching Giga missions, and marks unfinished
tracks as canceled. Running **Cache playlist** again resumes/retries the same track snapshot;
already completed or already shared tracks are skipped, while pending, failed, or canceled tracks
are retried. The playlist track list is seeded once per cache job, so newly added playlist entries
are not discovered by re-running an existing job.

On app startup, CachePipe reconciles jobs left behind by process death: completed files and
finished `.part` files are promoted, missing in-progress targets are reset for retry, and
unreferenced cached rows/files and orphan `.part` files are cleaned up.

## Offline playback and cache reuse

Cached audio is reused automatically by the normal player:

- When a queue item is loaded, CachePipe first tries persisted `StreamInfo` metadata for that
  service and stream URL. If valid metadata exists, the extractor is not contacted.
- The audio playback resolver computes the same cache key used during caching. If a complete,
  readable cached file exists, playback uses a local progressive media source instead of the
  network stream.
- Fully cached playback is reported as 100% buffered in the player's buffering indicator.
- If no valid cache entry exists, or the metadata is missing/invalid, playback falls back to normal
  online streaming and extraction.
- If a local cached source cannot be opened, or ExoPlayer reports certain local IO/parsing errors,
  CachePipe invalidates that cache entry and retries the track online once for that cache key.

**Play cached** starts the playlist's completed tracks from the beginning in the background
player, using position 0 rather than resuming previous playback progress.

## Shared and deduplicated cached tracks

Cached audio is deduplicated per playback cache key:

- A physical audio file is stored once in an app-private cache directory and referenced by
  `cacheKey`.
- If a later playlist contains a track whose audio is already fully cached, that track is marked
  as skipped and reuses the existing file instead of downloading it again.
- Removing a playlist's cached audio deletes that playlist's cache job and track references. A
  physical audio file and its metadata are deleted only when no other cached playlist still
  references the same cache key.
- Incomplete `.part` downloads belonging to the removed job are removed with it.

Cached files live under the app's private files area
(`.../files/playlist_cache/audio` and `.../files/playlist_cache/metadata`), so they are removed by
uninstalling the app or clearing its app data. CachePipe does not expose them through the public
downloads folder or an SD-card/SAF picker.

## Caching limitations

- Only direct **progressive HTTP audio** streams are cached.
- Live and post-live streams are not cached.
- Tracks without a progressive audio stream (for example DASH-only or HLS-only audio) cannot be
  cached; those tracks are marked failed and remain available only through online streaming.
- Only the audio stream selected by NewPipe's normal audio filter/format preference is cached, not
  every audio track or language variant.
- Only audio is cached. Video is not cached by this feature.
- Audio is not transcoded. The stored container follows NewPipe's normal audio post-processing.
- There is no automatic cache quota, LRU eviction, or scheduled cleanup. Space is reclaimed by
  removing playlist caches manually or by the orphan cleanup that runs at startup.
- Offline metadata is versioned and depends on the extractor/`StreamInfo` serialization. If the
  metadata version is unsupported or unreadable, CachePipe falls back to online extraction.
- A cache job can end as complete, partial, canceled, or failed depending on network, extractor,
  or per-track failures. Individual failed tracks remain listed and can be retried.

## Build and setup

The project uses the Gradle wrapper and the configuration in `buildSrc/src/main/kotlin/ProjectConfig.kt`.

Requirements:

- JDK 21 (the Kotlin/Java toolchain is configured for Java 21).
- Android SDK with the compile SDK platform installed (compile SDK 37, target SDK 35, minimum SDK 23).
- The Gradle wrapper (Gradle 9.8.2) downloads automatically on the first build.
- An Android SDK location, either through `ANDROID_HOME`/`ANDROID_SDK_ROOT` or a `local.properties`
  file in the project root:

```properties
# Windows
sdk.dir=C\:\\path\\to\\android-sdk

# Linux/macOS
sdk.dir=/path/to/android-sdk
```

Build the debug APK:

```bash
# Windows
gradlew.bat :app:assembleDebug

# Linux/macOS
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug package ID remains
`org.schabi.newpipe.debug`, and the displayed app name is `CachePipe`.

Install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds automatically run the project's Kotlin formatting/lint, Checkstyle, and dependency
ordering checks. They can also be run directly:

```bash
gradlew.bat :app:runKtlint :app:runCheckstyle :app:checkDependenciesOrder
gradlew.bat :app:testDebugUnitTest
```

If Gradle runs out of memory, increase the heap explicitly:

```bash
gradlew.bat :app:assembleDebug ^
  -Dorg.gradle.jvmargs="-Xmx4096M --add-opens=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED" ^
  -Dkotlin.daemon.jvmargs="-Xmx2048M"
```

## Update checking

The upstream NewPipe update checker has been removed. CachePipe does not check for or install app
updates. Update the app manually from the
[CachePipe repository](https://github.com/en4mu/CachePipe) when a build is available.

## Acknowledgements and license

CachePipe is based on NewPipe:

- NewPipe website: <https://newpipe.net/>
- NewPipe repository: <https://github.com/TeamNewPipe/NewPipe>
- NewPipe Extractor: <https://github.com/TeamNewPipe/NewPipeExtractor>

CachePipe is distributed under the GNU General Public License, version 3 or later. See
[`LICENSE`](LICENSE) and the SPDX/copyright notices in the source tree. CachePipe is an independent
fork and is not affiliated with or endorsed by NewPipe e.V., TeamNewPipe, or the NewPipe project.
