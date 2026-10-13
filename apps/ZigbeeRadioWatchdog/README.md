<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Zigbee Radio Watchdog

A Hubitat Elevation app for a Zigbee radio that goes offline and does not come back. It saves the hub's diagnostic state to File Manager and writes JVM thread dumps to the engineering logs, so Hubitat staff have something to look at. Then it reboots the radio and, if that fails, the hub.

## What it does

1. **Detects** the radio going offline from the `zigbeeOff` and `zigbeeStatus` location events, and from a poll of the radio state every 2 minutes in case an event is missed.
2. **Captures** the state below to one File Manager file, `zigbee-incident-<yyyyMMdd-HHmmss>.txt`, and writes thread dumps 30 s apart to the engineering logs.
3. **Waits** for the radio to recover on its own (default 5 min). Most radio drops recover by themselves.
4. **Reboots the Zigbee radio** only, the same as the Zigbee settings page's "Reboot radio" button, then waits again (default 3 min). Hubitat offers this on the C-8 and C-8 Pro only; on a C-7 or earlier the app skips straight to the hub reboot.
5. **Reboots the hub** if the radio is still offline. Before the reboot it saves a smaller final capture, `zigbee-incident-<yyyyMMdd-HHmmss>-final.txt`, and one more thread dump. Logs are kept across the reboot. It never reboots the hub more often than the configured gap (default 6 h).
6. After the hub restarts, it checks the radio and closes the incident with the outcome.

Each step can be turned off. Notifications go to any notification devices you pick. The app page lists past incidents with links to their files, and has a **Capture diagnostics now** button.

## What gets captured

Each capture is one text file. Every response is saved as the hub sent it, under a header line that names it, the request and the HTTP status:

```
===== zigbee-details  GET /hub/zigbeeDetails/json  HTTP 200  2026-10-12 21:22:10 =====
{...}
```

The file is saved again after each response, so a capture cut short keeps what it collected.

| Section | Content |
|---|---|
| `zigbee-details` | Radio state, per-device message counts and last message |
| `zigbee-tables` | The hub's neighbor and routing tables, as last seen |
| `zigbee-health` | The radio health flag |
| `runtime-stats` | Per-app and per-device runtime stats, scheduled and running jobs (lost at reboot) |
| `memory-history` | Free memory, CPU and Java heap every 5 min since boot (lost at reboot) |
| `backup-status`, `local-backups` | Whether a backup is running or stuck, and which nightly backups exist |
| `hub-data`, `temperature`, `db-size` | Hub identity, firmware, temperature, database size |
| `hub-events`, `location-events`, `past-logs` | Hub and location events, and the log buffer |

The final capture before a hub reboot has the Zigbee details, runtime stats, memory history and backup status. A full capture is about 2 to 3 MB, most of it the log buffer. The app keeps the files of the last 5 incidents by default and deletes older ones.

## Notes

- All calls go to the hub's own loopback address, so they work with hub security on.
- Radio reboots log no hub event or location event, so the app polls the radio state to see whether it recovered.
- The hub reboot uses the same request as **Settings → Reboot**, with log purge and database rebuild off.
