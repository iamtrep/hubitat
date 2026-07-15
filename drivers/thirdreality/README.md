<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# ThirdReality Drivers

Hubitat Elevation drivers for ThirdReality Zigbee devices.

## Drivers

- `ThirdReality_3RSP02028BZ.groovy` — **Third Reality Smart Plug (3RSP02028BZ)**: Zigbee on/off plug with power monitoring
- `ThirdReality_3RDP01072Z.groovy` — **Third Reality Dual Smart Plug (3RDP01072Z)**: Zigbee dual-outlet plug with per-outlet power monitoring; each outlet is a component child. Shared line voltage/frequency live on the parent. Requires the outlet component driver below.
- `ThirdReality_Outlet_Component.groovy` — **Third Reality Outlet (Component)**: child device for one outlet of the 3RDP01072Z — switch plus per-outlet power, current, energy, and power factor
- `ThirdReality_3RPL01084Z.groovy` — **ThirdReality Presence Sensor R3 (3RPL01084Z)**: 60 GHz mmWave presence sensor with RGB night-light, illuminance, and TVOC air quality (Zigbee 3.0)

## License

MIT — see individual source files for the full license text.

See the parent [drivers/README.md](../README.md) for the full driver index.
