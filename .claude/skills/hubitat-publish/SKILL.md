---
name: hubitat-publish
description: Publish a driver to other Hubitat hubs on the same account
argument-hint: "[driver name or ID]"
allowed-tools: Bash, Read, Glob, Grep
---

# Hubitat Publish Skill

Publish a driver's code to all other Hubitat hubs linked to the same account, and report distribution status.

## Instructions

Follow these steps exactly:

### Step 1: Read Configuration

Read `.hubitat.json` from the project root to get `hub_ip`.

### Step 2: Identify the Driver

Check `$ARGUMENTS`:

- **If a numeric ID** — use it directly as the driver ID.
- **If a name or filepath** — resolve it to a driver ID:
  1. Query `curl -s "http://{hub_ip}/hub2/userDeviceTypes"` to get the list of user drivers.
  2. If a filepath was given, extract the `name` from the file's `definition()` block and match it against the list.
  3. If a name was given, find the entry where `name` matches (case-insensitive).
  4. Get the `id` field from the matching entry.
- **If no argument** — find the most recently modified `.groovy` file in `drivers/` using `ls -t drivers/**/*.groovy 2>/dev/null | head -1`, extract its `name` from the `definition()` block, and resolve to a driver ID as above.

If no matching driver is found on the hub, report the error and stop.

### Step 3: Publish the Driver

Initiate publishing by calling:

```bash
curl -s "http://{hub_ip}/hub/publishCode/driver/{driverId}"
```

Note the path includes `driver` as the code type between `publishCode` and the ID.

Report the initial response to the user. The response is JSON like:
```json
{"success":true,"completed":false,"hubs":[{"id":"...","name":"Chalet","status":"Pending"},...]}"
```

### Step 4: Check Distribution Status

Poll the publish status endpoint:

```bash
curl -s "http://{hub_ip}/hub/publishCode/status"
```

The response is JSON:
```json
{"success":true,"completed":false,"hubs":[{"id":"...","name":"Chalet","status":"Pending"},{"id":"...","name":"Maison","status":"Done"}]}
```

- If `completed` is `false`, wait 2 seconds and poll again (up to 15 attempts).
- Once `completed` is `true` (or on error/timeout), report the final status.

### Step 5: Report Result

Summarize what happened:

- Driver name and ID that was published
- A markdown table of target hubs with columns: **Hub Name**, **Status**
- Any errors encountered
