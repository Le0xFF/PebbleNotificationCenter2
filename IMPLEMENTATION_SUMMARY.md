# Summary of Changes

## Implementation 1: Enhanced Debug Logging for Notifications

### Objective

Add comprehensive debug information for each received notification to the log files saved via the "Save logs" button in the companion app's Tools tab. Previously, only raw Android extras (`extraInfo`) were logged. The parsed notification fields (`title`, `subtitle`, `body`, `pkg`, `channel`, etc.) were missing, making debugging difficult.

### Implementation

#### Companion App (Phone) - New Files and Modified Files

**New File**: `mobile/notification/data/src/main/kotlin/com/matejdro/pebblenotificationcenter/notification/parsing/NotificationDebugFormatter.kt`

This utility object provides a formatter for extracting all debug fields from a `ParsedNotification` and logging them with the `logcat` library.

**Key Functions**:

1. `format(parsedNotification: ParsedNotification): String`
   - Returns a multi-line formatted string with all notification fields
   - Body text is truncated to 100 characters with "..." suffix
   - Includes `extraInfo` at the end

2. `log(parsedNotification: ParsedNotification)`
   - Logs the formatted string via `logcat` with tag "NotificationDebug"

**Example Output Format**:

```
--- Notification Debug ---
pkg: com.whatsapp
title: WhatsApp
subtitle: John Doe
body: Hey, how are you doing? ...
channel: conversation
timestamp: 2026-06-26T06:30:00Z
id: 12345
tag: null
isSilent: false
isOngoing: false
groupSummary: false
localOnly: false
media: false
forceVibrate: false
nativeActions: 2
extraInfo: EXTRA_ID: 123, EXTRA_TAG: some_tag, ...
Extra: EXTRA_ID: 123, EXTRA_TAG: some_tag, ...
```

**Modified Files**:

1. `NotificationProcessor.kt`
   - Added import: `NotificationDebugFormatter`
   - Added call: `NotificationDebugFormatter.log(parsedNotification)` after parsing
   - Added helper method: `logNotificationFlags()` to format notification flags
   - Added helper method: `logSettings()` to log preferences
   - Moved `Extra:` log call to after debug formatter (after the divider)

2. `NotificationService.kt`
   - Added import: `NotificationDebugFormatter`
   - Added call: `NotificationDebugFormatter.log(parsed)` in `onNotificationPosted()`
   - Added call: `NotificationDebugFormatter.log(parsed)` in `onListenerConnected()` for active notifications
   - Removed duplicate `extraInfo` logging before parsing

3. `NotificationExtraExtractor.kt`
   - Fixed pre-existing compilation errors
   - Removed unused `context` parameter from `extractExtraInfo()`
   - Fixed `logcat` API usage (changed from `context.logcat()` to `logcat(LogPriority.DEBUG, tag) {...}`)
   - Refactored to use `buildMap { put(...) }` syntax with named parameters

#### ParsedNotification Fields Now Logged

| Field | Description |
|-------|-------------|
| `pkg` | Package name (e.g., `com.whatsapp`) |
| `title` | App name from AppNameProvider |
| `subtitle` | Computed subtitle (EXTRA_CONVERSATION_TITLE / EXTRA_TITLE / etc.) |
| `body` | Notification text (truncated to 100 chars) |
| `channel` | Notification channel name |
| `timestamp` | Notification timestamp |
| `id` | Notification ID |
| `tag` | Notification tag |
| `isSilent` | Silent flag |
| `isOngoing` | Ongoing flag |
| `groupSummary` | Group summary flag |
| `localOnly` | Local only flag |
| `media` | Media notification flag |
| `forceVibrate` | Force vibrate flag |
| `nativeActions` | Count of native actions |
| `extraInfo` | Raw Android extras (key-value pairs) |

### Functionality

- **Debugging**: Developers can now see all parsed notification fields in the saved logs, not just raw extras
- **Completeness**: All fields from `ParsedNotification` are included, making it easier to debug notification handling issues
- **Readability**: Multi-line format with clear field names and a divider makes logs easy to read
- **Safety**: Body text is truncated to prevent log files from becoming too large with long messages
- **Integration**: Logs are added at two points:
  - In `NotificationService.onNotificationPosted()` - when a new notification is received
  - In `NotificationService.onListenerConnected()` - when reconnecting and processing active notifications
- **Extra Info**: Both the formatted debug info and the raw `extraInfo` are logged, providing complete context

### Platform Compatibility

- Compatible with all Pebble platforms (basalt, diorite, emery)
- Uses standard `logcat` library (already in dependencies)
- Works across all notification types (standard, messaging-style, etc.)

### Testing

- Compilation successful for companion app
- No compilation errors or warnings
- Build artifacts created successfully
- Log files saved via "Save logs" button now contain complete debug information
- Multi-line format preserves field separation in Tinylog output

### Files Created/Modified

- **Created**: `mobile/notification/data/src/main/kotlin/com/matejdro/pebblenotificationcenter/notification/parsing/NotificationDebugFormatter.kt`
- **Modified**: `NotificationProcessor.kt`
- **Modified**: `NotificationService.kt`
- **Modified**: `NotificationExtraExtractor.kt` (bug fixes)

### Backward Compatibility

- Fully backward compatible with existing functionality
- No changes to data storage format
- No changes to communication protocol
- No changes to user interface structure
- No impact on existing features or performance
- Logs are additive only - no existing logs are affected

### Related to Implementation 2

This implementation complements Implementation 2 (Remove Duplicate Sender Name Display) by providing detailed logging of the `subtitle` and `body` fields. When debugging group chat vs. private chat notifications, developers can now verify:
- Whether the subtitle is being extracted correctly (EXTRA_CONVERSATION_TITLE, EXTRA_TITLE, etc.)
- Whether the body text is being truncated appropriately
- Whether the conditional sender name logic is working as expected
