# Changelog

All notable changes to this project are documented in this file.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
