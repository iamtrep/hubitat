<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Season Manager

Publishes the heating and cooling season on a device, from dates and the outdoor temperature.

Season Manager decides which of four seasons it is (`winter`, `spring`, `summer`, `fall`) and publishes it on its own device, with a second flag for a winter credit period. Rules, dashboards and other apps read the device; nothing else needs to know how the season was decided. It knows nothing about heating equipment: what each piece of equipment may do in each season belongs to whatever reads the device.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `SeasonManager.groovy` | App | Season Manager |
| `SeasonManagerSeason.groovy` | Driver | Season Manager Season |

## Install

1. Add the app in **Apps Code** and the driver in **Drivers Code**.
2. In **Apps**, choose **Add user app** and pick **Season Manager**.
3. Pick the outdoor temperature sensor if you have one, then press **Done**. The app sets the starting season from the date, or from Open-Meteo's daily means inside a window where the weather decides. It asks only when neither works.

Saving creates the device **"Season"**. If you rename the app, the device is named "<app name> Season" instead, so a second instance does not create a second "Season". The device belongs to the app and is deleted with it.

## How the season changes

The year has two boundaries, and they behave differently.

**The winter boundary changes once a year each way.** Winter becomes spring inside a window of dates, as soon as the 3-day mean outdoor temperature is above a threshold, and on the window's last day at the latest. Fall becomes winter the same way, when the mean is below a threshold. Neither change goes back until the next year. This suits heating with a large thermal mass, such as a hydronic floor, which should not start and stop with each warm or cold spell.

**The summer boundary follows the weather both ways.** Between *summer possible from* and *summer possible until*, the season enters summer when the 3-day mean is above the entry threshold and leaves it when the mean is below the exit threshold, as often as the weather requires. There is no forced start: a cold June stays in spring. Summer always ends on its *until* date. Outside summer, the season is `spring` before the *fall from* date and `fall` from that date on.

A cold spell after summer starts is the costly mistake, since summer usually means the heat is off. Following the weather in and out of summer avoids it; a band of a few degrees between the two thresholds keeps the season from changing every day.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Use Open-Meteo daily means | on | Needs internet and the hub's location |
| Outdoor temperature sensor | | Optional; used on days Open-Meteo has no mean |
| Winter to spring, from / until | 03-11 / 04-08 | `MM-DD`; *until* is the last possible day |
| Winter to spring threshold | 3 °C | 3-day mean above it |
| Fall to winter, from / until | 10-26 / 11-11 | `MM-DD`; *until* is the last possible day |
| Fall to winter threshold | 3 °C | 3-day mean below it |
| Summer possible from / until | 05-10 / 09-14 | Summer ends on *until* |
| Fall from | 08-15 | Outside summer, spring becomes fall on this day |
| Enter summer threshold | 17 °C | 3-day mean above it |
| Leave summer threshold | 12 °C | 3-day mean below it; must be below the entry threshold |
| Winter credit on from / until | 12-01 / 03-31 | Both days included |
| Hub variable mirror | off | See below |

Temperatures use the hub's scale; on a °F hub the defaults are converted. The dates must follow each other through the year in this order: the winter to spring window, *summer possible from*, *fall from*, *summer possible until*, the fall to winter window. The page shows an error and the season stops changing until they do.

## Fixed behavior

These are not settings:

- Daily means come from the Open-Meteo archive at the hub's location, rounded to 0.1°. This is the data the default thresholds were calibrated on. The app logs a warning once while Open-Meteo is unavailable.
- On a day Open-Meteo has no mean, the sensor's mean is used, corrected by its mean difference from Open-Meteo over the last 7 days that have both. The sensor is read every hour, and a day's mean needs at least 12 readings. Readings are kept for 10 days.
- The season is checked once a day at 05:00, against the mean of the three previous daily means.
- A sensor reading older than 24 hours is not counted, and the app logs a warning once until the sensor reports again. Without a 3-day mean, only the date limits apply: a winter boundary change happens on its window's last day, and summer ends on its *until* date.
- The season changes at most once per day.
- If the hub was off for some days, the next daily check evaluates each missed day in order, up to 120 days back.
- The winter credit flag is updated just after midnight.
- On a hub restart nothing is recomputed: the season stays what it was.

## The Season device

| Attribute | Values |
|---|---|
| `season` | `winter`, `spring`, `summer`, `fall` |
| `winterCredit` | `on`, `off` |

| Command | What it does |
|---|---|
| `setSeason(season, holdDays)` | Sets the season at once and suspends automatic changes for `holdDays` days (default 3, 0 for none). When the hold ends, automatic changes continue from the season you set. |
| `resumeAuto()` | Ends the hold. The next daily check applies the rules. |

The same controls are on the app page, with the current season, the last three daily means and where each came from, the sensor's offset, and the next possible change.

## Outlook

Each morning the app also fetches Open-Meteo's 7-day forecast of daily means and runs the same rules forward from the current season. The app page lists the forecast means and every change the rules would make, such as "Likely: winter on Oct 27 (3-day mean 1.7 °C)". A change whose 3-day mean is within 1 °C of its threshold reads "Possible" instead. Changes forced by a date are listed with their reason. The outlook never changes the season.

## Hub variable mirror

For rules that still read a hub variable, the app can copy the season to a String variable, with your own label for each season, and the winter credit flag to a Boolean variable. It marks those variables as in use and follows a rename. Turn the mirror off once nothing reads them.

## Choosing thresholds

The defaults suit a Montréal-area climate and come from the 2005 to 2025 daily means: each window spans the 10th to 90th percentile of the day its threshold was first crossed. At a colder or higher site, lower the summer entry; where the summer normal peaks around 18 °C, an entry of 15 °C and an exit of 12 °C work. Keep the exit at least 3 °C below the entry. A narrower band makes the season change more often for no benefit.

## Tests

- `tests/test_core.groovy`: unit tests of the date arithmetic, validation, transition rules, daily means, Open-Meteo parsing (against a saved response in `tests/fixtures/`), the replay and the outlook, run off the hub under Groovy 2.4.21: `java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain tests/test_core.groovy`.
- `tests/test-season.sh`: behavior test on a hub, generated from `tests/spec-season.yaml`. It drives a test instance through a test day and a test mean (shown on the app page while debug logging is on) and checks the device: `bash tests/test-season.sh [@hub]`.
