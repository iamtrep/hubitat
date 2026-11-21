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

**Configuration waits for the hourly check-in.** Saving preferences and a driver update queue the configuration; it goes out when the sensor next checks in, so it can take up to an hour. That is the only moment the sensor is known to be listening: a configuration sent while it sleeps can be lost in part. A newly paired or re-paired sensor, and **Configure**, send it at once and again at the next check-in. A sensor that sends no check-in for 2.5 hours gets it right after one of its own messages, at most once a day, and again at its first check-in.

**Battery life.** The configuration sets the sensor to check its parent for messages every 6 s instead of the factory 2 s. The driver answers each check-in so the sensor goes straight back to sleep. If no battery report has arrived for two reporting intervals, or a new battery is detected, the driver sends the configuration again at the next check-in (for a missing report, at most once a day).

**Battery saver (experimental).** Sets how often the sensor polls its router: 6 s (off, the default), 30 s, 1 min, 5 min or 15 min. A longer poll should save battery, but a router may drop a sensor that polls too rarely, and how often is too rarely depends on the router. With a poll longer than 6 s, **Refresh** waits for the next hourly check-in. A change takes effect at the next check-in.

## Installation

Install with Hubitat Package Manager (search for "Xfinity"), or import the driver in **Drivers Code** → **New Driver** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/XfinityContactSensor.groovy
```

## License

Apache License 2.0. Some code is inspired by John Goughenour's Xfinity ZigBee Contact Sensor driver; see the source file header.
