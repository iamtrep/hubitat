<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Platform docs

What the Hubitat hub does where its documentation is silent or wrong. Each fact carries a mark saying how it is known.

| Doc | Holds |
|---|---|
| [hubitat-internal-apis.md](hubitat-internal-apis.md) | The hub as a client off the hub sees it: admin HTTP endpoints, websockets, port 8081, payloads, authentication, status codes |
| [hubitat-platform-notes.md](hubitat-platform-notes.md) | What Groovy code on the hub can do and see: sandbox, `state`, events, scheduling, outbound HTTP, app pages, platform objects |
| [hubitat-zigbee-helper.md](hubitat-zigbee-helper.md) | The `zigbee` object injected into drivers, and ZDO |
| [hubitat-device-reference.md](hubitat-device-reference.md) | Per-device and per-vendor protocol facts: cluster maps, encodings, quirks; and the hub models' hardware |
| [EVIDENCE.md](EVIDENCE.md) | Which file a fact belongs in, and how its evidence is marked |
| [logging-emoji-scheme.md](logging-emoji-scheme.md) | The emoji used in app and driver logs |
