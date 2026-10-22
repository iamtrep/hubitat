<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Mirror Switch

A Hubitat Elevation app that keeps a group of on/off devices in sync. When one member turns on or off, the app turns every other member on or off to match.

## How it works

The app sends a command only to members whose state differs from the new one. The events from those commands find every member already matching, so the group settles after one round and the devices never send each other back and forth.

On save, and after a code update, the member whose state changed most recently sets the state for the rest.

Each instance handles one group. Add an instance for each group.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Devices to keep in sync | _(required)_ | Any devices with the Switch capability; at least two |
| App name | Mirror Switch | Label for this instance |
| Enable info logging | on | |
| Enable debug logging | off | Turns itself off after 30 minutes |
| Enable trace logging | off | Shown when debug logging is on |

## Installation

In **Apps Code** → **New App** → **Import**, paste:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/MirrorSwitch.groovy
```

Then **Apps** → **Add User App** → **Mirror Switch**.

## License

MIT License. See the source file header for the full text.
