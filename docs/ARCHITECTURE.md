# Architecture

This document describes how the app is structured. Package root is `com.example` (applicationId `dev.liorbass.babybuddy`; app name "Baby Buddy Companion"). The external interfaces (Baby Buddy REST API, auth, sync semantics) are defined in [ICD.md](ICD.md).

## State flow

`BabyBuddyRepository` (singleton via `BabyBuddyRepository.instance`) is the single source of truth, holding all app state as `MutableStateFlow`s. `MainViewModel` re-exposes those flows, owns UI-only state (current screen, toast, modal visibility), and wraps repository mutations with user-facing toasts. Composables receive the ViewModel/state and callbacks from `MainActivity`.

## Navigation

Navigation is not Navigation-Compose: it's a `ScreenDestination` enum in `MainViewModel` and a `when (currentScreen)` switch in `BabyBuddyApp` in `MainActivity.kt`. Adding a screen means adding an enum value and a branch there. Back handling returns to `TODAY_DASHBOARD`.

## Persistence

`BabyBuddyApplication` calls `repository.attach(context)` on every process start. Saved state:

- the server login in SharedPreferences `baby_buddy` — the API token (and optional Cloudflare Access secret) is encrypted with an Android Keystore key via `SecretCipher`;
- the running nursing timer and sleep timer (same prefs);
- in `filesDir/offline/` (`OfflineStore`, Moshi JSON): the unsent-write queue and a cache of today's entries.

These are excluded from backups. Without a saved login the app shows only the login screen (`needsLogin` in `BabyBuddyApp`).

## Server integration

`data/api/BabyBuddyApi.kt`: Retrofit + Moshi (reflection `KotlinJsonAdapterFactory`) against the Baby Buddy REST API (`api/children/`, `api/feedings/`, `api/sleep/`, `api/changes/`, …), with `Authorization: Token <token>` passed per call. `BabyBuddyApiClient.createService` optionally installs a trust-all TLS manager for self-signed servers, and adds `CF-Access-Client-Id`/`CF-Access-Client-Secret` headers when a Cloudflare Access service token is configured.

- `repository.testConnection` fetches children and recent events and maps DTOs into UI models (`data/model/Models.kt`).
- `syncToday` fetches only entries since local midnight (UTC `start_min`/`date_min` filters) and merges them with local entries that still have queued writes (`mergeWithPending`). Sleeps are synced by end date (`end_min`).
- Trends (`loadTrends`) and History (`loadHistory`, a week of feeds/diapers/pumping/tummy time) query the server directly.
- Local events carry `serverId`/`timeMillis`.

## Write queue

All writes (create/update/delete for feedings, changes, tummy-times, pumping, …) are `QueuedWrite` data records sent in order by `flushPendingWrites` through the generic `createRecord`/`updateRecord`/`deleteRecord` endpoints:

- network/5xx errors retry with backoff;
- 4xx errors are dropped with a `syncErrors` toast;
- a retried create first looks for an existing record (lost-response recovery);
- a successful create fills in the server id of later queued edits;
- consecutive queued updates of one entry are coalesced.

## Caregiver and generated note text

Server notes carry `Caregiver: Name` (and on breastfeeds `Left: … | Right: … | Sides: …`). Mapping strips these into fields (`caregiver`, `sides`, durations) so `notes` holds only what people wrote; `composeFeedingNotes` / `withCaregiver` rebuild them when sending. New entries get this phone's name from Settings → You.

Diaper amount/rash/cream/consistency have no server fields and round-trip through one `Amount: … | Rash | Cream: … | Consistency: …` notes line. Baths are notes tagged `bath` (`BATH_TAG`); the milk stash is the latest note tagged `milk-stash` plus pumping since, minus breast-milk bottles since (`repository.milkStash`).

## Nursing timer

No save step: `repository.tapSide(side)` starts a feed (creating its feeding record immediately), switches side, or pauses the running side; every tap plus each elapsed minute updates that record through the write queue. A feed paused for 30 minutes (`SESSION_TIMEOUT_MS`) ends on its own; `endFeed()` ends it now.

- `setSideSeconds` corrects one side's time mid-feed (the Edit buttons under the side buttons); it bumps `LiveTimerState.editCount` so widgets and the notification redraw their clocks.
- `setFeedStart` moves the start (the difference goes to the first side); backs the Started field.
- The timer ticks in the repository (`startTimerTicker`, wall-clock based) and its state is persisted; the timer widget uses a self-ticking `Chronometer`.
- `repository.lastBreastfeed` is the latest breastfeed and drives the "last side" shown on Today, the Left/Right controls and the timer widget (`FeedingEvent.lastSide`).

Shared Left/Right UI lives in `ui/components/BreastSideControls.kt`.

## Sleep

Sleep (`api/sleep/`) has its own screen (`ui/screens/SleepScreen.kt`, `ScreenDestination.SLEEP`, a Today quick action): either a timer kept on the phone (`repository.sleepTimer`, saved in prefs) that creates the entry when stopped, or a past sleep entered by start/end. Only the phone that stops a sleep saves the sleep entry.

## Shared timers

A running breastfeed or sleep is mirrored to a Baby Buddy timer (`api/timers/`) whose name carries the live state (`data/repository/SharedTimers.kt`, marker `#bb1`; the syncing is `SharedTimerSync` in `SharedTimerSync.kt`, behind `repository.pollSharedTimers`). Local actions bump `changedAtMillis` and signal a conflated channel that creates/renames/deletes the timer (`pushTimer`); `pollSharedTimers` (on refresh and every 15 s while the app is resumed) adopts newer remote state, ends a timer removed elsewhere, and links an adopted feed to its feeding record via `feedingServerId`. Newest `changedAtMillis` wins; `feedEndedAt`/`sleepEndedAt` stop an ended timer from being re-adopted.

## Widgets, notifications, reminders

`widget/` contains three `AppWidgetProvider`s (QuickLog, Timer, Summary) using RemoteViews layouts in `res/layout/widget_*.xml`. Button taps go either to `WidgetActionReceiver` (broadcast actions that mutate `BabyBuddyRepository.instance` directly, then call `WidgetManagerHelper.updateAllWidgets`) or launch `MainActivity` with the `EXTRA_NAVIGATE_TO` extra, which `handleWidgetNavigation` maps to a `ScreenDestination`. New receivers/providers must be registered in `AndroidManifest.xml`.

Widgets refresh automatically from `repository.widgetState` (collected in `BabyBuddyApplication`). `widget/FeedNotification.kt` shows lock-screen Left/Right/End controls while a feed runs (re-posted every second while a side runs); `widget/SleepNotification.kt` is the lock-screen sleep timer (same toggle in Settings).

`widget/Reminders.kt` schedules inexact alarms for "feed due" (`ReminderSettings.feedAfterMinutes` after `repository.lastFeedMillis`), "awake for" (after `lastSleepEndMillis`) and "asleep for" (after the running sleep timer's start); `ReminderReceiver` refreshes from the server before posting, and each reminder fires once per anchor. `RescheduleReceiver` sets the alarms again after a restart, an app update or a clock/time-zone change.

Permissions: `INTERNET`, `POST_NOTIFICATIONS` (Android 13+; requested at start when the lock-screen timers or any reminder is on, and when turning those on) and `RECEIVE_BOOT_COMPLETED`. Cleartext HTTP is allowed so servers on a home network work.

## UI conventions

- UI screens are large single-file composables in `ui/screens/`; shared pieces are in `ui/components/CommonComponents.kt`, theme in `ui/theme/`.
- **Night mode:** `AppTheme.isNight` (ui/theme/Color.kt) flips every color getter to a dim warm palette; `MainActivity` sets it from Settings → Appearance (`ThemeMode`: by time of day 20:00–07:00 (default), same as phone, light, dark; `repository.settings.themeMode`) and refreshes on resume and every 3 minutes.
- **Time formats:** use `TimeFormat.clock` (`data/model/TimeFormat.kt`) — 24h by default, 12h via Settings → Appearance (`repository.settings.setUse24h`, which also reformats every stored `timeFormatted`). Don't hardcode `h:mm a`.
- Picked clock times go through `TimeField`/`TimePickDialog` (`ui/components/EntryDialogs.kt`), which resolve to the latest past moment (`TimeFormat.latestAt`) unless given a `resolve`. Every time/duration entry offers scroll wheels or typing (`ui/components/WheelPickers.kt`: `ClockTimeEntry`, `DurationWheels`, `InputModeToggle`; the choice is `TimeFormat.useWheel`, saved by the repository). Forms show times and durations as tap-to-edit rows that open one picker in a dialog, never inline wheels.
- **Dashboard widgets** (in-app cards) are driven by `DashboardWidgetType` / `DashboardWidgetConfig` in the repository; cards render in `ui/components/DashboardWidgetCards.kt` and are added via `AddWidgetModal.kt`. Card types: Tummy time, Temperature, Notes and Growth; which are on is saved in prefs `dashboard_widgets`.
- **The Log screen** (`QuickLogModal.kt`) is one list of entries (Breastfeeding, Bottle, Diaper, Sleep, Pumping, Bath, Growth; hidden tracked items left out); tapping one expands its form in place. The forms (`BottleForm`, `PumpingForm` in `EntryDialogs.kt`) are shared with the dialogs; the diaper entry is `DiaperForm` (`DiaperLogScreen.kt`), the same as the Diaper quick action.
- **Today's shortcut row** (`QuickAction`: Feeding, Diaper, Pump, Tummy time, Sleep) is user-chosen and ordered via the pencil button (`QuickActionsEditorDialog`); saved in prefs as `quick_actions`.
- **Item colors:** each tracked item has its own warm accent (`ui/theme/ItemColors.kt`, `tint`/`soft` per `TrackedItem`, day and night), used on Today tiles and shortcuts, Log entries, History dots and the diaper type buttons.
- **Growth** (`TrackedItem.GROWTH`, not a Today quick action) is weight, length and head circumference (`GrowthKind` → endpoints `weight`, `height`, `head-circumference`; `GrowthEvent`, date-only on the server so `dateMillis` is local noon; `repository.growth`, fetched with `getGrowth`). Logged together from the Log screen or the Growth card (`GrowthForm`/`GrowthDialog`), one server entry per filled-in field.
- **Tracked items** (`TrackedItem`, Settings → Tracking, prefs `hidden_items`, per phone): a hidden item is left out of Today (tiles, cards, quick actions), History (filters and entries via `HistoryDay.withoutHidden`), Trends and reminders. New UI for an item should check `hiddenItems`. Home-screen widgets don't follow it.
- Quick logs return the new entry's id so toasts can offer Undo (`UiNotification.undo`).

## Settings

`ConnectBackendScreen.kt` (`ScreenDestination.CONNECT_SERVER`): logged out it is just the login form; logged in it is a list of expandable categories (`SettingsSection`): Server connection, You, Appearance, Notifications. Put new settings in the fitting category.

## Trends & History

Trends (`TrendsScreen.kt`) has feeding, sleep and diaper rhythm charts (one row per day across 24h; sleep and diaper share `DayTimeChart`), built from `DayStats` marks in `repository.loadTrends`, and a Growth section (by age, independent of the selected range) that plots weight, length and head circumference against the WHO Child Growth Standards. `data/model/WhoGrowthStandards.kt` is generated from the WHO's official expanded LMS tables (who.int; weekly samples, birth to 2 years) and gives value, z-score and percentile (`growthPercentile`). The WHO charts differ by sex, which Baby Buddy doesn't store, so it's a phone setting (`repository.settings.babySex`, Settings → Baby or the Trends prompt). History opens as a list (`ui/screens/HistoryList.kt`, filtered by `HistoryFilter`; Today's summary tiles open it pre-filtered via `viewModel.openHistory`) with the week calendar behind a toggle. Notes and baths are editable in History (`EditNoteDialog`; `repository.updateNote`/`deleteNoteEvent` handle both and keep tags).

## Build notes

- The Gradle wrapper (Gradle 9.6.0; AGP 9.4, Kotlin 2.4, compileSdk 37, targetSdk 36) is checked in. Build with JDK 17+ (CI uses 21).
- Releases: `.github/workflows/release.yml` builds a signed release APK for a `vX.Y.Z` tag (version name from the tag, version code `X*10000+Y*100+Z`) and attaches it to a GitHub release. `checks.yml` runs the pre-commit hooks (`.pre-commit-config.yaml`: hygiene, gitleaks, actionlint, ktlint, blocks on secrets/APKs/data exports) on every push; Dependabot (`dependabot.yml`) opens weekly update PRs.
- The debug build type signs with `${rootDir}/debug.keystore` when present (gitignored). Per the AI Studio README, remove `signingConfig = signingConfigs.getByName("debugConfig")` locally if the build fails on signing — but don't commit that change.
- Dependency versions live in `gradle/libs.versions.toml`. Unused libraries are removed (not commented out), so Dependabot doesn't keep updating them; there is no KSP, Room or Firebase.
- Robolectric tests run with `@Config(sdk = [36])`.
