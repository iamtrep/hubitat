<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# VerbNav — Hubitat UI prototype

A single-page reimagining of the Hubitat hub UI, organized around five **verbs** that describe what you actually want to do, rather than the platform's object-centric menu structure.

| Verb         | What lives here                                            |
|--------------|------------------------------------------------------------|
| **Connect**  | Devices — radios, status, rooms, drivers in use            |
| **Automate** | Installed apps and rules                                   |
| **Monitor**  | Live event/log stream, device events                       |
| **Maintain** | Hub health, memory, CPU, temperature, backups              |
| **Extend**   | User drivers and user apps installed on the hub            |

## Files

- `verb_nav_prototype.html` — the entire UI (HTML + CSS + JS, no build step)
- `serve.py` — local proxy that serves the HTML and forwards `/hub*`, `/device/*`, `/installedapp/*`, `/driver/*`, `/app/*`, `/logs/*`, and the `/logsocket` WebSocket to the hub, bypassing CORS

## Running it

### Option A — local with the proxy (recommended for development)

```bash
python3 serve.py [hub_ip] [port]   # defaults: 192.0.2.10, 8000
```

Then open <http://localhost:8000/verb_nav_prototype.html>.

### Option B — served from the hub

Upload `verb_nav_prototype.html` to the hub's File Manager. Open it at `http://<hub-ip>/local/verb_nav_prototype.html`. Same-origin requests, no proxy needed.

The page detects which mode it's in (`detectHubBase()` at the top of the script) and uses relative paths when served from the hub or from a LAN IP, and the configured hub IP otherwise. The IP is editable via the gear icon and persisted in `localStorage`.

## Hub API surface used

Read-only `/hub2/*` endpoints for the bulk of the data — `devicesList`, `appsList`, `userDeviceTypes`, `userAppTypes`, `hubData`, `networkConfiguration`, `hubMeshJson`, `localBackups`, `cloudBackups`, plus per-radio status and CPU/memory/temperature. Real-time log stream over the `/logsocket` WebSocket.

## Status

Prototype. Tested against hub firmware **2.5.0.107** (`TESTED_VERSION` constant in the script); a banner is shown when running against a different version. No write/configure paths are implemented.
