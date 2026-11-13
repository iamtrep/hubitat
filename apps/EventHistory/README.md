<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Event History

Keeps the hub's hub and location events past the limits of the hub's own lists, in capped File Manager files, with a page to search them.

## What it does

The app subscribes to every location event and stores each one as it happens. Once an hour, after a restart, and 5 seconds after a save, it also reads the hub's two event lists (`/hub/eventsJson` and `/logs/eventsJson`) over loopback. That read adds what the subscription can't see: events from before the install, events during a restart, and events with no value. An event seen both ways is stored once, by its event id; an event without an id can be stored twice.

Events wait in memory and are written every 5 minutes, or as soon as 100 are waiting. A restart or a code save loses the waiting events; the next read of the lists brings back those the hub still holds.

## Install

1. Add `EventHistory.groovy` in **Apps Code**.
2. In **Apps**, choose **Add user app** and pick **Event History**, then press **Done**.
3. Open the app and click **Open event history**.

The app turns OAuth on for itself; if it can't, its page says how to do it by hand, and events are still kept. The page (`eventhistory-ui.html`) is downloaded from GitHub into File Manager on first use and again when its version differs from the app's. The app installs once per hub.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Events per file | 2000 | 100 to 10000 |
| Location files | 25 | 1 to 200. The oldest file is deleted when a new one starts beyond this count |
| Hub files | 5 | 1 to 200 |
| Maximum age in days | 0 | 0 for no age limit. A file whose newest event is older is deleted; the newest file is always kept |
| Delete the stored events when this app is removed | off | |
| Events to skip | _(blank)_ | Event names, one per line or separated by commas. End a name with `*` to match its start, as in `variable:hydro_*` |
| Also hide names and addresses | off | Applies to events stored from then on: device, app, room and hub names become aliases, and IP and MAC addresses, emails, phone numbers, coordinates and serial numbers are masked |
| Enable info logging / debug logging | on / off | |

Passwords, tokens and keys are always masked before an event is stored. A secret in a plain value under a harmless name can't be recognized: add that event to **Events to skip**.

The app's page shows how many events each list holds, how far back they go, when the hub's lists were last read, and how many events the settings keep. **Read the hub's lists now** and **Save waiting events now** are under **Maintenance and logging**.

## The page

Search by words, `-word` to leave events out, `"two words"` for a phrase, or a regular expression. Filter by source, type, event name and date range; add highlight rules, which stay in the browser; click an event for its fields and the events around it. The address holds the filters, so a bookmark brings a view back. **Export CSV** saves the events shown. **Help** on the page has the details.

## Files

| File | What it is |
|---|---|
| `EventHistory.groovy` | The app |
| `eventhistory-ui.html` | The page, served by the app from File Manager |
| `tests/test-masking.groovy` | Masking and exclude-list tests, run off the hub |
| `tests/test-ui-logic.js` | Tests of the page's query parsing, severity, CSV cells and filter picks, run with Node |

In File Manager the events are in files named `eventhistory-<token>-hub-<n>.jsonl` and `eventhistory-<token>-location-<n>.jsonl`, one event per line.

## Endpoints (OAuth)

| Endpoint | Returns |
|---|---|
| `GET /ui` | The page |
| `GET /api/index` | The stored files of each list, with their event counts and time range, and the app's status |
| `GET /api/file/:stream/:n` | One stored file, as text |
| `GET /api/tail` | The events waiting to be written |

## More

- [Apps](../README.md)
