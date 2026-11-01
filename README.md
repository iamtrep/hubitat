<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat

Personal collection of apps, drivers, and integrations for the [Hubitat Elevation](https://hubitat.com/) home automation platform.

## Apps

Automation apps for HVAC, humidity control, peak energy management, device monitoring, sensor aggregation, Zigbee inspection and hub administration. See [`apps/`](apps/README.md) for the full list.

### Hub Inspector

Comprehensive diagnostic dashboard served from the hub itself — devices, apps, network, performance history, snapshots, and a forum-friendly export. See [`apps/HubInspector/`](apps/HubInspector/README.md).

## Drivers

Zigbee, BLE, and cloud API device drivers — including families for Sinopé and Stelpro, Aqara sensors, a Google TV / Android TV remote, and standalone devices like ThirdReality plugs and presence sensors, Awair Element, Ecobee, and Environment Canada AQHI. See [`drivers/`](drivers/README.md) for the full list.

## Integrations

Parent-app + child-driver integrations for vendor cloud APIs: Blink home security cameras, Hilo energy use and winter credit, and VisiblAir indoor air quality sensors. See [`integrations/`](integrations/README.md) for the full list.

## Scripts

Off-hub helpers live under `scripts/`: [Hub Global Search](scripts/HubGlobalSearch/README.md), a userscript that adds one search box to the hub admin UI; a Hydro-Québec peak-event script; and Python helpers for log and event capture.

## Documentation

Platform findings, for what the hub does where Hubitat's documentation is silent, are indexed in [`docs/`](docs/README.md). To contribute, see CONTRIBUTING.md.

## License

Released under the **MIT License** — see [LICENSE](LICENSE). Individual source files carry an `SPDX-License-Identifier: MIT` header.

A small number of files under `drivers/` are derived from upstream community work and remain under their original license (each file's own header is authoritative).

**Apache License 2.0:**

- `drivers/IKEA-Blinds.groovy` (Wayne Man)
- `drivers/XfinityContactSensor.groovy` (John Goughenour)
- `drivers/sinope/Sinope_TH112xZB.groovy`
- `drivers/sinope/Sinope_TH13X0ZB.groovy` (community)
- `drivers/sinope/Sinope_VA422xZB.groovy` (sacua, kkossev, thebearmay)
- `drivers/stelpro/StelproKi.groovy` (Philippe Charette, Stelpro)
- `drivers/stelpro/StelproAllia.groovy` (Maxime Boissonneault, Philippe Charette, Stelpro)

**GPL-3.0-only:**

- `drivers/aqara/Aqara_MCCGQ11LM.groovy`
- `drivers/aqara/Aqara_WSDCGQ11LM.groovy`
