---
name: hubitat-behavior-test
description: "Use when the user wants a Mode 1 behavior test generated from a Hubitat YAML spec. Read .hubitat.json from the repo root, support optional @hubname prefixes, parse the spec, provision or reuse the full test rig idempotently on the hub, render a self-contained tests/test-*.sh artifact from the template, and run it once to verify the setup."
---

# Hubitat Behavior Test

Use this skill to turn a YAML behavior spec into a reusable, self-contained test script plus the Hubitat-side rig it needs.

## Core contract

- The skill is the generator and provisioner.
- The generated `tests/test-*.sh` is the long-lived artifact.
- Re-runs must be idempotent: discover existing rig pieces first, create only what is missing, and avoid mutating unrelated production objects.

## Workflow

1. Read `.hubitat.json` from the repo root.
2. Support an optional `@hubname` prefix. Otherwise use `default_hub`.
3. Resolve `hub_ip` and authenticate with `/login` if the hub has `username` and `password`.
4. Locate the spec:
   - explicit `spec-*.yaml` path if supplied
   - otherwise the most recently modified `apps/*/tests/spec-*.yaml`
5. Parse the YAML with `python3` and `pyyaml`. Stop with a clear instruction if PyYAML is missing.
6. Validate the spec:
   - referenced devices must be declared in `inputs` or `outputs`
   - role names should look like preference names
   - labels for test devices, app instances, and Maker API instances must not ambiguously collide with production objects
7. Ensure the parent app type and instance exist when `app.parent` is declared.
8. Ensure the child app type exists and ensure the test app instance exists.
9. Dispatch any provisioning-time button clicks declared in `app.setup_buttons`, then refresh config if those clicks expose new settings fields.
10. Ensure all virtual devices declared in `inputs` and `outputs` exist, typically via the `hubitat-create-device` workflow.
11. Ensure the dedicated Maker API instance exists, includes every test device, and expose its access token.
12. If `manual_setup_required` is true, stop after rig provisioning and report the one-time UI steps from the spec comment block instead of trying to overwrite config automatically.
13. Otherwise apply the app-under-test configuration through `/installedapp/update/json`, following the same rendered-input preservation rules used for `hubitat-app-device`.
14. Render the test from `.claude/skills/hubitat-behavior-test/test-template.sh.tmpl` into the app project's `tests/` directory, make it executable, and embed the resolved rig metadata.
15. Run the generated test once against the target hub and report the summary.

## Critical implementation details

- Capture new installed-app IDs from redirect `Location` headers when using `/installedapp/create/{typeId}`.
- Preserve all rendered inputs when posting config updates; do not clobber unrelated settings.
- Do not send invented `"[]"` values into numeric, enum, text, bool, or button fields for the app under test.
- For Maker API, discover the selected-device input from rendered config rather than assuming a fixed field name.
- If no Maker API token is discoverable, stop and report that OAuth must be enabled on that Maker API instance.
- The generated test must not depend on this skill at runtime.

## Reporting

Report:
- which rig parts were created versus reused
- key IDs for parent app, child app, Maker API instance, and devices
- generated test path
- verification command and exit status
- first meaningful failure detail when the first verification run is non-zero
