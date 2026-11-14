<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Aqara Drivers

Hubitat Elevation drivers for Aqara / Xiaomi (LUMI) Zigbee devices.

## Drivers

- `Aqara_WSDCGQ11LM.groovy` — **Aqara Weather Sensor WSDCGQ11LM**: Zigbee temperature, humidity and pressure sensor with battery reporting. Adds absolute humidity, pressure direction (rising, steady, falling), a choice of pressure units (kPa, mbar, inHg, mmHg) and an offset for each reading. Health status with offline and restored counters; when reports stop, the driver re-binds the reporting clusters and probes the sensor until it answers (mesh recovery mode preference, or the Start Recovery command). The FF01 check-in diagnostics (Zigbee parent, power-outage count, chip temperature) are surfaced to device state. The reset button's single press is decoded as a pushable button
- `Aqara_MCCGQ11LM.groovy` — **Aqara Contact Sensor MCCGQ11LM**: Zigbee door/window contact sensor with battery reporting, plus the FF01 check-in diagnostics the stock driver discards (Zigbee parent, power-outage count, chip temperature) surfaced to device state. Health status with offline and restored counters, and a count of mesh rejoins. The reset button's single press is decoded as a pushable button
- `Aqara_WS_USC01.groovy` — **Aqara Wall Switch WS-USC01 (No Neutral)**: H1 no-neutral single-rocker wall switch (WS-USC01 / WS-EUK01), with the manufacturer event-mode init required for reliable command response on a non-Aqara hub

## License

Mixed. Each file's header is authoritative. `Aqara_WSDCGQ11LM.groovy` and `Aqara_MCCGQ11LM.groovy` derive from upstream community work (BirdsLikeWires) and remain under the **GPL-3.0**; `Aqara_WS_USC01.groovy` is released under the **MIT License**.

See the [full driver index](../README.md).
