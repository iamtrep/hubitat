<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Location Event Mapper

A Hubitat Elevation app that subscribes to hub `location` events and reflects them onto virtual contact sensors. Each mapping is a child instance that picks one virtual contact sensor and the location events that should `open` it and the events that should `close` it. The sensor's state then becomes a usable trigger or required-expression input in Rule Machine and other apps.

The parent app (`Location Event Mapper`) is a singleton that owns the child instances. All event handling lives in the child — the parent does no work beyond holding the group.

## Features

### Supported location events

The child surfaces the platform's location events as a multi-select dropdown. Currently exposed:

- Lifecycle: `manualReboot`, `manualShutdown`, `systemStart`, `update`
- Solar: `sunrise`, `sunset`, `sunriseTime`, `sunsetTime`, `sunriseSunsetUpdated`
- Radio: `zigbeeOn`, `zigbeeOff`, `zigbeeStatus`, `zwaveCrashed`, `zwaveStatus`
- Health: `cloudBackup`, `deviceJoin`, `lowMemory`, `severeLoad`, `schedulerFailed`

A single child can map any subset of these to OPEN and any subset to CLOSE — the two sets are independent, so the same event may appear in both if you want a toggle, or only in one for an edge.

### Idempotence + missed-event warnings

The handler checks the sensor's current state before issuing `open()` or `close()` and logs a warning if the sensor was already in the target state. A warning on `already open — missed systemStart event?` is the typical signal that a shutdown-without-restart happened.

### Startup delay

The CLOSE for a `systemStart` event can be deferred by up to 3600 seconds so the sensor stays in its "hub restarting" state long enough for the platform to finish initializing apps, devices, and radios. The delay only applies when the close-triggering event is `systemStart`; other CLOSE events fire immediately.

### Per-instance logging

Each child has its own log-level selector (`warn`, `info`, `debug`, `trace`).

## Requirements

- **Hubitat platform 2.5.0 or newer** — the apps use the `menu:` definition field added in 2.5.0
- **One virtual contact sensor per child instance** — created from **Devices → Add Device → Virtual → Virtual Contact Sensor**; the child page links to the creation form

## Installation

### 1. Install the parent app

In **Apps Code** → **New App** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LocationEventMapper/LocationEventMapper.groovy
```

Click **Save**.

### 2. Install the child app

In **Apps Code** → **New App** → **Import**:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LocationEventMapper/LocationEventMapperChild.groovy
```

Click **Save**.

### 3. Create the parent instance

**Apps** → **Add User App** → **Location Event Mapper**. The parent page is the entry point for adding mappings.

### 4. Add a mapping

On the parent page, **Create New Location Event Mapper** opens a child config page:

1. Name the mapping (becomes the app label)
2. Pick a virtual contact sensor (one per mapping)
3. Pick the events that should OPEN the sensor — defaults to `manualReboot`, `manualShutdown`, `update`
4. Pick the events that should CLOSE the sensor — defaults to `systemStart`
5. Click **Done**

The defaults wire up a restart-state sensor: it opens when the hub is asked to shut down, reboot, or apply an update, and closes once `systemStart` fires after the hub comes back.

## Configuration (child)

| Setting | Default | Description |
|---|---|---|
| Name | _(required)_ | Display label for this mapping |
| Virtual Contact Sensor | _(required)_ | Sensor the mapping drives |
| Events to OPEN the device | `manualReboot`, `manualShutdown`, `update` | Multi-select from the supported location events |
| Events to CLOSE the device | `systemStart` | Multi-select from the supported location events |
| Startup delay (seconds) | `0` | Defer CLOSE on a `systemStart` event by this many seconds (0–3600); ignored for non-`systemStart` CLOSE events |
| Logging level | `info` | `warn`, `info`, `debug`, `trace` |

## File Structure

```
apps/LocationEventMapper/
  LocationEventMapper.groovy        # Parent app (singleton)
  LocationEventMapperChild.groovy   # Child app (one per mapped sensor)
```

## License

MIT License. See source file headers for the full text.
