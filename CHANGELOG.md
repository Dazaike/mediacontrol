# Changelog

All notable changes to this project are documented in this file.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [v1.0.1] - 2026-09-14

Changes since `v1.0.0` (755bd4a). Working tree at release; no interim commits.

### Added
- Local `UmoBridgeSessionService` MediaSession foreground service so Pixel UMO can drive phone playback through this app (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`).
- Hold-then-drag seek on the Now Playing progress bar; seek is relayed to the phone (`RelayCommand.Seek`).
- Queue screen highlights the currently playing track (primary colors + ▶ prefix).
- Optimistic volume on the watch so the stepper does not snap back while the DataItem syncs.
- `shouldPublishSession` / `sameUi` to drop position-only ticks that the watch interpolates locally.

### Changed
- Watch UI moved to Wear Compose Material 3 (`wear-compose` 1.5.0, Compose BOM 2025.08.00); dropped Horologist media/audio UI and Wear Material 2.
- Now Playing uses a 96px RGB_565 album-art backdrop plus dark scrim instead of a full-resolution `fillMaxSize` image.
- Removed `AppScaffold` / TimeText and `ScreenScaffold` chrome from the watch shell.
- Volume, queue, apps, and settings live on a swipe page off Now Playing.
- Tile and complication refreshes throttled to ≥10s and skipped when package/title/playing are unchanged.
- UMO foreground service start deferred 1s after `setContent` so it does not race the first Compose frames; bitmap decode runs on `Dispatchers.Default`.
- Phone `putDataItem` calls are marked urgent so session/volume updates reach the watch promptly.
- Disconnected Bluetooth status line is empty instead of "No audio device".

### Fixed
- Volume screen number and "Phone Volume" label overlapped in the Stepper content slot.
- Watch volume UI lagged phone STREAM_MUSIC because DataItems were not urgent and the stepper had no local override.

## [v1.0.0] - 2026-09-13

Initial release. No prior tags exist, so this entry covers the project's full
initial feature set rather than a diff against a previous version.

### Added
- Wear OS watch app (`:app`) built with Jetpack Compose for Wear OS: Now Playing,
  Players, Queue, Volume, and Settings screens.
- Now Playing screen: full-bleed album art, circular progress ring, transport
  controls (previous/play-pause/next), and a swipeable second page hosting the
  app-switcher and settings buttons (moved off the primary transport screen).
- Players screen: fixed presets for Spotify, YouTube Music, Pocket Casts, and
  Samsung Music, plus a live-relayed list of any other app currently holding an
  active media session on the phone (sorted playing-first).
- Phone companion app (`:phone`): discovers active `MediaSession`s via
  `MediaSessionManager`/Media3 `MediaController` and relays session metadata,
  queue, volume, Bluetooth-audio state, album art, and live-session list to the
  watch over the Wearable Data Layer.
- Shared relay wire protocol (`core`) using a parallel-array `DataMap` convention
  for session, queue, and live-session state.
- Custom adaptive app icon (dark navy background, skip/play/skip glyph) for both
  the watch and phone apps.
- Bluetooth audio device status display, synced from the phone.

### Fixed
- Scroll gesture on the Players and Settings screens (`SecondaryScaffold`): a
  `clickable` wrapper around `TimeText()` was swallowing touch-drag gestures,
  popping back to Now Playing instead of scrolling the list; removed the wrapper.
- Players and Settings screen content lists were single wrapped `item {}` blocks
  instead of proper `ScalingLazyListScope` `item`/`items` rows.
