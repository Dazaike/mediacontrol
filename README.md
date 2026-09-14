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
./gradlew :app:assembleDebug :phone:assembleDebug :core:assembleDebug test
```

Install on a paired Wear OS watch + phone over `adb`:

```sh
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s <watch-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture notes

- Watch and phone communicate exclusively through the Wearable Data Layer
  (`DataClient`/`MessageClient`); see `core`'s relay protocol for the wire format.
- The watch never reads or shows media playing locally on the watch itself — all
  playback state is relayed from the phone.
