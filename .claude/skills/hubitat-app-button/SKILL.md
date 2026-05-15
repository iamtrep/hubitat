---
name: hubitat-app-button
description: Click a button-type preference on an installed Hubitat app (invokes the app's appButtonHandler), without UI clicks
argument-hint: "[@hubname] {installed_app_id} {button_name}"
allowed-tools: Bash, Read
---

# Hubitat App Button Skill

Closes the §2.4 "button-press helper" gap. Programmatically invokes an installed app's `appButtonHandler(String btn)` by POSTing to the `/installedapp/btn` endpoint — the same call the Hubitat UI makes when a user clicks a `button`-type preference on the configure page.

Two payoffs (per TESTING.md §2.4):

- **Mode 5 stress / diagnostic apps** can be started without a UI click, then paired with [`scripts/lib/logsocket.py`](../../../scripts/lib/logsocket.py) for full closed-loop test wrapping.
- **Behavior tests** (`/hubitat-behavior-test`) can dispatch buttons during provisioning (e.g. `btnNewGroup` on `SwitchMonitor` to create a group) or inside cases (e.g. trigger a "Run now" action).

The skill does **not** wait for or assert on the button's effects — pair with the logsocket helper or attribute checks in the caller if you need to verify what the button did.

## Endpoint

```
POST http://{hub_ip}/installedapp/btn
Content-Type: application/x-www-form-urlencoded

id={installed_app_id}
&name={button_name}
&settings[{button_name}]=clicked
&{button_name}.type=button
```

Returns `{"status":"success"}` on HTTP 200. The app's `appButtonHandler(String btn)` is invoked synchronously inside the request — any `log.*` calls it makes appear on `/logsocket` immediately.

Discovered from a HAR capture; not in any Hubitat documentation as of firmware 2.5.0.140.

## Instructions

Follow these steps exactly.

### Step 1: Read configuration

Read `.hubitat.json` from the project root.

1. If `$ARGUMENTS` starts with `@hubname`, use that hub and strip the prefix. Otherwise use `default_hub`.
2. Look up `hub_ip` from `hubs[hubname]`.
3. If the hub has `username` and `password` (non-null), authenticate first:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Add `-b /tmp/hubitat_cookies_{hubname}` to subsequent curl commands.

### Step 2: Parse remaining arguments

After stripping `@hubname`, `$ARGUMENTS` should contain `{installed_app_id} {button_name}` separated by a space.

- `{installed_app_id}` — numeric ID of the installed app instance. Find it via `/hub2/appsList` (match on `data.name` for the user-set label per the `hubitat_appslist_name_field` memory) or it can be passed directly from prior context.
- `{button_name}` — the `input "{name}", "button", ...` declaration name from the app's preferences. Must match what the app's `appButtonHandler` switches on.

If either is missing, report the usage and stop.

### Step 3: POST the click

```bash
curl -s -X POST "http://{hub_ip}/installedapp/btn" \
  --data-urlencode "id={installed_app_id}" \
  --data-urlencode "name={button_name}" \
  --data-urlencode "settings[{button_name}]=clicked" \
  --data-urlencode "{button_name}.type=button"
```

### Step 4: Report

Parse the response. On success (`{"status":"success"}`), report:

- App ID and button name clicked
- That the app's `appButtonHandler` was invoked
- A suggestion to inspect `/logsocket` or `/installedapp/eventsJson/{id}` for the effect, if the caller needs verification

On a non-200 response, report the HTTP status and body. Most failures are:

- `404` — `{installed_app_id}` does not exist
- `500` — the button name is not declared in the app's preferences, or the app's `appButtonHandler` threw

## Behavioral notes

- **No verification.** The skill fires the click and reports. If the button's effect is async (`runIn`, scheduled job) the caller's verification step must wait appropriately.
- **No event correlation.** If you need "click and observe", use [`scripts/lib/logsocket.py`](../../../scripts/lib/logsocket.py)'s `LogCapture` context manager around the click — capture starts → click → wait → `cap.matches(...)`.
- **Type metadata required.** Omitting `{button_name}.type=button` causes the hub to reject the click silently with HTTP 200 but no handler invocation. The HAR capture confirms `.type=button` is part of the canonical form.
- **`settings[{button_name}]=clicked` payload is required.** The literal string `clicked` is what the UI sends; other values may or may not work — match the UI exactly.
