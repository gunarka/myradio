# RadioWidget — Developer Context

This file is intended for developers and AI assistants working on this codebase.
It documents architecture, component responsibilities, data flows, conventions,
and all bugs addressed in `radiowidget_fixes.patch`.

Source: https://github.com/gunarka/myradio

---

## Project at a Glance

| Property | Value |
|---|---|
| **Type** | Android App — Homescreen Widget + UI Activities |
| **Language** | Kotlin |
| **Package** | `de.radiowidget` |
| **Min SDK** | 31 (Android 12) |
| **Compile / Target SDK** | 36 |
| **AGP** | 9.1.1 |
| **Java** | 17 |
| **Build system** | Gradle 8 with Version Catalog (`gradle/libs.versions.toml`) |
| **UI language** | German |
| **Theme** | Dark; primary `#1A1A1A`, accent `#E8FF5A` (neon yellow-green) |

---

## Component Map

```
┌─────────────────────────────────────────────────────────────┐
│  Homescreen Widget (RadioWidgetProvider)                    │
│   • RemoteViews, 4×2 cells min (resizable)                  │
│   • Row 1: station name + genre · frequency                 │
│   • Row 2: ‹prev› [play/pause] ‹next›  ⚙manage             │
│   • Row 3: ‹page› [s0][s1][s2][s3][s4] ‹page›              │
│   • PendingIntent broadcasts for all controls               │
└────────────┬────────────────────────────────────────────────┘
             │ broadcasts (ACTION_PLAY / STOP / NEXT / PREV /
             │             ACTION_PAGE_NEXT / PAGE_PREV)
             ▼
┌─────────────────────────────────────────────────────────────┐
│  RadioService (Foreground Service)                          │
│   • MediaPlayer streaming (HTTP/HTTPS only)                 │
│   • foregroundServiceType=mediaPlayback                     │
│   • stopWithTask=false (survives recents-swipe)             │
│   • AudioFocus handling + isPausedForFocus flag             │
│   • Watchdog (30 s) + retry logic (10×, 5 s delay)         │
│   • Wake lock (PARTIAL) + WiFi lock (FULL_HIGH_PERF)        │
│   • Notification: Zurück / Stop / Weiter (MediaStyle)       │
│   • SCREEN_ON receiver for silent-death recovery            │
│   • requestBatteryExemptionOnce() on first play             │
└─────────┬──────────────────────────────────────────────────┘
          │ starts (once per session, first play)
          ▼
┌─────────────────────────────────────────────────────────────┐
│  BatteryOptimizationActivity                                │
│   • Transparent dialog theme (no UI flash)                  │
│   • One-time: guarded by "battery_asked" pref               │
│   • Guides user: system battery exemption + Samsung path    │
│   • Launched only if pm.isIgnoringBatteryOptimizations=false│
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│  StationRepository (singleton object)                       │
│   • SharedPreferences persistence (JSON array)              │
│   • Key: "all_stations_ordered"                             │
│   • 8 hardcoded German default stations                     │
│   • JSONException → graceful fallback to defaults           │
└─────────────────────────────────────────────────────────────┘
          ▲ read/write via Activity
┌─────────────────────────────────────────────────────────────┐
│  StationManagerActivity                                     │
│   • Tab "Meine Sender": RecyclerView, drag-to-reorder       │
│   • Tab "Suche": search via RadioBrowserApi                 │
│   • lifecycleScope coroutines (no manual cancel needed)     │
│   • Add from search: name, shortName=name.take(3).uppercase │
└────────────┬────────────────────────────────────────────────┘
             │ HTTP — must run on Dispatchers.IO
             ▼
┌─────────────────────────────────────────────────────────────┐
│  RadioBrowserApi (singleton object)                         │
│   • https://all.api.radio-browser.info/json (round-robin)   │
│   • searchByName(query, limit=30)                           │
│   • searchByTag(tag, limit=30)                              │
│   • User-Agent: "RadioWidget/1.0"                           │
│   • connect 5 s / read 8 s; disconnect() in finally         │
│   • Params: order=votes&reverse=true&hidebroken=true        │
└─────────────────────────────────────────────────────────────┘
```

---

## Data Models

### `RadioStation` — persisted in StationRepository

```kotlin
data class RadioStation(
    val name:      String,           // display name, e.g. "WDR 1Live"
    val shortName: String,           // widget slot label, e.g. "1L"
    val frequency: String,           // e.g. "104.2 FM", "DAB+", "128 kbps"
    val genre:     String,           // e.g. "Pop / Rock"
    val streamUrl: String,           // HTTP/HTTPS stream URL
    val isCustom:  Boolean = false   // true = added via search; shown as ★ in list
)
```

Default stations (loaded on first run):

| Name | shortName | frequency | genre |
|---|---|---|---|
| WDR 1Live | 1L | 104.2 FM | Pop / Rock |
| WDR 2 | W2 | 99.2 FM | Pop |
| WDR 3 | W3 | 95.9 FM | Klassik |
| Deutschlandfunk | DLF | 97.7 FM | Info |
| SWR3 | SWR | 93.4 FM | Pop |
| Energy NRW | ENY | 90.5 FM | Dance / EDM |
| Antenne 1 | A1 | 101.3 FM | Pop / Hits |
| NDR Info | NDR | DAB+ | News |

### `SearchResult` — transient, never persisted

```kotlin
data class SearchResult(
    val name:      String,
    val genre:     String,
    val country:   String,
    val bitrate:   Int,
    val streamUrl: String,
    val codec:     String
)
```

---

## SharedPreferences — key `radio_prefs`

| Key | Type | Default | Description |
|---|---|---|---|
| `all_stations_ordered` | String (JSON) | defaults | Serialised `List<RadioStation>` |
| `is_playing` | Boolean | false | Playback state — read by widget to show play/pause icon |
| `current_station` | Int | 0 | Index into the station list |
| `page` | Int | 0 | Current widget page (0-based) |
| `battery_asked` | Boolean | false | Guard for one-time battery exemption dialog |

**Important:** `saveState()` in `RadioService` must always be called **before** `RadioWidgetProvider.updateAllWidgets()`, otherwise the widget reads stale state.

---

## Widget Actions (Intent actions broadcast to `RadioWidgetProvider`)

| Constant | Action string | Extra | Forwarded to |
|---|---|---|---|
| `ACTION_PLAY` | `de.radiowidget.ACTION_PLAY` | `EXTRA_STATION` (Int) | `RadioService` via `startForegroundService` |
| `ACTION_STOP` | `de.radiowidget.ACTION_STOP` | — | `RadioService` via `startService` |
| `ACTION_NEXT` | `de.radiowidget.ACTION_NEXT` | — | `RadioService` via `startForegroundService` |
| `ACTION_PREV` | `de.radiowidget.ACTION_PREV` | — | `RadioService` via `startForegroundService` |
| `ACTION_PAGE_NEXT` | `de.radiowidget.ACTION_PAGE_NEXT` | — | Handled in `onReceive`, updates `page` pref |
| `ACTION_PAGE_PREV` | `de.radiowidget.ACTION_PAGE_PREV` | — | Handled in `onReceive`, updates `page` pref |

All six must be declared in `AndroidManifest.xml` in the receiver's `<intent-filter>`.

`broadcast()` helper in `RadioWidgetProvider.companion`:
```kotlin
fun broadcast(context: Context, action: String, stationIdx: Int, reqCode: Int): PendingIntent
```
Used both by the widget and by `RadioService.buildNotification()`.

---

## RadioService — State Machine

```
IDLE
 │  onStartCommand(ACTION_PLAY)
 ▼
STARTING → prepareAsync()
 │  onPrepared: mp.start(), isPlaying=true, watchdog scheduled
 ▼
PLAYING ◄──────────────────────────────────────────────────────┐
 │  AUDIOFOCUS_LOSS_TRANSIENT                                   │
 │    mp.pause(), isPausedForFocus=true, saveState, updateWidget│
 ▼                                                             │
PAUSED_FOR_FOCUS                                               │
 │  AUDIOFOCUS_GAIN (only if isPausedForFocus)                 │
 │    mp.start(), isPausedForFocus=false, saveState, updateWidget
 └──────────────────────────────────────────────────────────►──┘
 │  AUDIOFOCUS_LOSS → stopRadio()
 │  ACTION_STOP    → stopRadio() + stopSelf()
 ▼
STOPPED
  releaseLocks(), abandonAudioFocusRequest(),
  stopForeground(STOP_FOREGROUND_REMOVE), updateWidget
```

**Key flag: `isPausedForFocus: Boolean`**
Guards three locations from acting on an intentional pause:
1. `watchdogRunnable` — skip restart if `isPausedForFocus`
2. `screenOnReceiver` — skip restart if `isPausedForFocus`
3. `AUDIOFOCUS_GAIN` handler — only resume if `isPausedForFocus` was set by us

Reset to `false` in: `playStation()`, `stopRadio()`, `AUDIOFOCUS_GAIN` handler.

---

## Watchdog & Retry Logic

### Watchdog (`watchdogRunnable`, every 30 s)
Fires when `isPlaying == true`. Checks `mp.isPlaying` in a `try/catch` (Samsung MediaPlayer can throw on a dead player). Triggers forced restart **only when:**
- `isPlaying == true`
- `retryCount == 0` (no retry already scheduled)
- `isPausedForFocus == false`
- `mp == null || !mp.isPlaying`

### Retry (`retryRunnable`, after 5 s delay)
Called from `handleStreamEnd()` (shared by `setOnErrorListener` and `setOnCompletionListener`).
- If `retryCount < MAX_RETRIES (10)`: increment, re-call `startStream()`
- Else: `isPlaying = false`, update widget, `releaseLocks()`

### Battery Exemption (`requestBatteryExemptionOnce()`)
Called inside `playStation()` on every play, but guarded:
- Returns immediately if `battery_asked` pref is `true`
- Returns immediately if `pm.isIgnoringBatteryOptimizations(packageName)` is `true`
- Otherwise: sets `battery_asked = true`, starts `BatteryOptimizationActivity`

---

## Coroutine Strategy

| Location | Scope | Dispatcher |
|---|---|---|
| `StationManagerActivity` | `lifecycleScope` | `Main` (launch), `IO` (network call) |
| `RadioService` | `Handler(Looper.getMainLooper())` | Main thread; no coroutines used |

`RadioBrowserApi.fetch()` is a **blocking** call. It must always be wrapped in `withContext(Dispatchers.IO)`. Do not call it from the main thread.

---

## Permissions

| Permission | Where used |
|---|---|
| `INTERNET` | HTTP streaming + radio-browser.info API |
| `FOREGROUND_SERVICE` | RadioService |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Service type declaration (required API 29+) |
| `POST_NOTIFICATIONS` | Streaming notification (required API 33+) |
| `WAKE_LOCK` | `PowerManager.PARTIAL_WAKE_LOCK` in service |
| `ACCESS_WIFI_STATE` | WiFi lock |
| `CHANGE_WIFI_STATE` | WiFi lock (`WIFI_MODE_FULL_HIGH_PERF`) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery exemption dialog |

---

## Android API Compatibility Notes

| Issue | Fix applied |
|---|---|
| `registerReceiver` without export flag → `SecurityException` on API 34+ | `RECEIVER_NOT_EXPORTED` flag on API ≥ 33 (TIRAMISU); `@Suppress` on older path |
| `vh.adapterPosition` deprecated in RecyclerView 1.2+ | Use `vh.bindingAdapterPosition`; guard `== RecyclerView.NO_ID` before acting |
| Creating `RadioWidgetProvider()` instance to call an instance method | `updateWidget()` and `broadcast()` moved to companion object |

---

## Widget Layout (`widget_radio.xml`)

Three-row `LinearLayout`:

**Row 1 — Info**
- `tv_station_name`: bold, 16sp, white, single-line with ellipsis
- `tv_station_genre`: 11sp, 70% alpha white — shows `"${genre}  ·  ${frequency}"`

**Row 2 — Transport controls** (horizontal, `center_vertical`)
- `btn_prev` (40dp), `btn_play_pause` (48dp, margin 6dp), `btn_next` (40dp)
- Spacer (`weight=1`)
- `btn_manage` (36dp) — opens `StationManagerActivity`

**Row 3 — Station slots** (34dp height)
- `btn_page_prev` (28dp, `‹`) — `INVISIBLE` when on page 0
- `btn_s0` … `btn_s4` (equal weight) — short name; `btn_station_active` drawable when selected+playing
- `btn_page_next` (28dp, `›`) — `INVISIBLE` when on last page

Widget provider config (`radio_widget_info.xml`):
- `minWidth=250dp`, `minHeight=110dp`
- `targetCellWidth=4`, `targetCellHeight=2`
- `updatePeriodMillis=0` (widget updates itself via broadcasts only)
- `resizeMode=horizontal|vertical`

---

## Key Conventions

- All widget state updates flow through **`RadioWidgetProvider.updateAllWidgets(context)`** — never call `AppWidgetManager` directly elsewhere
- **`StationRepository.getAllVisible()`** is the single source of truth for the station list inside both the service and the widget
- **`saveState(idx, playing)`** must be called before `updateAllWidgets()` in the same code path
- **No `!!` operator** — use `coerceIn`, `getOrNull`, `runCatching`, or explicit null checks
- `StationAdapter` items marked `isCustom = true` show a ★ suffix in the sub-text
- When a user adds a station from search results, `shortName` is derived as `r.name.take(3).uppercase()` and `frequency` as `"${r.bitrate} kbps"` (or `"Stream"` if bitrate is 0)

---

## Patch Summary (`radiowidget_fixes.patch`)

### `RadioBrowserApi.kt`
- Hardcoded single node `de1.api.radio-browser.info` → round-robin `all.api.radio-browser.info`
- `try/finally` around HTTP call to guarantee `conn.disconnect()` on exceptions

### `RadioService.kt`
- Added `isPausedForFocus` flag
- `AUDIOFOCUS_LOSS_TRANSIENT`: pause + set flag + `saveState` + `updateAllWidgets`
- `AUDIOFOCUS_GAIN`: resume only if flag set; clear flag + `saveState` + `updateAllWidgets`
- Watchdog and `screenOnReceiver` both guard on `isPausedForFocus`
- API 33+: `registerReceiver` with `RECEIVER_NOT_EXPORTED`
- Extracted `handleStreamEnd()` — removed duplicate code from error and completion listeners
- `playStation()` and `stopRadio()` reset `isPausedForFocus = false`
- Notification buttons delegate to `RadioWidgetProvider.broadcast()` instead of a local copy

### `RadioWidgetProvider.kt`
- `updateAllWidgets()` no longer instantiates `RadioWidgetProvider()` — uses static `updateWidget()`
- `updateWidget()` and `broadcast()` moved to companion object (reusable by `RadioService`)
- Added shared `maxPage(stationCount: Int)` — eliminates inline duplication
- Empty station list guard in `updateWidget()` — was crashing with `IndexOutOfBoundsException`
- `ACTION_PAGE_NEXT` guard for empty list via `maxPage()`

### `AndroidManifest.xml`
- Added `ACTION_PAGE_NEXT` and `ACTION_PAGE_PREV` to the widget receiver's `<intent-filter>` — previously missing, causing silent drop by the OS

### `StationManagerActivity.kt`
- `CoroutineScope(Dispatchers.Main + SupervisorJob())` → `lifecycleScope`; `onDestroy()` override removed
- `adapterPosition` → `bindingAdapterPosition` throughout; `RecyclerView.NO_ID` guard added
- Delete dialog: capture station object before showing dialog, re-find by reference on confirm (race-condition fix for reordering between click and confirm)
- Removed duplicate `searchAdapter.clear()` inside coroutine body
- Removed redundant `notifyDataSetChanged()` after `addAll()` (already called internally)

### `StationRepository.kt`
- `parseList()` wrapped in `try { … } catch (e: JSONException)` returning `defaults` on corrupt data

### `libs.versions.toml` / `app/build.gradle.kts`
- Added `lifecycle = "2.8.7"` and `androidx-lifecycle-runtime = lifecycle-runtime-ktx`
- Added `implementation(libs.androidx.lifecycle.runtime)`
