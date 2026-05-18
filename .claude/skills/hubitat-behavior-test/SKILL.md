---
name: hubitat-behavior-test
description: Generate a Mode 1 behavior test for a Hubitat automation app from a YAML spec — provisions the test rig idempotently (virtual devices, dedicated Maker API instance, dedicated app instance) and writes a self-contained tests/test-{app}.sh that meets the TESTING.md §1.1 closed-loop contract
argument-hint: "[@hubname] [path/to/spec-*.yaml]"
allowed-tools: Bash, Read, Write, Glob, Grep
---


<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

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
  manual_setup_required: false  # set `true` when REST provisioning can't reliably
                             # establish subscriptions (sub-page configs, deeply
                             # conditional dynamicPage fields). See
                             # `references/provisioning-edge-cases.md`.
  setup_buttons:             # optional list of button-input names to click ONCE
                             # during provisioning (between Step 7 and Step 11).
    - btnNewGroup
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
      # ⚠ YAML 1.1 trap: bare `on`, `off`, `yes`, `no`, `true`, `false` parse
      # as Python booleans → URL segments like `/dev/123/True` → 404, silent failure.
      # ALWAYS QUOTE these values: command: "off" not command: off
      - { device: <input label>, command: <command name> }
    actions:                 # test trigger (inside the log-capture window)
      - { device: <input label>, command: <command name> }
    wait_seconds: <int>      # how long to wait for the app to react before asserting
    assert:                  # attribute-level expectations (exact string match by default)
      - { device: <output label>, attribute: <attribute name>, value: <expected> }
      # Use tolerance: <float> for numeric attributes (temperature, humidity, etc.)
      - { device: <output>, attribute: temperature, value: 22.0, tolerance: 0.1 }
    assert_logs:             # log-line expectations during the case window
      - { pattern: "Battery replacement detected", level: info }
    assert_events:           # device-event expectations during the case window
      - { attribute: "motion", value: "active", source: "test-mfc-out" }
    allow_warnings: false    # true → disable warn/error log guard for this case
    allow_log_patterns:      # regexes excluded from the log guard
      - "expected.*warning"
runtime_budget_seconds: <int>   # surfaces in the generated script header per §1.1
```

Validate: every device referenced in `cases.*.setup/actions/assert` must appear in `inputs` or `outputs`. Every `role` must look like a preference name (no spaces). Fail fast with a clear error.

For examples beyond the canonical case (parameterized `args`, `command_spacing_seconds`, button-click steps inside cases, multi-device rigs), load `references/yaml-spec-examples.md`.

For the `assert_logs` vs `assert_events` decision and log-guard field details, load `references/log-vs-event-capture.md`.

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
curl -s -i "http://{hub_ip}/installedapp/create/{childAppTypeId}"
```

**Capture the new instance id from the 302 `Location` header** — the response redirects to `/installedapp/configure/{newId}`. Do not re-fetch `/hub2/appsList`: orphaned child instances are not visible there until configured at least once.

In Python:
```python
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def http_error_302(self, req, fp, code, msg, headers): return None
opener_no_redir = urllib.request.build_opener(NoRedirect())
try:
    opener_no_redir.open(f"http://{hub_ip}/installedapp/create/{child_type_id}")
except urllib.error.HTTPError as e:
    loc = e.headers.get("Location", "")
    new_id = int(re.search(r"/configure/(\d+)", loc).group(1))
```

After creating the instance, set its label via the form-encoded POST procedure in `/hubitat-app-device` Step 6 — supply `label.type=text` and `label={instance_label}`. Without this step the new instance has no label, and discovery fails.

If parent linkage fails or the create returns unexpected results, load `references/provisioning-edge-cases.md`.

Record `instanceId`.

### Step 7b: Dispatch provisioning-time button clicks (if any)

Skip this step if `app.setup_buttons` is absent or empty.

For each name in `app.setup_buttons`, POST to `/installedapp/btn` against the test app instance — same payload as the [`/hubitat-app-button`](../hubitat-app-button/SKILL.md) skill:

```bash
curl -s -X POST "http://{hub_ip}/installedapp/btn" \
  --data-urlencode "id={instanceId}" \
  --data-urlencode "name={button_name}" \
  --data-urlencode "settings[{button_name}]=clicked" \
  --data-urlencode "{button_name}.type=button"
```

Each click invokes `appButtonHandler(String btn)` in the SUT synchronously. If the button makes new settings keys available, **re-fetch `/installedapp/configure/json/{instanceId}` after the click** before Step 11. Order: dispatch in the order given in the spec. Apps that need multiple `btnNewGroup` clicks declare them explicitly: `setup_buttons: [btnNewGroup, btnNewGroup]`.

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
- **If absent**, find the Maker API type ID and create an instance via `/installedapp/create/{makerApiTypeId}` — capture the new id from the 302 `Location` header. Load `references/provisioning-edge-cases.md` for the Maker API config POST quirks (HTTP 500 on missing inputs, `capability.*` type, `cloudAccess`/`localAccess` echo values).

For every device in `inputs` + `outputs`, ensure it's in the Maker API's device list. For each missing device, invoke `/hubitat-app-device add {deviceId} {makerApiInstanceId}` (or follow that skill's POST procedure inline).

Verify by re-fetching `/installedapp/configure/json/{makerApiInstanceId}` and confirming every test device's ID is now a key in `settings.{deviceInput}`.

### Step 10: Discover the Maker API access token

From the Maker API instance config (`/installedapp/configure/json/{makerApiInstanceId}`), search `configPage.sections[].body[]` paragraphs for any `description` or `url` field matching `access_token=([a-f0-9-]+)`. Capture the token.

If no token is found, OAuth may not be enabled. Do not try to enable OAuth automatically — that is `/hubitat-oauth`'s job. Report clearly that the user needs to open the Maker API configure page and enable OAuth. See `references/provisioning-edge-cases.md` for the full OAuth-missing procedure.

Construct `api_base = http://{hub_ip}/apps/api/{makerApiInstanceId}` and `token = {access_token}`. These are embedded in the generated test.

### Step 10b: Bail out if manual setup is required

If `app.manual_setup_required` is `true`, skip Step 11 and go to Step 12. See `references/provisioning-edge-cases.md` for the full bail-out procedure and what to include in the Step 14 report.

### Step 11: Apply the app-under-test configuration

POST to `/installedapp/update/json` for the test app instance, following the exact field-set rules captured in `/hubitat-app-device` Step 6.

For each `inputs[].role`, set `settings[{role}]` to the comma-separated list of device IDs for inputs assigned to that role. Same for `outputs[].role`. For every entry in `app.config`, set `settings[{preference_name}]` to the configured value, applying the type-conversion rules from `/hubitat-app-device`.

Also set `app.label = app.instance_label` (via the `label.type=text` + `label={value}` fields) so future runs find the instance reliably.

**Null-handling rule — critical.** Do NOT echo the literal string `"[]"` as a value for any input that has a `type`. Hubitat stores the field exactly as posted, and Groovy app code that does arithmetic on the setting will hit string repetition and crash. For unset preferences, either omit the field entirely or send its `defaultValue` from the configPage entry. Never send `settings[X]=[]` for `number`, `enum`, `text`, `bool`, or `button` fields.

Verify by re-fetching `/installedapp/configure/json/{instanceId}` and confirming all expected settings are present and that no setting contains the literal string `"[]"`.

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
| `{{CASES_JSON}}` | `repr(cases)` — full cases array as a **Python literal**, NOT JSON. The embedded heredoc parses the substituted text as Python source, so JSON `true`/`false`/`null` would raise `NameError`. `repr` emits Python syntax (`True`/`False`/`None`) with single-quoted strings. |
| `{{RUNTIME_BUDGET_SECONDS}}` | Integer literal, no quotes (e.g. `30`) |

All `*_JSON` placeholders must be substituted with Python/JSON literal syntax so the resulting `.sh` file has valid embedded Python.

The generated test depends on `scripts/lib/logsocket.py` at runtime: each case opens a `LogCapture` around its actions + assertions, and an implicit guard fails the case if the app under test emitted any warn/error log line not whitelisted by the spec. The template's `sys.path.insert(0, f"{project_root}/scripts/lib")` makes the import work for tests at any nesting depth — `$PROJECT_ROOT` is computed in the bash wrapper and passed to the Python heredoc as `sys.argv[5]`.

Write the rendered output to `<project>/tests/test-{app-slug}.sh`, where:

- `<project>` is the directory containing `app.source` (e.g. `apps/sensors/` for SADC).
- `{app-slug}` is `app.instance_label` lowercased with non-alphanumeric characters replaced by `-`, with any leading `test-` prefix preserved as-is.

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

For assertions that fire >5s after the action (runIn callbacks, debounces), prefer `assert_events` over `assert_logs`. See `references/log-vs-event-capture.md` for the full decision criteria.

### Step 14: Report what was done

Summarize:

- Whether each rig component was created or reused
- The spec path and generated test path
- Total runtime of the run-verification step
- Suggested next steps: commit the spec + generated test, add the spec to the project's `TEST_PLAN.md` if one exists

## Behavioral notes

- **Idempotency** — every "ensure exists" step is a discover-then-create pattern. Re-running the skill on a hub with the rig already present must take seconds, not minutes.
- **No production mutation** — every test instance, app instance, Maker API instance, and virtual device must have a label distinct from anything in production. The spec's labels (`instance_label`, `maker_api.label`, device names) are the only thing protecting against this — validate at Step 3 that none of them match an existing production label that doesn't belong to this test.
- **Parent-child apps** — Hubitat's parent-child install semantics via the REST API are not fully documented. If Step 7's parent-bound create fails, surface the failure clearly and let the user do the one-time UI step. Do not silently install an orphaned child instance.
- **Maker API token discovery requires OAuth enabled** — if Step 10 returns no token, do not try to enable OAuth automatically (that's `/hubitat-oauth`'s job). Report the missing-token state and stop.
- **The generated test must not depend on the skill at runtime** — it is a self-contained bash + Python heredoc. The skill is invoked once to *create* the rig and *render* the test; from then on, the test is run directly.
