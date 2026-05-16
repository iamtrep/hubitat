---
name: hubitat-list
description: "Use when the user wants concise listings or summaries of Hubitat user drivers, user app types, devices, or installed app instances. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, query the Hubitat inventory endpoints, and format compact markdown tables instead of raw JSON."
---

# Hubitat List

Use this skill to discover resources on a Hubitat hub.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Parse the request as one of:
   - `drivers`
   - `apps`
   - `devices`
   - `instances`
   - no category, meaning summary mode
5. Query the matching endpoint:
   - drivers: `/hub2/userDeviceTypes`
   - apps: `/hub2/userAppTypes`
   - devices: `/hub2/devicesList`
   - instances: `/hub2/appsList`

## Formatting

- Use markdown tables.
- Sort by name where practical.
- Do not dump raw JSON.
- For drivers, show ID, Name, Namespace, and device usage count from `usedBy`.
- For apps, show ID, Name, Namespace, and instance count from `usedBy`.
- For devices, show ID, Name, driver type, and a compact status summary from key current states.
- For installed app instances, show ID, Name, and Type.
- If no specific category was requested, provide counts for all four categories.
