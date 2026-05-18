<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat

Personal collection of apps, drivers, and integrations for the [Hubitat Elevation](https://hubitat.com/) home automation platform.

## Apps

Automation apps for humidity control, peak energy management, device monitoring, sensor aggregation, and hub administration. See [`apps/`](apps/README.md) for the full list.

### Hub Diagnostics

Comprehensive diagnostic dashboard served from the hub itself — devices, apps, network, performance history, snapshots, and a forum-friendly export. See [`apps/HubDiagnostics/`](apps/HubDiagnostics/README.md).

## Drivers

Zigbee, BLE, and cloud API device drivers — including families for Sinopé, Stelpro, ThirdReality, and standalone devices like Awair Element, Ecobee, and Environment Canada AQHI. See [`drivers/`](drivers/README.md) for the full list.

## Integrations

Parent-app + child-driver integrations for vendor cloud APIs — currently Blink home security cameras and VisiblAir indoor air quality sensors. See [`integrations/`](integrations/README.md) for the full list.

## Scripts

Backup, log analysis, and external integration scripts. See [`scripts/`](scripts/README.md) for the full list.

## Development

After cloning, run `scripts/install-git-hooks.sh` once to enable the pre-commit hook that keeps the AUTO-generated sections of every README in sync with the source.

## License

Released under the **MIT License** — see [LICENSE](LICENSE). Individual source files carry an `SPDX-License-Identifier: MIT` header.

A small number of files under `drivers/` are derived from upstream community work and remain under their original **Apache License 2.0** (each file's own header is authoritative):

- `drivers/IKEA-Blinds.groovy` (Wayne Man)
- `drivers/XfinityContactSensor.groovy` (John Goughenour)
- `drivers/sinope/Sinope_TH1300ZB.groovy` (community)
- `drivers/sinope/Sinope_VA422xZB.groovy` (sacua, kkossev, thebearmay)
- `drivers/stelpro/StelproKi.groovy` (Philippe Charette, Stelpro)
- `drivers/stelpro/StelproAllia.groovy` (Maxime Boissonneault, Philippe Charette, Stelpro)
