---
name: hubitat-delete
description: "Use when the user wants to delete a Hubitat installed app instance, app type, or driver type from the hub. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, resolve targets from a filepath or explicit ID form, enumerate dependents first, require explicit confirmation, and then call the matching Hubitat delete endpoint."
---

# Hubitat Delete

Use this skill for destructive Hubitat-side deletion of installed app instances, app types, or driver types.

## Safety contract

- Always list exactly what is about to be deleted before issuing the delete call.
- Always get explicit user confirmation before deleting.
- Never delete local files.
- Never delete devices with this skill.
- Do not cascade implicitly. Only delete dependent installed app instances if the user explicitly asks for that cascade.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Parse the target as one of:
   - local file path under `apps/` or `drivers/`
   - `apptype:{id}`
   - `driver:{id}`
   - `instance:{id}` or `installedapp:{id}`
5. Resolve the target from:
   - `/hub2/userAppTypes`
   - `/hub2/userDeviceTypes`
   - `/hub2/appsList`
6. Enumerate what will disappear, including any `usedBy` dependents.
7. Apply deletion rules:
   - installed app instance: allowed after confirmation
   - app type with no instances: allowed after confirmation
   - app type with instances: require explicit cascade confirmation, then delete instances first and the type second
   - driver type with attached devices: refuse and stop
8. Call the matching endpoint:
   - installed instance: `/installedapp/delete/{id}`
   - app type: `/app/edit/deleteJson/{id}`
   - driver type: `/driver/editor/deleteJson/{id}`
9. If any delete call fails, stop and report the hub response verbatim.

## Reporting

Summarize what was deleted, including IDs, names, dependents handled, and the target hub.
