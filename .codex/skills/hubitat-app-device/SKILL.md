---
name: hubitat-app-device
description: "Use when working with Hubitat app instances that manage selected devices, especially to list, add, or remove device IDs from an installed app such as Maker API. Read .hubitat.json from the repo root, support optional @hubname prefixes, handle hub login cookies when needed, inspect the installed app's rendered config, and update its selected device list through the Hubitat JSON config endpoint without clobbering unrelated settings."
---

# Hubitat App Device

Use this skill to inspect or modify the selected device list for an installed Hubitat app instance.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. If none is given, use `default_hub`.
3. Resolve `hub_ip` and, for Maker API defaults, `maker_api.app_id`.
4. If the hub config has `username` and `password`, authenticate first with `/login` and reuse the cookie jar.
5. Parse the requested operation:
   - `list [installed_app_id]`
   - `add device_id [installed_app_id]`
   - `remove device_id [installed_app_id]`
   If no app ID is supplied, default to `maker_api.app_id`.
6. Fetch the app config from `/installedapp/configure/json/{installedAppId}`.
7. Extract:
   - `app.version`
   - `app.label`
   - `configPage.name`
   - all input definitions from `configPage.sections[].input[]`
   - label inputs from `configPage.sections[].body[]` where `element == "label"`
   - current `settings`
   - the device input whose type starts with `capability.`
8. For `list`, display the current selected devices and stop.
9. For `add` or `remove`, validate membership first.
10. Rebuild the form POST for `/installedapp/update/json`.

## Update rules

- Preserve all existing settings, not just the device list.
- For each rendered input, send `{name}.type` and `{name}.multiple` when applicable.
- For bool inputs, include `checkbox[{name}] = on` only when the current value is truthy.
- For label inputs, send the label value from `app.label`.
- For the device input, send `settings[{name}]` as a comma-separated device ID list.
- Also send `deviceList` set to the device input name and include the empty-string field Hubitat expects.
- Include the usual form fields such as `_action_update=Done`, `formAction=update`, `currentPage`, `version`, `referrer`, `url`, and `_cancellable=false`.

## Data conversions

- String setting: send as-is.
- Device map setting: send its keys joined by commas.
- Null setting: avoid inventing values; preserve the current unset state unless the target app requires a default from the rendered input definition.

## Verification

After an update, fetch the config again and confirm the device ID was added or removed from the device input map.

## Reporting

Report:
- action performed
- device ID and name if available
- target app name and installed app ID
- resulting device list in a compact table
