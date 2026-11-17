<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat built-in apps

What Hubitat's built-in apps and integrations do, where their documentation leaves it out, as code that uses or replaces them needs to know. What Groovy code on the hub can do in general is in [platform notes](hubitat-platform-notes.md); Maker API's HTTP surface is in [internal APIs](hubitat-internal-apis.md#maker-api-specifics).

Every fact carries an evidence mark ([EVIDENCE.md](EVIDENCE.md)).

**Sections**

- [Built-in app names](#built-in-app-names)
- [Amazon Echo Skill](#amazon-echo-skill)
- [Climate Scheduler](#climate-scheduler)
- [Google Home](#google-home)
- [Google TV Integration](#google-tv-integration)
- [IPP Printer Integration](#ipp-printer-integration)
- [Power and Energy Manager](#power-and-energy-manager)
- [Rule Machine](#rule-machine)

## Built-in app names

- Match built-in apps by app-type id, not by name: names change, and community apps can share them. Firmware 2.4.2.128 swapped the HomeKit app names and 2.4.2.129 reverted the swap *(staff: [gopher.ny 2.4.2.128](https://community.hubitat.com/t/154531/4), [gopher.ny 2.4.2.129](https://community.hubitat.com/t/154531/5))*. On 2.5.2.129 "HomeKit Bridge" exposes hub devices to Apple Home and "HomeKit Controller Integration" controls HomeKit accessories from the hub *(probed 2.5.2.129, `/hub2/appsList`)*. Shelly, UniFi Network (2.4.3.122) and MQTT (2.4.4.151) became built-in apps that can coexist with community apps of the same name *(staff: [gopher.ny 2.4.3.122](https://community.hubitat.com/t/157204/1), [gopher.ny 2.4.4.151](https://community.hubitat.com/t/161597/8))*.
- HomeKit Controller Integration runs only on a C-8 Pro. It arrived in 2.4.2.119 marked "requires C8 pro hub" *(staff: [gopher.ny 2.4.2.119](https://community.hubitat.com/t/154531/1))*. Installing it on a C-7 shows "Incompatible: This integration works only with hub revisions C8 Pro and newer." *(probed 2.5.2.139, C-7)*

## Amazon Echo Skill

- **Device names.** The native Amazon Echo Skill sends the device's label to Alexa as its name and the driver's name (the device Type) as its description; the device's name field is not used. Changing the label and running discovery again updated the Alexa name, and switching the device's driver and running discovery again updated the description *(probed firmware not recorded, throwaway virtual device)*.

## Climate Scheduler

A built-in app, new in 2.5.3, that schedules thermostat setpoints, enforces temperature limits and runs booster fans *(docs: [2.5.3 release notes](https://community.hubitat.com/t/167413))*. Its app type is `Climate scheduler`, menu Automations, "Schedules, thermostats, booster fans, and temperature limits." *(probed 2.5.3.107–2.5.3.124, C-8 Pro, test instance with virtual devices)*. Its JSON API is in [internal APIs](hubitat-internal-apis.md#climate-scheduler-json-api). It overrides manual changes, so code that also drives its thermostats or fans will fight it.

- **Device use.** Thermostats and fan switches in its document list the app in `appsUsing` *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*. Its `settings` were empty *(probed 2.5.3.107, C-8 Pro; false 2.5.3.124)*; they now hold `devicesPicked`, a map of device id to label *(probed 2.5.3.124, C-8 Pro)*. The document was not in app `state` *(probed 2.5.3.107, C-8 Pro; false 2.5.3.124)*; it now is, as a `configJson` string, with `revision`, `ceilingFanPrevious` and the status keys *(probed 2.5.3.124, C-8 Pro)*.
- **A save takes effect at once**: the thermostat got mode `heat` and the current period's setpoint within 1 s of the save *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*. A period change ran at its minute *(probed 2.5.3.107, C-8 Pro)*. Every save rewrote the current period's setpoint, undoing a manual change inside the limits *(probed 2.5.3.107, C-8 Pro; false 2.5.3.124)*; now a save that changes the limits or the schedule temperatures rewrites it, and one that changes nothing, an area name or `limits.log` does not *(probed 2.5.3.124, C-8 Pro)*. A save with `season: "off"` set the thermostat's mode to `off` *(probed 2.5.3.124, C-8 Pro)*.
- **Limits.** With limits on, a manual setpoint above `heatMax` was set back to `heatMax` within 100 ms, logged at info as `Climate scheduler corrected thermostat <deviceId>` when `log` is on. With `outside: "schedule"` it went to the period's setpoint instead (23 with `heatMax` 22 became the scheduled 21.5). A manual setpoint inside the limits was kept, and no hold appeared in `status.holds`. With limits off, a manual setpoint was kept *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*.
- **Mode.** With `season: "heat"`, a thermostat set to `off` by hand went back to `heat` within 40 ms, with no log line. A Virtual Thermostat's operating state set to `pending cool` or `cooling` was followed within 0.1 s by mode `heat` and `heating`, so cooling demand stays untested *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*.
- **Booster fans** are switches that turn on `start` minutes after a thermostat in the area starts heating or cooling ("Start delay (minutes)", 0 to 120 in 0.5 steps, default 1) and stay on `stop` minutes after demand ends (default 7) *(source: vue-hub2-climate-scheduler.min.js, 2.5.3.107–2.5.3.124)*. With `start` 0 the fan turned on within 1 s of `heating` (30 ms on 2.5.3.124). Stops come on a once-a-minute pass, at second 0: with `stop` 1, the fan went off 62 to 79 s after `idle`; with `stop` 2 after 138 s and with `stop` 5 after 343 s *(probed 2.5.3.107, C-8 Pro; probed 2.5.3.124 for `stop` 1)*. `status.fans.<groupId>` shows `wanted`, `running` and `since` *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*.
- **The app owns the fan switch.** Switched off by hand during demand, it was turned on again; switched on with no demand, turned off again. On 2.5.3.107 that took 5.5 and 9.8 s; on 2.5.3.124 it waited for the next minute pass (58 and 17 s) *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*. A fan can't be left on by hand outside demand.
- **Only `heating` and `cooling` count.** `pending heat` and `fan only` did not start the fan, nor did heating with `heat: false` on the group or with the app's `season` `off` *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*. With two thermostats, the stop delay began when the last one went `idle` *(probed 2.5.3.107, C-8 Pro)*. A save during demand left a running fan on, a hub reboot during a 5-minute stop delay kept the delay, and a switch outside every fan group was left alone *(probed 2.5.3.107, C-8 Pro)*.
- **Deleting the app** leaves its thermostats in their last mode and setpoint and its fan switches as they were *(probed 2.5.3.107–2.5.3.124, C-8 Pro)*. Not tested: the 2-hour hold, `recheck`, the weekday/weekend split, the `cool` and `auto` seasons, several areas, a thermostat that does not confirm, a fan `start` above 0, cooling demand, ceiling fan groups, and a fan that is a dimmer or fan controller.

## Google Home

The built-in Google Home integration shares hub devices with Google Home. Linking Hubitat from the Google Home phone app (Works with Google) installs the app on the hub, which logs `installed...` *(probed 2.5.3.124, C-8 Pro)*. Google's API carries temperatures in degrees Celsius and accepts a setpoint command with up to one decimal place *(spec: [Google TemperatureSetting trait](https://developers.home.google.com/cloud-to-cloud/traits/temperaturesetting))*.

- **The built-in Virtual Thermostat is accepted.** A new Virtual Thermostat and one an app was driving both appeared in Google Home, showing mode `heat` and their setpoint. Adding a device to the app's device list and saving logged `updated...` and the device appeared without a re-sync *(probed 2.5.3.124, C-8 Pro)*. This contradicts a [forum report](https://community.hubitat.com/t/167286) that it doesn't appear.
- **Setpoint changes from Google reach the device in heat mode.** A change to 22.5 from the Google Home app's dial set `heatingSetpoint` and `thermostatSetpoint` to 22.5 °C about a second later, and the mode stayed `heat` *(probed 2.5.3.124, C-8 Pro, hub scale °C)*.
- **Google Home shows half degrees.** On a hub set to °C, the Google Home app showed the setpoint and the temperature rounded up to the next tenth, then to the nearest 0.5 °C; a value can therefore show up to 0.3 °C off, more often above the real value than below *(probed 2.5.3.124, C-8 Pro)*. Whether the integration or Google does the rounding was not established:

| On the hub | Google Home app shows |
|---|---|
| setpoint 21.2, 21.3, 21.5 | 21, 21.5, 21.5 |
| setpoint 21.71, 21.73 | 22, 22 |
| temperature 19.1, 19.37, 22.3, 22.72 | 19, 19.5, 22.5, 23 |

The table is the probe above *(probed 2.5.3.124, C-8 Pro, Virtual Thermostat)*. Not tested: a hub set to °F, mode changes from Google, setpoints sent by voice, sharing from a device's Assistants tab, and a component child device.

## Google TV Integration

A built-in app, new in 2.5.3, that pairs with an Android TV or Google TV over the Android TV Remote v2 protocol *(docs: [2.5.3 release notes](https://community.hubitat.com/t/167413))*. The protocol is in the [device reference](hubitat-device-reference.md#android-tv--google-tv-remote-protocol-v2). Tested against a mock TV only.

- **The device** has Switch, Refresh and NetworkDevice *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock)*, plus Actuator *(probed 2.5.3.124, C-8 Pro)*. Commands: `launchApp`, `sendRemoteCommand`, `setKeyboardText`, `volumeUp`, `volumeDown`, `toggleMute`, `play`, `pause`, `stop`, `on`, `off`, `refresh` *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock)*, and `setInput` (HDMI 1 to 4, "Some TVs ignore these keys; input feedback is unavailable") and `showInputMenu` *(probed 2.5.3.124, C-8 Pro)*. `sendRemoteCommand` takes 17 buttons *(probed 2.5.3.111, C-8 Pro)*, now 22: `up`, `down`, `left`, `right`, `select`, `back`, `home`, `menu`, `play`, `pause`, `play_pause`, `stop`, `next`, `previous`, `volume_up`, `volume_down`, `mute`, `input`, `hdmi_1` to `hdmi_4` *(probed 2.5.3.124, C-8 Pro)*. `switch`, `mute`, `volume`, `currentApp`, `networkStatus` and `ipAddress` follow the TV's own messages *(probed 2.5.3.111, C-8 Pro, mock)*; `volume` did after `volumeUp` *(probed 2.5.3.124, C-8 Pro, mock)*.
- **`launchApp`** sends a URL as is and a package id as `market://launch?id=<pkg>` *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock)*. Any other text was sent as a package id too (`YouTube`, `1`) *(probed 2.5.3.111, C-8 Pro, mock; false 2.5.3.124)*; it is now refused with the warning "Google TV launch failed: Use an app link such as https://www.youtube.com or a package id, not an app display name", and nothing reaches the TV *(probed 2.5.3.124, C-8 Pro, mock)*. A blank value logs `launch failed (IllegalArgumentException)` and keeps the session *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock)*. Some TVs refuse `market://` launches: a Sony Bravia profile answers `remote_error` 7 and closes the connection 45 s later *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock)*, so pass an `https://` link.
- **A lost connection.** The device didn't notice a TV that closed or reset the connection: it stayed `switch on` and `networkStatus online`, dropped commands with nothing logged above debug, and only "Start pairing" recovered it *(probed 2.5.3.111, C-8 Pro, mock; false 2.5.3.114)*. Now, within 15 s of the TV going away, the device goes `networkStatus offline`, and `switch`, `volume` and `mute` leave `currentStates` with no `switch off` event (`currentApp` and `ipAddress` stay). It reconnects with the saved pairing 3 to 17 s after the TV comes back, and nothing is logged above debug *(probed 2.5.3.114–2.5.3.124, C-8 Pro, mock)*. After the Sony profile closed the connection, the hub reconnected within 1 s with no `offline` event *(probed 2.5.3.124, C-8 Pro, mock)*. A rule that waits for `switch off` won't fire when a TV drops off the network; test `networkStatus` instead.

## IPP Printer Integration

A built-in app, new in 2.5.3, that adds a printer as a device and polls it over IPP *(docs: [2.5.3 release notes](https://community.hubitat.com/t/167413))*.

- **The poll** is a `POST` with `Content-Type: application/ipp`, `Accept: application/ipp` and `Connection: keep-alive`, whose body is an IPP 1.1 Get-Printer-Attributes (`0x000b`) asking for `printer-name`, `printer-make-and-model`, `printer-state`, `printer-state-reasons`, `printer-state-message`, `queued-job-count`, `marker-names`, `marker-types`, `marker-colors`, `marker-levels`, `printer-pages-completed`, `printer-impressions-completed` and `printer-media-sheets-completed` *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock IPP server)*.
- **Discovery builds the URI from the mDNS `rp` value**: a Brother `_ipp._tcp` record with `rp=duerqxesz5090` gave `ipp://192.0.2.31:631/duerqxesz5090` *(probed 2.5.3.111–2.5.3.124, C-8 Pro, avahi-browse and app state)*. Some printers advertise a path they don't serve: the Brother HL-2270DW answers 404 at its `rp` path and Get-Printer-Attributes at `/ipp` *(probed 2.5.3.111, raw IPP client from a LAN host)*.
- **The device.** Attributes `printerState`, `queuedJobs`, `networkStatus`, `ipAddress`, `statusMessage`, `stateReasons` and `lastUpdated`; supplies come as child devices, so a printer that reports no `marker-*` attributes gets none *(probed 2.5.3.111–2.5.3.124, C-8 Pro, a real printer and a mock)*. Poll choices are Manual only, 1, 5 and 15 minutes *(probed 2.5.3.111, C-8 Pro)*, with 1 hour and 1 day added *(probed 2.5.3.124, C-8 Pro)*; the default is 5, run as a cron job such as `47 3/5 * * * ?`. Changing the printer URI preference runs a refresh at once *(probed 2.5.3.124, C-8 Pro)*. A real printer's 5-minute poll cost 2508 ms of device runtime over 70 minutes *(probed 2.5.3.111, C-8 Pro, `/logs/json`)*.
- **An unreachable printer.** The first failed refresh sets `networkStatus` `offline` and `printerState` `unknown` and logs one warning, "IPP printer is unavailable. Check its connection, IPP settings, and printer URI."; later failures log only at debug, and polling continues. `statusMessage`, `stateReasons` and `queuedJobs` are cleared with no event, so they read null, and `ipAddress` keeps its last value. The next good refresh sets everything back with no log line at info level *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock IPP server)*. A rule on `networkStatus` sees the outage; a rule on `printerState` sees `unknown`.
- **Failure timing**, from the driver's debug log: a closed port fails with `HttpHostConnectException` in 18 to 199 ms; dropped packets (a non-routable address) with `ConnectTimeoutException` after 10.0 s; a connection accepted with no reply with `SocketTimeoutException` after 10.0 s *(probed 2.5.3.111–2.5.3.124, C-8 Pro, mock IPP server)*. An unused LAN address failed with `HttpHostConnectException` in that range *(probed 2.5.3.111, C-8 Pro)*, and with `NoRouteToHostException` after 2.7 to 3.1 s *(probed 2.5.3.124, C-8 Pro)*.
- **Adding a printer by URI.** An HTTP 404 logs `IPP printer setup failed (IOException)` and shows the generic "Could not read the printer" *(probed 2.5.3.111, C-8 Pro, mock IPP server; false 2.5.3.124)*; it now logs `IPP printer setup failed (Printer returned HTTP 404)` and shows "Printer returned HTTP 404. Check the printer resource path and access settings." *(probed 2.5.3.124, C-8 Pro, mock IPP server)*. "Check and add printer" ignored a URI typed before the click *(probed 2.5.3.111, C-8 Pro; false 2.5.3.114)*; it now uses it, logs `IPP printer added`, and refuses a URI already added with "This printer URI has already been added." *(probed 2.5.3.114, C-8 Pro, real printer; probed 2.5.3.124 for the add, mock IPP server)*.

## Power and Energy Manager

The built-in app (2.5.0 and later; its page shows version 2.0.1) holds four child apps *(probed 2.5.3.123, C-8 Pro, throwaway instances)*:

- **Energy Tracker** selects one device with the Energy Meter capability, so a device that reports only `power` can't be picked. It subscribes to nothing: a schedule at the top of each hour (`0 0 * * * ? *`) reads the device's `energy` and records the change since the last read. The first read counts from 0, so it records the device's whole total as that hour's usage; a drop in the total (a reset) is recorded as negative usage *(probed 2.5.3.123, C-8 Pro, throwaway instances on Virtual Power/Energy Meter devices, two hourly reads)*.
- **Maximum Usage** watches power, energy or time on. In power mode it selects a Power Meter and subscribes to `power`; in energy mode an Energy Meter and `energy`. The energy limit is compared with the device's reading as it stands, not with what was used since the app was set up: a 5 kWh limit turned the switch off on the first event from a meter already at 52 kWh *(probed 2.5.3.123, C-8 Pro, throwaway instances)*.
- **Load Shedder** selects one Power Meter; **Power Aggregator** selects Power Meters and creates a device for their sum *(probed 2.5.3.123, C-8 Pro, settings pages)*.

## Rule Machine

- **The `logging` setting** (Events, Triggers, Actions) does not gate the "Required Expression false" log line on any single option: it was still logged with only `Actions` set and with only `Events` set. Clearing every option was not tested *(probed 2.5.2.139)*.
