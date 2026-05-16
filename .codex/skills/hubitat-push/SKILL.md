---
name: hubitat-push
description: "Use when the user wants to push a local Hubitat Groovy app or driver update to a hub and see compile status. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, resolve the existing code ID from the local definition name, fetch the current version, submit the updated source, and report compile errors or success."
---

# Hubitat Push

Use this skill to update existing Hubitat app or driver code from a local file.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Determine the source file:
   - explicit path if provided
   - otherwise the most recently modified `.groovy` file under `apps/` or `drivers/`
5. Determine app vs driver from the path.
6. Extract the `name` from the local `definition()` block.
7. Resolve the existing Hubitat code entry by name:
   - apps: `/hub2/userAppTypes`
   - drivers: `/hub2/userDeviceTypes`
8. If the code does not exist yet, use the `hubitat-install` skill instead of guessing how to create it.
9. Fetch the current version:
   - apps: `/app/ajax/code?id={id}`
   - drivers: `/driver/ajax/code?id={id}`
10. POST the update to:
   - apps: `/app/ajax/update`
   - drivers: `/driver/ajax/update`
   sending `id`, `version`, and the local source.
11. Parse the JSON response and report success or compilation errors.
12. Include the `usedBy` usage information from the earlier code lookup so the user can see what instances or devices are affected.

## Reporting

On success, report the name and new version.
On failure, show the hub's compile or validation error clearly and avoid hiding the message.
