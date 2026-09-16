# Changelog

All notable changes to this project are documented in this file.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [v1.0.2] - 2026-09-15

Changes since `v1.0.1` (6297eaf): commit `e475aab` plus the working tree at release.

### Added
- Standalone Soundcore earbud control: `core` gains a pure `soundcore` package (frame
  builder, wrapping checksum, partial/resync-tolerant `SoundcoreFrameReader`, per-model
  `SoundcoreProfile` for Space One and P31i, legacy 4-byte fallback) with unit tests,
  and `phone` gains `SoundcoreController` driving RFCOMM directly — vendor UUID with SPP
  fallback, state read, patched sound-mode write, ACK with one retry (`e475aab`).
- Real mode outcome relayed back to the watch as `scMode` / `scError` and rendered in the
  swipe menu, replacing the previous optimistic "sent" text (`e475aab`).
- User-curated Players list: the phone publishes `/media-apps`, the watch persists the
  chosen apps, and a new Add-players screen toggles them (`e475aab`).
- Phone launcher icons published as `/media-app-icons` Assets and drawn on the main
  screen and in both player lists (`e475aab`).
- Auto-start toggle on the idle screen — resumes the phone's last player when nothing is
  playing, rate-limited to one attempt per 30s (`e475aab`).
- Curved `TimeText` clock and a `HorizontalPageIndicator` on Now Playing.
- Haptic feedback on every transport and Soundcore action.
- Artwork crossfade, animated track-title and play/pause transitions, marquee titles, and
  a fading volume overlay.
- Runtime `BLUETOOTH_CONNECT` request on the phone companion (`e475aab`).

### Changed
- Now Playing recomposition is scoped to leaves: only `uiState` is collected at the root,
  with artwork, volume, pending-launch and Soundcore status collected where they are drawn;
  `ReadyContent` takes stable scalars so it can skip (`e475aab`).
- `TrackProgress` draws through `drawBehind` and reads position inside lambdas, so the
  per-second tick invalidates draw instead of re-laying out the screen (`e475aab`).
- Dropped `animateWidth` from the transport row and swipe menu; `animatedShapes` kept.
- Theme reworked: vivid accent palette, dark accent-tinted button fills with white glyphs,
  white progress bar, smaller and raised transport row.
- New launcher icon (screen, play triangle and seek bar) on both the watch and phone apps.
- Release builds now run R8 with resource shrinking and keep rules for the reflectively
  invoked DataLayer callbacks and protolayout builders; phone release is debug-signed so
  the artifact installs.
- `material-icons-extended` (87 MB jar for 12 icons) replaced with local vectors; unused
  `coil-compose` and `media3-ui` dropped. APK 75.2 MB -> 6.2 MB.
- Wearable `DataClient` listener registration moved off the main thread; cold start
  4.16 s -> 0.90 s with no dropped frames at launch.
- Bluetooth status line trimmed from "🎧 <device> / Connected" to "🎧 <device>".

### Fixed
- Bezel volume was inverted — clockwise now raises volume.
- Filled buttons rendered as unreadable blocks on a pale accent: `onPrimary`/`onSecondary`
  are now derived from fill luminance, repairing the Queue screen's current-track row and
  the idle screen's "Open player" button.

### Removed
- CoreSwap dependency in every form: the launch path, the `SOUNDCORE_*` string constants,
  and both `<package>` manifest query entries (`e475aab`).
- Hardcoded player presets and the live-session auto-scan on the Players screen (`e475aab`).

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
