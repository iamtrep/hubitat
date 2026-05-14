---
name: hubitat-behavior-test
description: Generate a Mode 1 behavior test for a Hubitat automation app from a YAML spec — provisions the test rig idempotently (virtual devices, dedicated Maker API instance, dedicated app instance) and writes a self-contained tests/test-{app}.sh that meets the TESTING.md §1.1 closed-loop contract
argument-hint: "[@hubname] [path/to/spec-*.yaml]"
allowed-tools: Bash, Read, Write, Glob, Grep
---

# Hubitat Behavior Test Skill

Closes the §2.4 "named gap" for `/hubitat-behavior-test`. Given a YAML spec, ensures the rig exists on the hub (idempotently), applies the app under test's configuration, and generates a self-contained `tests/test-{app}.sh` that satisfies the §1.1 closed-loop contract: single invocation, `@hubname`-driven hub selection, self-discovery via `/hub2/appsList`, exit codes 0/1/2, `[PASS]/[FAIL]/[WARN]/[INFO]` labels, idempotent setup.

The generated test is portable — it does **not** depend on the skill at runtime. The skill is the *generator*; the test is the *artifact*.

Reference: [`TESTING.md`](../../../TESTING.md) §1.1 contract, §2.1 SADC worked example, §2.4 gap description.

## Instructions

Follow these steps exactly.

### Step 1: Read configuration

Read `.hubitat.json` from the project root. Parse the multi-hub config:

1. Check if `$ARGUMENTS` starts with `@hubname`. If so, use that hub name and strip the `@hubname` from arguments. Otherwise, use `default_hub`.
2. Look up the hub in `hubs[hubname]` to get `hub_ip`.
3. If the hub has `username` and `password` (non-null), authenticate first:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Then add `-b /tmp/hubitat_cookies_{hubname}` to **all** subsequent curl commands for this hub.

### Step 2: Locate the spec file

After stripping `@hubname` from `$ARGUMENTS`:

- If a path remains, use it.
- Otherwise, find the most recently modified `spec-*.yaml` under `apps/*/tests/`:
  ```bash
  ls -t apps/*/tests/spec-*.yaml 2>/dev/null | head -1
  ```

If no spec file is found, report the error and stop.

### Step 3: Parse the YAML spec

Use Python (`python3`) — PyYAML is the only external dependency. If `python3 -c 'import yaml'` fails, instruct the user to `pip install pyyaml` and stop.

The spec has this schema (see `apps/sensors/tests/spec-sadc.yaml` for a worked example):

```yaml
app:
  source: <relative path to .groovy>
  type_name: <name from definition()>
  instance_label: <label for the test instance>
  parent:                    # optional, omit for standalone apps
    source: <relative path to parent .groovy>
    type_name: <name from parent definition()>
    instance_label: <label for the parent instance>
  config:                    # settings applied via POST /installedapp/update/json
    <preference_name>: <value>
    ...
inputs:
  - { name: <device label>, driver: <virtual driver name>, role: <preference name> }
  ...
outputs:
  - { name: <device label>, driver: <virtual driver name>, role: <preference name> }
maker_api:
  label: <label for the dedicated Maker API instance>
cases:
  - name: <case label>
    setup:                   # optional, runs before each case (outside the log-capture window)
      - { device: <input label>, command: <command name> }
    actions:                 # test trigger (inside the log-capture window)
      - { device: <input label>, command: <command name> }
    wait_seconds: <int>      # how long to wait for the app to react before asserting
    assert:                  # attribute-level expectations
      - { device: <output label>, attribute: <attribute name>, value: <expected> }
    # Optional log-guard controls (default: guard ON, no whitelist).
    # Each case opens a LogCapture around its actions+asserts; after, the
    # generated test fails the case if APP_INSTANCE_LABEL emitted any warn/
    # error log line. These fields tune that guard:
    allow_warnings: false    # true → disable the guard for this case entirely
    allow_log_patterns:      # list of regexes (re.search on msg); matched lines are
      - "expected.*warning"  # excluded from the guard. Use sparingly — every entry
                             # is a future invisible regression vector.
runtime_budget_seconds: <int>   # surfaces in the generated script header per §1.1
```

Validate: every device referenced in `cases.*.setup/actions/assert` must appear in `inputs` or `outputs`. Every `role` must look like a preference name (no spaces). Fail fast with a clear error.

### Step 4: Ensure the parent app type is installed (if spec declares one)

Skip this step if `app.parent` is not in the spec.

```bash
curl -s "http://{hub_ip}/hub2/userAppTypes"
```

Search the response for an entry where `name == app.parent.type_name`. If absent, invoke `/hubitat-install` with `app.parent.source` (or follow its procedure inline: POST the Groovy source to `/app/saveOrUpdateJson`).

Record the parent's `appTypeId` for the next step.

### Step 5: Ensure the parent app instance exists

First, read the parent source (`app.parent.source`) and check whether its `definition()` block contains `singleInstance: true`. The branching is different for the two cases.

**SingleInstance parent (e.g. `Sensor Aggregator`):**

The hub allows only one instance of this app type. Search `/hub2/appsList` for any entry whose `data.type == app.parent.type_name`, **regardless of label**. If exactly one exists, use it as the parent — this will commonly be the user's production parent. Hanging the test child off the production parent is acceptable because singleInstance parents are organizational containers that don't aggregate config from their children. If zero exist, create one via `/installedapp/create/{parentAppTypeId}`; never try to create a second. `app.parent.instance_label` from the spec is ignored for singleInstance parents.

**Non-singleInstance parent:**

Recursively search the apps tree for an entry with `data.name == app.parent.instance_label`. (Quirk: on `/hub2/appsList`, the installed-app's user-set label is stored as `data.name`; `data.label` is always null. Use `/installedapp/configure/json/{id}` if you need the canonical `app.label`.) If absent:

```bash
curl -s "http://{hub_ip}/installedapp/create/{parentAppTypeId}"
```

Then set its label via the POST procedure in `/hubitat-app-device` Step 6, supplying `label.type=text` and `label={instance_label}`.

Record the parent's installed-instance ID in either branch.

### Step 6: Ensure the child app type is installed

Same as Step 4 but for `app.type_name` and `app.source`. Record `childAppTypeId`.

### Step 7: Ensure the test app instance exists

Search `/hub2/appsList` for an entry with `data.name == app.instance_label`. If absent:

```bash
curl -s "http://{hub_ip}/installedapp/create/{childAppTypeId}"
```

**Known limitation — parent linkage:** Hubitat's REST API does not appear to expose a way to bind a newly-created child instance to its parent. `/installedapp/create/{childAppTypeId}?parent={parentId}` (and the `parentAppId`/`parentInstalledAppId` variants) all create an orphaned instance (`app.parentAppId == null`). POSTing `parentAppId` in the form body of `/installedapp/update/json` is silently ignored. The instance still functions — event subscriptions fire normally — and it shows up at top level in `/hub2/appsList`. For v1, an orphaned test child is acceptable. If the parent has UI elements that aggregate state from children, behavior may differ; flag this in the test report.

After creating the instance, set its label via the form-encoded POST procedure in `/hubitat-app-device` Step 6 — supply `label.type=text` and `label={instance_label}`. Without this step the new instance has no label, and discovery fails.

Record `instanceId`.

### Step 8: Ensure virtual devices exist

For each entry in `inputs` and `outputs`:

1. Search `/hub2/devicesList` for a device with `data.label == {name}` OR `data.name == {name}`. (Devices created via `POST /device/save` get `data.name` set but `data.label` null — match either.) Skip if present.
2. Otherwise, delegate to `/hubitat-create-device`:
   ```
   /hubitat-create-device "{name}" "{driver}"
   ```
   (or call its API directly — POST `/device/save` then re-list `/hub2/devicesList` to capture the new ID).

Record `{name → deviceId}` for every input and output. Save these as a Python dict — they're embedded in the generated test.

### Step 9: Ensure the dedicated Maker API instance exists

A Maker API app type ships with the hub (built-in, not user code). Search `/hub2/appsList` for an instance with `data.name == maker_api.label`:

- **If present**, capture its installed-instance ID. Read its current device list (`/installedapp/configure/json/{id}`) to find the device-input field name (whose `type` starts with `capability.`).
- **If absent**, the Maker API type ID is needed. Fetch `/hub2/userAppTypes` *and* the built-in app types list (`/installedapp/availableApps` or the apps-create UI page) to find the Maker API type ID. Create an instance via `/installedapp/create/{makerApiTypeId}` and set its label via Step 11's POST.

For every device in `inputs` + `outputs`, ensure it's in the Maker API's device list. For each missing device, invoke `/hubitat-app-device add {deviceId} {makerApiInstanceId}` (or follow that skill's POST procedure inline).

Verify by re-fetching `/installedapp/configure/json/{makerApiInstanceId}` and confirming every test device's ID is now a key in `settings.{deviceInput}`.

### Step 10: Discover the Maker API access token

From the Maker API instance config (`/installedapp/configure/json/{makerApiInstanceId}`), search `configPage.sections[].body[]` paragraphs for any `description` or `url` field matching `access_token=([a-f0-9-]+)`. Capture the token.

If no token is found, OAuth may not be enabled. Report this clearly: the user needs to open `http://{hub_ip}/installedapp/configure/{makerApiInstanceId}`, scroll to the OAuth/access-token paragraph, and confirm OAuth is enabled.

Construct `api_base = http://{hub_ip}/apps/api/{makerApiInstanceId}` and `token = {access_token}`. These are embedded in the generated test.

### Step 11: Apply the app-under-test configuration

POST to `/installedapp/update/json` for the test app instance, following the exact field-set rules captured in `/hubitat-app-device` Step 6.

For each `inputs[].role`, set `settings[{role}]` to the comma-separated list of device IDs for inputs assigned to that role. Same for `outputs[].role`. For every entry in `app.config`, set `settings[{preference_name}]` to the configured value, applying the type-conversion rules from `/hubitat-app-device`.

Also set `app.label = app.instance_label` (via the `label.type=text` + `label={value}` fields) so future runs find the instance reliably.

Verify by re-fetching `/installedapp/configure/json/{instanceId}` and confirming all expected settings are present.

### Step 12: Render the test template

Read the template at `.claude/skills/hubitat-behavior-test/test-template.sh.tmpl`. It contains `{{PLACEHOLDER}}` tokens. Replace each by literal substitution (e.g. `python3 -c "print(open(tmpl).read().replace(...))"` or a sed loop).

| Placeholder | Substitute with |
|---|---|
| `{{SPEC_PATH}}` | Relative path of the spec file |
| `{{TEST_PATH}}` | Relative path of the generated test (the file you're about to write) |
| `{{APP_TYPE_NAME_JSON}}` | `json.dumps(app.type_name)` — quoted string literal |
| `{{APP_INSTANCE_LABEL_JSON}}` | `json.dumps(app.instance_label)` |
| `{{MAKER_API_LABEL_JSON}}` | `json.dumps(maker_api.label)` |
| `{{INPUT_DEVICE_LABELS_JSON}}` | `json.dumps([i.name for i in inputs])` |
| `{{OUTPUT_DEVICE_LABELS_JSON}}` | `json.dumps([o.name for o in outputs])` |
| `{{CASES_JSON}}` | `json.dumps(cases)` — full cases array; the template iterates it and resolves device labels to IDs at runtime |
| `{{RUNTIME_BUDGET_SECONDS}}` | Integer literal, no quotes (e.g. `30`) |

All `*_JSON` placeholders must be substituted with Python/JSON literal syntax so the resulting `.sh` file has valid embedded Python.

The generated test depends on `scripts/lib/logsocket.py` at runtime: each case opens a `LogCapture` around its actions + assertions, and an implicit guard fails the case if the app under test emitted any warn/error log line not whitelisted by the spec. Per-case spec fields `allow_warnings` and `allow_log_patterns` tune the guard (see Step 3 schema). The template's `sys.path.insert(0, f"{project_root}/scripts/lib")` makes the import work for tests at any nesting depth — `$PROJECT_ROOT` is computed in the bash wrapper and passed to the Python heredoc as `sys.argv[5]`.

Write the rendered output to `<project>/tests/test-{app-slug}.sh`, where:

- `<project>` is the directory containing `app.source` (e.g. `apps/sensors/` for SADC). The path is `<project>/tests/test-{slug}.sh`.
- `{app-slug}` is `app.instance_label` lowercased with non-alphanumeric characters replaced by `-`, with any leading `test-` prefix preserved as-is (e.g. `test-sadc-app` → `test-sadc-app`).

Make it executable: `chmod +x <path>`.

### Step 13: Run and verify

```bash
bash {project}/tests/test-{slug}.sh @{hubname}
```

Capture the exit code and final summary line. Report:

- Generated script path
- Rig IDs (parent, instance, Maker API, device IDs)
- Exit code
- `N passed, M failed, K warnings` summary

If the run exits non-zero, surface the first `[FAIL]` line plus a hint: "If this is the first run, the app's config may need a moment to apply — re-run once."

### Step 14: Report what was done

Summarize:

- Whether each rig component was created or reused
- The spec path and generated test path
- Total runtime of the run-verification step
- Suggested next steps: commit the spec + generated test, add the spec to the project's `TEST_PLAN.md` if one exists

## Behavioral notes

- **Idempotency** — every "ensure exists" step is a discover-then-create pattern. Re-running the skill on a hub with the rig already present must take seconds, not minutes.
- **No production mutation** — every test instance, app instance, Maker API instance, and virtual device must have a label distinct from anything in production. The spec's labels (`instance_label`, `maker_api.label`, device names) are the only thing protecting against this — validate at Step 3 that none of them match an existing production label that doesn't belong to this test.
- **Parent-child apps** — Hubitat's parent-child install semantics via the REST API are not fully documented. If Step 7's parent-bound create fails, the skill must surface the failure clearly and let the user do the one-time UI step. Do not silently install an orphaned child instance.
- **Maker API token discovery requires OAuth enabled** — if Step 10 returns no token, do not try to enable OAuth automatically (that's `/hubitat-oauth`'s job). Report the missing-token state and stop.
- **The generated test must not depend on the skill at runtime** — it is a self-contained bash + Python heredoc. The skill is invoked once to *create* the rig and *render* the test; from then on, the test is run directly.
