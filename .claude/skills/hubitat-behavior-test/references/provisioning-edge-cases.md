<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Provisioning Edge Cases

> **Load this when:** provisioning fails or behaves unexpectedly — Maker API instance missing, parent-linkage issues, orphaned child instances, `manual_setup_required` bail-out, or when applying Maker API config returns HTTP 500.

## manual_setup_required — bail-out procedure (Step 10b)

If `app.manual_setup_required` is `true`, the rig shell (devices + Maker API + uninitialized app instance) is now in place after Step 10. Skip Step 11 (config POST) and continue to Step 12 (render the test) — but in the final report (Step 14), instead of running the generated test, **list the one-time UI configuration steps** from the spec's top-of-file comment so the operator can do them in the browser.

Once the operator has saved the config in the UI (a few clicks, typically <30s), the generated test runs idempotently from that point on. Re-runs of the skill remain safe — they'll detect the existing rig and skip re-creation, but won't overwrite the manually-applied config.

Use `manual_setup_required: true` for apps whose REST provisioning can't reliably establish subscriptions (sub-page configs, deeply conditional `dynamicPage` fields). When set, include a top-of-file YAML comment block in the spec listing the exact UI steps (page, fields, values).

## Known limitation — parent linkage (Step 7)

Hubitat's REST API does not appear to expose a way to bind a newly-created child instance to its parent. `/installedapp/create/{childAppTypeId}?parent={parentId}` (and the `parentAppId`/`parentInstalledAppId` variants) all create an orphaned instance (`app.parentAppId == null`). POSTing `parentAppId` in the form body of `/installedapp/update/json` is silently ignored. The instance still functions — event subscriptions fire normally — and after Step 11's config save it shows up at top level in `/hub2/appsList`. For v1, an orphaned test child is acceptable. If the parent has UI elements that aggregate state from children, behavior may differ; flag this in the test report.

## Maker API config POST quirks (Step 9)

Learned the hard way on fresh Maker API instances:

1. **Echo back EVERY rendered input from configPage** — not just the ones you're changing. Maker API rejects with HTTP 500 if any bool/text/enum input from the rendered form is missing. For unset bool inputs, send `settings[name]=[]` + `name.type=bool` (no `checkbox[name]=on`). For unset text inputs, send `settings[name]=[]` + `name.type=text`. (The `"[]"` echo is safe for Maker API specifically because its app code never does arithmetic on these settings; the null-handling rule (SKILL.md Step 11) does not apply here. For *user* apps under test in Step 11, follow that rule strictly.)
2. The `pickedDevices` input has `type: "capability.*"` (literally with the asterisk). Send it back verbatim: `pickedDevices.type=capability.*` and `pickedDevices.multiple=true`.

If creating a Maker API instance from scratch, the `cloudAccess` bool is stored as the string `"false"` (not `"[]"`) on fresh instances — echo it back as `"false"`. `localAccess` is `"true"` — echo with `checkbox[localAccess]=on`.

## Maker API instance missing entirely

A Maker API app type ships with the hub (built-in, not user code). If no instance with `maker_api.label` appears in `/hub2/appsList`:

- Fetch `/hub2/userAppTypes` *and* the built-in app types list (`/installedapp/availableApps` or the apps-create UI page) to find the Maker API type ID.
- Create an instance via `/installedapp/create/{makerApiTypeId}` — capture the new id from the 302 `Location` header (same rule as Step 7: the instance is not visible in `/hub2/appsList` until configured at least once).
- Set its label and devices via Step 11's POST procedure.

## OAuth token missing (Step 10)

If Step 10 finds no `access_token=` in the Maker API config page paragraphs, OAuth is not enabled. Do not try to enable OAuth automatically — that is `/hubitat-oauth`'s job. Report the missing-token state clearly: the user needs to open `http://{hub_ip}/installedapp/configure/{makerApiInstanceId}`, scroll to the OAuth/access-token paragraph, and confirm OAuth is enabled.
