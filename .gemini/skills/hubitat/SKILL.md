<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Unified Hubitat Development Skill

This skill provides a comprehensive set of workflows for Hubitat Elevation development, covering the entire lifecycle from inspection and code-pushing to configuration and automated behavior testing. It achieves full parity with the 13 legacy Claude skills while providing a unified interface within the Gemini CLI.

## Core Procedures

### 1. Hub Configuration & Security
Every workflow depends on the `.hubitat.json` file in the project root.
- **`@hubname` Convention:** If a command or argument starts with `@hubname` (e.g., `@test apps/sensors/Foo.groovy`), the skill must target the named hub in `.hubitat.json`. Otherwise, use `default_hub`.
- **Hub Security:** If a hub has `username` and `password` defined, you must authenticate via `POST /login` and capture cookies (e.g., `-c /tmp/hubitat_cookies_{hubname}`) for all subsequent calls.

### 2. File Identification
When a filepath is required but not provided:
- Find the most recently modified `.groovy` file: `ls -t apps/*.groovy drivers/**/*.groovy 2>/dev/null | head -1`.
- Determine type (App vs. Driver) based on the parent directory (`apps/` vs. `drivers/`).

---

## Workflow: Inspect & Audit

### /hubitat-list `[drivers|apps|devices|instances]`
Discover resources on the target hub.
- **`drivers`**: List user drivers (ID, Name, Namespace, Device Count).
- **`apps`**: List user app types (ID, Name, Namespace, Instance Count).
- **`devices`**: List all devices (ID, Name, Type/Driver, Status).
- **`instances`**: List installed app instances (ID, Name, Type).
- **No arg**: Display a summary of all categories.

### /hubitat-arch-review `[filepath ... | --branch]`
Audit Groovy files against the project's `ARCHITECTURE.md`.
- **Precedence:** Project-specific `ARCHITECTURE.md` (if found in the directory chain) takes precedence over the root doc.
- **Audit Layers:**
    - **Mechanical:** Static types (no `def`), no in-place state mutation (`<<`, `+=`), log-gating guards.
    - **Semantic:** Lifecycle convergence (`installed`/`updated` → `initialize`), version constant checks, async HTTP callback safety.
- **Output:** A Markdown report categorized into **Errors**, **Warnings**, and **Judgment Calls**, with verbatim citations from the architecture docs.

---

## Workflow: Code Lifecycle

### /hubitat-push `[filepath]`
Update existing code on the hub.
1. Extract `name:` from the `definition()` block.
2. Resolve to Hub ID via `/hub2/userAppTypes` or `/hub2/userDeviceTypes`.
3. Fetch current version via `/{app|driver}/ajax/code?id={ID}`.
4. POST updated source to `/{app|driver}/ajax/update` with `id`, `version`, and `source`.
5. Report compile status (success vs. detailed error lines).

### /hubitat-install `[filepath]`
Create new code and install a test instance.
1. Perform creation via `POST /{app|driver}/saveOrUpdateJson`.
2. For Apps: Create an instance via `GET /installedapp/create/{appTypeId}` and report the configuration link.
3. For Drivers: Report that it is available for device assignment.

### /hubitat-publish `[filepath | name | ID]`
Propagate code to other hubs on the same account.
1. Resolve target ID and type.
2. Trigger `GET /hub/publishCode/{type}/{id}`.
3. Poll `GET /hub/publishCode/status` until `completed: true`.

### /hubitat-delete `[filepath | apptype:N | driver:N | instance:N]`
Remove code or instances from the hub.
- **Safety:** Always list what will be deleted and require explicit user confirmation.
- **Cascade:** Offer to delete all child instances before deleting an app type.
- **Guard:** Refuse to delete drivers that are currently in use by devices.

---

## Workflow: Install & Configure

### /hubitat-create-device `"Name" "Driver" [DNI]`
Create virtual devices on the hub.
1. Resolve driver name to `driverTypeId`.
2. Generate DNI from name if not provided.
3. POST to `/device/save`.
4. Verify creation via `/hub2/devicesList`.

### /hubitat-app-device `[add|remove|list] {device_id} [installed_app_id]`
Manage devices in an app's settings (e.g., Maker API).
- **Mechanism:** POST to `/installedapp/update/json` with `_action_update=Done`.
- **Constraint:** You must echo back ALL rendered inputs from the config page, including type metadata, to avoid corrupting app settings.
- **Null Safety:** Never send literal `[]` for number/enum/text fields; omit them or send their `defaultValue`.

### /hubitat-oauth
Self-healing OAuth bootstrapping.
- **Logic:** Add private helper methods (`getAppTypeId`, `autoEnableOAuth`, `checkOAuth`) to a Groovy app to allow it to enable its own OAuth via internal loopback (`127.0.0.1:8080`) when `createAccessToken()` fails.

### /hubitat-filemanager `[upload|download|list|delete] [filepath]`
Manage files in the hub's local storage (`/local/`).
- **Upload:** `POST /hub/fileManager/upload`.
- **Download:** `GET /local/{filename}`.
- **Delete:** `POST /hub/fileManager/delete` (with confirmation).

---

## Workflow: Runtime & Testing

### /hubitat-run `[device_id] [command] [args...]`
Interact with devices via Maker API.
- **Discovery:** If a filepath is provided, find the devices/instances using it and ask the user which to run.
- **Status:** Retrieve current attribute values via Maker API `GET /apps/api/{makerId}/devices/{deviceId}`.

### /hubitat-app-button `{installed_app_id} {button_name}`
Simulate a preference-page button click.
- **Endpoint:** `POST /installedapp/btn` with `settings[{name}]=clicked` and `{name}.type=button`.
- **Purpose:** Trigger `appButtonHandler(String btn)` for Mode 5 stress apps or test provisioning.

### /hubitat-behavior-test `[filepath]` (The Lead Tier)
Generate a Mode 1 behavior test from a YAML spec.
1. **Provision Rig (Idempotent):**
    - Ensure virtual input/output devices exist.
    - Ensure a dedicated "Test Maker API" instance exists and has the devices added.
    - Ensure the app-under-test instance exists and is configured with the test devices.
2. **Retrieve Token:** Discover the Maker API access token from its configuration HTML.
3. **Render Script:** Substitute rig IDs and cases into `test-template.sh.tmpl`.
4. **Execute:** Run the generated script and report the `[PASS]/[FAIL]` summary.
5. **Contract:** Generated tests must be self-contained (Bash + Python heredoc) and satisfy the `TESTING.md` §1.1 closed-loop contract.
