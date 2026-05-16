---
name: hubitat-install
description: "Use when the user wants to create and install a Hubitat app or driver from a local Groovy file. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, resolve app or driver type from the file path, create code on the hub if missing, and install a new app instance when applicable."
---

# Hubitat Install

Use this skill to create Hubitat code from a local Groovy source file and install it on the hub.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Determine the source file:
   - explicit path if provided
   - otherwise the most recently modified `.groovy` file under `apps/` or `drivers/`
5. Determine app vs driver from the path.
6. Read the source and extract the `name` from the `definition()` block.
7. Check whether that code already exists on the hub:
   - apps: `/hub2/userAppTypes`
   - drivers: `/hub2/userDeviceTypes`
8. If it already exists, do not blindly recreate it. Report the existing ID and avoid duplicating code unless the user explicitly wants a different path.
9. If it does not exist, create it by sending JSON `{"source": source, "version": 1}` to:
   - `/app/saveOrUpdateJson`
   - `/driver/saveOrUpdateJson`
10. For apps, create an installed instance via `/installedapp/create/{appTypeId}` and report the resulting configure URL.
11. For drivers, report that the driver is now available for device assignment and provide the device creation URL.

## Reporting

Report:
- app or driver name
- whether it was created or already existed
- hub code ID
- for apps, the installed app instance ID and configure URL
- for drivers, the device creation URL
