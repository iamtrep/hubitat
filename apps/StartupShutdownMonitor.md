<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Startup and Shutdown Monitor

A Hubitat Elevation app that drives a virtual contact sensor from the hub's own lifecycle events. The sensor opens when the hub is about to reboot, shut down or update, and closes when the hub has started again. Rules can use it as a trigger, condition or required expression, for example to hold off actions while the hub is coming back up.

## How it works

| Event | Contact |
|---|---|
| `manualReboot`, `manualShutdown`, `update` | open |
| `systemStart` | closed, after the startup delay |

If the hub starts while the sensor is still closed, no shutdown event came before the restart (a power loss or crash, for example). The app logs a warning and, when notification devices are selected, sends "unplanned restart".

The due time of a delayed close is kept in the app's state. If the scheduled close is lost, it completes on the next location event or save.

## Setup

1. Create a **Virtual Contact Sensor** (**Devices** → **Add Device** → **Virtual**). The app's page has a link to that page.
2. Install the app (below), then **Apps** → **Add User App** → **Startup and Shutdown Monitor**.
3. Select the sensor and click **Done**.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Virtual Contact Sensor | _(required)_ | The sensor the app opens and closes |
| Startup delay | 0 s | Wait this long after `systemStart` before closing (0–3600 s). Applies to `systemStart` only |
| Notify these devices | _(none)_ | Notification devices for an unplanned restart |
| Events to OPEN the device | `manualReboot`, `manualShutdown`, `update` | Advanced: any supported location event |
| Events to CLOSE the device | `systemStart` | Advanced: any supported location event |

Info, debug and trace logging can be turned on separately; debug and trace logging turn themselves off after 30 minutes.

The app allows one instance per hub.

## Installation

In **Apps Code** → **New App** → **Import**, paste:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/StartupShutdownMonitor.groovy
```

## License

MIT License. See the source file header for the full text.
