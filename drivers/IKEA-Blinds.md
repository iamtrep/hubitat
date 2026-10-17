<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: Apache-2.0
-->

# IKEA Window Blinds

A Hubitat Elevation Zigbee driver for IKEA roller and cellular blinds: FYRTUR, KADRILJ, PRAKTLYSING and TREDANSEN.

## Capabilities

- **Window Shade**: open, close, stop and set a position (0–100 %), with `windowShade` reporting `open`, `closed`, `partially open`, `opening` or `closing`
- **Switch Level** and **Switch**: the level is the position, and on/off open and close the blind, for dashboards and voice assistants that only know dimmers
- **Battery**, **Refresh**, **Configuration**
- `updateFirmware`: asks the blind to check for an OTA firmware update

## Preferences

| Preference | Default | Meaning |
|---|---|---|
| Shade Open Threshold | 97 % | At or above this position the blind is reported `open` |
| Shade Closed Threshold | 3 % | At or below this position the blind is reported `closed` |
| Open Margin | 0 % | Stop this far short of fully retracted to spare the mechanism; still reported as 100 % |

Info, debug and trace logging can be turned on separately; debug logging turns itself off after 30 minutes.

## Installation

Install with Hubitat Package Manager (search for "IKEA Window Blinds"), or import the driver in **Drivers Code** → **New Driver** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/IKEA-Blinds.groovy
```

Pair the blind, or select **IKEA Window Blinds** as the device's driver and click **Configure**.

## License

Apache License 2.0. Originally inspired by a4refillpad's IKEA Window Blinds driver (Wayne Man); see the source file header.
