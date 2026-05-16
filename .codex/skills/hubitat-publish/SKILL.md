---
name: hubitat-publish
description: "Use when the user wants to publish a Hubitat app or driver from one hub to other hubs on the same account. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, resolve the target by ID, file path, or name, call Hubitat publish endpoints, poll status, and report per-hub distribution results."
---

# Hubitat Publish

Use this skill to distribute Hubitat app or driver code to linked hubs on the same account.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Resolve the target code and type:
   - numeric ID: search both app and driver lists to determine type
   - file path: infer app vs driver from the path and extract `name` from `definition()`
   - plain name: search both user app types and user device types case-insensitively
   - no argument: default to the most recently modified local `.groovy` file under `apps/` or `drivers/`
5. If no match is found on the hub, report the error and stop.
6. Start publishing with `/hub/publishCode/{type}/{id}` where type is `app` or `driver`.
7. Poll `/hub/publishCode/status` every 2 seconds up to 15 attempts, or until `completed` is true.

## Reporting

Report:
- published name, type, and ID
- a markdown table of target hubs and publish status
- any timeout or hub-reported error
