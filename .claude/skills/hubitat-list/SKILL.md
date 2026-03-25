---
name: hubitat-list
description: List drivers, apps, and devices on the Hubitat hub
argument-hint: "[drivers|apps|devices|instances]"
allowed-tools: Bash, Read
---

# Hubitat List Skill

Discover and display resources on the Hubitat hub.

## Instructions

Follow these steps exactly:

### Step 1: Read Configuration

Read `.hubitat.json` from the project root. Parse the multi-hub config:

1. Check if `$ARGUMENTS` starts with `@hubname` (e.g., `@myhub drivers`). If so, use that hub name and strip the `@hubname` from arguments before further parsing. Otherwise, use `default_hub`.
2. Look up the hub in `hubs[hubname]` to get `hub_ip`.
3. If the hub has `username` and `password` (non-null), it has hub security enabled. Authenticate first:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Then add `-b /tmp/hubitat_cookies_{hubname}` to **all** subsequent curl commands for this hub.

### Step 2: Determine What to List

Check `$ARGUMENTS` (after stripping any `@hubname`) for what to list:

- `drivers` — list user-created drivers
- `apps` — list user-created app types
- `devices` — list all devices
- `instances` — list installed app instances
- (no argument) — show a summary of all categories

### Step 3: Query the Hub and Display Results

#### If `drivers`:

```bash
curl -s "http://{hub_ip}/hub2/userDeviceTypes"
```

Display as a table with columns: **ID**, **Name**, **Namespace**, **Devices** (count from `usedBy` array length).

#### If `apps`:

```bash
curl -s "http://{hub_ip}/hub2/userAppTypes"
```

Display as a table with columns: **ID**, **Name**, **Namespace**, **Instances** (count from `usedBy` array length).

#### If `devices`:

```bash
curl -s "http://{hub_ip}/hub2/devicesList"
```

Display as a table with columns: **ID**, **Name**, **Type** (driver name), **Status** (any key states like switch, motion, temperature).

#### If `instances`:

```bash
curl -s "http://{hub_ip}/hub2/appsList"
```

Display as a table with columns: **ID**, **Name**, **Type** (app name).

#### If no argument:

Query all four endpoints and display a summary:
- Number of user drivers
- Number of user app types
- Number of devices
- Number of installed app instances

### Formatting

- Use markdown tables for output
- Sort by name alphabetically
- For the `devices` listing, if there are many devices (>30), group them by driver type
- Keep the output concise — don't dump raw JSON
