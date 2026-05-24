<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Contributing — A Guided Tour of the Workflow

Welcome. This document is a tutorial for developers who have cloned or forked the repo and want to be productive in it.

The workflow rests on one assumption: **you do your Hubitat work inside [Claude Code](https://claude.com/claude-code)**. You're not opening Groovy files in vim and shelling out to `curl` to push them. You're sitting at the Claude Code prompt describing what you want — "build me an app that does X," "push it to the test hub," "write a behavior test for it," "the test failed, figure out why." Claude Code reads the project's conventions ([`ARCHITECTURE.md`](ARCHITECTURE.md), [`TESTING.md`](TESTING.md), similar code already in the repo), drafts the Groovy and YAML on your behalf, invokes the [thirteen Hubitat-specific skills under `.claude/skills/`](.claude/skills/) to talk to the hub, parses each result, and reports back. You review, redirect, approve.

The skills are the levers; **Claude Code's judgment about which lever to pull, in what order, and what to do with the output is the workflow itself**. This tutorial walks both — what each skill does, and what a session that uses them actually looks like. By the end you'll have built a small app, pushed it, generated a behavior test for it, and watched a test failure get diagnosed and fixed inside the loop.

Skill invocations in this doc are shown in `/skill-name args` form so you can see what's running under the hood. In practice you'll usually just describe intent and let Claude Code pick the skill. Both work; the explicit form is useful for learning what the agent is reaching for.

The companion documents — [`ARCHITECTURE.md`](ARCHITECTURE.md) for platform constraints and design conventions, and [`TESTING.md`](TESTING.md) for the test framework — are the authoritative reference material. This guide *uses* them, points to them, but does not duplicate them.

## Table of contents

1. [Prerequisites](#1-prerequisites)
2. [One-time setup](#2-one-time-setup)
3. [Repo tour](#3-repo-tour)
4. [The development loop, by worked example](#4-the-development-loop-by-worked-example)
5. [Writing tests — Mode 1 behavior tests](#5-writing-tests--mode-1-behavior-tests)
6. [Cross-hub deployment](#6-cross-hub-deployment)
7. [The skill catalog](#7-the-skill-catalog)
8. [Working efficiently](#8-working-efficiently)
9. [Going deeper](#9-going-deeper)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Prerequisites

Before starting, you need:

- **[Claude Code](https://claude.com/claude-code) or [Gemini CLI](https://github.com/google-gemini/gemini-cli) installed and signed in.** These are your IDEs and collaborators for the workflow. You'll describe intent in natural language ("draft a small app that…", "push it", "the test failed, debug it"), and they will read the project conventions, edit the files for you, invoke the right skills, parse the output, and report back. Skills auto-discover from [`.claude/skills/`](.claude/skills/) (for Claude) or [`.gemini/skills/`](.gemini/skills/) (for Gemini) when you run them from the repo root.
- **A Hubitat Elevation hub on your LAN.** Any model — C-5, C-7, C-8, C-8 Pro. You'll need its IP address; if you don't know it, find it in the Hubitat mobile app or via `arp -a` once the hub is on your network.
- **Basic Groovy familiarity.** The Hubitat platform runs sandboxed Groovy. You don't need to be an expert — the existing apps and drivers in this repo are good reading material — but you should be comfortable reading and editing it. The official [Hubitat developer documentation](https://docs2.hubitat.com/en/developer) covers platform mechanics (capabilities, lifecycle, OAuth, Zigbee helpers); this repo does not duplicate it.
- **`python3` with `pyyaml`.** Required by the behavior-test skill. `pip install pyyaml` if you don't have it.
- **`curl`, `jq`, and `bash`.** All the hub interactions are curl-driven; the test runner is bash.

Recommended but not required: a hub or two beyond your primary one. The workflow is designed around running tests on a dedicated test hub (or test instance) while production runs untouched on your main hub. One hub is enough to learn on — you'll just need to be more careful about test isolation, which the tooling helps with.

---

## 2. One-time setup

### 2.1 Clone

```bash
git clone https://github.com/iamtrep/hubitat.git
cd hubitat
```

### 2.2 Create `.hubitat.json`

Every skill in this repo reads `.hubitat.json` from the project root to figure out which hub(s) to talk to. The file is gitignored — it holds IPs, usernames, and passwords, none of which should ever be committed.

Create it by hand. Minimal one-hub example (no hub security):

```json
{
  "default_hub": "myhub",
  "hubs": {
    "myhub": {
      "hub_ip": "10.0.0.42",
      "maker_api": { "app_id": null, "token": null }
    }
  }
}
```

If your hub has security enabled (login screen at `http://<hub_ip>/login`), add `username` and `password`:

```json
{
  "default_hub": "myhub",
  "hubs": {
    "myhub": {
      "hub_ip": "10.0.0.42",
      "username": "admin",
      "password": "your-hub-password",
      "maker_api": { "app_id": null, "token": null }
    }
  }
}
```

Multi-hub setups follow the same shape, one entry per hub:

```json
{
  "default_hub": "main",
  "hubs": {
    "main":  { "hub_ip": "10.0.0.42",  "maker_api": { "app_id": null, "token": null } },
    "test":  { "hub_ip": "10.0.0.43",  "maker_api": { "app_id": null, "token": null } },
    "cabin": { "hub_ip": "192.168.50.5", "username": "admin", "password": "...",
               "maker_api": { "app_id": null, "token": null } }
  }
}
```

`maker_api.app_id` and `maker_api.token` can stay `null` for now. They're used by skills that need direct Maker API access; the test skill discovers them automatically from the hub on first run.

### 2.3 Verify

In Claude Code, run:

```
/hubitat-list
```

You should see a summary of your hub's installed apps, devices, and user drivers. If you get a connection error, see [Troubleshooting](#10-troubleshooting).

### 2.4 The `@hubname` convention

Every hub-touching skill in this repo accepts an `@hubname` prefix as its first argument to override `default_hub`. For example:

```
/hubitat-list @cabin
/hubitat-push @test apps/sensors/MotionFusionChild.groovy
```

Without the prefix, the skill targets `default_hub`. With it, the skill targets the named hub for that one invocation only. **At the start of a session where you'll be pushing code, decide which hub you're targeting and be explicit about it.** Pushing to the wrong hub is recoverable but annoying.

---

## 3. Repo tour

```
hubitat/
├── README.md                  # What's in the repo, end-user view
├── ARCHITECTURE.md            # Platform constraints, design conventions (REFERENCE)
├── TESTING.md                 # Test framework, closed-loop contract (REFERENCE)
├── TODO.md                    # Backlog
├── CONTRIBUTING.md            # This file
├── .hubitat.json              # Your local hub config (gitignored — create per §2.2)
│
├── apps/                      # Groovy apps
│   ├── sensors/               #   sensor aggregators, motion fusion
│   ├── HubDiagnostics/        #   self-served dashboard app
│   ├── utilities/             #   small utility apps
│   ├── WellPumpMonitor/       #   topic-specific apps
│   ├── VerbNav/
│   └── tests/                 #   in-hub Mode 5 stress apps
│
├── drivers/                   # Groovy drivers
│   ├── sinope/                #   per-vendor subdirectories
│   ├── stelpro/
│   ├── visiblair/
│   └── tests/                 #   companion test drivers (Mode 1 driver tests)
│
├── integrations/              # Parent/child integrations spanning multiple drivers
│   └── visiblair/
│
├───scripts/                   # Helper scripts and the top-level test runner
│   ├───run-tests.sh           #   discover + run every test in the repo
│   ├───lib/                   #   shared bash + Python utilities (logsocket, eventsocket)
│   └───perf/                  #   performance tooling
│
├───.claude/
│   ├───settings.local.json    # Skill + bash permission allowlist
│   └───skills/                # 13 Hubitat-specific skills (Claude-native)
│
└───.gemini/
    └───skills/
        └───hubitat/           # Unified Hubitat development skill (Gemini-native)

```

Per-project READMEs live next to the code they describe — e.g. [`apps/sensors/README.md`](apps/sensors/README.md), [`drivers/README.md`](drivers/README.md), [`integrations/visiblair/README.md`](integrations/visiblair/README.md). Per-project test plans live next to their tests — e.g. [`apps/HubDiagnostics/tests/TEST_PLAN.md`](apps/HubDiagnostics/tests/TEST_PLAN.md). Per-project architecture docs sit alongside per-project ARCHITECTURE.md files — e.g. [`apps/HubDiagnostics/ARCHITECTURE.md`](apps/HubDiagnostics/ARCHITECTURE.md) — and extend the top-level [`ARCHITECTURE.md`](ARCHITECTURE.md) with project-specific conventions.

---

## 4. The development loop, by worked example

The rest of the tutorial walks one example end-to-end. **The example app does not exist in the repo** — it's invented purely for the tutorial, small enough to fit on a page, large enough to exercise the whole workflow. Call it **DoorbellChimeChild**: when a contact sensor opens (someone opens the front door), turn a virtual switch ON; after a configurable `chimeSeconds` grace period, turn it OFF. In real life you'd wire that switch to a notification, a TTS device, or a relay.

A real version of the same shape lives at [`apps/sensors/SensorAggregatorDiscreteChild.groovy`](apps/sensors/SensorAggregatorDiscreteChild.groovy) with its spec at [`apps/sensors/tests/spec-sadc.yaml`](apps/sensors/tests/spec-sadc.yaml) — the canonical example referenced throughout [`TESTING.md`](TESTING.md). DoorbellChimeChild is structured the same way, just smaller.

### 4.1 Describe what you want

Open Claude Code in the repo root and tell it what you're building:

> "I want a small app that watches a contact sensor and turns on a switch when the sensor opens, then turns the switch back off after a configurable number of seconds. Call it DoorbellChimeChild. Put it in `apps/example/`."

Claude Code will read [`ARCHITECTURE.md`](ARCHITECTURE.md) to pick up the project conventions — static typing, version constant for code-push detection, `initialize()` as the single convergence point, `runIn` with a stable handler name, the three-bool logging discipline — and glance at a similar app under `apps/sensors/` to match the shape. Then it drafts the file. Something like:

```groovy
import groovy.transform.Field

@Field static final String APP_VERSION = "0.1.0"

definition(
    name: "Doorbell Chime Child",
    namespace: "tutorial",
    author: "you",
    description: "Toggle a switch when a contact sensor opens, off after N seconds",
    category: "Convenience",
    parent: null,
    iconUrl: "", iconX2Url: "", iconX3Url: ""
)

preferences {
    page(name: "mainPage", title: "Doorbell Chime", install: true, uninstall: true) {
        section {
            input "appName", "text", title: "Name this instance", defaultValue: "doorbell-chime"
            input "doorSensor", "capability.contactSensor", title: "Contact sensor", required: true
            input "chimeSwitch", "capability.switch", title: "Chime switch", required: true
            input "chimeSeconds", "number", title: "Chime duration (seconds)", defaultValue: 5
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

void installed() { initialize() }
void updated()   { unsubscribe(); unschedule(); initialize() }
void uninstalled() { /* no-op */ }

void initialize() {
    if (state.version != APP_VERSION) {
        log.warn "Doorbell Chime ${app.label}: version ${APP_VERSION} (was ${state.version})"
        state.version = APP_VERSION
    }
    app.updateLabel(settings.appName ?: "doorbell-chime")
    subscribe(doorSensor, "contact.open", "onOpen")
}

void onOpen(evt) {
    if (debugEnable) log.debug "${app.label}: door opened, chiming for ${chimeSeconds}s"
    chimeSwitch.on()
    runIn((settings.chimeSeconds ?: 5) as Integer, "chimeOff")
}

void chimeOff() {
    if (debugEnable) log.debug "${app.label}: chime off"
    chimeSwitch.off()
}
```

You're the reviewer. Scan it, redirect anything off, ask questions ("why the `?:` defaults if the preferences declare `defaultValue`?", "should `chimeSeconds` be `Integer` or `Long`?"). When the draft looks right, the next step is to get it onto the hub.

A few of the conventions baked into the draft, since they'll come up again: the version constant guarded by `state.version` exists because pushing source does *not* fire `updated()` on installed instances — see [`ARCHITECTURE.md`](ARCHITECTURE.md) §Platform constraints. `initialize()` is the single convergence point (`installed()` and `updated()` both route through it). `updated()` does `unsubscribe(); unschedule(); initialize()` so subscriptions and schedules from the prior config don't accumulate. `runIn` uses a stable handler name (`chimeOff`) so re-firings are self-cancelling by default. All of these are non-negotiable platform-level rules in this repo, not stylistic preferences.

### 4.2 First push to the hub

Say: "Push it to my hub." Claude Code recognizes this is the first time the file is going up and invokes [`/hubitat-install`](.claude/skills/hubitat-install/SKILL.md) under the hood (POSTs the source to `/app/saveOrUpdateJson` on the configured hub, captures the new app type's ID).

If the hub's compiler rejects the source — a typo, a missing import, an undefined method — Claude Code reads the error verbatim from the hub response, identifies the line and column, edits the file to fix it, and re-pushes. You watch the round-trip in the agent's narration. If a hypothesis looks wrong, you interrupt.

After the first push, subsequent edits use [`/hubitat-push`](.claude/skills/hubitat-push/SKILL.md) — same idea but matches the existing app type by name and POSTs a new version. Claude Code switches automatically; you don't have to track which skill applies.

### 4.3 Iterate

Subsequent changes go the same way. Tell Claude Code what you want next:

> "Add a setting for whether the chime fires only at night, defaulting to off. Use Hubitat's mode capability so I can wire it to my Night mode later."

Claude Code reads the relevant section of [`ARCHITECTURE.md`](ARCHITECTURE.md) and the existing app for naming conventions, edits the file, invokes `/hubitat-push`, and reports the new compile status. If you want to read the diff before it pushes, ask.

**A caveat baked into the workflow:** pushing source does NOT call `updated()` or `initialize()` on already-installed instances. The new code is loaded immediately (Groovy is interpreted), but existing subscriptions and `state` from the previous code remain until the user re-saves the app's preferences in the hub UI — or until the version-constant in `initialize()` catches the change on the next event and reconfigures itself. This is why the version constant matters; without it, an edit that changes the subscription shape silently leaves old subscriptions in place. See [`ARCHITECTURE.md`](ARCHITECTURE.md) §Version constants and code-push detection.

### 4.4 Install an instance for smoke-testing

You now have the *app type* on the hub. To exercise it, you need an *installed instance* with a real contact sensor and switch wired up.

For a one-off smoke test, the easiest path is the hub UI: **Apps → Add User App → Doorbell Chime Child**, pick two devices, Done. (The Hubitat UI is fine for one-time configuration; the workflow doesn't try to replace it.)

For repeatable rigs — which is what [`/hubitat-behavior-test`](.claude/skills/hubitat-behavior-test/SKILL.md) builds in §5 — the supporting skills are [`/hubitat-create-device`](.claude/skills/hubitat-create-device/SKILL.md) (virtual devices) and [`/hubitat-app-device`](.claude/skills/hubitat-app-device/SKILL.md) (add/remove devices on an instance). You won't drive them directly today; the behavior-test skill orchestrates them for you.

### 4.5 Smoke-test the live behavior

Once an instance exists, tell Claude Code: "open the front door sensor and check the chime fires." Claude Code invokes [`/hubitat-run`](.claude/skills/hubitat-run/SKILL.md) to send `open` via Maker API to the contact sensor, waits a moment, then reads the chime switch's `switch` attribute and reports `on` or `off`. If it stays `off`, Claude Code reads the hub log stream (via `scripts/lib/logsocket.py`, the same WebSocket subscriber the behavior-test framework uses), hypothesizes a cause — subscription didn't bind, handler-name typo, version-constant didn't run — proposes a fix, edits, re-pushes, retries. You watch and steer.

### 4.6 Inspect

Throughout the loop you'll want to confirm what's on the hub. Ask Claude Code:

- "What apps are installed on @cabin?" → invokes `/hubitat-list @cabin apps`
- "Find the chime switch's device ID" → invokes `/hubitat-list devices` and filters
- "What's actually subscribed to that contact sensor?" → invokes `/hubitat-list apps` and the per-instance config endpoint

[`/hubitat-list`](.claude/skills/hubitat-list/SKILL.md) is mechanical (it just calls `/hub2/{userDeviceTypes,userAppTypes,devicesList,appsList}`); Claude Code's value-add is knowing which slice to fetch and how to summarize it for the question you actually asked.

That's the daily edit loop. Describe intent → Claude Code reads conventions and edits → Claude Code invokes the right skill → Claude Code parses and reports → you redirect. The whole cycle for a small change is well under a minute, and most of it is the hub's compile time, not the agent's thinking time.

---

## 5. Writing tests — Mode 1 behavior tests

This is the lever the rest of the workflow rests on. Once an app has a Mode 1 test, you can edit, push, and run the test in a tight loop — and an agent can run that loop on your behalf, because the test satisfies the closed-loop contract defined in [`TESTING.md`](TESTING.md) §1.1.

### 5.1 The contract, in one paragraph

A Mode 1 test is a single bash script under `tests/` that satisfies five rules: runnable with one command (`bash tests/test-foo.sh [@hubname]`), no hardcoded hub IPs (reads `.hubitat.json`), auto-discovers the app instance via `/hub2/appsList`, exits 0/1/2 (pass / asserts failed / couldn't run), and prints `[PASS]/[FAIL]/[WARN]/[INFO]` lines plus a final `N passed, M failed, K warnings` summary. The full rules — idempotent setup/teardown, no production mutation, bounded wall-clock — are in [`TESTING.md`](TESTING.md) §1.1. The contract exists so an agent (or you) can run a test, read the result, and loop on it without human glue.

Nobody hand-writes the bash. You describe the test cases; Claude Code drafts a YAML spec; the [`/hubitat-behavior-test`](.claude/skills/hubitat-behavior-test/SKILL.md) skill provisions the rig on the hub and renders the bash from a template.

### 5.2 Ask for the test

Say: "Add a Mode 1 behavior test for DoorbellChimeChild. Cover the baseline, the open→on trigger, and the auto-reset after `chimeSeconds`."

Claude Code reads [`TESTING.md`](TESTING.md) §1.1 (closed-loop contract) and §2.1 (Mode 1 worked example), opens [`apps/sensors/tests/spec-sadc.yaml`](apps/sensors/tests/spec-sadc.yaml) as the canonical spec shape, and drafts something like this at `apps/example/tests/spec-doorbell-chime-child.yaml`:

```yaml
# Doorbell Chime Child — Mode 1 behavior test spec (tutorial example)

app:
  source: apps/example/DoorbellChimeChild.groovy
  type_name: "Doorbell Chime Child"
  instance_label: test-doorbell-chime
  config:
    appName: test-doorbell-chime
    chimeSeconds: 3        # short so the test stays fast
    debugEnable: true

inputs:
  - { name: test-doorbell-in,  driver: Virtual Contact Sensor, role: doorSensor }

outputs:
  - { name: test-doorbell-out, driver: Virtual Switch,         role: chimeSwitch }

maker_api:
  label: test-doorbell-maker

cases:
  - name: baseline-closed-off
    setup:
      - { device: test-doorbell-in, command: close }
    wait_seconds: 1
    assert:
      - { device: test-doorbell-out, attribute: switch, value: "off" }

  - name: open-triggers-chime-on
    actions:
      - { device: test-doorbell-in, command: open }
    wait_seconds: 1
    assert:
      - { device: test-doorbell-out, attribute: switch, value: "on" }

  - name: auto-reset-after-grace
    wait_seconds: 4        # chimeSeconds (3) + 1s buffer
    assert:
      - { device: test-doorbell-out, attribute: switch, value: "off" }

runtime_budget_seconds: 30
```

Review it the same way you reviewed the Groovy. The spec is small and declarative on purpose — the `app:` block names the source + type + test-instance label and the preferences to apply; `inputs`/`outputs` declare the virtual rig devices and which preference (`role:`) each one wires into; `maker_api:` names the dedicated test Maker API instance; `cases:` is a sequence of `setup` → `actions` → `wait_seconds` → `assert`.

Two subtleties Claude Code already accounts for in the draft, both learned the hard way in this repo:

- **`"on"` and `"off"` are quoted.** YAML 1.1 parses bare `on` as boolean True, which the skill would then render into Maker API URLs as the literal `True` — and `POST /devices/<id>/True` 404s silently. Always quote.
- **Numeric assertions need `tolerance: <float>`.** Our chime case asserts a discrete switch attribute, so exact string compare works. If you assert a temperature, add `tolerance: 0.1` because Hubitat formats/rounds floats.

Lots more spec features (button clicks, log-pattern assertions, event-stream assertions, per-case log-guard tuning) are documented at the top of [`.claude/skills/hubitat-behavior-test/SKILL.md`](.claude/skills/hubitat-behavior-test/SKILL.md). For most behavior tests you'll only need what's above.

### 5.3 Run it

Tell Claude Code: "Run the test." It invokes [`/hubitat-behavior-test apps/example/tests/spec-doorbell-chime-child.yaml`](.claude/skills/hubitat-behavior-test/SKILL.md), and the skill does the heavy lifting (all idempotent — re-running on a hub with the rig already in place takes seconds):

1. Picks the target hub from `.hubitat.json` (`@hubname` if you named one).
2. Validates the spec (every device in `cases` appears in `inputs`/`outputs`).
3. Ensures the app type `Doorbell Chime Child` is installed (delegates to `/hubitat-install` if not).
4. Ensures a test app instance labeled `test-doorbell-chime` exists, creating it if not.
5. Ensures virtual devices `test-doorbell-in` and `test-doorbell-out` exist.
6. Ensures a Maker API instance labeled `test-doorbell-maker` exists, with both virtual devices added to it.
7. Discovers the Maker API's access token (it's embedded in the configPage HTML, not in `settings`).
8. POSTs the `config` block into the test app instance's settings.
9. Renders `apps/example/tests/test-doorbell-chime.sh` from [`test-template.sh.tmpl`](.claude/skills/hubitat-behavior-test/test-template.sh.tmpl), substituting in the rig IDs and the cases.
10. Runs the generated test once.

The output Claude Code shows you looks roughly like:

```
[INFO] Starting test-doorbell-chime against @maison-pro (instance 1234)
[INFO] Case 1: baseline-closed-off
[PASS] test-doorbell-out.switch == off
[INFO] Case 2: open-triggers-chime-on
[PASS] test-doorbell-out.switch == on
[INFO] Case 3: auto-reset-after-grace
[PASS] test-doorbell-out.switch == off

3 passed, 0 failed, 0 warnings
```

Exit code `0`. Done.

### 5.4 When the test fails

This is where the loop earns its keep. Suppose case 2 came back `[FAIL] test-doorbell-out.switch == off` — the chime didn't fire. Claude Code parses the `[FAIL]` line, hypothesizes a small set of causes (the subscription didn't bind because `initialize()` didn't run on the test instance; the handler name passed to `runIn` doesn't match the method; the version-constant didn't trip on first install so the old config is in place), reads the relevant section of `DoorbellChimeChild.groovy`, picks the most plausible cause, proposes a one-line fix, re-pushes, and re-runs the test. You see the diagnosis and the diff before it pushes; if the hypothesis is wrong, you redirect.

If the failure is genuinely a "first run is flaky, retry" — Hubitat occasionally needs a beat for a freshly-configured instance to settle — Claude Code reruns the test once before doing anything else. The skill's own report tells it so.

The test artifact at `apps/example/tests/test-doorbell-chime.sh` is self-contained: once it exists, you (or Claude Code, or a CI job) can run it without invoking the skill again:

```bash
bash apps/example/tests/test-doorbell-chime.sh           # against default_hub
bash apps/example/tests/test-doorbell-chime.sh @test     # against the @test hub
bash apps/example/tests/test-doorbell-chime.sh @test 1234  # explicit instance id
```

This is the tight inner loop. "Make this change, re-run the test" → Claude Code edits → `/hubitat-push` → `bash apps/example/tests/test-doorbell-chime.sh` → reports. Sub-30-second cycle. Commit the spec *and* the generated test alongside your app change; the generated test is a real artifact, not throwaway.

### 5.5 Running everything

When you want a full sweep — "run every test in the repo against @test" — Claude Code invokes the top-level runner. You can also call it directly from a shell, or wire it into a pre-merge hook later. The runner discovers every test:

```bash
bash scripts/run-tests.sh                  # all tests, default_hub
bash scripts/run-tests.sh @test            # all tests, @test hub
bash scripts/run-tests.sh --list           # just print what would run
bash scripts/run-tests.sh --filter doorbell  # only tests whose path matches
bash scripts/run-tests.sh --verbose        # stream each test's stdout live
```

The runner finds every `**/tests/test-*.sh`, `**/tests/test-*.js`, and `**/tests/test_*.py` (Mode 3 JS unit tests and Mode 4 Python mirrors — see [`TESTING.md`](TESTING.md) §2.1), forwards `@hubname` to the bash ones, and aggregates the exit codes. Final exit is `0` if every test passed, `1` if any assertions failed, `2` if any couldn't run.

A test can opt out by including the literal marker `TEST-EXCLUDE` in its first 20 lines.

### 5.6 When Mode 1 isn't the right shape

The five test modes in [`TESTING.md`](TESTING.md) §2.1 are summarized as:

- **Mode 1** — behavior tests for automation apps (what we just did). Lead tier.
- **Mode 2** — API integration tests for apps that serve `/api/*` routes (HubDiagnostics, RuleLoggingManager).
- **Mode 3** — pure-JS unit tests for SPA helpers (HubDiagnostics dashboard).
- **Mode 4** — Python-mirror tests for pure Groovy logic (classifiers, parsers).
- **Mode 5** — in-hub stress apps, where the app itself is the test (async-HTTP, UDP, file-manager throughput).

The tiered bar — which modes new code is required to ship with — is in [`TESTING.md`](TESTING.md) §2.2. For now, the rule is: behavior apps need Mode 1; everything else is judgment.

---

## 6. Cross-hub deployment

If your Hubitat hubs are linked to a single Hubitat cloud account, code published from one hub can be pulled into the others without manually re-importing on each hub.

```
/hubitat-publish apps/example/DoorbellChimeChild.groovy
```

What this does: triggers Hubitat's `/hub/publishCode/{type}/{id}` endpoint on the hub you pushed from, then polls `/hub/publishCode/status` until distribution is complete, reporting each peer hub's status (`Pending` / `Done`). Per the memory rules in this repo: **publish from the same hub you pushed to** — `/hubitat-publish` does not push code that isn't on the originating hub already, so the typical flow is `/hubitat-push @main` followed by `/hubitat-publish @main`.

`/hubitat-publish` works for both apps and drivers. It does not propagate installed instances or their configuration — only the code. After publishing, you'll still need to install the app type on each peer hub (or use `/hubitat-behavior-test` on each, which provisions an instance idempotently).

---

## 7. The skill catalog

All thirteen skills live in [`.claude/skills/`](.claude/skills/). Each has a `SKILL.md` with full instructions; the one-liners below are the `description` field from each frontmatter. Group is the workflow phase where you'd reach for it.

### Inspect

| Skill | Purpose |
|---|---|
| `/hubitat-list` | List drivers, apps, and devices on the Hubitat hub |
| `/hubitat-arch-review` | Audit Groovy app/driver files against the project's ARCHITECTURE.md and any per-project arch docs, reporting concrete deviations with doc citations. Read-only — never modifies files. |

### Code lifecycle

| Skill | Purpose |
|---|---|
| `/hubitat-install` | Create, install, and configure a Hubitat app or driver from a local Groovy file |
| `/hubitat-push` | Push Groovy app or driver code to Hubitat hub and report compile status |
| `/hubitat-publish` | Publish a driver or app to other Hubitat hubs on the same account |
| `/hubitat-delete` | Delete a Hubitat installed app instance, app type, or driver type from the hub, with mandatory confirmation |

### Install & configure

| Skill | Purpose |
|---|---|
| `/hubitat-create-device` | Create virtual devices on the hub |
| `/hubitat-app-device` | Add or remove devices from a Hubitat app instance (e.g., Maker API) |
| `/hubitat-oauth` | Add self-enabling OAuth to a Hubitat Groovy app so the user never has to manually enable it in the code editor |
| `/hubitat-filemanager` | Upload, download, list, or delete files on the Hubitat hub's File Manager |

### Runtime control

| Skill | Purpose |
|---|---|
| `/hubitat-run` | Send commands to Hubitat devices or interact with app instances |
| `/hubitat-app-button` | Click a button-type preference on an installed Hubitat app (invokes the app's appButtonHandler), without UI clicks |

### Testing

| Skill | Purpose |
|---|---|
| `/hubitat-behavior-test` | Generate a Mode 1 behavior test for a Hubitat automation app from a YAML spec — provisions the test rig idempotently (virtual devices, dedicated Maker API instance, dedicated app instance) and writes a self-contained `tests/test-{app}.sh` that meets the TESTING.md §1.1 closed-loop contract |

For deeper detail on any skill — exactly what API endpoints it hits, what arguments it accepts, what failure modes it handles — open the matching `SKILL.md`. The behavior-test skill in particular has a long instructions section worth reading once before you write a complex spec.

---

## 8. Working efficiently

This section is for anyone who wants to be deliberate about how much context each task consumes. None of it is required — the workflow works fine without these patterns. But knowing when to reach for them keeps sessions focused and produces sharper results.

### 8.1 Direct skill invocation for routine work

When you know exactly which skill you want, type the skill invocation directly:

```
/hubitat-push apps/foo.groovy @hubname
```

The agent runs the skill without first deliberating about which skill to use or whether to use one at all. Same result, less round-tripping. The intent-driven form ("push this file") still works — direct is just faster when you already know.

### 8.2 The `./hub` CLI for ops that don't need reasoning

Some operations — pushing a known file, listing devices, tailing logs, sending a command, checking hub status — don't need an agent. The `./hub` CLI dispatches them straight to `curl`:

```
./hub push apps/foo.groovy @hubname
./hub list devices @hubname
./hub logs @hubname
./hub run "<device-id> on" @hubname
./hub status @hubname
./hub --help
```

The agent is for thinking; the CLI is for doing. Reach for the CLI when you don't need the agent to read code, weigh tradeoffs, or interpret output.

### 8.3 Heavy vs. light skills — a taxonomy

Skills vary in how much reference material they pull in:

- **Light:** `/hubitat-list`, `/hubitat-push`, `/hubitat-run`, `/hubitat-app-button`, `/hubitat-publish`, `/hubitat-delete`, `/hubitat-filemanager`, `/hubitat-create-device`
- **Heavy:** `/hubitat-behavior-test`, `/hubitat-arch-review`, `/hubitat-perf`, `/hubitat-oauth`, `/hubitat-app-device`, `/hubitat-install`

Heavy skills run multi-step workflows and consult `ARCHITECTURE.md` / `TESTING.md`. They're the right tool when their job is what you actually need. They're the wrong tool when a light skill or the `./hub` CLI would handle it.

### 8.4 One hub per task

If a task only needs one hub, target one hub. Read-only operations against an auth-free hub are the cheapest end of the spectrum. Cross-hub work (publishing a driver to every hub, diffing two hubs) costs more by nature — reach for it when you're actually doing cross-hub work, not as a default.

### 8.5 Single-purpose sessions

A focused session ("write a behavior test for X," "fix the bug in Y," "review Z") keeps context lean and the prompt cache warm. Long mixed sessions inflate the context as the agent shifts gears and re-loads reference docs. Splitting a workday into a few focused sessions tends to produce sharper results than one continuous one.

---

## 9. Going deeper

You now have the workflow. The reference material below is what you reach for when the workflow's defaults aren't enough.

- **[`ARCHITECTURE.md`](ARCHITECTURE.md)** — the platform-level design guide. Read it cover to cover at least once. The sections you'll come back to most:
  - *Platform constraints* — what the Hubitat sandbox blocks, where `sendEvent` deduplicates silently, why pushing source doesn't fire `updated()`, the 8-call async-HTTP concurrency cap.
  - *State tiers: `state`, `atomicState`, `@Field static`* — three storage options, very different durability and cost. Picking wrong is a real bug source.
  - *Coding conventions* — static typing, version constants, `@CompileStatic`, the lifecycle skeleton.
  - *Async HTTP callback contract* and *When sync HTTP is the right call* — the project's stance on the async/sync HTTP split, with concrete examples.
  - *Apps* and *Drivers* sections — OAuth-served endpoints, settings migration, parent/child patterns, Zigbee parse skeleton, command building.

- **[`TESTING.md`](TESTING.md)** — the test framework. The sections you'll come back to most:
  - §1.1 — the closed-loop contract.
  - §2.1 — the five test modes, with canonical examples for each.
  - §2.2 — the tiered bar by artifact type.
  - §4 — patterns to avoid (mocking hub responses, hardcoding IPs, snapshots without refresh procedures).

- **[`apps/HubDiagnostics/ARCHITECTURE.md`](apps/HubDiagnostics/ARCHITECTURE.md)** and **[`apps/HubDiagnostics/tests/TEST_PLAN.md`](apps/HubDiagnostics/tests/TEST_PLAN.md)** — worked examples of per-project architecture and test plans that extend the platform-level docs. Use these as templates when a project gets large enough to need its own design document.

- **[`ARCHITECTURE_CANDIDATES.md`](ARCHITECTURE_CANDIDATES.md)** and **[`TODO.md`](TODO.md)** — lower-priority observations and the open backlog. Worth a skim to see what's queued.

When you're considering a non-trivial change, run [`/hubitat-arch-review`](.claude/skills/hubitat-arch-review/SKILL.md) against the file you're editing. It's read-only — it just reports deviations from `ARCHITECTURE.md` with doc citations, so you find out at edit time rather than at code-review time.

### Building a dashboard SPA — conventions

Some apps in this repo aren't automations — they're **dashboards**: a Groovy app that serves a single-page HTML app and backs it with `/api/*` JSON endpoints (e.g. [`apps/HubDiagnostics/`](apps/HubDiagnostics/), [`apps/MultiHubInventory/`](apps/MultiHubInventory/)). [`apps/HubDiagnostics/hub_diagnostics_ui.html`](apps/HubDiagnostics/hub_diagnostics_ui.html) is the reference implementation — match its conventions rather than inventing simpler ones (a leaner dashboard built without them tends to look done but be unusable).

**The served-HTML pattern**

- The HTML lives in the hub's File Manager; the Groovy serves it via `downloadHubFile()` from a `mappings` route (`path('/ui.html') { action: [GET: 'serveUI'] }`) behind an OAuth check.
- `serveUI()` substitutes `${access_token}` and `${api_base}` placeholders into the HTML so the page can call its own `/api/*` endpoints **same-origin** (browsers can't reach another hub directly — see [`ARCHITECTURE.md`](ARCHITECTURE.md) "Cross-origin (CORS)…").
- **Two-part deploy:** push the Groovy *and* upload the HTML to File Manager — pushing only the Groovy leaves the UI stale. Keep `APP_VERSION` (Groovy) and `UI_VERSION` (HTML) in lockstep.
- Develop hub-free behind a `WORKBENCH = true` toggle + mock data, and unit-test the pure SPA helpers in Node (Mode 3 — see [`TESTING.md`](TESTING.md) §2.1).

**Display conventions (these are the difference between a demo and a usable tool)**

- **Device names are always links.** HubDiagnostics' helper: `dlink(id,n) → <a href="/device/edit/{id}" target="_blank">{name}</a>`. A flagged device you can't click is a device you can't find. In a *multi-hub* SPA the link must point at the device's **own hub** (`{hubWebBase}/device/edit/{id}`), not the serving hub — so expose each hub's web base (scheme+host only, never the token) to the page.
- **Counts link too:** `dlistlink(count, ids) → /device/list?ids=...`.
- **Inactivity is absolute days since last activity, color-thresholded** — never a bare timestamp. Tiers mirror HubDiagnostics: `< inactivityDays` (default 7) green (`--ok`); `≥ inactivityDays` orange (`--warn`); `≥ 2× inactivityDays` (14d) red (`--crit`); `"Never"` if no activity.
- **Classify devices properly.** The raw `controllerType`/protocol field is unreliable (null on most devices; leaks codes like `HKC`). Reuse HubDiagnostics' `classifyDevice` cascade (authoritative `isZigbee`/`isZwave`/… flags + a parent-app → integration lookup), not the raw field.
- **Reuse the UI primitives:** the sortable/filterable table helper (`tbl()`), the badge classes, and the card helpers — over one-off implementations.
- **Header links:** the SPA header should include a **⚙ Settings** link to the app's Hubitat config page (`/installedapp/configure/{id}`, derived from `api_base` by matching `^(https?://[^/]+)/apps/api/(\d+)`) and a **Docs ↗** link to the app's README on GitHub — matching HubDiagnostics. Omit the Settings link when `api_base` is the literal placeholder (WORKBENCH mode — no match).

---

## 10. Troubleshooting

**Hub unreachable** — `/hubitat-list` errors out, or `/hubitat-push` times out. Check `.hubitat.json`: the `hub_ip` must be right, you must be on the same LAN as the hub (or have a route to it), and if the hub has security enabled, the `username` and `password` must be correct. Confirm with `curl -s http://<hub_ip>/hub2/hubData` from the same shell — if that returns JSON, the skill should work too.

**`[FAIL]` on a behavior-test's first run, but pass on re-run** — when `/hubitat-behavior-test` creates an instance for the first time and applies its config in the same pass, occasionally the config save lags slightly and the first case fires before the app's `initialize()` has fully wired up subscriptions. The skill's own report mentions this verbatim ("If this is the first run, the app's config may need a moment to apply — re-run once"). Re-run the test; if the second run still fails, it's a real failure.

**Skill doesn't appear / unknown command** — make sure you're running Claude Code from the repo root, not from a subdirectory. Skills are discovered from `.claude/skills/` relative to your working directory. If the skill is recognized but a bash command inside it isn't allowed, the permission prompt will say so — accept it once and the allowlist in `.claude/settings.local.json` should remember.

**Compile error on push** — `/hubitat-push` reports the hub compiler's error verbatim, usually with a line and column. Fix in the file and re-push. The hub does not retain partial compilation state; the previous successfully-compiled version stays in place until a successful push replaces it.

**Stale test rig blocking a fresh test run** — if a prior test left a partially-configured app instance or a Maker API instance that's now broken, use `/hubitat-list apps` to find the offending instance, then `/hubitat-delete` to remove it. The next `/hubitat-behavior-test` run will recreate it cleanly.

**Maker API access token missing** — `/hubitat-behavior-test` reports "no access token found" on the Maker API instance. OAuth probably isn't enabled on that instance. Open `http://<hub_ip>/installedapp/configure/<maker-api-id>` in a browser, scroll to the OAuth section, and confirm OAuth is enabled. `/hubitat-oauth` handles this for user apps but not for built-in apps like Maker API.

---

That's the workflow. Edit, push, install, test, publish — all mediated by skills, all driven from the command line, all reproducible. Welcome aboard.
