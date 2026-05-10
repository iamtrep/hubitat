# Architecture Guide — Candidate Topics

Lower-priority observations from the codebase review. Kept here for later reconsideration; promote to [`ARCHITECTURE.md`](ARCHITECTURE.md) if a topic reaches the bar of *"skipping it causes a real bug or substantial confusion."*

Items that are pure platform mechanics — capability/attribute/command syntax, `metadata` block layout, `dynamicPage` / `href` / `paragraph` / `input` tutorial material — are intentionally left out. The official Hubitat developer docs at <https://docs2.hubitat.com/en/developer> are the source of truth for those.

## Common

- **`importUrl` convention.** Every app/driver `definition()` block points at the canonical raw GitHub URL so the hub UI can install/update from URL. Project-wide convention.
- **License headers.** Two patterns coexist: MIT (newer apps) and Apache-2.0 (drivers, often inherited from upstream). Consider whether to standardize.
- **Median / rolling-window state idiom.** DevicePing (`RESPONSE_HISTORY_SIZE = 21`), AwairElement (`MAX_PM25_READINGS = 5`), HumidityFanController (sensor median) all converge: list in `state`, drop oldest when over cap, compute median/avg, save back. Worth canonicalizing if a fourth instance appears.
- **Firmware-conditional behavior.** DevicePing checks `location.hub.firmwareVersionString` before picking between `NetworkUtils.ping` signatures; IKEA-Blinds gates capabilities by the `softwareBuild` data value. A short pattern for "behave differently on older firmware without crashing" might be worth documenting.

## Apps

- **Multi-page preferences with editing context.** SwitchMonitor / LogMonitor / HubDiagnostics / WellPumpMonitor share an idiom: pages return `Map`, navigate via `href` + `params`, share editing context via `state.editing*Index`, and defer destructive ops via pending-action state flags (e.g. `state.removeSettingsForGroupNumber`). The pending-flag pattern is worth documenting if it stays load-bearing.
- **`appButtonHandler` for in-page actions.** Button-name prefix matching with parameterized names (`btnConfirmDelete_${id}`, `btnFix_${deviceId}_${state}`) is a recurring project shape on top of platform-standard button inputs.
- **`singleInstance` vs `singleThreaded` decision rule.** `singleInstance: true` for parents/integration roots (only one allowed per hub). `singleThreaded: true` for serialized mutable state — file IO (AttributeLoggerChild), scan orchestration (rlm), pinging (DevicePing). Both are platform-standard; the *when-to-use* rule is the project bit.
- **Status text rendered as HTML.** Apps with rich state build `StringBuilder` HTML inside `paragraph` calls in preference pages — gives a status panel without a separate UI artifact. Style is consistent (red for problems, green for OK, tables with `border-collapse`).

## Drivers

- **WebSocket driver pattern.** Two implementations (LogMonitorBridge, LogEventMonitor) share: `interfaces.webSocket.connect(uri, pingInterval:N)`, `webSocketStatus(msg)` for state strings, `parse(msg)` for frames, `atomicState.intentionalDisconnect` to suppress reconnects on intentional close, exponential backoff capped at 60s, `location.hub.uptime`-based startup delay. Worth a short subsection if a third implementation appears.
- **Bidirectional constants Map.** `@Field static final Map x = ["A":0, 0:"A", ...]` — same map used for encode and decode. Sinope drivers use 5+ of these. Idiomatic; could be canonicalized.
- **Specs companion files.** `Sinope_switch_specs.groovy`, `Stelpro_orleans_specs.groovy` — comment-only Groovy files documenting full Zigbee node descriptors and cluster/attribute tables. Reference docs that ride along with drivers. Project convention worth a one-line mention.
