<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Humidity Fan Controller

A Hubitat Elevation app that runs a bathroom extractor fan while the bathroom is more humid than a reference room, and stops it once the bathroom has dried back down. The app's name on the hub is **Humidity-Based Fan Controller**.

## How it works

The app compares the bathroom humidity with a reference humidity, usually a nearby room or the rest of the house. With several sensors in a role, it uses their median. A sensor that has not reported within the inactivity timeout is left out.

**Turning on.** The fan turns on when the bathroom stays high for the activation delay. High means the bathroom is above the absolute low threshold plus its tolerance and one of these holds:

- the bathroom is more than the high offset above the reference;
- the bathroom is above the absolute high threshold;
- the bathroom is climbing fast (see below).

**Turning off.** When the fan turns on, the app records the reference humidity. The fan turns off when the bathroom stays below that recorded value plus the normal offset for the deactivation delay. If the reference rises above the recorded value during the cycle, the live reference is used, since the bathroom cannot dry below it. The fan also turns off when the bathroom falls below the absolute low threshold minus its tolerance.

**Skipping the activation delay.** A fast climb turns the fan on at once: at least the burst count of rising readings within the burst window, at a slope of at least the minimum slope. With occupancy sensors selected, a fast climb counts only while motion is active or was active within the occupancy window. A single reading that jumps by at least the excessive change threshold also skips the delay (off by default, for sensors that report rarely).

**Fan control.** The app turns the fan off only when it turned the fan on itself. It checks that each command took effect within the verification timeout and notifies when one did not. If the fan is turned off at the wall switch (a physical off), the app stops treating it as its own and leaves it off. If another app or a rule turns it off while the bathroom is still high and the app isn't restricted, the app turns it back on, so a fan shared with another app stays on while the bathroom needs it. It does this at most once per 5 minutes in a humidity episode; a second off within that time is left off and logged as a warning. A switch whose driver doesn't mark wall presses as physical gets every off treated as another app's. When no bathroom humidity event arrives for the maximum run time, the app turns the fan off and notifies.

**Physical activation.** Optionally, a fan turned on at the wall switch runs for at least a set time, then turns off. Humidity and restriction requests to turn it off wait until that time has passed. Pressing the wall switch again turns it off at once.

**Restrictions.** While any "must be off" switch is on, or any "must be on" switch is off, the app pauses. A fan the app turned on is turned off, and it turns back on when the restriction clears if the bathroom is still high.

**High humidity switch.** An optional virtual switch is on while the bathroom is high (from activation until the fan's off delay ends) and off otherwise.

**Dew-point mode (experimental).** Compares dew point in °C instead of relative humidity, which removes the difference that temperature alone makes between rooms. Bathroom and reference sensors must also report temperature; sensors without it are left out of the median.

## Settings

### Devices

| Setting | Meaning |
|---|---|
| Fan Switch | The fan to control (required) |
| Bathroom Humidity Sensor(s) | One or more; the median is used (required) |
| Reference Humidity Sensor(s) | One or more; the median is used (required) |
| High Humidity State Switch | Optional virtual switch that follows the high state |
| Notification Device | Optional; for failed commands and maximum run time |

### Thresholds (%RH)

| Setting | Default |
|---|---|
| High Humidity Threshold Offset | 20 |
| Normal Humidity Threshold Offset | 15 |
| Absolute High Threshold | 90 |
| Absolute Low Threshold | 60 |
| Absolute Low Tolerance | 2 |

### Dew point (°C, when enabled)

| Setting | Default |
|---|---|
| High Dew-Point Offset | 4.0 |
| Normal Dew-Point Offset | 3.0 |
| Absolute High Dew Point | 22.0 |
| Absolute Low Dew Point | 12.0 |
| Absolute Low Tolerance | 0.5 |

### Fast climb and single-event bypass

| Setting | Default |
|---|---|
| Minimum Slope (%RH/min, 0 disables) | 2.5 |
| Burst Event Count | 3 |
| Burst Window (s) | 60 |
| Slope Window (s) | 180 |
| Excessive Change Threshold (%RH, 0 disables) | 0 |
| Occupancy Motion Sensors | _(none)_ |
| Occupancy Window (s) | 600 |

### Timing

| Setting | Default |
|---|---|
| Activation Delay (s) | 60 |
| Deactivation Delay (s) | 120 |
| Switch Verification Timeout (s) | 30 |
| Sensor Inactivity Timeout (min) | 60 |
| Maximum Fan Run Time (min, 0 disables) | 120 |
| Auto-off after physical activation | off |
| Run time after physical activation (min, 1–240) | 30 |

### Restrictions

| Setting | Meaning |
|---|---|
| Restriction Switches (Must Be OFF) | Pause while any of these is on |
| Restriction Switches (Must Be ON) | Pause while any of these is off |

Info, debug and trace logging can be turned on separately; debug and trace logging turn themselves off after 30 minutes. The top of the app's page shows the current humidity state and the time left on a pending change.

## Installation

In **Apps Code** → **New App** → **Import**, paste:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HumidityFanController.groovy
```

Then **Apps** → **Add User App** → **Humidity-Based Fan Controller**. Add one instance per bathroom.

## License

MIT License. See the source file header for the full text.
