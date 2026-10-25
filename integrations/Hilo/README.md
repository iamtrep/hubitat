<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Hilo Integration

*[Version française](README.fr.md)*

Unofficial parent/child integration for [Hilo](https://www.hiloenergie.com/), Hydro-Québec's home energy service. The manager app logs in to a Hilo account, reads one location's hourly consumption, and keeps the full history in File Manager. A child device shows the day's energy and, pushed by Hilo about once a minute, the whole home's power from the utility meter.

> **Note:** This integration is not affiliated with or endorsed by Hilo or Hydro-Québec. Report problems with it here, not to Hilo or Hydro-Québec.

## Components

| Component | Type | Description |
|---|---|---|
| **Hilo Manager** | App | Hilo login, hourly consumption read, history files in File Manager |
| **Hilo Energy Meter** | Driver | One device per location: energy today, yesterday and last hour, live power |
| **Hilo Winter Credit** | Driver | Optional, one device per location: the next peak event's target and the plan for the hours before it |
| `hilo-history.html` | Page | Charts the history files; served from File Manager |
| `hilo-winter-credit.html` | Page | Optional: each winter's peak events, targets and curtailment; served from File Manager |

## Installation

### 1. Add the code

In **Apps Code**, click **New App**, then **Import**, and paste:

```
https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloManager.groovy
```

Save. Do the same in **Drivers Code** (**New Driver** → **Import**) with:

```
https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloEnergyMeter.groovy
```

For the [winter credit targets](#winter-credit-targets), add this driver too:

```
https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloWinterCredit.groovy
```

### 2. Add the app

**Apps** → **Add User App** → **Hilo Manager**. The page has two steps:

![Hilo Manager login page: step 1, the Log in to Hilo link; step 2, the field for the pasted address](images/hilo-login-1-app.png)

### 3. Log in to Hilo

Click **Log in to Hilo**. Hilo's sign-in page opens in a new tab, at `connexion.hiloenergie.com`. Sign in with your Hilo email and password.

![Hilo's sign-in page, with connexion.hiloenergie.com in the address bar](images/hilo-login-2-hilo.png)

### 4. Copy the address of the page you land on

After the sign-in, the browser lands on a **my.home-assistant.io** page. It may offer to link the account to Home Assistant, or say the link is not set up. Ignore it and **don't click anything on it**: the login code is in the page's address, and that is all the app needs.

Copy the whole address from the browser's address bar:

![The my.home-assistant.io page: copy the whole address from the address bar; don't click Link account or Decline](images/hilo-login-3-copy-url.png)

### 5. Paste it

Back in the app, paste the address into step 2 and press Enter. The app logs in and reads the account's location; if the account has several, pick one. The app then creates the device and starts reading, without waiting for **Done**. Press **Done** to close the page.

The address works once. If the app reports a login error, click **Log in to Hilo** again for a new one.

The app is renamed "Hilo Manager - <location>"; change the name under **Name** on the app page. The device is named "Hilo <location>". It then reads the account's history into File Manager in the background (see [History files](#history-files)).

One instance serves one Hilo location. For a second account or location, add another instance. A location already served by another instance is refused.

## Language

The app page, the devices' event descriptions and settings, the notifications and both HTML pages are in French or English. **Langue / Language** at the top of the app page picks one; it starts in French. The devices follow it at once; the pages follow it when opened from the app page's buttons, or, opened directly, from `hilo-<location>-ui.json` in File Manager. Logs are always in English. Hubitat's own screens and buttons (**Done**, **File Manager**) stay in English.

## Login

Hilo has no login of its own for third-party apps, so the manager uses the public client of the Home Assistant integration. Its only registered return address is my.home-assistant.io, hence the paste step. No Home Assistant install or account is needed. my.home-assistant.io receives the login code in the page address, as it does for Home Assistant users, but the code is useless without the PKCE verifier that only the app holds.

The refresh token is stored encrypted and renewed with each use. The access token lasts an hour and is kept in memory only, so a reboot or code update costs one refresh. If Hilo refuses the saved login (for example after a password change, or about 14 days without the hub reaching Hilo), the manager logs an error, stops polling, marks the device offline, and shows the login link again.

**Log out of Hilo** forgets the tokens and stops polling and the live stream.

## Device

### Hilo Energy Meter

- `energy` — kWh used today, from midnight local time
- `energyYesterday` — kWh used yesterday, set once all 24 hours are in
- `energyLastHour` / `lastHourStart` — kWh used in the latest hour Hilo has reported, and when that hour started
- `power` — the home's power in W, pushed by Hilo about once a minute
- `healthStatus` — `online` / `offline`. The event description gives the reason.
- `lastUpdate` — time of the last consumption read

Hilo publishes an hour's consumption with a delay of an hour or more, so `energy` lags the meter. **Refresh** reads consumption again and reopens the live stream if it has gone quiet.

The **Live power from Hilo** preference (on by default) keeps a connection open to Hilo for `power`. Turn it off to keep only the hourly reads.

## Polling

Consumption is read once an hour, at a minute between :10 and :50 picked at random each time the app settings are saved, so hubs do not all call Hilo at once. The device's **Refresh** reads it at once.

When Hilo can't be reached, each of the first two failed reads logs a warning. The third logs one error and marks the device offline, and a reminder follows every hour while the outage lasts. The first good read logs the recovery and marks the device online. When Hilo answers with `Retry-After`, polling pauses for that long, up to an hour.

After a hub restart, the live stream first connects at a random point 1 to 3 minutes after boot. It is reopened with a fresh token every 50 minutes. When it drops or stays silent for 5 minutes, it reconnects after a delay that doubles from 5 s to 5 min. After 3 failed reconnects in a row, the device is marked offline until power arrives again.

## History files

The manager keeps every day's hourly consumption in File Manager, one file per month:

```
hilo-<location id>-energy-YYYY-MM.json
```

```json
{
  "unit": "kWh",
  "location": "<location id>",
  "days": {
    "2026-10-20": [ { "start": "2026-10-20T00:00:00-04:00", "kWh": 1.512 }, ... ]
  }
}
```

A day is written once all its hours are in. An hour Hilo has no value for is `null`.

On first setup the manager finds where the account's history starts, then reads it into the files one day a minute, so a year takes about six hours. The app page shows how many days are left.

### Browsing the history

`hilo-history.html` is a page that charts the files: daily totals, each day's hours, the average day by hour with the peak-event windows (6–9 and 16–20) marked, and a month table. Upload it to File Manager (**Settings → File Manager**); the app page then shows a **Browse the history** link, or open `http://<hub>/local/hilo-history.html`. It reads the files from the hub it is served by. With internet access it also overlays the hourly outdoor temperature at the hub's location (Settings → Hub Details), taken from [Open-Meteo](https://open-meteo.com/en/docs/historical-weather-api)'s historical model data, and adds a chart of daily use against mean outdoor temperature. It also marks Hydro-Québec's actual peak events, from the utility's [open data](https://donnees.hydroquebec.com/explore/dataset/evenements-pointe/) for the residential winter credit (offer CPC-D, the feed the Hydro Peak Events app uses): a mark on each event day, the event's hours shaded in purple on that day's chart, event days coloured in the temperature chart, and the average day drawn separately for event days. The 6–9 and 16–20 windows are shaded on days from December 1 to March 31. Drag across days on the daily chart to zoom every chart and table into that range; **Zoom out** (or Esc) goes back one step, and picking a month from the table is a step too. Without internet access the page shows the energy charts alone. From December to March, and on event days, a day's chart links to the same day on the [winter credit page](#winter-credit-page).

Read a file from another app or a script with `downloadHubFile`, or over HTTP from `http://<hub>/local/<file name>`.

## Winter credit targets

For a home on Hydro-Québec's Rate D with the Winter Credit Option. During a peak event the credit pays for every kWh below a target: Hydro-Québec's *reference energy*, the Hilo app's "limite de consommation". Device attributes and settings say *target*; the winter credit page uses Hydro-Québec's terms. Hydro-Québec doesn't show the target before the event, and past 40 kWh curtailed it can drop. The manager computes it from the history ahead of time.

### The rule

From Hydro-Québec's Electricity Rates, chapter 2, article 2.59:

- **Base:** for each of the event's 4 hours, the mean of that hour on the 5 previous days of the same kind (weekdays, or weekend days) without a peak event, leaving out the lowest and the highest value. These days are the *reference period*. Days without peak hours (December 24, 25, 26 and 31, January 1 and 2, Good Friday, Easter Monday) are not reference days.
- **Anchor window** (Hydro-Québec's *période d'ancrage*): 01:00–04:00 before a morning event, 12:00–14:00 before an evening one. Its reference is computed the same way.
- **Target** (*reference energy*; the Hilo app's "original consumption limit") = base + (anchor-window use − anchor reference) × 4/3 for a morning event, × 2 for an evening one. The second term is the *temperature adjustment*.
- **Cap:** when the *energy curtailed* exceeds 40 kWh, the temperature adjustment is *limited* to 2 × the anchor reference × the same factor. The target then becomes the **capped target** (the Hilo app's "adjusted consumption limit"), base + 2 × anchor reference × the factor, when that is lower. It is known the evening before; the uncapped target only once the anchor window is over.

This reproduces Hydro-Québec's targets for winter 2025–26 to 0.1 kWh, the event's own anchor-window use aside, where Hilo's hourly values and Hydro-Québec's can differ by about a kWh.

Everything but the anchor-window use is known the evening before, so the plan is too:

- **Capped target at least 40 kWh above what the home uses with the heating cut** (the *floor*): cut as deep as possible during the event. Anchor-window use of at least 3 × its reference lifts the target to the capped target; more changes nothing.
- **Capped target lower than that:** use **at most** the anchor budget in the anchor window, which sets the target at floor + 40, and do the rest of the pre-heat after it (04:00–06:00 or 14:00–16:00 count for nothing). Cutting deep then curtails exactly 40 kWh.

### Settings

Under **Winter credit settings** on the app page:

- **Compute the reference energy of each peak event the evening before** turns the feature on and creates the Hilo Winter Credit device.
- **kWh the home uses during a 4-hour event with the heating cut**: the floor, 3 kWh by default.
- **Notify** and **Evening notification at** (22:00 by default): the evening before an event, a notification with the reference energy with the limit and the plan for the anchor period. With **Notify every winter evening**, one every evening from December to March, as if the next morning were an event.
- **Events missing from Hydro-Québec's open data**: the open data has missed an event; add it as `YYYY-MM-DD AM` or `PM`, comma-separated. A missing past event changes the reference days.
- **Compute now** computes at once, in any season.

Peak events come from Hydro-Québec's [open data](https://donnees.hydroquebec.com/explore/dataset/evenements-pointe/) (offer CPC-D), read twice a day at the same minute, between 15:15 and 15:29 and between 21:15 and 21:29. The windows from yesterday morning to tomorrow evening are recomputed after each hourly read, all year, and a minute after each event window ends. A minute after each anchor window ends on an event day, the manager recomputes with that window's use and notifies with the reference energy and what pays: cutting to the floor, or consuming just enough to curtail exactly 40 kWh when the limit would otherwise cut a deep curtailment below 40 kWh. 

The app page's **Winter credit** card lists yesterday's and today's finished windows with their result (energy used against the reference energy, energy curtailed, credit), then the current window and the next. The current one shows what to do now: the plan, the anchor period's count against it, the advice until the event starts, then the event's count with a projection at the current power. A window with no announced event is a simulation, in grey; an announced event's day gets an amber banner with a countdown. Results stay provisional until Hilo's hourly values for the event are in. Off season (April to November) the card says so. The power, the counts and the countdowns update live while the page is open, from the hub's event socket.

### Hilo Winter Credit device

Shows the next announced event, or the current or next window as if it were one, and the last finished window's result:

- `eventDate`, `window` (`AM` / `PM`), `announced` (`true` / `false`)
- `base`, `anchorReference`, `cappedTarget` — kWh
- `anchorPlan` (`at most` / `at least`) and `anchorTarget` — kWh to use in the anchor window
- `expectedCurtailment` — kWh curtailed if the plan is followed and the event is cut to the floor
- `anchorUsed` / `anchorWindow` — the anchor window's energy so far, counted from the live power, during 01:00–04:00 and 12:00–14:00
- `eventUsed` — the event window's energy so far, counted the same way, during 06:00–10:00 and 16:00–20:00
- `target`, `advice` (`pending` / `cut` / `keep`), `keepUse` — once the anchor window is over. `keep` means using `keepUse` kWh during the event keeps 40 kWh curtailed and pays more than cutting to the floor.
- `referenceDays`, `summary`
- `lastWindow` (`YYYY-MM-DD AM` / `PM`), `lastAnnounced` (`true` / `false`), `lastConsumed`, `lastCurtailed` — kWh, `lastCredit` — $, `lastFinal` (`false` while it uses the live count instead of Hilo's hourly values)

A rule can stop pre-heating when `anchorPlan` is `at most` and `anchorUsed` reaches `anchorTarget`. **Refresh** recomputes.

The last computation, inputs included, is in File Manager as `hilo-<location id>-peak-target.json`.

### Winter credit page

`hilo-winter-credit.html` shows one winter (December to March) at a time, in the terms of Hydro-Québec's Electricity Rates (article 2.59). With internet access it adds the hourly outdoor temperature at the hub's location from Open-Meteo, as the history page does: under each date of the calendar, as a line on the day's chart, and as the mean of the anchor and event hours on each reference day and on the event day, so a temperature adjustment can be checked against the weather. Upload it to File Manager next to `hilo-history.html`; the app page then shows a **Winter credit analysis** link under **Winter credit**, or open `http://<hub>/local/hilo-winter-credit.html`. It needs internet access for Hydro-Québec's event list, and adds the events entered under **Events missing from Hydro-Québec's open data**.

- Tiles: the winter's peak events, the energy curtailed, the events where the temperature adjustment was limited, and the credit before taxes, at the rate in effect on each event's date (none shown before April 2025). Only events with every hour on file count.
- A calendar of the winter with the events marked. Click a day for:
  - its hours: the anchor period (01:00–04:00 or 12:00–14:00) with each hour's reference-period average and the consumption above it, which raises the reference energy; the event hours with each hour's share of the reference energy and the energy curtailed under it;
  - the energy curtailed and the credit, and whether the limit on the temperature adjustment applied;
  - a table of three choices for the event's consumption (cut to the floor, as consumed, just enough to curtail exactly 40 kWh), each with its energy curtailed, credit, the cost of the energy consumed above the floor, the credit minus that cost, and how much less or more that is than cutting to the floor;
  - the event hour by hour, summed: consumption against the reference energy, with and without the limit;
  - a flow chart: the reference period and the temperature adjustment make the reference energy, which splits into consumption and energy curtailed; the part of the adjustment over the limit is shown apart;
  - under **How Hydro-Québec computes it**, the arithmetic and the reference period: each hour's values with the minimum and maximum struck out.

  A day without an event is computed as if it had one; pick the morning or evening event above the chart.
- The energy curtailed at each event, against 40 kWh, and a table of every event with its reference energy, the reference energy with the limit, the consumption and the energy curtailed.

**Open in history** shows the same day on the history page. The address keeps the day and event (`#day=2026-01-24&start=6`), so a link or bookmark opens on them.

## Credits

The client id, scope and API subscription key are those used by [python-hilo](https://github.com/dvd-dev/python-hilo) (MIT), the library behind the Home Assistant Hilo integration. No source is incorporated.

## License

MIT — see individual source files for the full license text.
