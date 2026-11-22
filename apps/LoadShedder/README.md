<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Load Shedder

Keeps switches off while any of a set of reasons holds, then turns back on the ones it turned off.

## What it does

Each instance has a group of loads (switches) and a set of reasons: switches that shed while on, switches that shed while off, and hub modes. While any reason holds, the loads stay off. When none holds, the app turns back on the loads it turned off, and only those.

- **When a shed starts,** the app remembers which loads are on and turns them off. A load that is already off is not the app's, and the app never turns it on.
- **While it sheds,** a second reason changes nothing: the shed lasts until the last reason clears, and the loads are restored once.
- **When no reason holds,** the app turns back on the loads it turned off, then forgets them. A load that is already back on is left alone.
- **The decision is level-triggered.** It is made again on every reason change, mode change, load change, hub start, save and every 5 minutes. A shed that should be in force but isn't (the hub restarted just before a reason began, an event was lost) is applied at the next evaluation, and a restore missed the same way is done then too.

Use one instance per group of loads that share their reasons. Two instances should not shed the same load.

## Install

1. Add `LoadShedderStatus.groovy` in **Drivers Code**, then `LoadShedder.groovy` in **Apps Code**.
2. In **Apps**, choose **Add user app** and pick **Load Shedder**.
3. Pick the switches to shed and at least one reason, name the instance, and press **Done**.

Saving creates the device **"<app name> status"**. It belongs to the instance, follows its name, and is deleted with it.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Switches to shed | | One or more |
| Shed while any of these is on | none | Switches |
| Shed while any of these is off | none | Switches |
| Shed while the mode is any of these | none | Hub modes |
| When a shed switch is turned back on during a shed | Leave it on | See below |
| Wait before restoring | 0 minutes | 0 to 1440. A reason that comes back during the wait resumes the shed |
| Time between restoring two switches | 0 seconds | 0 to 3600. Restores one load at a time, in the order they were shed |
| Time a switch has to confirm a command | 30 seconds | 5 to 300 |
| Notification devices | none | Told when a switch does not obey, and when every switch obeys again |

At least one reason is required, and a switch to shed cannot also be a reason.

### A switch turned back on during a shed

- **Leave it on** (default): the person wins. The app leaves the load on and no longer counts it as its own, so it is not touched at the end of the shed. A load that was off when the shed began and is turned on during it is left on too.
- **Turn it off again:** the app turns the load off again, at most once every 5 minutes per load, so it can't fight another automation in a loop. A load that was off when the shed began and is turned on during it is turned off and restored at the end.

After a hub restart, a load the app holds off that reads on came back with the power, not by hand: the app turns it off again under either setting. The app treats an evaluation as following a restart at hub start, in the first 10 minutes of uptime, and when it has not evaluated for 11 minutes (it does so at least every 5).

## Checking the commands

After each off or on, the app checks the load after **Time a switch has to confirm a command**. A load that has not reached the commanded state gets a second command. If it still has not, the app logs a warning, opens the status device's contact and tells the notification devices. Every 5 minutes it sends the command again while the load is still meant to be in that state; the contact closes when every load obeys.

A load whose restore did not land still counts as the app's: if a new shed starts first, the load stays the app's, and the app turns it on at the end of that shed.

Hubitat's Command Retry (Settings, Command Retry) resends a radio command the device did not acknowledge, before this app's check sees it.

## The status device

| Attribute | Values |
|---|---|
| `switch` | `on` while the app sheds: from the first reason until the last load is restored, so it stays on during a restore wait |
| `contact` | `open` while a load does not obey, `closed` otherwise |
| `reasons` | What holds, comma-separated (`Peak on, mode Away`), or `none` |
| `shedLoads` | The loads the app holds off, comma-separated, or `none` |

The `on` and `off` commands do nothing: the app is the only writer. Other apps can use the switch as a reason or a pause of their own, and the contact for a notifier.

## Notes

- Changing the settings during a shed keeps the shed. A load added while the shed runs is shed if it is on. A load removed from the app while held off is left as it is, with a log line: the app can no longer reach it.
- Removing the app turns back on the loads it holds off.
- The app never talks to other apps. A shed for a peak event follows the event's switch, so skipping or aborting the event at its source ends the shed.

## Tests

- `tests/test_core.groovy`: unit tests of the decision (reasons in every order and overlap, loads already off, restore of only the app's loads, both manual-on settings, restart mid-shed, commands that do not land, restore wait and stagger), run off the hub under Groovy 2.4.21: `java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain tests/test_core.groovy`.
