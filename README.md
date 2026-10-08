<p align="center">
  <img src="docs/icon.svg" width="96" height="96" alt="MediaControl app icon" />
</p>

# MediaControl

A Wear OS media remote: control media playback on your phone from your watch, with
live queue, volume, and Bluetooth-audio status synced over the Wearable Data Layer.

## Modules

- **`app`** (minSdk 30) — the Wear OS watch app. 100% Kotlin, built with Jetpack
  Compose for Wear OS Material 3 (`androidx.wear.compose.material3` / `.foundation` / `.navigation`).
  Never plays media locally — it's strictly a remote for whatever is playing on the
  paired phone.
  - Now Playing: album-art backdrop, hold-to-seek bar, and transport controls; swipe
    for volume, queue, apps, and settings.
  - Players screen: switch between known media apps (Spotify, YouTube Music, Pocket
    Casts, Samsung Music) or any other app currently holding an active media session
    on the phone.
  - Queue and volume screens, synced live from the phone.
- **`phone`** (minSdk 26) — the phone companion. Listens for active `MediaSession`s via
  `MediaSessionManager`, relays session/queue/volume/artwork/live-session state to the
  watch over the Data Layer, and receives transport commands back.
- **`core`** (minSdk 26) — shared code: `MediaRemoteRepository` (phone-side session
  discovery via Media3 `MediaController`), relay wire protocol, and shared data models.

## Building

```sh
./gradlew :app:assembleRelease :phone:assembleRelease :core:assembleDebug test
```

Install on a paired Wear OS watch + phone over `adb` (v1.0.7). Use the release builds on
the watch: debug builds run uncompiled and lag badly there.

```sh
adb -s <phone-serial> install -r phone/build/outputs/apk/release/phone-release.apk
adb -s <watch-serial> install -r app/build/outputs/apk/release/app-release.apk
adb -s <watch-serial> shell cmd package compile -m speed -f com.mediacontrol.remote
```

## Architecture notes

- Watch and phone communicate exclusively through the Wearable Data Layer
  (`DataClient`/`MessageClient`); see `core`'s relay protocol for the wire format.
- The watch never reads or shows media playing locally on the watch itself — all
  playback state is relayed from the phone.
- Lyrics: the phone (the only module with `INTERNET`) scrapes LRC.red, BetterLyrics,
  LyricsPlus, LRCLIB, KuGou or Genius, converts the result to one model
  (`core/.../lyrics`), and ships it to the watch as a gzip Asset on `/lyrics`. While the
  watch's Lyrics page is open the phone sends its live playback position on
  `/lyrics-sync` about every 1.5 s. The provider is chosen under Settings → Lyrics
  source (or by tapping the Lyrics page); `Auto` prefers word-synced, then line-synced,
  then Genius plain text, while an explicit provider never falls back.
- Player controls: the phone mirrors the active session's `PlaybackState` custom actions
  (favourite, shuffle, ...) and their icons on `/media-actions`; the watch lists them on
  the page left of Now Playing (swipe right) and sends `action|<id>` back to fire them.
- Stay open (Settings): registers the watch activity for ambient mode so the player stays
  up, dimmed, after the wrist drops; once the screen has been off longer than the chosen
  time the task moves back. Off by default, in which case no ambient support is registered.
