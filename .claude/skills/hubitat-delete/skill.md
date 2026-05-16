---
name: hubitat-delete
description: Delete a Hubitat installed app instance, app type, or driver type from the hub, with mandatory confirmation
argument-hint: "[filepath | apptype:N | driver:N | instance:N] [@hubname]"
allowed-tools: Bash, Read, Glob, Grep
---


<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Delete Skill

Permanently delete an installed app instance, an app type (optionally cascading through its instances), or a driver type from the hub. Refuses to delete code with dependents unless the user explicitly opts into cascade. Never deletes local Groovy files — that's `git rm`'s job, not this skill's.

Device deletion is intentionally out of scope.

## Safety contract

This skill is destructive on the hub. Always:

- **List exactly what is about to disappear** (target name, ID, dependents) before deleting.
- **Ask the user to confirm** before issuing any delete call. Do not proceed on silence.
- **No bulk delete**, no `--all`, no implicit cascade. Cascade only when the user opts in.
- **Local filesystem is untouched.**

## Instructions

### Step 1: Read Configuration

Read `.hubitat.json` from the project root. Parse `$ARGUMENTS`:

1. If `$ARGUMENTS` contains `@hubname` (e.g., `@maison-pro` or `apptype:595 @maison`), use that hub and strip it. Otherwise, use `default_hub`.
2. Look up `hubs[hubname]` for `hub_ip`.
3. If `username`/`password` are present (hub security enabled), authenticate first:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Pass `-b /tmp/hubitat_cookies_{hubname}` on all subsequent curl calls.

### Step 2: Identify the Target

Four input forms (parse `$ARGUMENTS` after stripping `@hubname`):

- **Filepath** (e.g. `apps/sensors/Foo.groovy` or `drivers/foo.groovy`): read the file, extract the `name:` value from the `definition()` block. If the path contains `apps/`, look up in `/hub2/userAppTypes`; if it contains `drivers/`, look up in `/hub2/userDeviceTypes`. Target = the matching entry.
- **`apptype:N`**: target = app type ID `N`.
- **`driver:N`**: target = driver type ID `N`.
- **`instance:N`** (alias: `installedapp:N`): target = installed app instance ID `N`.

If the lookup fails (e.g. filepath name not found on the hub), stop and tell the user — don't guess.

### Step 3: Enumerate What Will Disappear

**Installed instance** — fetch `/hub2/appsList`, find the entry with the matching `id`, then print:

```
About to delete installed app instance on {hubname} ({hub_ip}):
  ID:    {id}
  Label: {label}
  Type:  {appTypeName}
```

**App type** — from `/hub2/userAppTypes`, find the entry, then print:

```
About to delete app type on {hubname} ({hub_ip}):
  ID:        {id}
  Name:      {name}
  Namespace: {namespace}
  Used by:   {N} installed instance(s)
    - {instance_id}: {instance_label}
    - ...
```

**Driver type** — from `/hub2/userDeviceTypes`, find the entry, then print:

```
About to delete driver type on {hubname} ({hub_ip}):
  ID:        {id}
  Name:      {name}
  Namespace: {namespace}
  Used by:   {N} device(s)
    - {device_id}: {device_name}
    - ...
```

### Step 4: Confirm With the User

Always ask before deletion. Wait for an explicit yes.

- **Instance**: "Delete installed instance `{label}` (ID {id}) on {hubname}?"
- **App type with no instances**: "Delete app type `{name}` (ID {id}) on {hubname}?"
- **App type with instances** — the hub will reject deletion while instances exist. Offer two choices:
  1. **Cascade**: delete every instance first, then the app type.
  2. **Abort**.
  Do not offer "delete type only" — it will fail.
- **Driver type with no devices**: "Delete driver type `{name}` (ID {id}) on {hubname}?"
- **Driver type with devices** — refuse. Print: "Driver `{name}` is in use by {N} device(s). Remove or reassign those devices first; this skill won't delete devices." Then abort. (Device deletion is out of scope; no cascade option here.)

### Step 5: Delete

- **Installed instance**: `GET /installedapp/delete/{installedAppId}` → expect `{"success":true,"message":null}`.
- **App type**: `GET /app/edit/deleteJson/{appTypeId}` → expect `{"status":true}`.
- **App type with cascade**: loop the installed-instance delete for each entry in `usedBy`, verify each `{"success":true}`, then delete the app type.
- **Driver type**: `GET /driver/editor/deleteJson/{driverTypeId}` → expect `{"status":true}`.

If any call returns a non-success body or non-2xx HTTP, stop and report the response verbatim. Do not retry blindly.

### Step 6: Report

Summarize what was deleted, with IDs and names, on which hub. Do not edit any local files.

## Out of scope (by design)

- **Device deletion** (physical or virtual). Intentionally not automated. If a user targets a device, refuse with: "device deletion is not in scope — remove the device via the Hubitat UI."

## Endpoints reference

| Action | Method | Endpoint | Success body |
|---|---|---|---|
| List app types | GET | `/hub2/userAppTypes` | JSON array (each has `id`, `name`, `namespace`, `usedBy`) |
| List driver types | GET | `/hub2/userDeviceTypes` | JSON array (each has `id`, `name`, `namespace`, `usedBy`) |
| List installed app instances | GET | `/hub2/appsList` | JSON array |
| Delete installed app instance | GET | `/installedapp/delete/{id}` | `{"success":true,"message":null}` |
| Delete app type | GET | `/app/edit/deleteJson/{id}` | `{"status":true}` |
| Delete driver type | GET | `/driver/editor/deleteJson/{id}` | `{"status":true}` |
