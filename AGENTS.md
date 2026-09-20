# AGENTS.md — PebbleNotificationCenter2

You are reading this file because you are about to modify, build, or debug this codebase. This document is the single reference for how the project works: what each component does, the exact byte-level communication contract between phone and watch, the invariants that must never be broken, and where to make specific kinds of changes. Read it fully before touching code. If this document and the source disagree, **the source wins** — fix this document instead of "adapting" the code.

## 1. Project overview

PebbleNotificationCenter2 ("NC2") is a notification center for Pebble smartwatches. It collects Android notifications on the phone, applies user-defined rules, and delivers them to the watch as compact "bucket" entries; the watch renders a list UI, full details, actions, images, and preferences. Three components must work together:

| Component | Path | Technology | Role |
|---|---|---|---|
| Watch app | `watch/` | C (Pebble SDK 3, waf/`wscript`) | On-watch UI: notification list, actions, settings, image viewer |
| Companion app | `mobile/` | Kotlin, Android (AGP 9.4.0, Compose, Gradle multi-module) | Collects Android notifications, processes them through a rule engine, sends them to the watch over Bluetooth (PebbleKit2) |
| Commons | `PebbleCommons/` (git submodule → `https://github.com/matejdro/PebbleCommons.git`) | C (watch side) + Kotlin (phone side) | Shared code: AppMessage transport (`bluetooth`), bucketsync protocol on both sides, `bluetooth-common` (PebbleKit2 layer), `bucketsync` (SQLite sidecar), logging/crash reporting |

Key facts:

- The Android app supports **only microPebble or Pebble/Core (rebble)**. The old Pebble app is not supported (see README).
- Watch app identity: `name: notificationcenter`, displayName `Notify Center`, UUID `1c5f3908-e3ea-419b-ae55-7f167ea8fafa` (`watch/package.json`, equal to `WATCHAPP_UUID` in `mobile/bluetooth/api/.../bluetooth/api/Constants.kt`), `sdkVersion: 3`, `targetPlatforms: basalt, diorite, emery`.
- Android app identity: `applicationId com.matejdro.pebblenotificationcenter2`, namespace `com.matejdro.pebblenotificationcenter` (note: namespace and applicationId have different suffixes), `targetSdk 37`.
- Workspace device codenames relevant here: `basalt` (Pebble Time Steel) and `emery` (Pebble Time 2).
- Current version: `2.10` (root `version.txt` + `mobile/version.txt` + `watch/version.txt` + `watch/package.json`).

### Versioning & releases (automated — do not touch by hand)

- Root `version.txt` uses the scheme `MAJOR.MINORPATCH` where `MINORPATCH = MINOR*10 + PATCH` (PebbleOS does not allow three-number semver). When `MINORPATCH` reaches 100, `MAJOR` is incremented and `MINORPATCH` resets to 0 (logic in `.github/workflows/develop.yaml`).
- Version bumps are **never done manually by contributors**: the nightly workflow `develop.yaml` recomputes the version from conventional commits (feature/fix), rebuilds `mobile/` and/or `watch/` only if changed, publishes a GitHub Release with APK + PBW, and commits `version.txt`/`watch/package.json` plus a tag.
- Commits use **conventional commits** with scope = name of the module changed (see `CONTRIBUTING.MD`).

## 2. Repository layout

```
PebbleNotificationCenter2/
├── protocol.md            → SPEC of the AppMessage communication + bucket layouts (source of truth of the protocol)
├── version.txt            → current version (authoritative for releases)
├── docs/                  → screenshots and badges for the README
├── .github/workflows/     → verifyPr.yaml (CI on PR), develop.yaml (nightly release), pull-request-post.yaml
├── watch/                 → watch app in C (Pebble SDK)
│   ├── wscript            → waf build script (used by `pebble build`)
│   ├── CMakeLists.txt     → IDE/clang support only; HARDCODED include paths `~/.pebble-sdk/...`
│   ├── package.json       → app metadata (UUID, platforms, resource IDs)
│   ├── version.txt
│   ├── resources/*.png    → bitmaps (`~bw` and `~color` variants, managed via .gitattributes/LFS)
│   └── src/
│       ├── main.c                     → entry point, declares PROTOCOL_VERSION (=9)
│       ├── connection/packets.c/.h    → serialization/deserialization of ALL AppMessage packets
│       ├── connection/notification_details_fetcher.c/.h → "details" fetch (packet 4→5) with queue when BT busy
│       ├── data/preferences.c/.h      → parsing of bucket 1 (mute watch/phone, wrap scroll, backlight, auto-close)
│       ├── utils/bucket_utils.c/.h    → "are any notification buckets active?" (bucket != 1)
│       └── ui/
│           ├── window_status.c/.h       → "No notifications" / error window (lock screen)
│           ├── window_preferences.c/.h  → on-watch settings (mute watch/phone, restore hidden)
│           ├── window_image.c/.h        → image window (multi-packet PNG reassembly, zoom via packet 15)
│           ├── layers/status_bar.c/.h   → custom status bar: clock + state icons (busy/disconnected/error)
│           ├── layers/dots.c/.h         → "dots" layer (one per notification) with pagination arrows
│           └── window_notification/     → MAIN WINDOW
│               ├── window_notification.c/.h → state (NotificationWindowData), layout, scrolling
│               ├── data_loading.c/.h        → bucket ingest, selection, details parsing, "read" flags
│               ├── buttons.c/.h             → button mapping (UP/DOWN/SELECT/BACK)
│               ├── action_list.c/.h         → action menu (custom MenuLayer, voice dictation)
│               └── idle_handler.c/.h        → auto-close timer + periodic vibration
├── mobile/                → companion Android app (Gradle)
│   ├── settings.gradle.kts → includes local modules + PebbleCommons modules via projectDir
│   ├── build.gradle.kts    → root plugins (NO subprojects/allprojects blocks: project isolation)
│   ├── config/libs.toml    → version catalog (single, updatable via versionCatalogUpdate)
│   ├── buildSrc/           → convention plugins: all-modules-commons, android-module-commons,
│   │                        app-module (Metro DI + moduleGraphAssert), library-android-module,
│   │                        pure-kotlin-module, compose, navigation, serialization, showkase,
│   │                        sqldelight, di, unmock, test-fixtures, checks (detekt+jacoco+dep-analysis)
│   ├── detekt/             → project-specific detekt rules
│   ├── keys/               → debug/release keystores
│   ├── app/                → Application, MainActivity, PebbleListenerService, root DI graph, crash report
│   ├── notification/       → api: models (ParsedNotification, ProcessedNotification, Action, PauseStatus,
│   │                          NotificationRepository, NotificationServiceStatus/Controller, AppNameProvider);
│   │      data: NotificationService (NotificationListenerService), NotificationParser, NotificationProcessor
│   │           (core of the pipeline), RuleResolver, ActionHandlerImpl, SubmenuActionHandlerImpl,
│   │           PauseControllerImpl, NotificationServiceControllerImpl, ScreenStateChecker,
│   │           HistoryInserter, AppNameProviderImpl/AppColorProvider
│   ├── rules/              → api: RuleOption (ALL per-rule setting keys), GlobalPreferenceKeys, PebbleFont,
│   │                          MasterSwitch, RuleMetadata, RulesRepository, PreferenceKey types with defaults;
│   │      data: RulesRepositoryImpl (rules DB + one DataStore file per rule), Mappings, DatastoreFactory;
│   │      ui:  RuleListScreen (drag&drop reorder), RuleDetailsScreen (+ dialogs per option)
│   ├── history/            → api (HistoryEntry, HistoryRepository), data (SQLite), ui (HistoryScreen)
│   ├── home/ui             → HomeScreen (tabs) + OnboardingScreen (permissions)
│   ├── tools/ui            → ToolsScreen (global settings) + ActionOrderListScreen
│   ├── tasker/             → api (TaskerTaskStarter, plugin constants), data (TaskerActionService, LegacyTaskerReceiver,
│   │                          TaskerActionRunner, TaskerTaskStarterImpl, TaskerServiceInjector), ui (ToggleMuteScreen,
│   │                          TaskerConfigurationActivity, TaskerTaskSetScreen)
│   ├── bluetooth/          → api: WatchSyncer, WatchappOpenController, SubmenuController (+SubmenuType),
│   │                          ImageSender, WatchMetadata, SubmenuActionHandler, WATCHAPP_UUID;
│   │      data: WatchSyncerImpl (notification→bucket serialization), WatchappConnectionImpl (protocol
│   │            state machine on phone side), NotificationDetailsPusherImpl (packets 5+7),
│   │            SubmenuControllerImpl (packet 9), ImageSenderImpl (packet 11 chunked),
│   │            WatchappOpenControllerImpl, images/ (indexed/grayscale PNG for Pebble, palette, crop)
│   ├── common/             → pure Kotlin: PreferenceUtils (merge), ActionLogger, Outcome, InMemoryDataStore (tests)
│   ├── common-android/     → AndroidVersion qualifier, NavigationInjectingApplication
│   ├── common-compose/     → theme, components, ReorderableList, showkase launcher, previews
│   ├── common-navigation/  → navigation keys (Home, Rules, History, Tools, Onboarding, Tasker…) + instructions
│   ├── navigation-impl/    → TabListDetailScene (tab list-detail scaffolding used by all UI)
│   ├── shared-resources/   → shared resources (strings, icons)
│   └── app-screenshot-tests/ → Paparazzi + Showkase
└── PebbleCommons/         → SUBMODULE (never commit as "part of the repo"; update as a pin)
    ├── watch/
    │   ├── connection/bluetooth.c/.h  → AppMessage: inbox/outbox, retry, reconnect, callbacks
    │   ├── connection/bucket_sync.c/.h → bucket persistence (persist_write), sync state machine, callbacks
    │   ├── structures/vec.h/.c        → C vectors (BSD, from Mashpoe/c-vector)
    │   ├── bytes.h/.c                 → read_uint16/32 big-endian
    │   └── math.h                     → MAX/MIN
    └── mobile/
        ├── bluetooth-common/ → WatchappConnectionsManager(Impl) (routing per watch), WatchAppConnection,
        │                       PacketQueue (priority queue + retry with backoff), PebbleDictionary util,
        │                       BaseWatchappConnectionScope (DI graph per connection), FakePebbleSender
        ├── bucketsync/       → api: BucketSyncRepository, BucketUpdate, BucketSyncWatchLoop(Impl in data),
        │                       BackgroundSyncNotifier; data: BucketsyncRepositoryImpl (SQLite dbBucket),
        │                       BucketlistPackets (packet chunking), di, workManager (OpenWatchappWorker,
        │                       GetConnectedWatchesWorker, GetForegroundAppWorker, WorkController);
        │                       test: FakeBucketSyncRepository, InMemoryDataStore…
        └── logging/          → api (FileLoggingController), data (tinylog rolling file), crashreport (UI crash)
```

Note on PebbleCommons Kotlin includes: `mobile/settings.gradle.kts` exposes them as regular Gradle projects (`:bluetooth-common`, `:bucketsync:api|data|test`, `:logging:api|data|crashreport`) pointing at `../PebbleCommons/mobile/...`.

Exact Gradle module list (from `mobile/settings.gradle.kts`): `:app`, `:app-screenshot-tests`, `:common`, `:common-android`, `:common-compose`, `:common-navigation`, `:detekt`, `:history:api`, `:history:data`, `:history:ui`, `:home:ui`, `:notification:api`, `:notification:data`, `:shared-resources`, `:bluetooth:api`, `:bluetooth:data`, `:navigation-impl`, `:rules:api`, `:rules:data`, `:rules:ui`, `:tasker:api`, `:tasker:data`, `:tasker:ui`, `:tools:ui`, plus the PebbleCommons projects listed above.

## 3. The communication contract

The full specification lives in `protocol.md` — that file is the **source of truth**. Any format change requires updating `protocol.md` AND both implementations (C in `packets.c`/`bucket_sync.c`, Kotlin in `WatchappConnectionImpl`/`BucketlistPackets`/`WatchSyncerImpl`/`NotificationDetailsPusherImpl`/`ImageSenderImpl`/`SubmenuControllerImpl`). Summary below for orientation.

Every AppMessage packet carries `dictionary key 0` = packet ID (uint8); payloads live in `key 1` (byte array).

### Phone → Watch packets

| ID | Name | Notes |
|---|---|---|
| 1 | Phone Welcome | Response to watch packet 0. Keys: `1` phone protocol version (u16), `2` bucketsync data (byte array, see below), `3` present → watch auto-closes after sync, `4` present → watch does not close "to last app". If versions mismatch: only key `1` is sent. |
| 2 | Re-start bucketsync sync | Sent when the watch is open and buckets change. Data layout: sync-complete-flag (u8: 1=last, 0=followed by packet 3), bucketsync version (u16), count of active buckets (u8), (bucketId u8 + flags u8)*, then (bucketId u8 + size u8 + data)*. |
| 3 | Follow-up bucket data | Extra packets after 1/2: sync-complete-flag + (id,size,data)*. |
| 5 | Notification details | After packet 4. `1`: bucketId (u8), number of actions (u8), per action: actionId (u8) + text (cstr ≤ 20 bytes + null), icon byte count (u16, 0=none) + PNG icon (indexed on color watches, grayscale on B&W), full text (cstr, up to packet max, NO null terminator; the watch treats END OF BUCKET as termination). |
| 7 | Vibrate | `1`: sequence of u16 (vibe, pause, vibe, pause, …). |
| 9 | Show submenu | `1`: bucketId, menuId (u8), number of actions (u8), per action: text (cstr ≤20+null) + "voice" flag (u8 1/0 → the watch asks for dictation and replies with the text in key `4` of packet 6). |
| 11 | Show image | One or more consecutive packets: `1`: notificationId (u8), total size (u16), flags (u8: 0x01 first, 0x02 last), image data (PNG indexed/grayscale). |
| 12 | Request re-init | The watch must resend packet 0 (welcome). |

### Watch → Phone packets

| ID | Name | Notes |
|---|---|---|
| 0 | Watch Welcome | At launch. `1` watch protocol version (u16), `2` watch bucketsync version (u16), `3` AppMessage inbox size in bytes (u16, capped at 4096 on watch), `4` watch flags (0x01 = color screen), `5` screen width (u16), `6` height (u16), `7` list of active bucket ids (byte array). |
| 4 | Notification opened | `1`: id of the viewed bucket. |
| 6 | Activate action | `1` bucket id, `2` action ID (main menu) / index (submenu), `3` menu id (0 = regular actions from packet 5, else menu id sent via packet 9), `4` custom voice text (optional, cstr). |
| 8 | Close me | Phone closes the watch app "to the last app". |
| 10 | Change setting | `1` setting id (0=mute watch, 1=mute phone), `2` value (0/1). |
| 14 | Reload all notifications | Re-show all hidden notifications. |
| 15 | Re-send image | `1` bucket id, `2` 1=send cropped image (zoom in), 0=not cropped. |

### Bucketsync data inside packets 1/2 (byte array of key `2`/`1`)

`sync status (u8): 2 = watch already up to date (stop here); 1 = this is the last packet; 0 = packet 3 follows` — then, if status != 2: newer bucketsync version (u16), number of active buckets (u8), (bucketId + flags) for each active bucket, then (id, size, data) for the updated buckets that fit in the packet.

### Buckets (watch memory)

- Max 15 buckets on legacy watches (basalt/diorite), 127 on `emery`/`flint` (see `PebbleCommons/watch/connection/bucket_sync.h`: `#if defined PBL_PLATFORM_EMERY || defined PBL_PLATFORM_FLINT`).
- Each bucket: data ≤ 255 bytes (`PERSIST_DATA_MAX_LENGTH`), stored on the watch under persist key `id + 2000` (bucket 1 → 2001, …).
- **Bucket 1 = SETTINGS** (not a notification): byte 0 = flags (0x01 mute watch, 0x02 mute phone, 0x04 no scroll wrap, 0x08 backlight-on-vibration, 0x10 large status bar font) + u16 auto-close seconds (0 = disabled). Phone side: `WatchSyncerImpl.syncPreferences()` serializes `GlobalPreferenceKeys` into this format, debounced 50 ms.
- **Buckets 2+ = notifications**: `timestamp unix u32` + `titleFont u8` + `subtitleFont u8` + `bodyFont u8` + `accentColor u8` (GColor8 ARGB, 0 = absent) + `title cstr ≤20` + `subtitle cstr ≤20` + `body` up to the 255-byte total (no null; terminated by end-of-bucket).
- **Notification bucket flags**: 0x01 = unread, 0x02 = paused, 0x04 = periodic vibration desired (while the notification is still unread).

### Watch non-bucket persist keys (`PebbleCommons/watch/connection/bucket_sync.c`, `data_loading.c`)

- `1000` = list of active buckets (array of id/flag pairs),
- `1001` = current bucketsync version of the watch,
- `1002` = protocol version used in the last write; **if it changes over time, ALL watch data is wiped and re-synced** (bucketsync is not retrocompatible),
- `3000 + bucketId` (min constant `STORAGE_BUCKET_FLAGS_ID_MIN = 3000`; e.g. 3002 for bucket 2) = "user has seen this" flag (1 byte). A notification is "unread" only if flag 0x01 is present AND this byte is not 1 (`is_notification_unread` in `data_loading.c`).

### Versions and constants (critical invariants, values verified)

- `PROTOCOL_VERSION = 9`: declared in `watch/src/main.c`, declared extern in `PebbleCommons/watch/connection/bluetooth.h`, and MUST stay equal to `PROTOCOL_VERSION: UShort = 9` in `mobile/bluetooth/data/.../bluetooth/Constants.kt`.
- `BUCKET_DATA_VERSION: UShort = 4` (phone side only): passed to `BucketSyncRepository.init()`; if different from the last saved value, the bucketsync DB is wiped (`BucketsyncRepositoryImpl.init`).
- Runtime version mismatch: the watch shows an error window ("Please update watch/phone app") if versions differ (packet 1); if the watch is newer, the phone replies with packet 1 containing only key 1.
- `MAX_BUCKET_ID = 255`, `MAX_BUCKET_SIZE_BYTES = 255`, `MAX_BUCKETS_LEGACY_WATCHES = 15`, `MAX_BUCKETS_CORE_WATCHES = 127` (`BucketSyncRepository.kt`; watch side `MAX_BUCKETS`).

## 4. Watch app (C)

### Boot sequence (`watch/src/main.c`), in order

1. `packets_init()` — registers inbound handlers on `bluetooth`
2. `bluetooth_init()` — opens AppMessage: inbox `app_message_inbox_size_maximum()` capped at 4096, outbox 400; subscribes to the connection service
3. `window_notification_data_app_started()` — registers the bucket-deleted callback
4. `bucket_sync_init()` — re-reads persist 1000/1001/1002, wipes everything if the protocol version changed
5. `notification_details_fetcher_init()`
6. `reload_preferences()` — reads bucket 1
7. `send_watch_welcome()` — packet 0
8. Shows `window_notification` if at least one bucket != 1 exists, otherwise `window_status_show_empty`
9. `app_event_loop()`

### Transport (`PebbleCommons/watch/connection/bluetooth.c`)

- State: `is_phone_connected`, `is_currently_sending_data`, `sending_error`, `ignore_bluetooth_busy_errors`.
- `bluetooth_app_message_outbox_send()` is the single send point: clears `sending_error` only when not ignorable; `APP_MSG_NOT_CONNECTED`/`SEND_TIMEOUT` → disconnection; after a reconnect, packets are sent after a 1000 ms timer delay because right after a connection change packets get "stuck".
- `bluetooth_register_reconnect_callback` exists in commons but is **NOT used** by this app: re-synchronization is driven by the phone (packet 12, see §7).
- `bluetooth_register_sending_finish(callback)` is a vector (vec.h): when sending finishes, all registered callbacks are invoked and then cleared. Used for close-me retries, details retries, etc.
- `bluetooth_show_error` is declared in commons but **defined in the app** (`window_status.c`) → blocking error window.

### Bucket sync on the watch (`bucket_sync.c`)

- `bucket_sync_on_start_received` (packets 1/2) and `bucket_sync_on_next_packet_received` (packet 3).
- Sync status == 2 → "up to date": stop syncing, optionally auto-close (`close_after_sync`).
- Otherwise: update the `BucketList` (id/flag pairs), delete buckets no longer active (via `bucket_deleted_callback` → the watch removes the "seen" persist `3002+id`… precisely `STORAGE_BUCKET_FLAGS_ID_MIN + id`), persist the list to 1000, write bucket data via `persist_write_data(id+2000)`.
- On completion (status 1 or final packet 3): save version to 1001 and protocol to 1002; `close_after_sync` → `window_stack_pop_all(true)`.
- Available callbacks: list change, data change (per bucket), syncing status (two slots: `register_syncing_status_changed_callback` + `register_second_syncing_status_changed_callback`), bucket deleted.

### Main UI (`ui/window_notification/`)

- Global state lives in the struct `NotificationWindowData` (extern): selected bucket + index, bucket count and `dot_states[14]`, fonts/title/subtitle/body text (heap-like buffer `char body_text[4040]`), GBitmap icon, actions (max 20, 21B text) and submenus (max 20, with voice flag), `menu_displayed`, `open_menu_on_success`, `currently_displayed_menu_id`.
- `data_loading.c`:
  - `on_buckets_changed` / `on_bucket_updated` (bucketsync callbacks) → recompute dot states (UNREAD via `is_notification_unread`: flag 0x01 AND not seen via `3000+id`; PAUSED if flag 0x02; else NORMAL), bucket selection (if launch=PHONE and not interacted → force-select the first), `persist_write_data(STORAGE_BUCKET_FLAGS_ID_MIN+id, {1})` when the user moves away from an unread one (saved only, UI not updated immediately: "temporary unread").
  - `reload_data_for_current_bucket`: reads the bucket (max 256B), decodes timestamp/fonts/color/title/subtitle/body, calls `notification_details_fetcher_fetch` (→ packet 4; if busy → queue `next_notification_to_fetch` + `bluetooth_register_sending_finish`), appends the receive date to the body ("Received at %H:%M" / "yesterday" / "on %b %d, %H:%M" depending on 24h clock).
  - `on_text_received` (packet 5): replaces the body with the full text (max `MAX_BODY_TEXT_SIZE` = 4000), actions, PNG icon (GBitmap, 2-color palette derived from the banner).
  - `receive_show_submenu` (packet 9): if the menu is already open → store `open_menu_on_success`, else open the menu with the new menu id.
- `window_notification.c`: layout (custom status bar on top, dots left of the status bar, ScrollLayer with custom content: banner title/subtitle + 32px icon top-right + body). Fonts: the saved u8 index is the ordinal in the `fonts[]` array (18 entries, from `FONT_KEY_GOTHIC_14` to `FONT_KEY_DROID_SERIF_28_BOLD`) — **it must match the ordinal of the `PebbleFont` enum on the phone side** (it matches today, same 18 entries). On color watches, if `color != 0` the banner is colored (GColor8) and text uses `gcolor_legible_over`.
- `buttons.c`: UP/DOWN single click → scroll ±32 px (`SINGLE_SCROLL_HEIGHT`, `window_raw_click_subscribe` to avoid hold delay; repeating → `click_recognizer_is_repeating`); UP/DOWN double click → previous/next notification (with wrap-around); SELECT → toggles the action menu (if `num_actions == 0` → double buzz, details not arrived yet); BACK → `send_close_me()` (packet 8) or hides the menu; BACK double click → `window_preferences_show()`.
- `action_list.c`: custom MenuLayer (1 section, 30px rows, text only). UP/DOWN nav with wrap; SELECT → `window_notification_action_select()`: if the action has `voice` → `DictationSession` (300 ms) and the transcript travels in packet 6 key 4; otherwise `confirm_action` → `send_action_trigger` → **freezes the menu** (`frozen`, inverted highlight) until `on_sending_finished`: success → (re)opens `open_menu_on_success` submenu or closes; failure → unfreeze + double buzz.
- `idle_handler.c`:
  - Auto-close: if `launch_reason == APP_LAUNCH_PHONE` and no interaction and `auto_close_timeout != 0` → `send_close_me()` after N seconds (timer recomputed on each registration).
  - Periodic vibration: if there exists a notification with flag 0x04 still unread (or "temporary unread": selected bucket with UNREAD dot) and `any_notification_vibrated` and the user hasn't interacted → every 10 s (`PERIODIC_VIBRATION_PERIOD_MS = 10000`) a 50 ms vibration (`PERIODIC_VIBRATION_PATTERN`), skipped during quiet time (`quiet_time_is_active()`).
- `layers/status_bar.c`: custom black bar with a clock (GOTHIC_14, 16 px, or GOTHIC_24_BOLD, 30 px if flag 0x10 in bucket 1); based on `PBL_DISPLAY_WIDTH > 190` adds corner padding (emery). Right-side icons: error (`sending_error != APP_MSG_OK`), disconnected, busy (sending/syncing/details-fetch in progress). `custom_status_bar_get_left_space` returns space for the dots. The clock updates every minute (tick MINUTE); global listeners (tick, connection, error, syncing, details-fetch) are attached/detached only while a status bar is active.
- `layers/dots.c`: up to N dots (radius 4 or 3 depending on space), UNREAD = diamond (bitmap, Rajah color on color watches), PAUSED = square (very light blue on color), NORMAL = circle (outline = selected); if they don't all fit → pagination with `<` `>` arrows (`fix_pages`).
- `window_status.c`: "No notifications." (auto-switches to the notification window as soon as a bucket != 1 arrives; if launched from the phone and a sync is in progress: closes itself if it stays empty, `close_on_empty_and_no_sync`), or the indefinite ERROR window (`window_status_show_error`). Also **defines** `bluetooth_show_error` (declared in commons).
- `window_preferences.c`: SimpleMenuLayer "Muting" (Mute watch / Mute phone → packet 10 with setting id 0/1) and "Notifications" (Restore hidden notifs → packet 14). On send failure → double buzz.
- `window_image.c`: receives chunks of packet 11 (header: notifId u8, totalSize u16, flags u8, then PNG). First packet → creates the window; last packet → `gbitmap_create_from_png_data`. SELECT → zoom toggle: sends packet 15 with `crop = (bitmap does not cover the screen)` (sends the cropped/non-cropped version), then waits for the new packet 11.

### Building the watch app

- Canonical build: from `watch/`, `pebble sdk install latest` (if needed) then `pebble build` → `build/watch.pbw`. CI installs the toolchain with `uv tool install pebble-tool && pebble sdk install latest` (Python 3.14 in the workflow).
- `watch/CMakeLists.txt` exists only for IDE support (clang, basalt) and has **hardcoded include paths** (`~/.pebble-sdk/...`); the parent workspace (`/home/leo/PEBBLE/AGENTS.md`) instead points at `${HOME}/.local/share/pebble-sdk/...`. Before using CMake directly, check which prefix actually exists on your system (and correct it if needed, noting the discrepancy). `package.json` contains `enableMultiJS: true` but there is no JS in the project (C only).
- Bitmaps in `watch/resources/` come in `~bw`/`~color` variants registered in `package.json` (resource IDs such as `MENU_ICON`, `INDICATOR_BUSY`, `INDICATOR_DISCONNECTED`, `INDICATOR_ERROR`, `INDICATOR_UNREAD_LARGE`, …). New bitmaps must provide both variants and be added to `package.json`.

## 5. Companion app (Kotlin)

### Entry point and services (module `app`)

- `NotificationCenterApplication` : `NavigationInjectingApplication`, `CrashWindowThemeProvider`. Creates the Metro graph (`createGraphFactory<MainApplicationGraph.Factory>()`), sets up dispatchers (`DefaultDispatcherProvider` with a callback dispatcher that crashes in debug if global dispatchers are used), wires logging (logcat + Kermit → TinyLog rolling file + ErrorReportingKermitWriter), StrictMode (debug: throw), NC notification channels, WorkManager initialized manually (no auto-init) with `NotificationCenterWorkerFactory`, and starts `watchSyncer.init()` + `backgroundSyncNotifier.notifyAppStarted()`.
- `MainActivity` + `MainViewModel`: Compose UI (TabListDetailScene), guided onboarding.
- `PebbleListenerService` (PebbleKit2 `BasePebbleListenerService`): receives `onMessageReceived/onAppOpened/onAppClosed` from phone↔watch and forwards them to `WatchappConnectionsManager`. Exported with intent-filter `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH`.
- `NotificationService` (in `notification/data`): a `NotificationListenerService`. `onListenerConnected` → (once) `onNotificationsCleared` + `reloadAllNotifications` (re-parse active SBNs with `suppressVibration = true` = APP_STARTUP) + `controlListenerHintsAndOpenOnReconnect` (hints `HINT_HOST_DISABLE_NOTIFICATION_EFFECTS` if mutePhone && watch connected; reopens the watch app on reconnect if a vibration is pending and `notifyOnReconnect`). `onNotificationPosted/Removed` → coroutines on `DefaultCoroutineScope` serialized by a `Mutex` → `NotificationParser.parse` → `NotificationProcessor.onNotificationPosted / onNotificationDismissed`. `getNotificationChannel` requires CompanionDeviceManager active (waits up to 10×100 ms; `SecurityException` handled).
- Manifest permissions: `POST_NOTIFICATIONS`, `VIBRATE`, `io.rebble.pebblekit2.permission.SEND_DATA_TO_WATCH`, `io.rebble.pebblekit2.permission.READ_PROVIDER`, `QUERY_ALL_PACKAGES`, feature `companion_device_setup`.

### Dependency injection — Metro, not Hilt/Dagger

- Root graph: `MainApplicationGraph` = `@DependencyGraph(AppScope::class, additionalScopes = [OuterNavigationScope::class])`, extends `ApplicationGraph = NavigationInjectingGraph + NotificationInject + TaskerServiceInjector` (+ ErrorReporter, DefaultCoroutineScope, DateFormatter, logging, WatchSyncer, sync notifier, PebbleAppPicker…).
- Subgraph **per connected watch**: `WatchappConnectionScope` (commons `bluetooth-common/di`): `WatchappConnectionsManagerImpl` (AppScope) creates a `WatchAppConnection` per watch via `WatchappConnectionImpl.Factory` (AppScope) → `WatchappConnectionGraph.Factory` (provided by `bluetooth/data/di/WatchappConnectionScopeProviders`). Components in `WatchappConnectionScope`: `PacketQueue`, `BucketSyncWatchLoop`, `NotificationDetailsPusher`, `ActionHandler`, `SubmenuController`, `NotificationServiceController` (bound to AppScope), `ImageSender` (AppScope), etc.
- Injection pattern in services/activities: `(application as ...).applicationGraph.inject(this)` + `@Inject lateinit var` (e.g. `NotificationService.onCreate`, `PebbleListenerService.onBind`).
- Scopes: `@SingleIn(AppScope::class)` for singletons (NotificationProcessor/PauseController/RulesRepository/BucketsyncRepositoryImpl…), `@SingleIn(WatchappConnectionScope::class)` for per-watch objects.
- Coroutines: the `dispatch` library (DefaultCoroutineScope, IOCoroutineScope, `withIO`, `flowOnDefault`, `withDefault`) — do NOT use `Dispatchers` globally; in debug it crashes via `AccessCallbackDispatcherProvider`.

### Notification pipeline (`notification/data`)

`NotificationService.onNotificationPosted(sbn)` → `parseNotification` (ranking + channel + pref `showMessagingStyleChronologically`) → `NotificationParser.parse`:

- title = app name (`AppNameProvider`); subtitle = EXTRA_CONVERSATION_TITLE → HIDDEN_CONVERSATION_TITLE → TITLE → TITLE_BIG; body = messagingStyle (messages joined "Persona: text", ordered chronologically/anti-chronologically, first image recovered) → inbox style → BIG_TEXT → TEXT → SUMMARY_TEXT → SUB_TEXT → INFO_TEXT;
- "useless char" control (`\p{Cf}|\p{M}`);
- **if title > 20 chars** (MAX_TITLE_LENGTH) → it goes to `conversationTitle` and is prepended to the body (`"$title\n$body"`), subtitle empty (so it can be removed with `hideSubtitle`);
- `isSilent` from channel importance + vibrate/sound; `isFilteredByDoNotDisturb` from ranking `matchesInterruptionFilter() == false`; `forceVibrate` if app == NC itself with extra `KEY_FORCE_VIBRATE`; `overrideVibrationPattern` from extra; smallIcon → Drawable; largeImage (messaging image / EXTRA_PICTURE / EXTRA_PICTURE_ICON); color = notification.color or app color (`AppColorProvider`); if largeImage present → 📷 prefixed to subtitle.

`NotificationProcessor.onNotificationPosted(parsed, suppressVibration=false)`:

1. `RuleResolver.resolveRules(parsed)` → `(affectedRules, preferences)`: rules in their order; the `Default Settings` rule (id 1, `RULE_ID_DEFAULT_SETTINGS`) **is always applied**; others match if: (a) `conditionAppPackage` null or equal to `pkg`; (b) `conditionNotificationChannels` empty or containing `channel`; (c) ALL whitelist regexes match (title|subtitle|body); (d) NO blacklist regex matches. The `Preferences` are merged sequentially (top→bottom) via `plus` — **lower rules override higher ones**; a setting absent in a rule inherits the one above (default of `RuleOption`).
2. `shouldHide`: `forceVibrate` → never; otherwise hide if `masterSwitch == HIDE`, or (ongoing + `hideOngoingNotifications`), (groupSummary + `hideGroupSummaryNotifications`), (localOnly + `hideLocalOnlyNotifications`), (media + `hideMediaNotifications`) → history (unless `hideFromHistory`) + `onNotificationDismissed(key)` + return.
3. `pauseController`: if new notification and `autoAppPause`/`autoConversationPause` → auto-mute per pkg/key; `PauseStatus(app, conversation)`.
4. `processActions` → list of `Action`: NC actions id 0..n (`Dismiss`, `Snooze` (Android 8+ only), `ShowImage` (if largeImage), `TaskerTask`* (configured), `PauseApp`/`PauseConversation` (title flips pause), `HideFromWatch`) + native actions of the app (`Action.Native` / `Action.Reply` with remoteInput; name collisions → suffix "(App)"). Final action order is remapped by `ActionOrderRepository` (`GlobalPreferenceKeys.actionOrder`) at send time.
5. `getVibrationPattern` (in order): `forceVibrate` → always; `suppressVibration` → MUTE (APP_STARTUP); `muteWatch` (global) → WATCH_MUTE; paused (before insertion) → PAUSE; `masterSwitch == MUTE` → MASTER_SWITCH; silent + `muteSilentNotifications`; DND + `muteDndNotifications`; identical text to previous + `muteIdenticalNotifications`; screen-on + `muteScreenOn`. Otherwise: pattern = `overrideVibrationPattern` (from the notification) or `parseVibrationPattern(preferences[RuleOption.vibrationPattern])` (string "50, 50, …", default 10×50). The pattern is put in `nextVibration` (AtomicReference) and the watch app is opened (`openController.openWatchapp()`).
6. `applyTextRules`: `regexReplacements` (set of pair sets) on title/subtitle/body; if `hideSubtitle` → subtitle="" and remove `conversationTitle` from the body.
7. `watchSyncer.syncNotification(processed, prefs)` → bucket id (see below); saves `ProcessedNotification` (bucketId, actions, unread = !suppressVibration, paused, vibrated) in internal maps (`bucketId → notification`, `key → bucketId`).
8. `insertIntoHistory` (unless `hideFromHistory`).

Complete `RuleOption` catalog (keys with defaults, from `rules/api/.../RuleOption.kt`):

| Key | Type | Default |
|---|---|---|
| `condition_app_package` | nullable string | `null` |
| `condition_notification_channels` | string set | empty |
| `condition_whitelist_regexes` | string set | empty |
| `condition_blacklist_regexes` | string set | empty |
| `master_switch` | enum `MasterSwitch` | `SHOW` |
| `vibration_pattern` | string | `"50, 50, 50, 50, 50, 50, 50, 50, 50, 50"` |
| `periodic_vibration` | boolean | `false` |
| `reply_canned_texts_list` | string list | `["Yes", "No", "Okay"]` |
| `snooze_intervals` | int list | `[10, 30, 60, 90]` |
| `tasker_task_actions` | string set | empty |
| `hide_from_history` | boolean | `false` |
| `priority` | int | `50` |
| `font_title` | `PebbleFont` | `GOTHIC_24_BOLD` |
| `font_subtitle` | `PebbleFont` | `GOTHIC_14_BOLD` |
| `font_body` | `PebbleFont` | `GOTHIC_14` |
| `hide_subtitle` | boolean | `false` |
| `auto_app_pause` | boolean | `false` |
| `auto_conversation_pause` | boolean | `false` |
| `mute_silent_notifications` | boolean | `true` |
| `mute_dnd_notifications` | boolean | `true` |
| `mute_identical_notifications` | boolean | `true` |
| `hide_ongoing_notifications` | boolean | `true` |
| `hide_group_summary_notifications` | boolean | `true` |
| `hide_local_only_notifications` | boolean | `true` |
| `hide_media_notifications` | boolean | `true` |
| `regex_replacements` | string pair-set | empty |

Complete `GlobalPreferenceKeys` (from `rules/api/.../GlobalPreferenceKeys.kt`):

| Key | Type | Default |
|---|---|---|
| `mute_watch` | boolean | `false` |
| `mute_phone` | boolean | `false` |
| `action_order` | string list | empty |
| `auto_close` | int (seconds, 0 = off) | `0` |
| `show_messaging_style_chronologically` | boolean | `false` |
| `notify_on_reconnect` | boolean | `true` |
| `wrap_around_scroll` | boolean | `true` |
| `turn_on_backlight` | boolean | `false` |
| `large_status_bar_font` | boolean | `false` |
| `mute_screen_on` | boolean | `false` |

**PebbleFont** (18 values, ordinals must mirror the C `fonts[]` array exactly): `GOTHIC_14, GOTHIC_14_BOLD, GOTHIC_18, GOTHIC_18_BOLD, GOTHIC_24, GOTHIC_24_BOLD, GOTHIC_28, GOTHIC_28_BOLD, BITHAM_30_BLACK, BITHAM_42_BOLD, BITHAM_42_LIGHT, BITHAM_42_MEDIUM_NUMBERS, BITHAM_34_MEDIUM_NUMBERS, BITHAM_34_LIGHT_SUBSET, BITHAM_18_LIGHT_SUBSET, ROBOTO_21_CONDENSED, ROBOTO_49_SUBSET, DROID_SERIF_28_BOLD`. NOTE: the Kotlin enum names differ slightly from the C font keys for two entries (`ROBOTO_21_CONDENSED` ↔ `FONT_KEY_ROBOTO_CONDENSED_21`, `ROBOTO_49_SUBSET` ↔ `FONT_KEY_ROBOTO_BOLD_SUBSET_49`); only the ordinal positions matter.

### Sync toward the watch (`bluetooth/data`)

- `WatchSyncerImpl.syncNotification`: serializes into the buffer: `timestamp u32` (big-endian via `writeUInt`), `fontTitle/subtitle/body u8` (`PebbleFont` ordinal), `color → GColor8` (`toPebbleColor`: `0xC0 | (r>>6)<<4 | (g>>6)<<2 | (b>>6)` — 2 bits per channel), title UTF-8 ≤ 20 B + null, subtitle ≤ 20 B + null, body ≤ rest of 255 B (`.fixPebbleIndentation()`), then `bucketSyncRepository.updateBucketDynamic(upstreamId = notification.key, data, sortKey = -epochSecond * priority, flags)`. Flags: 0x01 unread, 0x02 `paused.any`, 0x04 `vibrated && periodicVibration` (rule option).
- `WatchSyncerImpl.init`: `bucketSyncRepository.init(BUCKET_DATA_VERSION, dynamicPool = 2..MAX_BUCKET_ID)`; if false (protocol version changed) → everything must be re-synced. Then `syncPreferences` → bucket 1 (debounce 50 ms).
- `markAsRead(bucketId)` → `updateBucketFlagsSilently` (does NOT bump the version: the "read" flag only travels when the bucket is re-TRANSMITTED).

### `PebbleCommons/mobile/bucketsync` (phone-side sidecar for the watch)

- SQLite `dbBucket` (sqldelight, in `PebbleCommons/mobile/bucketsync/data`): `(id INTEGER PK, data BLOB [NULL = inactive], version INTEGER, sortKey, upstreamId, flags, groupId)`. `insert` = `REPLACE` only if `(data,flags,sortKey,upstreamId)` changed; `version = MAX(version)+1` on every mutation (wraps at `UShort.MAX` → `resetAllVersions`).
- `BucketsyncRepositoryImpl`: `checkForNextUpdate`/`awaitNextUpdate` (100 ms debounce on `MAX(version)`) → `BucketUpdate(toVersion, activeBuckets[≤ maxActive], bucketsToUpdate, flags)`; active buckets = `data IS NOT NULL ORDER BY sortKey ASC, id ASC LIMIT max` (max 15 legacy / 127 core); "extra" ones (become active again after being removed from the watch) are re-sent even if unchanged. `updateBucketDynamic(upstreamId)`: reuses the bucket with the same `upstreamId`; otherwise `MAX(id)+1` (min. `dynamicPool.first`); pool exhausted → recycles the "least useful" bucket of the pool (`getOldestBucketInRange`: first inactive, then without sortKey, then sortKey DESC, then id ASC).
- `BucketSyncWatchLoopImpl` (per-watch, `WatchappConnectionScope`): `sendFirstPacketAndStartLoop(helloPacketBase, watchVersion, watchBufferSize, activeBuckets, maxActive, onBucketsChanged)` → sends the welcome response (packet 1 with whatever fits), then `observeForFutureSyncs` = infinite loop: `awaitNextUpdate` → packet 2 (+ packet 3 extras, chunking in `BucketlistPackets.createBucketsyncPackets` — static header then (id,size,data) tuples until `watchBufferSize` is filled) → `onBucketsChanged` (`WatchappConnectionImpl` hooks `pushVibration` = packet 7 with priority `PRIORITY_VIBRATION = -1`).
- `WatchappConnectionImpl.onPacketReceived`: dispatch per packet id (0 welcome → respond packet 1 + start loop; 4 → `notificationDetailsPusher.pushNotificationDetails(bucketId, watchBufferSize)`; 6 → `actionHandler`/`submenuActionHandler` (voice text in key 4); 8 → `watchappOpenController.closeWatchappToTheLastApp(watch)`; 10 → update `GlobalPreferenceKeys` in the `DataStore`; 14 → `serviceController.reloadAllNotifications()`; 15 → `imageSender` with fill). On connection start: `packetQueue.runQueue()` + `sendReinitRequestAfterAWhile` (after 5 s, if no welcome arrived, send packet 12 to make the watch re-sync).
- `PacketQueue` (commons): PriorityQueue per watch; `sendPacket(dict, priority)` suspends until actual send (Deferred); retry with exponential backoff (initial 100 ms) on `FailedTimeout/WatchNotConnected/WatchNacked`; `FailedNoPermissions/Unknown/null` → `UnrecoverableWatchTransferException`; priorities: `PRIORITY_USER_INTERACTION = 2` (actions/menu/images) > `PRIORITY_WATCH_TEXT = 1` (details) > `PRIORITY_SYNC = 0` > `PRIORITY_VIBRATION = -1` (vibration, always last so the user sees everything first).
- `ImageSenderImpl`: converts icon/largeImage to Pebble PNG (`DrawableExtractor`: indexed with palette for color watches, grayscale for B&W; crop/resize to screen if `fill`), chunks of `watchBufferSize - packetOverhead`, header (notifId u8, totalSize u16, flags 0x01/0x02), max ~24000 B otherwise `error(...)`; sent with `PRIORITY_USER_INTERACTION`.
- `SubmenuControllerImpl` (per-watch): packet 9 (bucketId, menuId = `SubmenuType.ordinal + 1` → REPLY_ANSWERS = 1, SNOOZE = 2, items max 20 × 20 B + voice flag) and keeps payloads in memory (`menuItems` map) for `getPayloadForMenuItem` (used by `SubmenuActionHandlerImpl` when the watch responds: reply = `triggerReplyAction(pendingIntent, resultKey, text)` incl. voice; snooze = `snoozeNotification(key, minutes)`; Android 8+ only). `SubmenuType` enum: `REPLY_ANSWERS, SNOOZE`.

### Rules (`rules`)

- Persistence: rule list in SQLite (`dbRule`: id, name, sortOrder — the default rule is id 1, not deletable/reorderable, created on-the-fly if missing); **each rule's payload is a dedicated `DataStore<Preferences>` file** (name = rule id, `DatastoreFactory`); copying a rule = new insertion + copy of the datastore.
- UI: `RuleListScreen` (reorder drag&drop, + create empty rule that "inherits" everything), `RuleDetailsScreen` (conditions + settings) with dialogs: `AppSelectionScreen`, `ChannelSelectionScreen`, `RegexReplacementSetEditScreen`, `StringLIstEditScreen`, `NumberListEditScreen`, `VibrationPatternScreen`, `NameEntryScreen` (details in `rules/ui`).

### Other modules

- `history`: every notification (even muted/hidden) lands in the DB with its reason (`MuteReason`/`HideReason`) via `HistoryInserter`; `HistoryScreen` shows the list with reasons.
- `tools/ui`: `ToolsScreen` (toggle mute watch/phone, action order, auto-close, wrap-around, messaging-style chronological, notify-on-reconnect, backlight, large status font) + `ActionOrderListScreen`.
- `tasker`: Tasker plugin — `TaskerActionService` (receives the broadcast from Tasker to toggle watch mute), `TaskerTaskStarter` (starts a Tasker profile as a notification action, `TaskerTask` in `Action`), configuration UI (`TaskerConfigurationActivity`, `TaskerTaskSetScreen`).
- `home/ui`: `HomeScreen` (container of tabs: Rules / History / Tools) and `OnboardingScreen` (permissions: notification listener, companion device, notifications).
- `common*` / `navigation-impl`: Material3 Compose theme, `ReorderableList` (dnd), `TabListDetailScene` (list-detail pattern with tabs), navigation keys for every screen (Navigation3 + kotlinova).
- `shared-resources`: shared MDP/icons.
- `app-screenshot-tests`: Paparazzi + Showkase (script `config/generate-screenshots.sh`).

## 6. End-to-end scenarios

1. **New notification with vibration**: NLS → parse → rules → not hidden → not paused → pattern OK → `syncNotification` (bucket, version bump) → `nextVibration` set + `openWatchapp()` → PebbleKit2 opens the watch app → watch sends packet 0 → phone responds packet 1 (full sync or delta) → watch vibrates (packet 7, sent after details are in hand).
2. **User opens a notification on the watch**: watch sends packet 4 (bucket id) → phone: `markAsRead` + `NotificationDetailsPusher` (packet 5: actions ordered by `ActionOrderRepository`, 32px icon as PNG, full text up to `watchBufferSize`) → then packet 7 (vibration, lowest priority).
3. **Action**: watch → packet 6 → `ActionHandlerImpl`: Dismiss → `serviceController.cancelNotification(key)`; Native → `triggerAction(pendingIntent)` (background-start mode depending on Android); Reply → answers submenu (packet 9, voice via dictation) → packet 6 with text → `triggerReplyAction`; Snooze → interval submenu → `snoozeNotification` (Android 8+); PauseApp/Conversation → `PauseController.toggle*` → `notifyPackagePauseStatusChanged` (recompute flags of all the app's buckets and silent resync); ShowImage → `ImageSender` (packet 11 chunked); TaskerTask → fire Tasker profile; HideFromWatch → removes only on the watch side (the SBN stays on the phone).
4. **Setting changes from the watch**: packet 10 (mute watch/phone) → global `DataStore` → `WatchSyncerImpl` re-serializes bucket 1 → watch updates `preferences` via the bucket-1 callback.
5. **Close**: BACK → packet 8 → phone closes the watch app "to the last app" (or the watch closes itself if `close_via_phone == false`, packet 1 key 4). Retry 3×250 ms; auto-close from timer if `autoCloseSeconds` configured.
6. **Disconnection/reconnection**: watch: double vibration + icon; phone: if the watch app is open, sends packet 12 after 5 s → watch restarts the sync (packet 0 → 1…); `openOnReconnect` reopens the watch app if `notifyOnReconnect` and a vibration is pending.
7. **Pause**: bucket flag 0x02 (square in the dots) = app or conversation paused; new notifications from the app do not vibrate until an explicit unpause or all the app's notifications are dismissed (`PauseController.onNotificationDismissed` → auto-unpause of the package).

## 7. Build & test

### Mobile (`mobile/`)

- Gradle wrapper (distribution ALL), Java Temurin **25** in CI (AGP 9.4.0 + Kotlin 2.4.20), Android SDK: platform-tools + `platforms;android-33` and `android-34` (needed by Paparazzi), remote build cache (burrunan/gradle-cache-action).
- Canonical commands (from CI, `verifyPr.yaml`):

```bash
./gradlew compileReleaseKotlin assembleDebug
./gradlew compileDebugUnitTestSources
./gradlew lintRelease runDebugDetekt assertModuleGraph buildHealth detectTooManyFiles :bucketsync:data:verifyDebugDatabaseMigration reportMerge --continue
./gradlew :bucketsync:data:generateDebugDatabaseSchema :bucketsync:data:verifyDebugDatabaseMigration
./gradlew runDebugTests -x :app-screenshot-tests:testDebugUnitTest --continue
./gradlew --continue verifyPaparazziDebug
./gradlew aggregatedJacocoReport
```

  Plus the watch build: `pebble build` in `watch/`. Screenshot generation: `config/generate-screenshots.sh <branch>` before `verifyPaparazziDebug`.
- Build conventions (buildSrc, precompiled plugins): every module applies `android-module-commons`/`pure-kotlin-module` + feature plugins (`androidAppModule` = `app-module` id, `compose`, `navigation`, `serialization`, `showkase`, `sqldelight`, `di` = Metro plugin `dev.zacsweers.metro`); Java toolchain 21 (CI compiles with JDK runtime 25); opt-in experimental coroutines; plugin `unmock` (Android on JVM) and `test-fixtures` (fakes); `checks` = detekt + jacoco + dependency-analysis; `custom { enableEmulatorTests.set(true) }` enables instrumented tests (only in modules that have them).
- **Detekt**: config + custom rules in `mobile/detekt/`; ktlint rules via wrapper; `runDebugDetekt` per module (detektMain + detektTest). `reportMerge` = SARIF merge (with post-processing workaround in the root build.gradle.kts).
- **Module graph assertion** (jraska plugin, in `app-module`): `maxHeight = 6`; `common-navigation` may depend on no feature module; only `app` (and `logging:crashreport`) may depend on `:data` modules; only `app` may depend on `:ui` modules; `common-*` modules depend only on other `common-*`/`shared-resources`.
- `detectTooManyFiles` / `detectTooManyKotlinFiles`: watchdog against modules with too many files (especially `common-*` and `app`).
- **Database**: SQLDelight 2.3.2. `:bucketsync:data` has schema `databases/*.db` + migrations `*.sqm` (2→5→6); `:app` has `Database` (package `com.matejdro.pebblenotificationcenter`) including the sqldelight deps of `history:data` and `rules:data`. Migrations must be verified with `verifyDebugDatabaseMigration`; regenerate schemas with `generateDebugDatabaseSchema`.
- **Tests**: JUnit 5 + Kotest matchers + turbine + `kotlinx-coroutines-test`; fakes in `api` modules (testFixtures: `FakeWatchSyncer`, `FakeWatchappOpenController`, `FakeRulesRepository`, `FakeNotificationRepository`, `FakeActionHandler`, `FakeActionOrderRepository`, …) and PebbleCommons `test` modules (`FakeBucketSyncRepository`, `FakePebbleSender`, `InMemoryDataStore`); `FakeActivity` (kotlinova core-test) + `unmock-android` for Android APIs on JVM. Instrumented tests: `NotificationParserTest` (notification/data androidTest), `./gradlew :app:connectedAndroidTest -PtestAppWithProguard` for integration (CONTRIBUTING).
- **Manual run**: `./gradlew :app:assembleDebug` and install on a device; or run the `app` module from Android Studio (debug keys in `mobile/keys/`).

### Watch (`watch/`)

`pebble sdk install latest`, then `pebble build` (waf/wscript). The PBW lands in `watch/build/watch.pbw`. For compilation only (no bundle) waf runs for every target in `targetPlatforms` (basalt, diorite, emery).

### CI (GitHub)

- `verifyPr.yaml` (on pull_request): checkout with LFS + submodules, install pebble SDK, Java 25, Android platforms 33/34, remote cache read-only, then all tasks above, upload artifacts (XML tests, merged SARIF, jacoco report, failing screenshots) + automated comment on the PR (workflow `pull-request-post.yaml`).
- `develop.yaml` (daily cron): automatic version bump (convergent commit analysis), builds only the changed parts, renames APK, changelog, commits `version.txt`/`mobile/version.txt`/`watch/version.txt`/`watch/package.json`, tag, GitHub Release with APK + PBW.

## 8. Critical invariants & gotchas

1. **`PROTOCOL_VERSION` must be equal** in `watch/src/main.c` (=9) and `mobile/bluetooth/data/.../bluetooth/Constants.kt` (=9). `BUCKET_DATA_VERSION` (=4, phone side only) must be incremented whenever the phone-side bucket data format changes (DB wipe). The byte-exact contract is in `protocol.md`: **any format change requires updating protocol.md AND BOTH implementations** (C in `packets.c`/`bucket_sync.c` + Kotlin in `WatchappConnectionImpl`/`BucketlistPackets`/`WatchSyncerImpl`/`NotificationDetailsPusherImpl`/`ImageSenderImpl`/`SubmenuControllerImpl`).
2. **Font ordinals**: `PebbleFont` (Kotlin, 18 entries) and the C `fonts[]` array (18 entries) in `window_notification.c` must have the same ordering: the bucket stores the ordinal. Never reorder or remove an entry on one side without the other (appending at the end simultaneously is the only safe operation).
3. **Protocol numeric limits**: bucket ≤ 255 B; title/subtitle cstr ≤ 20 B (+1 null); action text ≤ 20 B; max 20 actions; notification icon 32×32; image max ~24000 B; watch AppMessage inbox limited to 4096 B; `watchBufferSize` comes from packet 0 and drives chunking of packets 5/11 — never assume a fixed size.
4. **Bucket 1 is "special"**: it is the only "static" bucket (settings), not a notification (`is_any_notification_bucket_active` excludes id 1 in C). On the phone side `init` uses `dynamicPool = 2..255` (bucket 1 is never dynamic).
5. **Watch persist keys**: `2001+` for buckets (id+2000), `3000+id` (e.g. 3002) for "seen", `1000/1001/1002` for metadata. "Seen" is local to the watch: reset only on the protocol-change wipe. A notification is UNREAD only if (flag 0x01) AND (3000+id != 1): `is_notification_unread` in C.
6. **PebbleCommons is a submodule**: changes there go to the `matejdro/PebbleCommons` repo (separate branch/PR) and then the pin is updated; CI does `checkout --recurse-submodules` + `git lfs fetch`. Do not put random local changes in the submodule expecting them to survive.
7. **Packet priority order** (phone→watch): user interaction (2) > text (1) > sync (0) > vibration (−1). A new vibration must never overtake text: `PRIORITY_VIBRATION = -1`.
8. **`updateBucketFlagsSilently` does not bump the version**: used for "mark read" without disturbing the watch; the updated flags travel only when the bucket is re-transmitted.
9. **No `subprojects`/`allprojects` blocks** in the root `build.gradle.kts` (project isolation for configuration cache).
10. **Conventional commits** (scope = module) + automated releases: **never manually commit a version bump**; `version.txt`/`package.json` are managed by automation.
19. **Never commit on your own initiative**: agents must never run `git commit` (or any other git mutation such as `add`, `push`, `tag`) unless the user explicitly asks for it in that session; all commits must be user-driven. When committing at the user's request, the message must follow [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/) with scope = name of the module being updated, exactly as specified in `CONTRIBUTING.MD`.
20. **Git submodules are never modified**: `PebbleCommons/` is a submodule and its contents must never be edited locally. All changes to PebbleCommons go through its own repository (`matejdro/PebbleCommons`, separate branch/PR); this repo only updates the submodule pin.
21. **Sole upstream: `ORIGIN:main`** — the `origin` remote points at the upstream project (`https://github.com/matejdro/PebbleNotificationCenter2`). This remote must never be touched: no pushes, no force operations, nothing.
22. **Upstream pulls go only into `MAIN:main`**: when pulling from `ORIGIN:main`, always update the local `main` branch. The `main` branch must never receive direct modifications of any kind (no direct commits, no merges other than the upstream pull itself).
23. **`MAIN:vibecoding` is the only branch where changes are allowed**: all development work happens on `vibecoding`; no other branch may ever be modified.
11. **PebbleKit2 requires Companion Device Manager** for notification channels: the code handles absence/inactive CBM gracefully (`SecurityException` catch). The app **must** have the Notification Listener permission (onboarding).
12. **The watch has no active reconnect callback**: re-synchronization after a drop is driven ONLY by the phone (packet 12 after 5 s, then a fresh welcome). Do not rely on `bluetooth_register_reconnect_callback` in this app's code.
13. **`fixPebbleIndentation()`**: texts sent to the watch have indentation reworked (Pebble handles multiple indents poorly); applied both in the bucket (`WatchSyncerImpl`) and in details (`NotificationDetailsPusher`).
14. **Colors**: accent color starts as an Android ARGB int and reaches the watch as GColor8 (1 byte, 2 bits/channel + alpha 0xC0): `Int.toPebbleColor()`; 0 = no accent. Icons: PNG indexed (2 colors) on color watches, grayscale on B&W (`DrawableExtractor`); the icon palette in the UI is recomputed in `scroll_content_paint` (on_banner, banner).
15. **Quiet time**: the watch skips vibrations (regular and periodic) if `quiet_time_is_active()`.
16. **`enableMultiJS: true`** in `package.json` is a leftover (there is no JS in the project); do not add JS without a real need.
17. **Waf/CMake**: `watch/CMakeLists.txt` has hardcoded SDK paths (`~/.pebble-sdk`): verify them before use; the canonical build is `pebble build` (wscript).
18. **Strings**: shared UI strings live in `shared-resources` (do not duplicate resources across modules); watch-side strings are inline in C.

## 9. Where to make changes

| Task | Main files/modules |
|---|---|
| Add/modify a rule setting | `rules/api/RuleOption.kt` (+ key type in `rules/api/keys/`) → `RuleResolver`/`NotificationProcessor` if used in the pipeline → `WatchSyncerImpl` if synced into a bucket → UI dialog in `rules/ui/details/` (`Settings.kt`, `Dialogs.kt`, `Conditions.kt`) and screens in the dialogs |
| Add a global setting | `GlobalPreferenceKeys.kt` + `ToolsScreen.kt` (+ possibly new dialogs) + if it goes to the watch: bucket 1 flag in `WatchSyncerImpl.syncPreferences` AND parsing in `watch/src/data/preferences.c` (and use in `packets.c`/UI) + `protocol.md` |
| Modify the AppMessage protocol | `protocol.md` + `watch/src/connection/packets.c` (C) + `mobile/bluetooth/data/WatchappConnectionImpl.kt` (+ `NotificationDetailsPusher.kt`, `ImageSenderImpl.kt`, `SubmenuControllerImpl.kt`, `BucketlistPackets.kt` if sync is touched) + bump `PROTOCOL_VERSION` in BOTH |
| Change the notification bucket layout | `WatchSyncerImpl.kt` (serialize) + `watch/src/ui/window_notification/data_loading.c` (deserialize) + `protocol.md` + bump `BUCKET_DATA_VERSION` and `PROTOCOL_VERSION` |
| Change watch UI | `watch/src/ui/...` (+ resources in `watch/resources/` with `~bw`/`~color` variants registered in `package.json`) |
| Change phone UI | `:ui` modules (+ `common-compose` for components), navigation keys in `common-navigation`, scenes in `navigation-impl` |
| Add a notification action | `Action.kt` (sealed, in `notification/api`) + `processActions` in `NotificationProcessor.kt` + dispatch in `ActionHandlerImpl.kt` (+ `SubmenuActionHandlerImpl.kt` if submenu) + nothing else needed on the watch side (actions travel via packet 5) |
| Tests | `src/test/kotlin` (JVM, fakes from testFixtures), `src/androidTest/kotlin` (only `notification/data` for now), `:app-screenshot-tests` for screenshots (Paparazzi: `recordPaparazziDebug` to update) |
| New modules | follow CONTRIBUTING (template, `settings.gradle.kts`, dependency in `app/build.gradle.kts`, removal of leading spaces from generated `.gitignore`) |

## 10. Glossary

- **Bucket**: unit of storage on the watch (≤ 255 B, persisted on flash). In this app: #1 = settings, #2..N = active notifications.
- **Bucket sync ("bucketsync")**: incremental phone→watch protocol carrying (a) the list of active buckets with their flags, (b) the data of changed buckets, in one or more packets.
- **Watchapp**: the app on the watch. **Companion app**: the Android app.
- **UpstreamId**: phone-side identifier of a dynamic bucket (in NC: the `Sbn.key` of the notification).
- **Dynamic pool**: range of bucket ids (2..255) from which the phone may allocate dynamic buckets.
- **AppMessage**: the message format (dictionary of tuples) of the Pebble transport (PebbleKit2 on the phone side, `app_message_*` on the watch side).
- **PebbleKit2**: rebble's successor library of the Pebble SDK for companion apps (`io.rebble.pebblekit2`).
- **microPebble / Pebble Core**: the modern runtimes replacing the old Pebble app; the only ones supported.
- **Dots**: indicators at the top-left of the watch window, one per notification (unread = diamond, paused = square, read = circle).
- **Pause (app/conversation)**: anti-spam mechanism: no vibrations for new notifications from the package/conversation until an "unpause" or all the app's notifications are dismissed.
- **Rebble**: the community/foundation maintaining the SDKs after Pebble's shutdown.

## 11. Further reading

Two workspace-level `AGENTS.md` files exist outside this repository and define environment conventions. Read them for anything beyond this repo:

- **`/home/leo/PEBBLE/AGENTS.md`** (everything Pebble-related): `/home/leo/PEBBLE` contains all Pebble smartwatch projects; each subfolder is a dedicated compilable app — search inside the correct subfolder when an app is mentioned. "Companion app" means the Android application required for the Pebble app to work properly. Relevant device codenames: `basalt` (Pebble Time Steel) and `emery` (Pebble Time 2). Pebble SDK include directories:
  - basalt: `${HOME}/.local/share/pebble-sdk/SDKs/current/sdk-core/pebble/basalt/include`
  - emery: `${HOME}/.local/share/pebble-sdk/SDKs/current/sdk-core/pebble/emery/include`
  - NOTE: `watch/CMakeLists.txt` in this repo instead hardcodes the `~/.pebble-sdk/...` prefix; whoever uses CMake directly must verify which prefix actually exists on their system.
- **`/home/leo/ANDROID/AGENTS.md`** (everything Android-related): `/home/leo/ANDROID` contains all Android smartphone projects (except the `sdk` directory); each subfolder is a compilable app. Android SDK root: `${HOME}/ANDROID/sdk`. Keystore to sign **all** release builds: `${HOME}/ANDROID/release.keystore`. Reusable/adaptable `local.properties` example: `${HOME}/ANDROID/local.properties`.

Keystore divergence note: this repository actually signs release builds with `mobile/keys/release.jks` using a password taken from CI secrets (`mobile/app/build.gradle.kts`), while the workspace convention prescribes `${HOME}/ANDROID/release.keystore`. Both facts are reported deliberately: for this project's CI the repository configuration is authoritative, while the workspace convention is what to use in new local environments.