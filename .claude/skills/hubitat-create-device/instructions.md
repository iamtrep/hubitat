# Hubitat Create Device Skill

Create a virtual device on the Hubitat hub using a specified driver.

## Instructions

### Step 1: Read Configuration

Read `.hubitat.json` from the project root. Parse the multi-hub config:

1. Check if arguments start with `@hubname` (e.g., `@chalet "Test Switch" 2216`). If so, use that hub name and strip the `@hubname` from arguments. Otherwise, use `default_hub`.
2. Look up the hub in `hubs[hubname]` to get `hub_ip`.
3. If the hub has `username` and `password` (non-null), authenticate:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Then add `-b /tmp/hubitat_cookies_{hubname}` to all subsequent curl commands.

### Step 2: Parse Arguments

Parse the remaining arguments:

- **`"Device Name" {driverTypeId}`** — create a device with the given name and driver type ID
- **`"Device Name" "Driver Name"`** — look up the driver by name, then create
- **`"Device Name" {driverTypeId} {deviceNetworkId}`** — also specify a custom DNI

If no DNI is provided, generate one from the device name: uppercase, spaces replaced with underscores (e.g., `"Test Switch A"` → `TEST_SWITCH_A`).

### Step 3: Look Up Driver (if name given instead of ID)

If a driver name was given instead of a numeric ID:

```bash
curl -s "http://{hub_ip}/hub2/userDeviceTypes"
```

Find the entry where `name` matches (case-insensitive). Use its `id` as the driver type ID.

If not found in user drivers, the driver may be a system/built-in driver. Report the error and suggest using the numeric driver type ID instead.

### Step 4: Create the Device

```bash
curl -s -X POST "http://{hub_ip}/device/save" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "name={deviceName}" \
  --data-urlencode "label={deviceName}" \
  --data-urlencode "deviceNetworkId={dni}" \
  --data-urlencode "deviceTypeId={driverTypeId}"
```

A successful creation returns HTTP 302 (redirect to the device edit page).

### Step 5: Find the New Device ID

Query the device list to find the newly created device:

```bash
curl -s "http://{hub_ip}/hub2/devicesList"
```

The response is `{"devices": [...]}`. Each device is `{"key": "DEV-{id}", "data": {"id": N, "name": "...", ...}}`. Find the entry matching the device name.

### Step 6: Report Result

- On success: "Created device **{name}** (ID {id}) using driver **{driverName}** (ID {driverTypeId})"
- On failure (HTTP 500 or other): "Failed to create device — check that the driver type ID is correct and the DNI is unique"

### Batch Mode

If multiple device names are given (comma-separated or multiple quoted names), create each one sequentially with the same driver. Report all results as a table:

| ID | Name | DNI | Driver |
|----|------|-----|--------|
