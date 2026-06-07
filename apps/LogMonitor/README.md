<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Log Monitor

A Hubitat Elevation app that taps the hub's live `/logsocket` WebSocket stream and routes matching log entries to one or more output actions. Supports connections to multiple hubs simultaneously through child bridge devices, and an arbitrary set of independently-configured filters layered over those bridges.

The parent app (`Log Monitor`) orchestrates everything. Each WebSocket connection is owned by a child `Log Monitor Bridge` device — purely a transport, no filtering. Filtering, deduplication, rate-limiting, and output dispatch all happen in the parent.

## Features

### Bridges (WebSocket sources)
- Up to **5 bridges**, each connecting to one hub's `/logsocket`
- Local hub uses `127.0.0.1:8080`, remote hubs connect to `ws://{ip}/logsocket` on port 80
- Auto-reconnect with exponential backoff (5 → 60s cap), configurable ping interval, intentional-disconnect handling
- 60-second cold-start delay when hub uptime is below 60s, so the connection isn't initiated before the platform's WebSocket stack is ready
- HTML entities arriving from remote hubs (often double-encoded over port 80) are unescaped at the bridge
- Self-monitoring guard: the bridge skips its own device logs, the parent skips its own app logs — no feedback loops

### Filters (matching + output)
- Up to **10 filters**, each independently configured
- Source bridge selector — pin a filter to one bridge or apply it to all bridges
- Log types — any combination of `dev`, `app`, `sys`
- Log levels — any subset of `trace`, `debug`, `info`, `warn`, `error` (defaults to `error`)
- ID lists — comma-separated device IDs (applied to `dev` logs) and app IDs (applied to `app` logs)
- Regex include and exclude patterns matched against the log message
- Deduplication window (0–300s) — drops repeats of the same `type:level:name:msg` signature within the window
- Per-minute rate limit (1–60) — caps output volume during log storms

### Outputs
Each filter can fan out to any combination of:
- **Notification devices** — `capability.notification` devices receive a formatted string
- **Hub log file** — appended to a file in File Manager as CSV (`timestamp,bridge,type,level,"name","msg"`)
- **HTTP POST** — JSON body `{filter, bridge, type, level, name, msg, id, time}` posted to a configurable URL with a 5-second timeout

The matched-line format sent to notifications and the hub log is:

```
[<bridge>/<type>/<level>] <name>: <msg>
```

### Statistics
Each filter tracks an `eventsMatched` running counter (visible on the filter edit page) and a per-minute window that resets via a `runEvery1Minute` schedule.

## Requirements

- **Hubitat platform 2.5.0 or newer** — the app uses the `menu: "Apps"` definition field added in 2.5.0
- **Per-bridge IP and port** — local bridge needs no preference (loopback); remote bridges connect over port 80

No external devices are required to run the parent app — the default install creates one local bridge automatically.

## Installation

### 1. Install the bridge driver

In **Drivers Code** → **New Driver** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LogMonitor/LogMonitorBridge.groovy
```

Click **Save**.

### 2. Install the app

In **Apps Code** → **New App** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LogMonitor/LogMonitor.groovy
```

Click **Save**.

### 3. Create the app instance

**Apps** → **Add User App** → **Log Monitor**. A child device called **Local Hub** is created automatically on first install and connects to `127.0.0.1:8080/logsocket`.

### 4. (Optional) Add remote-hub bridges

On the main page, **Add Bridge** prompts for a label and an IP address. Each remote hub gets its own child device and its own connection state.

### 5. Add filters

**Add New Filter** on the main page. Each filter is saved on the **Next** button at the bottom of the filter page (it returns to the main page).

## Configuration

### Bridge

| Setting | Default | Description |
|---|---|---|
| Bridge name | _(required)_ | Display label; used in formatted output lines |
| Hub IP address | _(blank)_ | Leave blank for the local hub. For a remote hub, plain IP (no scheme); the bridge always connects on port 80 |
| Auto-reconnect | true | Schedule reconnect on disconnect with backoff |
| Ping interval | 30s | WebSocket keepalive ping (range 10..300) |
| Enable debug logging | false | Per-bridge debug log lines |
| Enable trace logging | false | Per-received-frame trace (very chatty) |

### Filter

| Setting | Default | Description |
|---|---|---|
| Filter name | _(required)_ | Display label; included in notifications and log lines |
| Source bridge | All bridges | Restrict to one bridge or apply to every connected bridge |
| Device / App / System logs | all on | Type-of-source toggles |
| Levels | `error` | Multi-select: `trace`, `debug`, `info`, `warn`, `error` |
| Device IDs | _(blank)_ | Comma-separated; restricts the `dev` log type to these IDs |
| App IDs | _(blank)_ | Comma-separated; restricts the `app` log type to these IDs |
| Include pattern | _(blank)_ | Groovy regex matched against the log message |
| Exclude pattern | _(blank)_ | Groovy regex matched against the log message; matches are dropped |
| Dedupe window | 5s | 0 disables; otherwise drops same-signature events within the window |
| Max events per minute | 30 | Output cap per filter, reset on a one-minute schedule |
| Send to notification device(s) | false | Pick one or more `capability.notification` devices |
| Append to hub log file | false | Provide a File Manager filename (e.g., `logmonitor.csv`) |
| POST to URL | false | Provide a webhook URL; JSON body, 5s timeout |

### Logging (parent app)

| Setting | Default | Description |
|---|---|---|
| Enable debug logging | false | Verbose internal trace for the parent app |

## Output formats

### Notifications and hub log file

```
[<bridge>/<type>/<level>] <name>: <msg>
```

CSV (one line per matched event, appended to the chosen File Manager file):

```
timestamp,bridge,type,level,"name","msg"
```

`timestamp` is `yyyy-MM-dd HH:mm:ss` in the hub's local time zone.

### HTTP POST body

```json
{
  "filter":  "<filter label>",
  "bridge":  "<bridge label>",
  "type":    "dev | app | sys",
  "level":   "trace | debug | info | warn | error",
  "name":    "<source device or app name>",
  "msg":     "<log message>",
  "id":      <numeric id, or null>,
  "time":    "<hub-emitted timestamp string>"
}
```

## File Structure

```
apps/LogMonitor/
  LogMonitor.groovy         # Parent app
  LogMonitorBridge.groovy   # Child bridge driver (WebSocket connection)
```

## License

MIT License. See source file headers for the full text.
