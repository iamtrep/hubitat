<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hydro-Québec Peak Period Manager

Publishes Hydro-Québec peak events and their phases on a device.

The app reads Hydro-Québec's peak events from its [open data](https://donnees.hydroquebec.com/explore/dataset/evenements-pointe/) (no account or login) and publishes where the next event stands on a **Hydro peak** device. Rules, HVAC Scheduler, Interlock and dashboards follow the device or its switches; to stand down for one event, use **skip** or **abort** on the device and everything that follows it stands down too.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `HydroPeakEvents.groovy` | App | Hydro-Québec Peak Period Manager |
| `HydroPeak.groovy` | Driver | Hydro Peak |

## Install

1. Add the app in **Apps Code** and the driver in **Drivers Code**.
2. In **Apps**, choose **Add user app** and pick **Hydro-Québec Peak Period Manager**.
3. Pick the offer, then press **Done**.

Saving creates the **Hydro peak** device and three switches: **Hydro peak announced**, **Hydro peak anchor** and **Hydro peak active**. They belong to the app and are deleted with it.

## Phases

| Phase | When | Switches on |
|---|---|---|
| `none` | No event listed | none |
| `announced` | From the announcement until the anchor window, or until the event with Flex D | announced |
| `anchor` | The anchor window: 01:00–04:00 before a morning event, 12:00–14:00 before an evening one (Winter Credit Option only) | announced, anchor |
| `gap` | From the anchor window's end to the event's start | announced |
| `active` | During the event | active |
| `skipped` | From **skip** until the event's end | none |
| `aborted` | From **abort** until the event's end | none |

After an event ends the phase moves to the next listed event, so a morning and an evening event on the same day follow each other.

The phase is worked out from the list of events and the clock every 5 minutes, after every fetch, at hub start and at each phase boundary. A reboot through a boundary or a missed job is corrected at the next check. When Hydro-Québec's data can't be reached, the last events fetched stay in effect.

## The Hydro peak device

| Attribute | Value |
|---|---|
| `phase` | one of the phases above |
| `window` | `AM`, `PM` or `none` |
| `eventDate` | `yyyy-MM-dd`, or `none` |
| `eventStart`, `eventEnd` | ISO 8601 with offset, or `none` |
| `anchorStart`, `anchorEnd` | ISO 8601 with offset, or `none` (Flex D) |
| `offer` | `CPC-D` or `TPC-DPC` |

| Command | Does |
|---|---|
| `skip` | Before an event starts (`announced`, `anchor`, `gap`): the phase reads `skipped` until the event ends |
| `unskip` | Undoes **skip** until the event starts |
| `abort` | During an event: the phase reads `aborted` until it ends. It can't be undone |
| `refresh` | Fetches the events now |

The three switches follow the phase. Turning one on or off by hand is put back at once, with a warning in the log.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Offer | Winter Credit Option (CPC-D) | Or Flex D (TPC-DPC), which has no anchor window |
| Your own switches | none | Optional: event switches (on during the event), pre-event switches (on for the set minutes before it), upcoming switches (on while an event is coming, until the pre-event period). They follow skip and abort too |
| Event hub variables | none | Optional DateTime hub variables set to the next event's start and end |
| Check every | 1 hour | How often the events are fetched |
| Test mode | off | Reads one made-up event, starting a set number of minutes after Done, from a local file |
