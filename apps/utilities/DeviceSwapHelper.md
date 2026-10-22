<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Device Swap Helper

A Hubitat Elevation app that replaces a device in every app that uses it, for example when a failed sensor is replaced by a new one. It lists the apps the hub reports as using the old device, then swaps through the hub's own **Swap Apps Device**, or input by input for swaps the hub won't do.

## Using it

1. Pick the **device to replace** (source) and the **replacement device** (target). The page compares their capabilities and lists any the target lacks.
2. **Scan & Preview Swap** reads every app that uses the source. The page updates by itself while it scans.
3. Choose how to swap (below), confirm, and read the results.

A local hub backup before swapping is recommended. The confirmation page links to it.

## Swap Apps Device

When the hub can swap the pair, the preview offers **Swap with Swap Apps Device…**, which opens the hub's own page with both devices selected. The hub exchanges the two devices:

- every app keeps using the source's device id, which now belongs to the new hardware, so no app setting changes;
- the name, label, room and driver move with the hardware, and the old hardware takes the target's id;
- event history stays with the device id.

The preview warns when apps already use the target (they move to the old hardware) and when apps subscribe to attributes the target doesn't report. **Undo** opens the hub's swap page again with the same two devices.

## Per-input swap

For swaps the hub won't do, or to change only some apps, the per-input swap writes the target into the selected app inputs and saves each app, so each app runs its `updated()`. The preview sorts what it found:

- **Auto-swap eligible**: inputs on an app's main page. Inputs with a warning, such as a missing capability, start unselected.
- **Manual edit required**: inputs on sub-pages or in app state. An **Edit** link opens the page that holds the input.
- **Other apps using the source**: apps the hub lists but whose reference the scan can't read, such as rules kept in their own documents. Change these by hand.
- **Mobile dashboards** showing the source.

**Undo Last Swap** puts the source back in the inputs the last swap changed. Every swap and undo appends one line per input to `device_swap_audit.txt` in File Manager, linked at the bottom of the app's page.

## Installation

In **Apps Code** → **New App** → **Import**, paste:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/utilities/DeviceSwapHelper.groovy
```

Then **Apps** → **Add User App** → **Device Swap Helper**. The app allows one instance per hub.

## License

MIT License. See the source file header for the full text.
