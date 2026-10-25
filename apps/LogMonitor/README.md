<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Log Monitor

> [!WARNING]
> Log Monitor handles every line each connected hub logs, so it adds load to the hub it runs on, and a hub that logs a lot can slow down noticeably. Install it at your own risk, and remove it if the hub becomes sluggish. It is not a Hubitat product: report problems on [GitHub](https://github.com/iamtrep/hubitat/issues), not to Hubitat support.

A Hubitat Elevation app that reads the live log stream (`/logsocket`) of one or more hubs and sends the lines that match its filters to notification devices, a File Manager file or a webhook. Passwords, tokens and keys in those lines are masked before they leave the app; device, app and hub names can be replaced with aliases too.

The parent app (`Log Monitor`) does the filtering, deduplication, rate limiting, masking and output. Each connection to a hub is a child `Log Monitor Bridge` device, which only receives log lines and passes them on.

## Features

### Bridges (log sources)
- Up to **5 bridges**, each connected to one hub's `/logsocket`
- The local hub is reached on `127.0.0.1:8080`; a remote hub on `ws://{ip}/logsocket`, port 80
- Reconnects after a drop, waiting 5, 10, 20, then 40 s between attempts; configurable ping interval; a manual disconnect stays disconnected
- Waits 60 s before connecting when the hub has been up for less than 60 s, so it doesn't connect before the hub's websocket service is ready
- Decodes HTML entities in log text and names, including the double encoding remote hubs often send
- The main page shows each bridge's connection status and how many log lines it has received
- Loop guards: a bridge ignores its own device's logs, the app ignores its own logs, and a log line from a notification device never triggers a notification

### Filters
- Up to **10 filters**, each configured separately
- Source bridge: one bridge, or all of them
- Log types: any combination of device (`dev`), app (`app`) and system (`sys`) logs
- Levels: any of `trace`, `debug`, `info`, `warn`, `error` (default `error`)
- Device IDs and app IDs: comma-separated lists. When either list is set, a line matches only if it comes from a listed device or a listed app; lines of other types, system logs included, are dropped
- Include and exclude patterns: regular expressions searched for anywhere in the log message
- Dedupe window (0–300 s): drops a line identical to one already sent (same type, level, name and message) within the window
- Rate limit (1–60 per minute): caps how many lines a filter sends each minute
- Each filter counts the lines it sent (shown on its edit page)

### Outputs
Each filter can send to any combination of:
- **Notification devices**: any `capability.notification` device, such as Pushover or the Hubitat mobile app
- **File**: appended to a File Manager file as CSV
- **HTTP POST**: a JSON body sent to a URL, 5-second timeout. After a failure the app pauses POSTs to that URL, doubling the pause up to 60 minutes, or longer if the server sends `Retry-After`. A 401, 403, 404 or 410 stops POSTs to the URL until a filter that uses it is saved again

The app also logs each line it sends, at info level.

### Privacy

**Secrets are always masked**, in every output, in the app's own log lines and in its error messages. There is no setting to turn this off. A masked token or key keeps its last 4 characters, so it can still be matched against the issuing side (Maker API's token, an app's endpoint URL); a password, PIN or Basic credential is replaced entirely.

| Found in a log line | Shown as |
|---|---|
| A URL's query or fragment parameter named like a credential (`access_token`, `apiKey`, `X-Amz-Signature`, ...) | `access_token=••••••••a7b8` |
| The password in `user:password@` | `admin:••••••••@host` |
| A URL path segment that looks generated (16+ letters and digits), where Slack, IFTTT and Home Assistant webhooks keep their key | `/services/T0001/B0002/••••••••Z3nB` |
| `Authorization: Bearer …`, `Bearer …` | `Bearer ••••••••Z3nB` |
| `Authorization: Basic …`, `Digest …`, `Negotiate …` | `Basic ••••••••` |
| A value next to a secret-looking key, in JSON, Groovy maps or `key=value` text (`"apiKey": "…"`, `password:…`, `refresh_token=…`) | `"apiKey": "••••••••Z3nB"`, `password:••••••••` |
| A JSON Web Token | `••••••••NDU2` |
| A bare UUID (Hubitat's access tokens are UUIDs) | `••••••••a7b8` |
| The body of a PEM private key | `-----BEGIN PRIVATE KEY----- •••••••• -----END PRIVATE KEY-----` |

A key that only describes a secret (`tokenExpiry`, `authUrl`, `passwordSet: true`) is left as is.

**Private details are hidden per filter**, with *Hide private details* (off by default):

| Found in a log line | Shown as |
|---|---|
| Device, app, room and hub names, and bridge names | a stable alias such as `amber-otter` |
| Private IPv4 address | first and last octet: `192.•.•.40` |
| Public IPv4 address | first octet: `161.•.•.•` |
| IPv6 address | first group (unique local: first and last) |
| MAC address, Zigbee EUI-64 | vendor prefix and last byte: `34:E1:D1:••:••:18` |
| Email address | `a•••@example.com` |
| Phone number | last 2 digits |
| Coordinates | rounded to 1 decimal (about 10 km) |
| Serial number | last 4 characters (2 when shorter than 12) |
| Wi-Fi SSID, Z-Wave home ID | `••••••••` |
| `.local` host name | `••••.local` |

An alias stays the same for a given name on a given install, and it is mixed with a random value created on install, so a guessed name can't be checked against it. Names come from the log lines themselves and, for the local hub, from its device and app lists, read each time the app is saved with **Done** and daily at about 4 AM. A name is replaced only where it stands as a whole word, longest name first. Names shorter than 3 characters, mode names, app labels left at their app's name, device names left at their driver's name, and generic names (`Home`, `Hubitat`, `Hub`, `Local Hub`) are not replaced. Loopback, link-local and documentation addresses and firmware versions such as `2.5.2.129` are left as they are. Device and app IDs and log timestamps are kept.

Filters match on the original line: include and exclude patterns, ID lists and the dedupe window see the line before masking.

## Requirements

- **Hubitat platform 2.5.0 or newer**: the app uses the `menu: "Apps"` definition field added in 2.5.0
- No devices are needed: the install creates one bridge to the local hub

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

**Apps** → **Add User App** → **Log Monitor**. The first install creates a child device called **Local Hub**, which connects to `127.0.0.1:8080/logsocket`.

### 4. (Optional) Add bridges to other hubs

On the main page, **Add Bridge** asks for a name and an IP address. Each hub gets its own child device and connection status.

### 5. Add filters

**Add New Filter** on the main page. **Next** at the bottom of the filter page saves the filter and returns to the main page. Each filter has its own **Delete** link on the main page.

## Configuration

### Bridge

Set on the app's bridge page:

| Setting | Default | Description |
|---|---|---|
| Bridge name | _(required)_ | Shown in output lines |
| Hub IP address | _(blank)_ | Blank for the local hub. For another hub, its IP address, without `http://` or a port; the bridge connects on port 80 |

Set on the bridge device's page:

| Setting | Default | Description |
|---|---|---|
| Auto-reconnect on disconnect | on | Reconnect after a drop, with the waits above |
| WebSocket ping interval | 30 s | Keepalive ping, 10–300 s |
| Enable info logging | on | Connect and disconnect messages |
| Enable debug logging | off | Connection details; turns itself off after 30 minutes |
| Enable trace logging | off | One line per log line received (very verbose); turns itself off after 30 minutes |

The bridge device has **Connect**, **Disconnect** and **Reconnect** commands. Its `connectionStatus` attribute is `initializing`, `connecting`, `connected`, `reconnecting`, `disconnected` or `error`.

### Filter

| Setting | Default | Description |
|---|---|---|
| Filter name | _(required)_ | Shown in notifications, log lines and the POST body |
| Source bridge | All bridges | Shown when there is more than one bridge |
| Device / App / System logs | all on | Which log types to watch |
| Levels to monitor | `error` | Any of `trace`, `debug`, `info`, `warn`, `error` |
| Device IDs | _(blank)_ | Comma-separated device IDs |
| App IDs | _(blank)_ | Comma-separated app IDs |
| Include pattern | _(blank)_ | Regular expression; only matching lines are sent |
| Exclude pattern | _(blank)_ | Regular expression; matching lines are dropped |
| Dedupe window | 5 s | 0 turns deduplication off |
| Max events per minute | 30 | Per filter |
| Send to notification device(s) | off | Pick one or more notification devices |
| Append to hub log file | off | A File Manager file name, such as `logmonitor.csv` |
| POST to URL | off | The webhook URL. It is stored encrypted and shown masked; leave the field empty to keep the stored URL |
| Hide private details | off | Aliases and masks as described under [Privacy](#privacy) |

### App logging

| Setting | Default | Description |
|---|---|---|
| Enable info logging | on | One line per line sent |
| Enable debug logging | off | Rate-limit and output errors; turns itself off after 30 minutes |
| Enable trace logging | off | Shown when debug logging is on; turns itself off after 30 minutes |

## Output formats

### Notifications and the app's own log lines

```
<filter>: [<bridge>/<type>/<level>] <name>: <msg>
```

### File

One CSV line per line sent:

```
timestamp,bridge,type,level,"name","msg"
```

`timestamp` is `yyyy-MM-dd HH:mm:ss` in the hub's time zone. The app reads and rewrites the whole file on each line it adds, and never trims it, so a busy filter writing to a large file costs more over time. Delete or rename the file in File Manager now and then.

### HTTP POST body

```json
{
  "filter":  "<filter name>",
  "bridge":  "<bridge name>",
  "type":    "dev | app | sys",
  "level":   "trace | debug | info | warn | error",
  "name":    "<device or app name>",
  "msg":     "<log message>",
  "id":      <device or app id, or null>,
  "time":    "<timestamp from the hub>"
}
```

`name`, `msg` and `bridge` are masked as described under [Privacy](#privacy).

## Performance

Each bridge passes every log line of its hub to the app, which checks it against each filter, one line at a time. Watching `debug` or `trace` levels, or a remote hub that logs heavily, adds the most load. Masking runs only on lines a filter sends, after the rate limit. With *Hide private details*, the app keeps the names it has seen (up to 2000) in its state.

## File Structure

```
LogMonitor/
  LogMonitor.groovy         # Parent app
  LogMonitorBridge.groovy   # Child bridge driver (websocket connection)
  tests/test-masking.groovy # Offline tests for the masking
```

## License

MIT License. See source file headers for the full text.
