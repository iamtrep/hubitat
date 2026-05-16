---
name: hubitat-create-device
description: "Use when the user wants to create one or more virtual devices on a Hubitat hub from a driver type ID or driver name. Read .hubitat.json from the repo root, support optional @hubname prefixes, authenticate with hub cookies when needed, resolve user driver IDs, create devices via Hubitat web endpoints, and report the created device IDs."
---

# Hubitat Create Device

Use this skill to create Hubitat devices, typically virtual devices backed by a chosen driver.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Parse the request into:
   - one or more device names
   - either a numeric driver type ID or a driver name
   - optional custom DNI per device or a shared DNI pattern
5. If the driver was given by name, query `/hub2/userDeviceTypes` and find a case-insensitive match.
6. If no DNI is provided, generate one from the device name using uppercase with spaces replaced by underscores.
7. Create each device with a POST to `/device/save` using:
   - `name`
   - `label`
   - `deviceNetworkId`
   - `deviceTypeId`
8. Query `/hub2/devicesList` afterward and find the created device by name to obtain the actual device ID.

## Constraints

- This workflow is reliable for user drivers discoverable through `/hub2/userDeviceTypes`.
- If the requested driver is built-in and only given by name, prefer asking for the numeric driver type ID rather than guessing.
- Treat duplicate DNI or HTTP 500 responses as creation failures and report them clearly.

## Batch mode

If the user gives multiple device names, create them sequentially with the same driver and report results in a compact table with ID, Name, DNI, and Driver.

## Reporting

On success, report the created device name, new device ID, driver name, and driver type ID.
On failure, report the likely cause: missing driver, duplicate DNI, or hub-side validation error.
