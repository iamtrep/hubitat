<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Energy From Power

Builds an energy total (kWh) from a power meter that reports only power, and publishes it on an energy meter device.

## What it does

The app reads one power meter and adds up power × time into a running total in kWh. It publishes the total on an energy meter device, which the hub's Power and Energy Tracker, dashboards and rules can read. One instance watches one load.

Each reading counts until the next one. A power meter reports when its reading changes (send-on-delta), so between reports the power has stayed at the last reading [1]. Counting each reading for the time it held (a zero-order hold, the "left" Riemann sum) is exact for a load that steps between fixed levels, such as a water heater element or a compressor [2]. A trapezoid between readings would spread each step over the gap between reports, which is wrong for these loads.

At each power reading the app counts the energy up to that reading and publishes the total when it has grown by the set step (1 kWh by default) since the last publish. A meter that stays quiet for over an hour, such as one holding a steady load, doesn't hold the total back: an hourly check publishes it then if it has grown at all.

## Install

1. Add `EnergyFromPower.groovy` in **Apps Code**.
2. In **Apps**, choose **Add user app** and pick **Energy From Power**.
3. Pick the power meter and press **Done**.

Unless you pick an energy meter, saving creates one with the hub's **Virtual Power/Energy Meter** driver. Its label follows the app's: an app named "Water heater" gets "Water heater Energy".

## Settings

Most installs pick the power meter and press **Done**. The settings that only some meters need are in the collapsed **Advanced** section.

| Setting | Default | Notes |
|---|---|---|
| Power meter | | Any device with the Power Meter capability. A reading in kW is scaled to W |
| Publish the total on | a device this app creates | Or a device you pick, which needs a `setEnergy` command. The page links to the device in use |
| Publish the total each time it grows by (kWh) | 1 | Checked at each power reading |
| Reset the total to 0 kWh | button | |
| **Advanced** | | |
| Decimals in the published total | 3 | |
| Stop counting when the power meter goes offline | off | For a meter that can drop off the network while it reads high, which would otherwise keep counting that power. Then **Count the load as off after this long without a reading** (60 min by default). Leave it off for a plug that reports only when the power changes: it can be silent for hours while the load runs steadily |
| Ask the power meter for a reading on a schedule | off | For a meter that does not send readings on its own: calls its `refresh()` every 15 min by default |
| Forward each power reading to the energy meter | off | Every power reading becomes a second event, which adds load to the hub with a meter that reports often |

## The total

The total starts at the energy meter's value when the app is first saved, so a device that already holds a reading counts on from it. A value set on the energy meter outside the app, such as its **Reset Energy** or **Set Energy** command, becomes the new total. The app tells its own values apart from these by how far they are from what it last published.

Across a hub restart, the power read before the restart counts for the time the hub was down. **Stop counting when the power meter goes offline** limits that.

## Using it with the Power and Energy Tracker

The hub's Power and Energy Tracker takes an energy meter, not a power meter, so it can't read a meter that reports only power. Select this app's energy meter in the tracker instead. The tracker reads the total once an hour, on the hour, and reads nothing else from the device. Its hourly figures move in steps: energy counts in the hour the total is published, so with a 1 kWh step a light load shows as 1 kWh every few hours. A smaller step evens that out. The power meter can stay as it is for the apps that take power, such as Load Shedder and Power Aggregator.

The tracker's first reading counts the whole total as that hour's usage, so select a new energy meter, at 0 kWh, or expect the first hour to show the total the device held. A reset of the total shows as negative usage in the hour it happens.

## Tests

`tests/test_core.groovy` runs the app's pure core off-hub: integration over step loads, readings handled late, the offline limit, the publish rule and telling its own values from outside ones.

## References

1. M. Miskowicz, "Send-On-Delta Concept: An Event-Based Data Reporting Strategy," *Sensors* 6(1), 49–63, 2006. [mdpi.com/1424-8220/6/1/49](https://www.mdpi.com/1424-8220/6/1/49)
2. Home Assistant, Integral integration, `method`: the left method "is extremely accurate at estimating rectangular functions which are very stable for long periods of time." [home-assistant.io/integrations/integration](https://www.home-assistant.io/integrations/integration/)
