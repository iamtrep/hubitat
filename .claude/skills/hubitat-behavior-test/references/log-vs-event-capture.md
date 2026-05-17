<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Log vs Event Capture

> **Load this when:** choosing between `assert_logs` and `assert_events` in a spec, writing assertions for observations that fire >5s after the action (runIn callbacks, debounces), or when LogCapture is missing expected entries in a multi-case run.

## Decision criteria: assert_logs vs assert_events

**Prefer `assert_events`** when:
- The observable is a device-state transition (a capability attribute change).
- The assertion fires >5s after the triggering action (runIn callbacks, debouncer timeouts, scheduled handlers).
- The test has multiple cases in sequence — LogCapture can miss late-firing entries when the next case starts before the previous callback completes.
- You want stability across refactors — event `attribute`/`value` fields are tied to the capability contract; log wording can change at any time.

**Use `assert_logs`** when:
- The app's primary observable is a log line, not an attribute change (e.g., a battery-replacement alert that logs but doesn't set an attribute).
- The assertion is expected to fire promptly (within `wait_seconds`, which must be ≤5s for reliable LogCapture).
- The log pattern is stable and unlikely to change across app versions.

## The late-firing trap

LogCapture opens a WebSocket to `/logsocket` around the case's `actions` + assertion window. If the app's response fires via `runIn` or `schedule` (e.g., a debounce handler running 10s later), the log line may arrive after LogCapture has already closed for that case. EventCapture (`/eventsocket`) is more reliable for these patterns because device-state events are durable and queryable after the fact via `/apps/api/{id}/devices/{deviceId}/events`.

Validated pattern from WellMonitor testing: `assert_logs` flaked at case 5 (a callback observation >5s after action) while `assert_events` against the same device was reliable for all cases.

## assert_logs spec fields (full reference)

```yaml
assert_logs:
  - { pattern: "Battery replacement detected", level: info }
  # Optional fields:
  #   level   — exact level (info/warn/error/debug/trace) or list of levels
  #   source  — regex matched against the log's `name` field. Defaults to
  #             APP_INSTANCE_LABEL (the app under test). Use a different
  #             value if you need to assert on logs from a peer app/device.
  #   negate: true → asserts the pattern did NOT appear.
  - { pattern: "Battery replacement detected", negate: true }
```

## assert_events spec fields (full reference)

```yaml
assert_events:
  - { attribute: "motion", value: "active", source: "test-mfc-out" }
  # Fields:
  #   attribute — regex matched against the event's `name` field
  #               (e.g. "motion", "switch", "humidity"). None → any.
  #   value     — str (exact match against `value` string) or omit.
  #   source    — deviceId int → match by id; str → regex on
  #               `displayName`. None → any source.
  #   pattern   — regex matched against `descriptionText`. None → any.
  #   negate: true → asserts no matching event appeared.
  - { attribute: "motion", value: "inactive", source: "test-mfc-out", negate: true }
```

## Log-guard controls

Each case opens a LogCapture around its actions + asserts; after, the generated test fails the case if `APP_INSTANCE_LABEL` emitted any warn/error log line. Per-case spec fields tune the guard:

```yaml
allow_warnings: false    # true → disable the guard for this case entirely
allow_log_patterns:      # list of regexes (re.search on msg); matched lines are
  - "expected.*warning"  # excluded from the guard. Use sparingly — every entry
                         # is a future invisible regression vector.
```

These fields are optional. Default: guard ON, no whitelist. `allow_log_patterns` is safer than `allow_warnings: true` because it remains sensitive to unexpected warn/error lines outside the listed patterns.
