<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: Apache-2.0
-->

# Universal Electronics / Visonic / Xfinity Contact Sensor

A Hubitat Elevation Zigbee driver for the door and window contact sensors sold by Xfinity, made by Universal Electronics (URC4460BC0-X-R), Visonic (MCT-350 SMA) and Leedarson (LDHD2AZW).

## Capabilities

- **Contact Sensor**: `open` / `closed`
- **Tamper Alert**: the case was opened
- **Temperature Measurement**, with a calibration offset
- **Battery**, plus `batteryVoltage`, `lowBattery` (`true` / `false`) and `batteryDefect` (`detected` / `clear`)
- `setBatteryReplacementDate`: records when the battery was last changed (today when left blank), in the device data `batteryReplacementDate`

## Preferences

| Preference | Default | Meaning |
|---|---|---|
| Battery Reporting Interval | 12 h | How often the sensor reports its battery |
| Temperature Reporting Interval | 720 min | How often the sensor reports its temperature |
| Temperature Calibration | 0 | Degrees added to each reading (−128 to 127) |

The sensor sleeps between messages, so **Refresh** is held until it next transmits (open or close it to send one). Info, debug and trace logging can be turned on separately; debug logging turns itself off after 30 minutes.

## Installation

Install with Hubitat Package Manager (search for "Xfinity"), or import the driver in **Drivers Code** → **New Driver** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/XfinityContactSensor.groovy
```

## License

Apache License 2.0. Some code is inspired by John Goughenour's Xfinity ZigBee Contact Sensor driver; see the source file header.
