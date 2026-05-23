---
name: hubitat-run
description: Send commands to Hubitat devices or interact with app instances
argument-hint: "[device_id] [command] or [filepath]"
allowed-tools: Bash, Read, Glob, Grep
---


<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Run Skill

Send commands to devices or interact with app instances on the Hubitat hub.

## Instructions

Follow these steps exactly:

### Step 1: Read Configuration

Read `.hubitat.json` from the project root. Parse the multi-hub config:

1. Check if `$ARGUMENTS` starts with `@hubname` (e.g., `@myhub 42 on`). If so, use that hub name and strip the `@hubname` from arguments before further parsing. Otherwise, use `default_hub`.
2. Look up the hub in `hubs[hubname]` to get `hub_ip` and check if `maker_api` credentials are configured (both `app_id` and `token` must be non-null).
3. If the hub has `username` and `password` (non-null), it has hub security enabled. Authenticate first:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Then add `-b /tmp/hubitat_cookies_{hubname}` to **all** subsequent curl commands for this hub.

### Step 2: Parse Arguments

`$ARGUMENTS` (after stripping any `@hubname`) can be one of:

1. **`{device_id} {command}`** — a numeric device ID and a command to send (e.g., `42 on`, `42 off`, `42 setLevel 50`)
2. **`{device_id}`** — just a device ID to show its current status
3. **`{filepath}`** — a `.groovy` filename to discover which devices/apps use it
4. **No arguments** — prompt the user for what they want to do

### Step 3: Smart Discovery (if filepath given)

If the argument looks like a file path (contains `/` or ends in `.groovy`):

1. Read the file and extract the `name` from the `definition()` block
2. Determine if it's an app or driver (based on `apps/` or `drivers/` in path)
3. Query the hub:
   - **Drivers**: `curl -s "http://{hub_ip}/hub2/userDeviceTypes"` → find by name → get `usedBy` list
   - **Apps**: `curl -s "http://{hub_ip}/hub2/userAppTypes"` → find by name → get `usedBy` list
4. Display the list of devices/instances using this code with their IDs and names
5. Ask the user which device they want to interact with

### Step 4: Execute Command

#### If Maker API is configured:

**To send a command:**
```bash
curl -s "http://{hub_ip}/apps/api/{app_id}/devices/{device_id}/{command}?access_token={token}"
```

For commands with arguments (e.g., `setLevel 50`), the format is:
```bash
curl -s "http://{hub_ip}/apps/api/{app_id}/devices/{device_id}/{command}/{value}?access_token={token}"
```

**To get device status:**
```bash
curl -s "http://{hub_ip}/apps/api/{app_id}/devices/{device_id}?access_token={token}"
```

Display the device's current attributes in a readable format.

#### If Maker API is NOT configured:

You can still send commands via the internal admin endpoint `POST /device/runmethod` — no token or Maker API setup required:

```bash
# no-arg command (e.g. "42 refresh")
curl -s -X POST "http://{hub_ip}/device/runmethod" -H "Content-Type: application/json" \
  -d '{"id":{device_id},"method":"{command}","args":[]}'
# command with arguments (e.g. "42 setLevel 50") — one {type,value} per parameter;
# get each parameter's type from GET /device/fullJson/{device_id} -> device…commands[].parameters[]
curl -s -X POST "http://{hub_ip}/device/runmethod" -H "Content-Type: application/json" \
  -d '{"id":{device_id},"method":"setLevel","args":[{"type":"NUMBER","value":50}]}'
```

Response is `{"success":true,"message":null}`. The command runs **async**, so read state back from:
```bash
curl -s "http://{hub_ip}/device/fullJson/{device_id}"
```
Current attribute values are under `device.currentStates[].value` — poll until the value changes rather than reading once.

**Caveat:** `/device/runmethod` is the web-UI invocation channel; its script-instance/binding lifecycle could differ from app- or Maker-API-driven calls. For production-representative testing — anything sensitive to cross-invocation state — prefer Maker API instead: install "Maker API", add the device, and put `app_id` + `token` in `.hubitat.json`. Endpoint details in [`docs/hubitat-internal-apis.md`](../../../docs/hubitat-internal-apis.md).

### Step 5: Report Result

After sending a command:

1. Wait 1 second for the device to process
2. Query the device status again to confirm the state changed
3. Report the result: what command was sent, what the device's new state is

### Step 6: App Instances

For interacting with app instances (if the user specifies an app):

- Provide the configuration link: `http://{hub_ip}/installedapp/configure/{instance_id}`
- Show the app's current status from the hub's app list
