# Hubitat Testing Guide

This document captures how apps and drivers are tested in this repository, and codifies the conventions that make tests agent-runnable as part of a closed-loop write/push/test/fix workflow. Per-project test plans (for example, [`apps/HubDiagnostics/tests/TEST_PLAN.md`](apps/HubDiagnostics/tests/TEST_PLAN.md)) build on this one and add the specifics of their own coverage targets.

Treat this guide as the default. The closed-loop contract in §1.1 is not negotiable: tests that don't satisfy it can run but are not part of the loop.

The guide is organized like [`ARCHITECTURE.md`](ARCHITECTURE.md): **Common** conventions that apply to anything tested here, then **Apps**-specific guidance with the five test modes and the tiered bar for new code, then **Drivers**-specific guidance, then **Patterns To Avoid**.

Automation apps — the ones that subscribe to device events, run logic, and emit synthesized state — get priority attention in this guide. They directly affect user experience and frequently replace or augment built-in apps, which makes regressions in their behavior more user-visible than regressions in computation-heavy or UI-heavy apps. The lead tier in §2.1 reflects that priority.

**Hubitat platform reference.** Platform mechanics — lifecycle methods, Maker API, virtual device creation — are covered in [`ARCHITECTURE.md`](ARCHITECTURE.md) and the official Hubitat documentation. This guide does not duplicate that material; it focuses on test conventions, the closed-loop contract, and the patterns that work in this repo.

## 1. Common

### 1.1 The closed-loop contract

A test is "agent-runnable" — usable in a write/push/test/parse/fix loop without human glue — when it satisfies all of the following.

- **Single invocation.** Runnable from a project-relative path with no positional setup, using the mode's standard runner:
  - `bash <project>/tests/test-*.sh [@hubname] [<instance-id>]` for bash tests
  - `node <project>/tests/test-*.js` for JavaScript unit tests
  - `pytest <project>/tests/test_*.py` for Python tests

  All other arguments are optional and have defaults.

- **Hub selection via `.hubitat.json`.** Never hardcode IPs. Default to `default_hub`; accept an `@hubname` prefix to override. Credentials come from the config; no flags or env vars for connection details.

- **Self-discovery of installed app instance.** Auto-locate the app under test via `GET /hub2/appsList` by `appTypeName`; accept an explicit instance ID as the second positional argument as a fallback.

- **Exit-code semantics.** The agent loop branches on these.
  - `0` — all assertions passed.
  - `1` — one or more assertions failed.
  - `2` — couldn't run at all (config missing, hub unreachable, fixture absent).

- **Output format.** Four canonical labels, written by `ok`/`fail`/`warn`/`info` helpers:
  - `[PASS]` — single passing assertion.
  - `[FAIL]` — single failing assertion.
  - `[WARN]` — soft warning, does not fail the test.
  - `[INFO]` — informational context.

  End every run with a one-line summary: `N passed, M failed, K warnings`. Color codes are optional; the labels are required because they are what the agent parses.

- **Idempotent setup/teardown.** Re-runnable back-to-back with no manual reset. Reset state explicitly at the start of the test; do not rely on a clean slate.

- **No production mutation.** Tests run against dedicated test app instances and virtual test devices. Never reconfigure a production app instance to run a test.

- **Bounded wall-clock.** Every test states an upper-bound runtime in its header comment. The `RUN_SLOW_TESTS=1` environment variable gates anything over ~30s.

The contract applies to Modes 1–4 (see §2.1). Mode 5 (in-hub stress apps) is the documented exception: hub-log output and a manual button press to start. Two of the named gaps in §2.4 — a log-assertion helper and a button-press helper — would together bring Mode 5 into the loop. Log-assertion is the larger lever, because it lets *any* test assert on log content, which is the main observability surface Hubitat exposes.

### 1.2 Snapshot-diff regression

For undocumented hub endpoints whose schema may shift between firmware versions, commit a JSON snapshot under `<project>/tests/snapshots/<endpoint-name>.json`. The test asserts the live response matches the snapshot structurally (key set, value types). Schema additions are tolerated by default; schema removals fail.

A refresh procedure must be documented next to the snapshot — typically a `--save-snapshots` flag on the test script. Canonical example: `bash apps/utilities/tests/test-rule-logging-manager.sh --save-snapshots` rewrites the committed snapshots from the current live response.

### 1.3 Test rig hygiene

- **Virtual test devices.** Create via `POST /device/save` with a stable name prefix (e.g. `test-` or `vt-`). One device per logical test input or output.
- **Dedicated Maker API instance for tests.** Keeps test devices out of any production Maker API instance and isolates token leakage.
- **Dedicated test app instance.** Install the app under test in a separate instance with test-friendly timing (e.g. `grace=0`, short verify delays). Never reconfigure a production instance for a test run.
- **Stable references across runs.** Discover devices and app instances by name or label, not by hardcoded numeric ID. IDs change between hubs and after reinstall.

### 1.4 Where tests live

- `<project>/tests/test-*.sh` — project-scoped test scripts (integration, behavior, snapshot).
- `<project>/tests/test-*.js` — pure-JavaScript unit tests, `node`-runnable.
- `<project>/tests/snapshots/` — committed JSON snapshots for §1.2.
- `<project>/test_*.py` or `<project>/tests/test_*.py` — Python unit tests, pytest convention.
- `apps/tests/*.groovy` — in-hub stress and diagnostic apps. This directory is the exception, not the convention.
- `drivers/tests/*Test.groovy` — paired test drivers (companion drivers that drive the behavior of another driver under test).

The repo-root `tests/` directory is reserved for cross-project tooling and a future top-level runner (see §2.4).

## 2. Apps

### 2.1 The five test modes (behavior first)

Each mode below has a purpose, a canonical example to mimic, and notes on how it fits the closed-loop contract.

#### Mode 1 — Behavior tests (lead tier)

**Purpose:** verify automation apps that subscribe to device events, run logic, and emit synthesized state or commands. The most user-visible category, and the one this guide prioritizes.

**Pattern:** virtual test devices created via `POST /device/save`; a dedicated Maker API instance fronting them; a dedicated installed instance of the app under test, configured with the test devices and test-friendly timing; a test script (bash + Python or bash + curl) driving the cycle `reset() → action() → wait() → verify()`.

**Canonical example:** [`apps/sensors/tests/test-sadc.sh`](apps/sensors/tests/test-sadc.sh), generated from [`apps/sensors/tests/spec-sadc.yaml`](apps/sensors/tests/spec-sadc.yaml) by the [`/hubitat-behavior-test`](.claude/skills/hubitat-behavior-test/SKILL.md) skill (§2.4).

The spec maps directly onto the procedure below — for [`apps/sensors/SensorAggregatorDiscreteChild.groovy`](apps/sensors/SensorAggregatorDiscreteChild.groovy) (SADC), which aggregates discrete sensor values across N inputs into a single virtual output device:

- Setup
  - Create 3 virtual contact sensors: `test-sadc-in-1`, `test-sadc-in-2`, `test-sadc-in-3`.
  - Create 1 virtual contact sensor: `test-sadc-out` (the aggregated output).
  - Create a dedicated Maker API instance: `test-sadc-maker`, add all 4 devices to it.
  - Create a dedicated SADC instance: `test-sadc-app`, configured to aggregate `capability.contactSensor` on the 3 inputs into `test-sadc-out`, watching `contact == open`.

- Test cases
  - **All-closed baseline.** Force all 3 inputs to `closed` via Maker API; wait 2s; assert `test-sadc-out.contact == closed`.
  - **Single-open propagation.** Drive `test-sadc-in-1` to `open`; wait 2s; assert `test-sadc-out.contact == open`.
  - **Idempotent reset.** Drive `test-sadc-in-1` back to `closed`; wait 2s; assert `test-sadc-out.contact == closed`.

- Exit code: `0` if all asserts pass, `1` if any fails, `2` if config or hub setup fails.

**Closed-loop notes:** every attribute-level expectation is observable through the Maker API (`GET /apps/api/{appId}/devices/{deviceId}` returns the current attribute value). On top of that, every generated Mode 1 test opens a [`LogCapture`](scripts/lib/logsocket.py) window around each case and an implicit guard fails the case if the app under test emitted any unexpected warn/error log line — so an exception or unintended warning surfaces as a `[FAIL]` even if the attribute assertions still hold. Specs can opt out per-case via `allow_warnings: true` or whitelist patterns via `allow_log_patterns: [regex]`. For apps whose *primary* behavior shows up only in logs, write spec assertions against `LogCapture` directly using the same helper.

#### Mode 2 — API integration tests

**Purpose:** validate apps that serve an OAuth-gated `/api/*` surface (HubDiagnostics, RuleLoggingManager) end-to-end against the real hub.

**Pattern:** bash + embedded Python heredoc. The bash wrapper reads `.hubitat.json`, parses `@hubname` and instance-id args, and pipes the hub/instance metadata into a Python script that performs the actual assertions. Outputs `[PASS]`/`[FAIL]`/`[WARN]`/`[INFO]` to stdout and an exit code per the contract.

**Canonical examples:**
- [`apps/HubDiagnostics/tests/test-hub-diagnostics-api.sh`](apps/HubDiagnostics/tests/test-hub-diagnostics-api.sh) — 170+ assertions across every API surface, ground-truth comparison against raw hub endpoints, snapshot-diff for schemas.
- [`apps/utilities/tests/test-rule-logging-manager.sh`](apps/utilities/tests/test-rule-logging-manager.sh) — undocumented-endpoint monitor with structural assertions and snapshot diffs.

**Closed-loop notes:** these are the most mature tests in the repo and define the bash-wrapper-around-Python idiom that newer tests should mimic. Discovery via `/hub2/appsList` by `appTypeName` is the convention; pass an explicit instance ID as the second positional arg if discovery fails.

#### Mode 3 — Pure-JS unit tests

**Purpose:** cover the pure rendering/parsing/formatting helpers in SPA `.html` files (HubDiagnostics' `hub_diagnostics_ui.html`), which have no test coverage by default.

**Pattern:** copy the helpers out of the HTML into a standalone `.js` file with a header comment pointing at the source line range, write a tiny `ok`/`fail`/`section` harness, run with `node`. A browser-runnable variant (open `tests/spa/index.html` in any browser) is sketched in HubDiagnostics' `TEST_PLAN.md` Phase D.

**Canonical example:** [`apps/HubDiagnostics/tests/test-diffStats.js`](apps/HubDiagnostics/tests/test-diffStats.js).

**Closed-loop notes:** exit code is `0` if all pass, `1` if any fail. Labels are `[PASS]`/`[FAIL]` per the contract. The copy from HTML to `.js` is a deliberate trade — keeping the helpers Node-runnable is worth the manual sync.

#### Mode 4 — Python mirror tests

**Purpose:** test pure Groovy logic (classifiers, parsers, normalizers) by mirroring the logic into Python and unit-testing it there, where the toolchain is friendlier and the test loop is faster.

**Pattern:** Python file with a comment header pointing at the Groovy source line range it shadows; `pytest` or a `__main__` harness; assertions on the mirror, periodic manual cross-check against the Groovy.

**Canonical examples:**
- [`apps/HubDiagnostics/test_classification.py`](apps/HubDiagnostics/test_classification.py) — mirrors the `INTEGRATION_TABLE` classification logic.
- [`scripts/perf/tests/`](scripts/perf/tests) — pytest suite for the JSONL log analyser.

**Closed-loop notes:** when the Groovy is the source of truth and the Python mirror drifts, the mirror is wrong by definition. Keep the source-line-range comment in the mirror up-to-date; periodic review is a code-review concern, not an automated one.

#### Mode 5 — In-hub stress / diagnostic apps

**Purpose:** workloads where running outside the hub JVM defeats the purpose — async-HTTP concurrency, UDP latency under load, file-manager API throughput.

**Pattern:** a Hubitat app that *is* the test. Installed temporarily, started by a button press in the app's UI, output goes to hub logs. Removed when testing is complete.

**Canonical examples:**
- [`apps/tests/asyncHttpStressTest.groovy`](apps/tests/asyncHttpStressTest.groovy) — overlapping `asynchttpGet` calls.
- [`apps/tests/udpStressTest.groovy`](apps/tests/udpStressTest.groovy) — UDP round-trip latency under load.
- [`apps/tests/fileManagerTests.groovy`](apps/tests/fileManagerTests.groovy) — file manager API benchmark.

**Closed-loop notes:** Mode 5 does not satisfy the contract today — button press is manual and output is hub-log only. See the log-assertion and button-press gaps in §2.4 for how this category would join the loop.

### 2.2 The tiered bar — applies to new or significantly-changed code only

Pre-existing artifacts are not required to retroactively meet this bar.

| Artifact | Required mode(s) |
|---|---|
| Automation / behavior app (subscribe → logic → emit) | Mode 1 |
| App with an OAuth-served `/api/*` surface | Mode 2 |
| App with a UI bundle (SPA, dashboard HTML) | Mode 3 |
| App with significant pure Groovy computation | Mode 4 |
| Driver | None required; Mode 1 with a paired test driver when behavior is non-trivial |
| Stress / diagnostic app | Exempt — the app itself is the test |

A single app may need multiple modes. HubDiagnostics has Mode 2 and would benefit from Mode 3 and Mode 4; that's tracked in its own `TEST_PLAN.md`.

### 2.3 Per-project `TEST_PLAN.md` convention

When a project has phased coverage targets — multiple test modes in flight, gaps to close on a schedule — it gets a `TEST_PLAN.md` next to its tests. The plan tracks phases with a status column (`Done` / `In-progress` / `Planned`) and references the modes by their numbers from this guide.

Canonical example: [`apps/HubDiagnostics/tests/TEST_PLAN.md`](apps/HubDiagnostics/tests/TEST_PLAN.md) — Phase A (Mode 2, shipped), Phase B (Mode 2 audit-HTML, planned), Phase C (Mode 2 snapshot-diff, planned), Phase D (Mode 3 SPA unit tests, planned).

### 2.4 Named gaps

All four originally named gaps are now shipped. Listed in priority order for historical reference.

- ~~**`/hubitat-behavior-test` skill (top priority).**~~ **Shipped.** See [`.claude/skills/hubitat-behavior-test/SKILL.md`](.claude/skills/hubitat-behavior-test/SKILL.md). Reads a YAML spec, provisions the rig idempotently (virtual devices, dedicated Maker API instance, dedicated test app instance) on the chosen hub, renders a self-contained `tests/test-{app}.sh` from [`test-template.sh.tmpl`](.claude/skills/hubitat-behavior-test/test-template.sh.tmpl), and runs it. Reuses `/hubitat-create-device`, `/hubitat-app-device`, and `/hubitat-install`. Pilot test: `apps/sensors/tests/test-sadc.sh` from spec-sadc.yaml.

- ~~**Top-level runner — `scripts/run-tests.sh [@hubname]`.**~~ **Shipped.** See [`scripts/run-tests.sh`](scripts/run-tests.sh). Discovers every `<project>/tests/test-*.sh`, `<project>/tests/test-*.js`, and `<project>/tests/test_*.py` (excluding `.claude/worktrees`), forwards `@hubname` to bash tests, invokes `node` for `*.js` and `python3 -m pytest` for `test_*.py`, aggregates exit codes, prints a final summary. Supports `--list`, `--filter <substr>`, `--verbose`. Opt-out: `TEST-EXCLUDE` marker in the first 20 lines of the test file.

- ~~**Log-assertion helper (`ws://<hub>/logsocket`).**~~ **Shipped.** See [`scripts/lib/logsocket.py`](scripts/lib/logsocket.py). `LogCapture` context manager subscribes to the hub's logsocket WebSocket in a background thread and exposes `matches(pattern, level=…, source=…)`, `no_matches(...)`, `count(...)`, `find_all(...)`, and `wait_for(pattern, timeout=…)` over the captured set. Source filter accepts either an app/device `id` (int) or a regex against `name`. Level filter accepts a string or list. End-to-end test: [`scripts/tests/test-logsocket.sh`](scripts/tests/test-logsocket.sh). Two payoffs now unlocked:
  - Mode 1 behavior tests can assert on warnings or errors the app emits, not just on attribute changes.
  - Mode 5 stress apps gain real assertions: a test wrapper drives the in-hub app via the button-press helper below, watches the logsocket for the result lines the app already prints, and produces structured `[PASS]`/`[FAIL]`.

- ~~**Button-press helper (nice to have).**~~ **Shipped.** See [`.claude/skills/hubitat-app-button/SKILL.md`](.claude/skills/hubitat-app-button/SKILL.md). POSTs to `/installedapp/btn` with `id`, `name`, `settings[name]=clicked`, `name.type=button` — invokes the app's `appButtonHandler(String btn)` synchronously. The behavior-test template integrates it two ways: `app.setup_buttons:` in the spec for provisioning-time clicks that mutate state shape (e.g. `btnNewGroup` on SwitchMonitor to create groups), and a per-step `{ button: <name> }` shape in `setup` / `actions` for runtime clicks inside a case window. Pair with [`LogCapture`](scripts/lib/logsocket.py) (the same `cap` the template already opens around each case) to assert on the click's effect.

## 3. Drivers

Most drivers in this repo need no automated tests. Two cases warrant them.

- **Non-trivial behavior.** State machines, retry logic, OAuth refresh chains. Use Mode 1 with a paired test driver alongside — a companion driver that emits the events or returns the responses the driver under test needs to react to. Canonical example: [`drivers/tests/LogEventMonitorTest.groovy`](drivers/tests/LogEventMonitorTest.groovy), a companion driver that emits log messages at controlled levels to exercise the LogEventMonitor app's filtering.
- **Pure helpers.** Parsers, formatters, computed transformations. Qualify for Mode 4 (Python mirror) when the helper has enough cases to be worth testing. Most don't.

The tiered bar in §2.2 leaves drivers explicitly unrequired. The bar can tighten later if a particular driver category (e.g. OAuth-integration drivers) accumulates enough regression history to justify it.

## 4. Patterns to avoid

- Mutating production app instances from tests. Use dedicated test instances per §1.3.
- Tests with no exit-code semantics — scripts that print "ok" and exit `0` regardless of pass/fail. Agents cannot loop on these.
- Hardcoding hub IPs or device IDs. Use `.hubitat.json` and discovery.
- Implicit dependence on `default_hub`. Every test must accept `@hubname` even if it defaults to the configured default.
- Color-only output with no machine-readable `[PASS]`/`[FAIL]`/`[WARN]`/`[INFO]` labels. Agents parse labels, not ANSI escape codes.
- Snapshots committed without a refresh procedure documented next to them. A snapshot the user can't regenerate is a permanent regression.
- Skipping idempotent reset. A test that passes on the first run and fails on the second isn't a test, it's a one-shot.
- Mocking hub responses in integration tests. Run against a real hub — the value of integration tests is exercising the actual hub surface, including firmware quirks and undocumented behavior. Mocks erase that.
- Long blocking sleeps (>30s) without `RUN_SLOW_TESTS=1` gating. Default test runs must stay fast.

## 5. Per-project guides

Per-project test plans inherit from this guide. Existing plans:

- [`apps/HubDiagnostics/tests/TEST_PLAN.md`](apps/HubDiagnostics/tests/TEST_PLAN.md) — phased coverage for HubDiagnostics: Mode 2 (done), Mode 2 audit-HTML (planned), Mode 2 snapshot-diff (planned), Mode 3 SPA helpers (planned).

This section grows as more projects gain plans. The convention is one `TEST_PLAN.md` per project with phased coverage targets — see §2.3.
