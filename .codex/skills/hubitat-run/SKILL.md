---
name: hubitat-run
description: "Use when the user wants to send commands to Hubitat devices, inspect current device state, or discover which devices or app instances use a local Groovy file. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, use Maker API when configured, fall back to hub inventory endpoints when it is not, and report resulting device state changes."
---

# Hubitat Run

Use this skill to interact with Hubitat devices or to discover which devices or app instances are tied to local code.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and check whether Maker API `app_id` and `token` are configured.
4. If the hub has `username` and `password`, authenticate with `/login` and reuse the cookie jar.
5. Parse the request as one of:
   - `device_id command [arg ...]`
   - `device_id`
   - local `.groovy` file path

## File-path discovery mode

If the user supplied a local Groovy path:
- read the file
- extract the `name` from `definition()`
- infer app vs driver from the path
- query `/hub2/userDeviceTypes` or `/hub2/userAppTypes`
- report the `usedBy` list in a compact form
- if the user wants interaction afterward, continue with the selected device or app instance

## Device command mode

### If Maker API is configured

- Send commands via `/apps/api/{app_id}/devices/{device_id}/{command}?access_token={token}`.
- For commands with arguments, append each argument as an extra path segment after the command.
- For status reads, GET `/apps/api/{app_id}/devices/{device_id}?access_token={token}`.
- After sending a command, wait briefly and fetch status again to confirm the new state.

### If Maker API is not configured

- Explain that Maker API is required for command execution.
- Still provide useful fallback information from `/hub2/devicesList`.
- Give the direct hub UI link for the device.

## Reporting

Report:
- command sent
- target device ID and name
- resulting state after verification when possible
- if only discovery was requested, list the matching devices or app instances concisely
