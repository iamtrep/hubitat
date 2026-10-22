<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Sinopé Drivers

Hubitat Elevation drivers for Sinopé Zigbee devices.

## Drivers

- `Sinope_DM2500ZB.groovy` — **Sinope Dimmer (DM2500ZB)**: Zigbee dimmer switch
- `Sinope_RM3250ZB.groovy` — **Sinope Load Controller (RM3250ZB)**: Zigbee 50A load controller with energy meter
- `Sinope_RM3500ZB.groovy` — **Sinope Water Heater Controller (RM3500ZB)**: Zigbee water heater controller with energy meter, water temperature and leak sensor
- `Sinope_SW2500ZB.groovy` — **Sinope Switch (SW2500ZB)**: Zigbee on/off switch
- `Sinope_TH112xZB.groovy` — **Sinope Thermostat (TH112xZB)**: Zigbee line-voltage thermostat (TH1123ZB, TH1124ZB and their G2 versions)
- `Sinope_TH13X0ZB.groovy` — **Sinope Floor Thermostat (TH13X0ZB)**: Zigbee floor-heating thermostat (TH1300ZB, TH1320ZB-04)
- `Sinope_VA422xZB.groovy` — **Sinope Water Valve (VA422xZB)**: Zigbee water valve with optional flow sensor support

## Reference notes

- `Sinope_switch_specs.groovy` — Zigbee node-descriptor reference for the Sinopé switch/dimmer hardware (commented reference data; not a driver)

## License

Mixed. Each file's header is authoritative. `Sinope_TH112xZB.groovy`, `Sinope_TH13X0ZB.groovy` and `Sinope_VA422xZB.groovy` derive from upstream community work and remain under the **Apache License 2.0**; the rest are released under the **MIT License**.

See the [full driver index](../README.md).
