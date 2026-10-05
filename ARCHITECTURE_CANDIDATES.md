<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Architecture Guide — Candidate Topics

Lower-priority observations from the codebase review. Kept here for later reconsideration; promote to [`ARCHITECTURE.md`](ARCHITECTURE.md) if a topic reaches the bar of *"skipping it causes a real bug or substantial confusion."*

Items that are pure platform mechanics — capability/attribute/command syntax, `metadata` block layout, `dynamicPage` / `href` / `paragraph` / `input` tutorial material — are intentionally left out. The official Hubitat developer docs at <https://docs2.hubitat.com/en/developer> are the source of truth for those.

## Common

- **Rolling-window state idiom.** IPReachabilitySensor (`RESPONSE_HISTORY_SIZE = 21`, median) and AwairElement (`MAX_PM25_READINGS = 5`, average) converge: list in `state`, drop oldest when over cap, compute the statistic, save back. Worth canonicalizing if a third instance appears.

## Apps

- **Multi-page preferences with editing context.** SwitchMonitor and LogMonitor share parts of an idiom: pages return `Map` and navigate via `href` + `params`; LogMonitor keeps editing context in `state.editing*Index`, and SwitchMonitor defers destructive ops via a pending-action flag (`state.removeSettingsForGroupNumber`). The pending-flag pattern is worth documenting if it stays load-bearing.

## Drivers

- **WebSocket driver pattern.** Two implementations (LogMonitorBridge, LogEventMonitor) share: `interfaces.webSocket.connect(uri, pingInterval:N)`, `webSocketStatus(msg)` for state strings, `parse(msg)` for frames, `atomicState.intentionalDisconnect` to suppress reconnects on intentional close, exponential backoff capped at 60s, `location.hub.uptime`-based startup delay. Worth a short subsection if a third implementation appears.
- **Bidirectional constants Map.** `@Field static final Map x = ["A":0, 0:"A", ...]` — same map used for encode and decode. Sinope drivers use 5+ of these. Idiomatic; could be canonicalized.
