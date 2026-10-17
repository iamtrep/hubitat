<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Android TV Remote

A Hubitat Elevation driver for Google TV and Android TV devices, over the LAN, with no bridge. It
speaks the protocol Google's TV remote app uses: TLS on port 6467 for pairing and 6466 for the
remote session.

## Setup

Here "the TV" is the device running Android TV or Google TV: with a box such as an NVIDIA Shield or a
Chromecast, the box, not the screen it plugs into.

1. Add a virtual device with the **Android TV Remote** driver and click **Save Preferences**, leaving the
   IP address blank. The driver finds the TV on the network and fills in its address; reload the page
   after a few seconds to see it. With several TVs, a **TV** list appears instead: pick one and save again.
2. Press **Start Pairing**. A 6-character code appears on the TV.
3. Enter it with **Submit Pairing Code**. The device shows `pairing: paired` and connects.

The driver remembers which TV it is paired with. If the TV gets a new IP address, the driver finds it
again and reconnects, so the TV doesn't need a fixed address.

### If the TV isn't found

The log says so when the search finds nothing. The TV must be on and on the same network as the hub:
the search uses mDNS, which doesn't cross VLANs or subnets unless the router repeats it. Enter the TV's
IP address instead, and give the TV a fixed address (a DHCP reservation in your router), since the
driver can't follow it to a new one without the search.

After a power cut the hub may not find the TV again until the hub restarts: a TV that drops off the
network without signing off, then comes back under the same name, is missing from the hub's search
results. A DHCP reservation keeps the TV reachable either way.

**Find TVs** searches again: use it to switch the device to another TV, or after turning the TV on.
The search needs firmware 2.5.2 or later, as far as tested; on older firmware, enter the IP address.

The hub makes its own client certificate on the first pairing, which takes a few seconds. Its
private key is stored encrypted with the hub's key. After a hub restore or migration the key can't
be read back, and the driver asks you to pair again.

Right after pairing, the log shows an error `java.lang.InterruptedException (method socketStatus)`. The hub
logs it when the pairing connection closes; it is harmless, and the remote session connects right after.

## Commands and attributes

- `on`, `off`: the TV's power key. `switch` follows the power state the TV reports.
- `sendKey`: a key name or an Android key code. See below.
- `launchApp`: opens an app. See below.
- `volumeUp`, `volumeDown`, `mute`, `unmute`, `setVolume`. See below.
- `connect`, `disconnect`, `forgetPairing`.
- `findTvs`: searches the network for TVs. See Setup.
- `currentApp`: the package name of the app in the foreground, updated on every change.
- `networkStatus`: `online` while the session is up. The driver reconnects on its own after the
  TV drops the connection, goes quiet for 20 s, or rejects a command.

## Keys

`sendKey` takes one of these names (any case; a `KEYCODE_` prefix is ignored), or any key code number
from Android's [`KeyEvent` list](https://developer.android.com/reference/android/view/KeyEvent), such as
`7`–`16` for the digits 0–9 or `183`–`186` for the color keys:

| Name | Code | Name | Code |
|---|---|---|---|
| `HOME` | 3 | `MEDIA_PLAY_PAUSE` | 85 |
| `BACK` | 4 | `MEDIA_STOP` | 86 |
| `DPAD_UP` | 19 | `MEDIA_NEXT` | 87 |
| `DPAD_DOWN` | 20 | `MEDIA_PREVIOUS` | 88 |
| `DPAD_LEFT` | 21 | `MEDIA_REWIND` | 89 |
| `DPAD_RIGHT` | 22 | `MEDIA_FAST_FORWARD` | 90 |
| `DPAD_CENTER` | 23 | `MEDIA_PLAY` | 126 |
| `VOLUME_UP` | 24 | `MEDIA_PAUSE` | 127 |
| `VOLUME_DOWN` | 25 | `VOLUME_MUTE` | 164 |
| `POWER` | 26 | `INFO` | 165 |
| `ENTER` | 66 | `CHANNEL_UP` | 166 |
| `MENU` | 82 | `CHANNEL_DOWN` | 167 |
| `SEARCH` | 84 | `GUIDE` | 172 |
| `SLEEP` | 223 | `CAPTIONS` | 175 |
| `WAKEUP` | 224 | `SETTINGS` | 176 |
| | | `TV_INPUT` | 178 |

Whether a key does anything depends on the box and the app in front: a set-top box may pass volume keys
to the TV over HDMI-CEC, for example.

## Launching apps

Use a link the app handles, such as `https://www.youtube.com` or `vnd.youtube://www.youtube.com`. The TV
opens a link only when an installed app handles it. A package name (`com.google.android.youtube.tv`) is
sent as a `market://launch?id=` link; since a Google Play update in August 2026 no app handles that, so
TVs refuse it. `intent:` and `android-app://` links were refused too. When the TV refuses a link, the
log says so.

Links for common apps, from Home Assistant's [Android TV Remote docs](https://www.home-assistant.io/integrations/androidtv_remote/)
(only the YouTube ones tested here):

| App | Link |
|---|---|
| YouTube | `https://www.youtube.com`, `vnd.youtube://www.youtube.com` |
| Netflix | `netflix://`, or `https://www.netflix.com/title/<id>?source=30` for a title |
| Prime Video | `https://app.primevideo.com` |
| Disney+ | `https://www.disneyplus.com` |
| Plex | `plex://` |
| Twitch | `twitch://home` |

### Finding an app's link

- Try it: send `launchApp` with a guess, such as the service's website (`https://www.<service>.com`) or
  `<appname>://`. If the app opens, `currentApp` changes to it; if not, the log shows the TV refused the link.
- With ADB (developer mode on the TV): `adb shell dumpsys package <package>` lists the schemes and hosts
  the app declares, and `adb shell am start -a android.intent.action.VIEW -d '<link>'` opens a link the
  standard Android way.
- From the app's APK: `aapt dump xmltree <app>.apk AndroidManifest.xml`, and look for an intent filter
  with the `VIEW` action, the `BROWSABLE` category and a `data` scheme.

Some apps declare no link, and can't be launched this way.

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
on the screen. If the device isn't paired, Run instead checks how far pairing gets: it opens a pairing
dialog on the TV and answers it with a wrong code.
