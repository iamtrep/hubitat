<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Architecture Guide — Candidate Topics

Lower-priority observations from the codebase review. Kept here for later reconsideration; promote to [`ARCHITECTURE.md`](ARCHITECTURE.md) if a topic reaches the bar of *"skipping it causes a real bug or substantial confusion."*

Items that are pure platform mechanics — capability/attribute/command syntax, `metadata` block layout, `dynamicPage` / `href` / `paragraph` / `input` tutorial material — are intentionally left out. The official Hubitat developer docs at <https://docs2.hubitat.com/en/developer> are the source of truth for those.

## Common

- **`importUrl` convention.** App and driver `definition()` blocks point at the canonical raw GitHub URL so the code editor's Import button can fetch it. Nearly universal; BatteryChangeLogger, DeviceReplacement, BTHomeV2-Motion and VirtualSwitchPowerSource lack it.
- **License headers.** Two licenses coexist: every app is MIT, and most drivers are too; a minority of drivers keep Apache-2.0 inherited from upstream (e.g. IKEA-Blinds, XfinityContactSensor, Aqara_MCCGQ11LM). Consider whether to standardize.
- **Rolling-window state idiom.** DevicePing (`RESPONSE_HISTORY_SIZE = 21`, median) and AwairElement (`MAX_PM25_READINGS = 5`, average) converge: list in `state`, drop oldest when over cap, compute the statistic, save back. Worth canonicalizing if a third instance appears.
- **Firmware-conditional behavior.** DevicePing checks `location.hub.firmwareVersionString`, which returns the platform's version, before picking between `NetworkUtils.ping` signatures; IKEA-Blinds picks its battery-percentage divisor from the `softwareBuild` data value, the device's firmware version. A short pattern for "behave differently on older firmware without crashing" might be worth documenting.

## Apps

- **Multi-page preferences with editing context.** SwitchMonitor and LogMonitor share parts of an idiom: pages return `Map` and navigate via `href` + `params`; LogMonitor keeps editing context in `state.editing*Index`, and SwitchMonitor defers destructive ops via a pending-action flag (`state.removeSettingsForGroupNumber`). The pending-flag pattern is worth documenting if it stays load-bearing.
- **`appButtonHandler` for in-page actions.** Button-name prefix matching with parameterized names (`btnConfirmDelete_${id}`, `btnFix_${deviceId}_${state}`, in SwitchMonitor) on top of platform-standard button inputs. One app so far.
- **`singleInstance` decision rule.** `singleInstance: true` for parents/integration roots (only one allowed per hub). The `singleThreaded` half of this rule moved to `ARCHITECTURE.md` (*Choosing between `singleThreaded` and concurrent `@Field` structures*).
- **Status text rendered as HTML.** Apps with rich state build `StringBuilder` HTML inside `paragraph` calls in preference pages — gives a status panel without a separate UI artifact. Style is consistent (red for problems, green for OK, tables with `border-collapse`).
- **Admin interface menu affinity.** Add a "menu" key to the app metadata.  Choose among the three possible choices: "Automations", "Integrations" or "Apps". Two apps use other values ("Utilities", empty) and two omit the key.

## Drivers

- **WebSocket driver pattern.** Two implementations (LogMonitorBridge, LogEventMonitor) share: `interfaces.webSocket.connect(uri, pingInterval:N)`, `webSocketStatus(msg)` for state strings, `parse(msg)` for frames, `atomicState.intentionalDisconnect` to suppress reconnects on intentional close, exponential backoff capped at 60s, `location.hub.uptime`-based startup delay. Worth a short subsection if a third implementation appears.
- **Bidirectional constants Map.** `@Field static final Map x = ["A":0, 0:"A", ...]` — same map used for encode and decode. Sinope drivers use 5+ of these. Idiomatic; could be canonicalized.
- **Specs companion files.** `Sinope_switch_specs.groovy`, `Stelpro_orleans_specs.groovy` — comment-only Groovy files documenting full Zigbee node descriptors and cluster/attribute tables. Reference docs that ride along with drivers. Project convention worth a one-line mention.
