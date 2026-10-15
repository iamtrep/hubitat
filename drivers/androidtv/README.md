<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Android TV Remote

A Hubitat Elevation driver for Google TV and Android TV devices, over the LAN, with no bridge. It
speaks the protocol Google's TV remote app uses: TLS on port 6467 for pairing and 6466 for the
remote session.

## Setup

1. Give the TV a fixed IP address (a DHCP reservation in your router).
2. Add a virtual device with the **Android TV Remote** driver, enter the TV's IP address and save.
3. Press **Start Pairing**. A 6-character code appears on the TV.
4. Enter it with **Submit Pairing Code**. The device shows `pairing: paired` and connects.

The hub makes its own client certificate on the first pairing, which takes a few seconds. Its
private key is stored encrypted with the hub's key. After a hub restore or migration the key can't
be read back, and the driver asks you to pair again.

## Commands and attributes

- `on`, `off`: the TV's power key. `switch` follows the power state the TV reports.
- `sendKey`: a key name (`HOME`, `BACK`, `DPAD_UP`, `MEDIA_PLAY_PAUSE`, ...) or an Android key code.
- `launchApp`: opens an app. See below.
- `volumeUp`, `volumeDown`, `mute`, `unmute`, `setVolume`. See below.
- `connect`, `disconnect`, `forgetPairing`.
- `currentApp`: the package name of the app in the foreground, updated on every change.
- `networkStatus`: `online` while the session is up. The driver reconnects on its own after the
  TV drops the connection, goes quiet for 20 s, or rejects a command.

## Launching apps

Use the app's deep link, such as `https://www.youtube.com`. A package name
(`com.google.android.youtube.tv`) is sent as a `market://launch?id=` link, which some TVs and apps
accept and others reject. When the TV rejects one, the log says so and suggests the deep link.

## Volume

Many set-top boxes pass volume keys to the TV over HDMI-CEC and never report a volume level.
`volumeUp`, `volumeDown` and `mute` still work there. `setVolume` needs a TV that reports its level
in answer to a volume key; until it has seen one, it logs that it can't set the volume. `mute` and
`unmute` each send the mute key when the TV doesn't report whether it is muted.

## Android TV Remote Tester (optional)

`tests/AndroidTVRemoteTester.groovy` is an app that runs this driver through a fixed set of tests
against your TV and writes a report of where your TV differs from what the driver expects. Select
the Android TV Remote device (and, for the power-cut test, the smart plug feeding the TV), press
Run, and paste the report in the forum thread. Tests 1 to 8 take about 2 minutes and change what is
on the screen.
