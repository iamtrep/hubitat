<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Indoor Air Quality Controller

Runs ventilation in stages from indoor CO2 and warns when a window should be opened or the air is too dry.

## What it does

The app combines the readings of one or more CO2 sensors into one value (the highest reading by default, or the average). It has 1 to 4 stages, each a group of switches with an on threshold, an off threshold and a dwell time in minutes. Stage N turns on when CO2 has stayed above its on threshold for its dwell and stage N-1 is running. It turns off when CO2 has stayed below its off threshold for its dwell and stage N+1 is off, so stages start from the bottom up and stop from the top down. A sensor silent for 2 hours drops out of the combined value. With no sensor left, the app keeps the stages as they are and stops escalating until a sensor reports.

Two advisories run beside the stages. The open-window advisory is raised when CO2 has stayed above its threshold for its duration while every stage is running or held, or while the app is paused. It repeats at the chosen interval and clears when CO2 falls below the top stage's off threshold. The low-humidity advisory is raised when the lowest humidity reading has stayed below its threshold for its duration, and only in winter when an HVAC Season Manager device is selected. It clears when the reading has stayed 3 %RH above the threshold for 1 hour, or when the season leaves winter. It raises one notification per episode. Both advisories are published on the Air device and sent to the notification devices.

## Install

1. Add `IndoorAirQualityController.groovy` in **Apps Code** and `IndoorAirQualityControllerAir.groovy` in **Drivers Code**.
2. In **Apps**, choose **Add user app** and pick **Indoor Air Quality Controller**.
3. Pick the CO2 sensors and the switches of each stage, then press **Done**.

Saving creates the Air device and leaves its switch off. Turn the switch on to start the app. Use one instance per ventilated space, and give each instance its own stage switches.

## Settings

| Setting | Default | Notes |
|---|---|---|
| CO2 sensors | | One or more |
| Combine the readings | Highest reading | Or Average |
| Number of stages | 3 | 1 to 4 |
| Stage switches | | One or more per stage |
| On above (ppm) | 625 / 1150 / 1250 / 1350 | Stages 1 to 4. Must rise from stage to stage |
| Off below (ppm) | 575 / 1050 / 1150 / 1250 | Must be below the stage's own on threshold |
| For (minutes) | 5 / 6 / 6 / 6 | 1 to 120 minutes |
| After a switch is turned off by hand, leave its stage off for | 60 minutes | 1 to 1440 minutes |
| Modes | every mode except Away | Modes in which the app manages air |
| Pause while any of these is on | | Switches |
| Pause while any of these is off | | Switches |
| Smoke detectors | | See Stopping |
| CO detectors | | See Stopping |
| Add to every stage threshold | 0 ppm | See Threshold offset |
| Offset while any of these is on | | Switches |
| Offset while any of these is off | | Switches |
| Window advisory: CO2 above | 1400 ppm | Does not move with the threshold offset |
| Window advisory: for | 20 minutes | 1 to 240 minutes |
| Window advisory: repeat every | 3 hours | 1 to 24 hours |
| Notify when CO2 is back down | off | Sent when the window advisory clears |
| Humidity sensors | | Optional. The lowest reading is used |
| Humidity below | 30 %RH | 5 to 80 %RH |
| Humidity: for | 12 hours | 1 to 72 hours |
| HVAC Season device | | Optional. When set, the low-humidity advisory runs only in winter |
| Send both advisories to | | Notification devices |
| Enable info logging | on | |
| Enable debug logging | off | Turns off after 30 minutes |
| Testing: a minute lasts a second and an hour a minute | off | For the behavior test |

The page shows an error while the on thresholds do not rise from stage to stage, or while an off threshold is not below its on threshold, and the app manages nothing until it is fixed. A status block at the top shows each stage, the effective thresholds and why the app is stopped, if it is.

## Sharing a switch with another app

The app turns off only the switches it turned on. A switch that is already on when a stage starts stays on when the stage stops.

When another app or rule turns off a switch of a running stage, the app turns it back on and takes it as its own, at most once per switch every 5 minutes. A physical off holds the stage: the app sends no command to it for the hold time, the stages above it keep working, and when the hold ends the stage turns on again if CO2 is still above its on threshold or a stage above it is still running. A physical off needs a device that reports `physical`.

An off from a dashboard or the device page is digital, so the app turns the switch back on while its stage runs. Use a physical switch press or the pause inputs to stop it.

The pause inputs match the restriction switches of Humidity Fan Controller. Its *Must Be OFF* list corresponds to *Pause while any of these is on*, and its *Must Be ON* list to *Pause while any of these is off*. One switch can pause both apps.

## Stopping

On entering a stopped state the app turns off the switches it turned on and sets every stage to off, then sends no commands while any of these holds:

- A *Pause while any of these is on* switch is on, or a *Pause while any of these is off* switch is off.
- The hub mode is not one of the selected modes.
- The Air device's switch is off.

When the last of them clears, the app evaluates from the current CO2 value and starts stages through their dwell times.

While a smoke or CO detector reports `detected`, the app stops and leaves every switch as it is, so a fire rule keeps control. When every detector is clear the app forgets which switches it turned on and evaluates from the current CO2 value.

## Threshold offset

While any *Offset while any of these is on* switch is on, or any *Offset while any of these is off* switch is off, the offset in ppm is added to the on and off thresholds of every stage. With no offset switch selected the offset never applies. A change takes effect at the next decision: a running stage keeps running until CO2 has stayed below its new off threshold for its dwell.

Example: an offset of 200 ppm and an offset switch that is on while the A/C runs. Outside air then brings in heat and moisture, and the stages wait for 200 ppm more CO2 before they start.

## The Air device

The device is named **Indoor Air** and labelled "Indoor Air", or "<app name> Air" when the app is renamed. It belongs to the app and is deleted with it.

| Attribute | Values |
|---|---|
| `carbonDioxide` | Combined value, ppm |
| `humidity` | Lowest reading, %RH |
| `ventilationStage` | 0 to the number of stages: the highest stage running or held |
| `windowAdvisory` | `active`, `inactive` |
| `lowHumidityAdvisory` | `active`, `inactive` |
| `switch` | `on`, `off`: the enable switch of the app |

`on()` and `off()` enable and disable the app. The switch starts off, so a new instance does nothing until it is turned on. Turning it off stops the app as described under Stopping.

## Tests

- `tests/test_core.groovy`: unit tests of the stage, advisory and offset logic, run off the hub under Groovy 2.4.21: `java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain tests/test_core.groovy`.
- `tests/test-iaq.sh`: behavior test on a hub, generated from `tests/spec-iaq.yaml`. It drives a test instance with virtual sensors and switches, with the testing setting on: `bash tests/test-iaq.sh [@hub]`.

The Mode 1 test needs the two test drivers `VirtualCO2Sensor.groovy` and `VirtualSwitchPhysical.groovy` from `drivers/tests/` installed on the hub.
