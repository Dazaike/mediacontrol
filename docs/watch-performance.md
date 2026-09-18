# Watch performance

This file exists because every cosmetic edit on the Galaxy Watch 6 has a talent for turning Now Playing into sludge, after which someone spends thirteen sessions rediscovering the same list. The list is already paid for. Read it before touching `:app` UI, Gradle, or the DataLayer. If a change fights a rule here, the rule wins.

Measured “good” on this hardware (SM-R940, release, R8 on): cold start ~0.90 s, zero `Skipped N frames` at launch, idle Now Playing not producing a continuous frame stream. Debug Compose is not a performance signal.

## Install the fast variant or do not talk about lag

```
gradlew.bat :app:assembleRelease
```

Release is debug-signed (`app/build.gradle.kts` `signingConfig = signingConfigs.getByName("debug")`) so it installs over debug. `isMinifyEnabled` and `isShrinkResources` stay `true`. Keep rules live in `app/proguard-rules.pro` for GMS `DataClient.OnDataChangedListener` / `WearableListenerService` and protolayout/tiles.

`assembleDebug` is for compile checks. It is not the watch the user feels. Wear Compose debug keeps recomposition tracking on; that alone has shown 101 dropped frames and ~4 s cold start. Judging lag from a debug install is how this bug report gets filed in a loop.

Do not disable R8 to “make a change easier.” Do not add keep rules by deleting minify.

## Hard rules (Now Playing)

File: `app/src/main/java/com/mediacontrol/remote/ui/NowPlayingScreen.kt`

- Collect session state only in `NowPlayingPage`. `NowPlayingScreen` itself does not collect `uiState`, artwork, volume, or pending-launch. Artwork lives in `AlbumBackdrop`. Volume overlay lives in `VolumeOverlay`. Pending-launch lives in `PendingOrIdle`. Soundcore status lives in `SoundcoreStatusText`. A bezel detent must not recompose the pager, the backdrop, or the transport row.
- `HorizontalPager` keeps `beyondViewportPageCount = 0`. The swipe-menu page is not composed offscreen.
- `ReadyContent` takes stable scalars only (`packageName`, `title`, `artist`, `isPlaying`, `positionMs`, `durationMs`, plus the ViewModel and lambdas). Never put `ByteArray` (or any unstable artwork type) in that signature — it makes the composable and every child non-skippable.
- `NowPlayingViewModel` stays `@Stable`. Rotary accumulation stays in `onRotary(pixels, stepPx): Boolean`, not Compose state. Clockwise bezel is louder (`nudgeVolume(-steps)`).
- Progress: `TrackProgress` draws the bar with `drawBehind` and reads position inside lambdas. The 1 s tick invalidates draw, not the Now Playing column. Elapsed time stays in `ElapsedText`. Do not drive the bar with `CircularProgressIndicator`, and never use the indeterminate spinner (`progress` omitted / infinite rotation). Duration 0 or unknown is a static empty track, not an animation clock.
- Do not wrap the transport row or the swipe-menu rows in `ButtonGroup`. That morphing layout ate frames on the Watch 6 and was removed for that reason. `Row` of fixed-size icon buttons is the layout. Do not `weight` them across the full width — that stretches the circles into stadiums. `IconButtonDefaults.animatedShapes()` is allowed. `animateWidth` is not — it remeasures the row on every press.
- `basicMarquee` uses `MarqueeIterations = 3`, never `Int.MAX_VALUE`. Infinite marquee kept the screen at ~30 fps while idle (~42 ms/draw) because each tick re-recorded the fullscreen artwork and curved `TimeText`. Title, artist, artwork `Image`, and `TimeText` each keep their own `graphicsLayer()`.
- Album art: decode off the UI thread, cap at 480 px, `RGB_565`. Dim is a `SrcAtop` colour filter on the `Image`, not a second fullscreen `Box`. `PhoneRelaySource` must not re-fetch the artwork asset on play/pause of the same track.
- Animations that are allowed (Crossfade artwork 500 ms, `AnimatedContent` on title/artist and play/pause glyph, `AnimatedVisibility` on the volume arc) stay inside the leaf that already owns that data. Do not lift those collections back up to `NowPlayingScreen` / `NowPlayingPage` “to share state.”
- Now Playing is a single screen. Do not bring `ScalingLazyColumn` onto this surface. Lists belong on Queue / Players / Settings via `SecondaryScaffold`.

## Hard rules (process, DataLayer, APK)

- `MediaRemoteApp` scope is `Dispatchers.Default`, not `Dispatchers.Main.immediate`. First touch of `mediaSource` is not allowed to run GMS/Tile work on the main thread before first frame (`app/src/main/java/com/mediacontrol/remote/MediaRemoteApp.kt`).
- `PhoneRelaySource` registers `Wearable.getDataClient().addListener` on `Dispatchers.IO`, inside the existing mutex, *before* prefetch (`app/src/main/java/com/mediacontrol/remote/data/PhoneRelaySource.kt`). Constructor-inline GMS binder calls cost ~1.4 s on main and 101 skipped frames.
- `onDataChanged` copies events off the GMS binder thread, then parses on IO behind that same mutex. Do not parse `DataMap` / rebuild sessions on the callback thread.
- Cache the phone node id (`cachedNodeId`). Taps must not do a Wear node lookup on the critical path.
- Phone publishes position ticks only when `shouldPublishSession` / `sameUi` say so (`core/.../ControlledSession.kt`). Play/pause must publish the interpolated position, not the last tick; the DataLayer push key includes `positionMs` so that snapshot is not dropped. The watch interpolates progress locally. Do not “fix” a frozen bar by pushing position every second — that recomposes `ReadyContent`.
- No watch-local media session discovery. `activeSession` is phone relay only. Samsung’s OEM proxy (`com.samsung.android.wearable.media.sessions`) is not a source.
- Icons: `MediaIcons.kt` only. Never re-add `compose-material-icons-extended` (87 MB jar for a dozen glyphs), `coil-compose`, or `media3-ui`. Catalog aliases may still sit in `gradle/libs.versions.toml`; `:app` must not depend on them.
- App icons downsample to 48 px RGB_565 (`AppIcon.kt`). Do not decode full-size launcher PNGs on the UI thread.
- Tile / complication refresh stays throttled to ≥10 s and skipped when package/title/playing are unchanged.

## When it feels laggy again

Walk this list before inventing a new architecture:

1. Is the installed APK release + R8, or debug?
2. Did a new `collectAsStateWithLifecycle` land in `NowPlayingScreen` / `ReadyContent`?
3. Did `ButtonGroup`, `animateWidth`, indeterminate `CircularProgressIndicator`, or `ScalingLazyColumn` return to Now Playing?
4. Did marquee go infinite, or did a `graphicsLayer()` get removed from title / artist / artwork / `TimeText`?
5. Did `PhoneRelaySource` listener registration or `MediaRemoteApp` collection move back onto main?
6. Did a new dependency pull icons-extended, Coil, or media3-ui into `:app`?
7. Is the phone publishing position-only DataItems again?

If you add motion, add it in a leaf and re-measure cold start with `am start -W -S` on the release APK. Do not “verify” by driving the watch with ADB taps, Playwright, or any UI automation. The user confirms feel on hardware.

## Why the last thirteen sessions happened

| Symptom | Actual cause | Fix that stuck |
|---|---|---|
| 4 s launch, 101 skipped frames | Debug Compose + R8 off; GMS `addListener` on main in `Application.onCreate` | `assembleRelease` with minify; listener on IO |
| Transport stutter / whole screen jank | Root collected 5 flows; `ByteArray` in `ReadyContent`; `ButtonGroup` morph; `animateWidth` | Leaf collects; stable scalars; `Row`; shapes only |
| Idle GPU ~30 fps | `basicMarquee(Int.MAX_VALUE)` invalidating the window root | 3 passes + per-widget `graphicsLayer` |
| Spinner eating the frame budget | Indeterminate `CircularProgressIndicator` when duration was 0 | Static track |
| Scroll stutter on the main surface | `ScalingLazyColumn` curvature on a one-screen layout | Non-scrolling Now Playing |
| 75 MB APK, slow dex | `material-icons-extended` + Coil + media3-ui | `MediaIcons.kt`, drop unused deps |
