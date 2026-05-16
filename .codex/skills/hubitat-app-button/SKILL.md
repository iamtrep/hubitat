---
name: hubitat-app-button
description: "Use when the user wants to click a button-type preference on an installed Hubitat app without using the UI. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, POST the same form fields the Hubitat UI sends to /installedapp/btn, and report the result without guessing at downstream effects."
---

# Hubitat App Button

Use this skill to invoke an installed app's `appButtonHandler(String btn)` through Hubitat's button endpoint.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Parse the remaining arguments as `{installed_app_id} {button_name}`.
5. POST to `/installedapp/btn` with:
   - `id={installed_app_id}`
   - `name={button_name}`
   - `settings[{button_name}]=clicked`
   - `{button_name}.type=button`
6. Parse the response and report success or the raw hub error.

## Notes

- This skill fires the button click only. It does not verify the app's follow-on behavior.
- If the caller needs verification, suggest checking `/logsocket`, app state, or downstream device attributes separately.
- Preserve the exact `clicked` payload and `.type=button` metadata; the endpoint may otherwise return HTTP 200 without actually invoking the handler.
