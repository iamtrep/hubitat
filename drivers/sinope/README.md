<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Sinopé Drivers

Hubitat Elevation drivers for Sinopé Zigbee devices.

## Drivers

- `Sinope_DM2500ZB.groovy` — **Sinope Dimmer (DM2500ZB)**: Zigbee dimmer switch
- `Sinope_SW2500ZB.groovy` — **Sinope Switch (SW2500ZB)**: Zigbee on/off switch
- `Sinope_TH1300ZB.groovy` — **Sinope Thermostat TH13X0ZB DEV**: Zigbee floor-heating thermostat
- `Sinope_VA422xZB.groovy` — **Sinope Water Valve (VA422xZB)**: Zigbee water valve with optional flow sensor support

## Reference notes

- `Sinope_switch_specs.groovy` — Zigbee node-descriptor reference for the Sinopé switch/dimmer hardware (commented reference data; not a driver)

## License

Mixed. Each file's header is authoritative. `Sinope_TH1300ZB.groovy` and `Sinope_VA422xZB.groovy` derive from upstream community work and remain under the **Apache License 2.0**; the rest are released under the **MIT License**.

See the parent [drivers/README.md](../README.md) for the full driver index.
