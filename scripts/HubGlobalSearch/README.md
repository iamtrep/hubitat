<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Hub Global Search

A userscript that adds one search box to every page of the Hubitat admin UI. Press Ctrl+K (Cmd+K on a Mac) or click **Search** at the bottom right, then type. Results are grouped as devices, rooms, dashboards, automations, integrations, apps, app code, driver code, libraries and bundles. Arrow keys move through results, Enter opens one, Ctrl/Cmd+Enter opens it in a new tab, Escape closes.

Matching is case- and accent-insensitive, and every word must match ("ch maitres stores" finds "Stores Ch Maîtres"). A device matches on its name, label, driver and room; an app on its name, type and parent app.

## Install

1. Install [Tampermonkey](https://www.tampermonkey.net/) or [Violentmonkey](https://violentmonkey.github.io/).
2. In Chrome, open `chrome://extensions`, then the extension's **Details**, and turn on **Allow User Scripts**.
3. Open the [raw script](https://raw.githubusercontent.com/iamtrep/hubitat/main/scripts/HubGlobalSearch/hub-global-search.user.js) and click **Install**. The extension checks that address for updates.

The script loads on every site but stops at once unless the page is a Hubitat admin page, so any hub address works without editing.

With hub security on, log in to the hub as usual. The script uses the browser's session.

## How it works

It reads `/hub2/devicesList`, `/hub2/appsList`, `/room/listRoomsJson`, `/hub2/userAppTypes`, `/hub2/userDeviceTypes`, `/hub2/userLibraries` and `/hub2/userBundles` (firmware 2.5.2.129). Some of these take several seconds on a large hub, so the index is kept in `sessionStorage` and refreshed in the background when opened more than 5 minutes after the last load. Results appear as each list arrives.

It does not search inside rules or app settings. Room results open the Rooms page.
