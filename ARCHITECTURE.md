<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Development Architecture Guide

This document captures architectural principles and platform constraints that apply across all Hubitat Groovy development in this repository — apps, drivers, and integrations alike. Per-project guides (for example, `apps/HubInspector/ARCHITECTURE.md`) build on top of this one and add the specifics of their own design.

Treat this guide as the default. Project-level guides may extend specific sections, but the platform constraints below are not negotiable: violating them produces silent failures or lost work.

Conventions apply to new code and to files when they are edited. Existing files adopt them when touched, with no sweep, so an architecture review flags a deviation only in code the change touches.

The guide is organized in three parts: **Common** principles that apply to anything built for Hubitat, then **Apps**-specific guidance, then **Drivers**-specific guidance. A companion file [`ARCHITECTURE_CANDIDATES.md`](ARCHITECTURE_CANDIDATES.md) holds lower-priority observations from the codebase review, kept aside for later reconsideration.

**Hubitat platform reference.** Platform mechanics — lifecycle methods, capabilities, app and driver metadata, OAuth, Zigbee helpers, etc. — are covered authoritatively at <https://docs2.hubitat.com/en/developer>. This guide does not duplicate that material. It focuses on project-specific conventions, workarounds for platform quirks, and failure modes that are easy to miss.

## Common

### Platform constraints

Several standard Groovy and Java patterns are blocked or behave differently in the Hubitat sandbox. A *verified* mark names the firmware a platform fact was last measured on; the evidence is in `docs/hubitat-platform-notes.md`, marked as [`docs/EVIDENCE.md`](docs/EVIDENCE.md) describes, and `apps/tests/test-architecture-claims.sh` re-checks it. Re-run that test after each major firmware update. Statements without a mark are project conventions.

- **`value.getClass()` is sandbox-blocked.** The hub rejects the code when it is saved, so a `try`/`catch` can't guard it. Use the global `getObjectClassName(value)` to get a runtime class name string. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))*
- **Change an `atomicState` collection by writing the whole value back** (or `atomicState.updateMapValue(key, mapKey, value)` in an app; drivers don't have it). In-place changes to an `atomicState` collection (`atomicState.m.k = v`, `atomicState.l << v`, or through a local alias) are lost, because `atomicState` hands back a copy. In-place changes to plain `state` collections persist, in apps and drivers, from every entry point *([verified 2.5.2.134](docs/hubitat-platform-notes.md#platform-behavior))*. Key `state` maps by `id.toString()`: numeric keys come back as Strings, so `state.m[device.id]` misses after a reload.
- **Pushing source code does not trigger `updated()`.** Updated Groovy takes effect immediately, but `updated()` and `initialize()` are not called. Subscriptions and `state` from the previous version persist until the user re-saves the app's preferences in the hub UI, and `@Field static` values are reset. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* See *Version constants and code-push detection* below for the workaround.
- **`sendEvent()` deduplicates unless someone asks for repeats.** An unchanged value without `isStateChange: true` is not stored and not delivered to default subscribers. A subscriber that passes `[filterEvents: false]` receives every repeat, and the repeats are then stored too. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* A consumer that needs every sample subscribes with `filterEvents: false`. Drivers call `sendEvent` on every report and set `isStateChange: true` only for events that are new by nature, such as button presses.
- **Concurrent async HTTP calls are capped at 8 per app, and at 5 per destination host and port** (most likely the HTTP library's default connection pool; see the platform notes). Calls beyond either limit queue and complete later; none are lost. The call's `timeout` includes the queue wait, so every callback arrives within the timeout of dispatch, and a reaper deadline set above the timeout is safe *([verified 2.5.2.134](docs/hubitat-platform-notes.md#platform-behavior))*. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* Code that fans out one request per device serializes behind the pool at scale. Prefer batched or aggregated endpoints.

### State tiers: `state`, `atomicState`, `@Field static`

Three storage options are available, with very different durability and cost:

- **`state`** — persisted to the hub database; committed when the method exits. Survives hub restarts. The default.
- **`atomicState`** — persisted to the hub database; committed on every write. Survives hub restarts, and a write survives the handler failing afterwards. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* Use for flags another thread must see right away (scan progress) in apps. In a driver, a `state` write-back at method exit overwrites `atomicState` keys written after the method's first `state` access, so driver flags such as intentional-disconnect go in a `@Field static` concurrent map instead *(verified 2.5.2.134)*. It does **not** make a read-modify-write atomic: concurrent callbacks that each read a map, add a key and write it back lose entries in `atomicState` as well as `state`. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))*
- **`@Field static`** — in-memory only, no database I/O. Survives across script executions within the same hub uptime, lost on hub restart, code push, and app/driver reinstall. It is shared by every instance of the app or driver type, so key per-instance entries by `app.id` or a scan ID. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* Use for transient scan/orchestration state, request-scoped caches with a bounded TTL, and fast-path counters where DB writes would dominate the work. HubInspector uses this deliberately, with explanatory comments at the declaration site.

  Mark a `@Field static` field with `volatile` when it may be read or written by concurrent OAuth endpoint handlers. Without `volatile`, readers may see stale values across threads.

**Gathering results from concurrent async callbacks.** Collect them in a `@Field static` `ConcurrentHashMap` keyed by instance, using `put`/`putIfAbsent` (never `get` then `put`), count completions with an `AtomicInteger`, and write the result to `state` once when the last callback arrives. That kept 20 of 20 results where a shared map in `state` or `atomicState` lost entries. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* A value that must survive on its own goes in its own top-level `state` key. The in-memory results are lost on a push or reboot, so the handler must tolerate an unfinished batch. HubInspector's device audit scan is the reference implementation.

Choosing the wrong tier is a real bug source: transient per-scan data in `state` causes unnecessary DB writes; concurrent callbacks sharing one map in `state` or `atomicState` lose entries; long-lived configuration in `@Field static` is lost on every reboot or push.

### Concurrency: when to use `singleThreaded`

Without `singleThreaded: true`, every entry point into an instance runs as soon as it arrives, in parallel with the others. The flag makes them take turns. It is cheap when turns are short and costly when they are long, so decide from the handlers, not by habit.

**What it serializes.** One execution at a time per app or driver **instance**; two instances of the same type still run in parallel. Every entry point takes part, in every combination: endpoints (`mappings`), scheduled handlers (cron `schedule()` included), async HTTP callbacks, device and location event handlers, button handlers, page renders and `updated()` in an app; commands (from an app or over HTTP), scheduled handlers, callbacks and LAN `parse()` in a driver; and a child device or child app calling into its parent (`parent.componentX()`). Waiting calls run in arrival order, each a few ms after the one before ends. A call back into the instance from inside its own execution (the app calls its child device, which calls `parent.x()`) does not wait. *([verified 2.5.1.183–2.5.3.108](docs/hubitat-platform-notes.md#platform-behavior))* The calling child's thread blocks in its `parent.x()` call while it waits, so a slow parent stalls every child that calls into it. Hubitat staff have said child-to-parent calls are not serialized, so treat this as observed behaviour, not a documented guarantee. Radio `parse()`, Maker API commands and cloud-relay requests have no direct measurement yet.

Inside a singleThreaded file, plain `state` is enough for anything written from callbacks, schedules and events, and `atomicState` only adds per-write DB cost. The flag does not cover `@Field static` data, which every instance of the type shares and reads in parallel (use `volatile` or a concurrent structure), files that other apps also write, or the `state` of a method that throws (it is dropped, flag or not).

**What it costs.** Nothing measurable while executions don't overlap: `runIn` lateness and event-to-handler time had the same medians with and without the flag. Every cost comes from waiting *([verified 2.5.3.108](docs/hubitat-platform-notes.md#platform-behavior))*:

- **Latency adds up.** A call waits for every call queued ahead of it. A polled UI or API endpoint feels each slow job or callback directly.
- **A call that waits more than 120 s is dropped.** The hub logs `Failed to acquire single-threaded gate for app <id>, method <name> within 120 seconds`, and the call never runs: an event is lost to the app, a scheduled job is skipped, an endpoint client gets no reply. An instance can never have more than 120 s of handler time queued, whatever the source. A hub restart can reach it on its own: right after one, ordinary methods ran for two minutes, and flagged apps lost what queued behind them. A flagged app must tolerate a dropped call (a cursor that only advances when a response is processed, a poll that runs again).
- **Waiting event handlers stall event delivery for the whole hub.** Each waiting event holds one of the hub's event-delivery slots. With about 40 waiting, every other app's event handlers started 1.2 to 5.7 s late until the backlog fell. Queued scheduled jobs did not do this.
- **Two flagged instances that call each other can hang until the 120 s drop.** A flagged app called its flagged child device while the child was inside a `parent.x()` call back to the app *([observed 2.5.2.139](docs/hubitat-platform-notes.md#platform-behavior))*. A flagged app that calls its own endpoint synchronously waits until its HTTP timeout.
- **Callers block too.** An app's command to a flagged driver, and a child's call to a flagged parent, wait on the caller's thread, which holds up the caller's own queue if it is flagged as well.
- **In-app tests that wait for the app's own handlers can't work.** In SensorAggregatorDiscreteChild, 21 of 43 tests failed because sensor events queued behind the test button *(observed 2.5.2.134)*.

**Use it** when an instance has several entry points that read, change and write back shared `state`, and its work fits easily in the budget: every handler short (tens of ms) and non-blocking, and the most calls that can arrive together times the longest handler far below 120 s and, for events, far below 40 waiting at once. These fit:

- Automation apps over a handful of devices that keep many `state` keys (HumidityFanController, SwitchMonitor, MirrorSwitch).
- Apps that write device attributes they also subscribe to (setpoints sent to a thermostat). The echo of the app's own write otherwise runs alongside the handler that sent it and the two overwrite each other's `state` *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))*.
- A child app or child device that owns the contended state of a concurrent parent, so only that piece takes turns: HvacInterlockGroup is flagged, HvacInterlock is not.
- One app appending to its own small File Manager files: two writers to one file lose rows, and the flag keeps one writer per instance. Appends slow as the file grows (below), so rotate files before they reach a few MB.

**Leave it off** when any of these hold, and use an alternative below:

- **A handler blocks.** Sync HTTP, `pauseExecution`, long loops, and large File Manager writes (200 to 390 ms per append at 8 MB) make everything behind them wait. Use async HTTP first; if the call must stay sync, the instance should not be flagged.
- **The instance is a fan-in point.** Integration managers and parents whose child devices call into them, apps subscribed to many devices that change together (a scene, a mode change), and apps serving a polled UI or API. One slow call stalls them all, and a burst of events can reach the hub-wide limit.
- **Two instances call each other.** Flag at most one side of a parent/child pair, the one that never calls the other synchronously while handling a call from it.
- **The code calls its own endpoints, or tests wait on its own handlers.**
- **Radio drivers.** Whether the flag serializes Zigbee and Z-Wave `parse()` is not measured ([staff have said](https://community.hubitat.com/t/80969) hub-invoked methods such as `parse()` take turns). Don't add it to fix a race on the radio path until it is.

**Alternatives, narrowest first.**

- **Start from the race, not the flag.** Name the key and the two entry points that can run at the same time and both read, change and write it back. In an app, a handler that overwrites a whole key (a flag, a timestamp, a status) is not a lost update; the last write wins, which is often what you want. In a driver it can be: concurrent handlers write the whole store back, so one can undo another's write to a different key. Then pick the narrowest fix below.
- **In an app, replace whole keys, or give each writer its own top-level `state` key,** when the contention is one map or list and an occasional lost write is tolerable. Separate keys kept 20 of 20 concurrent writes in 6 of 7 runs and 18 once, where one shared map kept 14 to 17 *(verified 2.5.2.134)*. In a driver this gives no protection; see the platform notes.
- **Use `atomicState` in an app whose total state is small** (under about 10 KB, where a write costs a few ms; the cost grows about 0.35 ms per KB of total state, verified 2.5.2.134 in an app) **and concurrent writers replace whole keys** rather than read, change and write back a shared map or counter. It keeps handlers concurrent and makes each write visible at once. It does not make a read-modify-write safe: one shared map kept 14 to 16 of 20 writes in `atomicState` *(verified 2.5.2.129)*. For that, use a `@Field static` concurrent structure or `singleThreaded`. In a driver, an `atomicState` write made after the method's first `state` access is lost *(verified 2.5.2.134)*, so keep cross-thread driver flags in a `@Field static` concurrent map keyed by `device.id`.
- **Use `@Field static` concurrent structures** for in-memory data, and when the app must keep handling work while slow calls are in flight: an app serving a polled UI or API, or one fanning out async calls whose callbacks should not queue behind each other. Keep the shared data in `ConcurrentHashMap`/`AtomicInteger`, change it only through their atomic methods, and write durable results to `state` at one point (see *Gathering results from concurrent async callbacks* above). HubInspector works this way. This data is lost on a push or reboot and shared across instances.
- **Queue the stateful work and drain it on one thread.** When many concurrent calls do a little stateful work each (a fan-in point), let them do the concurrent-safe part (matching, dedupe with `putIfAbsent`, counters) and add the rest to a `@Field static` `ConcurrentLinkedQueue`; whichever caller wins a `putIfAbsent` guard drains it, the others return at once, and the drainer checks the queue again after letting go so nothing is stranded (the serial-executor pattern in the `java.util.concurrent.Executor` docs). The drainer owns the runtime data in memory and writes snapshots with `atomicState` while it holds the guard: a `state` write is saved when its execution ends, and two executions can end in the other order. Batch slow work per pass (one file rewrite for many rows). LogMonitor is the reference.
- **Recompute from the source instead of accumulating.** A handler that reads the current device values and writes the derived result as whole keys has nothing to lose to a race; at worst a late writer leaves a slightly older result, which the next event corrects. Prefer it to counters and deltas kept in `state`.

**Default for new code.** Start a small automation app with the flag when its handlers are short and non-blocking and it keeps several `state` keys from several entry points: the flag then costs nothing and removes a class of races. Leave it off for integration managers, parents of many children, apps serving endpoints and anything that blocks, and use the alternatives above. For existing code, fix the specific race with the narrowest option, and check the *Leave it off* list before adding the flag.

### Hubitat libraries are not real modularity

Moving Groovy code into Hubitat libraries does not provide architectural separation. Library code shares the host's namespace, lifecycle, and sandbox. Treat libraries as include files for code reuse, not as modules with enforced boundaries.

### Coding conventions

**Static typing.** Use explicit types for return values, parameters, and local variables. Avoid `def`.

```groovy
void refresh() { ... }
String formatLabel(int id, String prefix) { ... }
Map jsonData = parseJson(raw)
```

Two exceptions: Hubitat callback parameters (`evt`, `resp`, `data`) stay untyped per platform convention; genuinely polymorphic values (e.g. `aValue` passed straight to `sendEvent`) stay untyped. Don't use `Object` as a substitute for `def` — it adds no value.

**Constants and pure computation.** Declare constants with `@Field static final` — a top-level variable without `@Field` reads as `null` inside methods, with no error. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* Use `@CompileStatic` on pure computation methods that don't access Hubitat dynamic properties (`settings`, `state`, `device`, etc.).

**Capabilities.** Use current capabilities, not ones the [capability list](https://docs2.hubitat.com/en/developer/driver/capability-list) marks deprecated. Use `capability "Refresh"` for pollable devices, not `capability "Polling"`.

**Integer division.** `Long / Long` and `int / int` yield a `BigDecimal`, which breaks overload resolution at runtime (`Math.max(0L, ms / 1000L)` throws `Ambiguous method overloading`). Use `.intdiv()`.

### Lifecycle skeleton

The standard lifecycle methods (`installed`, `updated`, `uninstalled`, `initialize`) are platform-defined; two project conventions matter on top:

- **The lifecycle has a single convergence point.** Both the install path and the preferences-saved path route through it so subscriptions, schedules, and version checks live there exactly once. The convergence method depends on the file shape:
  - **Apps** and **drivers with persistent runtime state** (LAN/cloud sockets, OAuth tokens, reconnect logic): `initialize()`.
  - **Local-radio drivers** (Zigbee, Z-Wave) with no startup work: `configure()`. `initialize()` is omitted — don't add an empty stub or one that only calls `configure()`. `Drivers → Driver lifecycle` expands on this.
- **`updated()` resets before reinitializing** — `unsubscribe(); unschedule(); <convergence>` (drivers omit `unsubscribe()`). Same-handler-name `runIn`/`runInMillis`/`runOnce`/`schedule` calls are self-cancelling because the platform's `options.overwrite` defaults to `true` *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* — so a static handler name re-scheduled in the new config does not need `unschedule()` to avoid accumulation. The defensive `unschedule()` matters in narrower cases: handler names that change across configs (e.g., `"check_${index}"` when the index shifts), handlers scheduled from event handlers that the new config no longer fires, and any call site that passes `[overwrite: false]`. Calling `unschedule()` unconditionally remains the convention because it's cheap and protects against all three.

### Version constants and code-push detection

Because pushing source code does not trigger `updated()`, code-aware reconfigure is up to the file itself. The idiom: declare a version constant, and on every entry into a known-reachable lifecycle path, compare it against `state.version` and run any necessary reconfigure.

```groovy
@Field static final String CODE_VERSION = "1.2.0"

void initialize() {
    if (state.version != CODE_VERSION) {
        log.warn "New version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
        // run reconfigure if needed
    }
    ...
}
```

For Zigbee drivers, place the check in `parse()` instead, so the device auto-reconfigures on the first event after a code push (the user doesn't have to re-save preferences). Trigger the reconfigure via `runInMillis` so it doesn't run inline with parsing.

An app's `initialize()` only runs on install or save, so a check there alone sees a push only at the next save. Apps wrap the comparison in a `checkVersion(boolean reinit = true)` helper: `initialize()` calls `checkVersion(false)` to record the version, and every event handler, scheduled handler, endpoint and `appButtonHandler` calls `checkVersion()`, which logs the change with `logVer`, records it and schedules `runIn(1, "updated")`. A push then behaves like pressing Done on the first event after it. Drivers call the same check from the entry point that runs on its own after a push (`parse()`, a poll, the parent-fed update or socket handler) and re-run their convergence point: `configure()` for radio drivers, `updated()` for socket and cloud drivers (it closes the old connection before `initialize()` opens a new one), nothing for virtual and parent-fed children.

### Logging discipline

Every app and driver should expose three boolean preferences and a small set of gated helpers.

```groovy
input name: "txtEnable",   type: "bool", title: "Enable info logging",            defaultValue: true
input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
if (debugEnable) {
    input name: "traceEnable", type: "bool", title: "Enable trace logging",       defaultValue: false
}
```

Implement the category-based emoji log helpers defined in
[`docs/logging-emoji-scheme.md`](docs/logging-emoji-scheme.md): one emoji per
line encoding the line's *category* (the UI already colors by level), with
`logWarn`/`logError` carrying severity flags and `logTrace` the raw firehose.
Each helper checks the file's existing debug/trace gate and prefixes
`${device.displayName}` (drivers) or `${app.getLabel()}` (apps). Plain
`logInfo`/`logDebug` remain emoji-less for uncategorized lines. `txtEnable` gates
all info-level output (`logInfo` and the info-level category helpers), with an
unset value counting as on; version notices (`logVer`, warn level), warnings and errors always log.

Auto-disable debug and trace after about 30 minutes:

```groovy
if (debugEnable || traceEnable) runIn(1800, "logsOff")
```

The `logsOff` handler clears both flags via `device.updateSetting` / `app.updateSetting` and logs that it did with `logWarn`, so the reason debug output stopped shows whatever the info setting. One-time notices such as settings migrations also use `logWarn`.

### Date handling

Hubitat hub endpoints return ISO 8601 strings with numeric timezone offsets, e.g. `"2026-05-05T23:07:43.088-0400"`. Browsers have not always agreed on parsing an offset without a colon (`-0400`), and an unparseable date fails silently as `Invalid Date`. Current WebKit and V8 both parse it, but older engines are untested. *(unverified for older browsers)*

Always convert timestamps to epoch milliseconds in Groovy before including them in any UI or external API response:

```groovy
long ts = 0
try { ts = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", (String) raw.date).time } catch (Exception ignored) {}
```

In a SPA or other consumer, format with `new Date(ts).toLocaleString()`. Never pass a raw ISO offset date string through to a UI as a display field.

### State and caching discipline

Use persistent `state` only for data that should survive app reloads or is intentionally durable across requests. Use volatile or static in-memory fields when loss on JVM reload is acceptable. Avoid storing in `state` cache data that is readily available from the hub in a single fetch.

Before adding a new cache, define:

- what is cached
- when it expires
- what event clears it
- whether loss on reboot is acceptable

If you cannot explain invalidation in one or two sentences, the cache design is not ready.

### Never store `DeviceWrapper` (or other live platform proxies) in `state`

`state` and `atomicState` are JSON-serialized. Live platform proxies — `DeviceWrapper`, `InstalledAppWrapper`, `location`, the hub (`location.hubs[0]`), event/subscription objects — do not survive a serialization round-trip cleanly. They appear to "work" within a single method call (because the in-memory list is read back before commit), but on the next invocation each one comes back as a plain `HashMap` of device properties, and any method call on it (`it.currentValue(...)`, `it.getLabel()`) throws. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))*

Store **device IDs** (Hubitat-issued, e.g. `it.id` — string) and rehydrate at read time from the input selection:

```groovy
// write
state.includedSensors = wrappers.collect { it.id }

// read
List<DeviceWrapper> live = humiditySensors.findAll { it.id in state.includedSensors }
// or, for a single id
DeviceWrapper d = humiditySensors.find { it.id == storedId }
```

Prefer IDs over labels — labels can be renamed by the user and silently break the lookup.

Citation: Hubitat co-founder bravenel, ["No, you can't put a device in state."](https://community.hubitat.com/t/save-list-of-devices-to-stat-variable/3552) (2018). Vintage but still consistent with current community guidance ([2024 thread](https://community.hubitat.com/t/best-way-to-store-information-settings-about-devices/126036)) and with Hubitat's own example apps (e.g., [`modeSwitches.groovy`](https://github.com/hubitat/HubitatPublic/blob/master/example-apps/modeSwitches.groovy) keys `state.modeSwitch` by `dev.id`, never stores the wrapper).

### Backend owns normalization

When a Groovy app exposes data to a UI, mobile client, or external consumer, normalize raw Hubitat payloads in Groovy whenever practical: stable field names, consumer-friendly maps and lists, computed labels and classifications, firmware-difference handling, dates shaped into safe fields. The consumer should not be the place that learns hub payload quirks.

### Random jitter on recurring schedules

For cloud or external-API polling, randomize the cron offset to avoid synchronized stampedes across hubs and across schedule restarts.

```groovy
Random rng = new Random()
String cron = "${rng.nextInt(60)} */${rate} * ? * *"
schedule(cron, "refresh")
```

For sub-minute jitter on `runIn`, use `delay = intervalSecs - 7 + new Random().nextInt(15)`.

### Async HTTP callback contract

The async-HTTP API (`asynchttpGet`/`Put`/`Post`) is platform-standard. The project convention is that every callback runs three checks in order before reading the response body:

1. `resp.hasError()` — log and return.
2. `resp.getStatus() == 200` (or `207` if applicable) — log and return otherwise.
3. Then read `resp.data`/`resp.json`/etc., with a `try`/`catch` around any JSON parsing.

Pass per-request context (URL, retry count, identifying ID) through the `extraData` argument; it arrives as `data` on the callback.

### When sync HTTP is the right call

The async-HTTP contract above is the project default. It is the right tool for **background work** — scheduled polls, event-handler reactions, fan-out queries across many devices — where the caller has nothing to wait on, the workload may run concurrent with other async work, and the platform's 8-call async pool per app needs headroom.

Two cases legitimately call for sync HTTP. Both are present in this repo.

**1. Inside `dynamicPage` rendering.** Hubitat `dynamicPage` builds and returns the page Map in a single synchronous method call; there is no platform mechanism to suspend rendering and await an async callback mid-render. Any data the page needs from hub APIs has to be in hand by return time. The async escape hatch — "kick off the call, render placeholders, force a page reload when the result lands" — replaces a short blocking call with a multi-reload state machine that the user can interrupt by clicking away. It is worse, not better. Compounding the constraint: pages that iterate (one call per app/device) can issue dozens of HTTP calls per render, far past the per-app pool of 8 concurrent async calls; the excess would queue behind it and land after the page has already rendered.

Canonical example — `apps/utilities/DeviceSwapHelper.groovy`, `previewPage()`. Its scan loops over the apps using a device with sync `httpGet` calls to `statusJson` and `listJson`, and `discoverPageGraph()` renders configuration pages until it has found each input, up to 8 per app, skipping wizard apps such as Rule Machine whose inputs sit on pages it can't open. Each page is a full render: before that cap and skip, a 17-app preview took 13 s, almost all of it in 67 page renders; after, 2 to 3 s.

When even that is several seconds (a device used by dozens of apps on a C-7), the page shouldn't sit blank. The same app moves the whole sync scan into one scheduled job, `backgroundScan()`: the first render starts it with `runInMillis` and returns "Scan in progress, please wait…" with `dynamicPage(refreshInterval: 2)`; the job stores its result in `state`, and the next refresh renders it. Every later render (a checkbox click, a return from the confirm page) reuses the stored scan until Refresh Scan, a swap or the main page clears it. This is not the async escape hatch above: the work is still sync calls in one execution, there are no per-call callbacks, and with `singleThreaded: true` a refresh that arrives mid-scan simply waits for it.

**2. Driver commands that return a value to their caller.** Hubitat invokes driver commands synchronously: `setHeatingSetpoint`, `setMode`, `refresh`. If the command's body calls an external API and acts on the result — fire a device event reflecting the new state, retry on auth failure, decide whether the operation actually succeeded — the simplest correct implementation is a sync HTTP call inside the command. Restructuring around async means continuation chains: token-check callback → token-refresh callback → API-call callback → event-emit, with intermediate state stashed in `state.*` between hops. The sync form has 5 lines and obvious semantics; the async form is a 30-line state machine with new failure modes (callback never fires, state from a previous command bleeds into the next, retries race the timeout). Latency on a user-initiated thermostat command is invisible.

Canonical example — `drivers/EcobeeCompanion.groovy`. `callEcobeeApi(method, path, ...)` returns a `Map` synchronously to every command path and retries once on HTTP 401; `refreshToken()` and `checkAndRefreshToken()` return a `Boolean` that callers check before issuing API calls. The OAuth bootstrap calls in `connect()` and `authorize()` could migrate to async without any of these concerns, but they're one-shot user-triggered calls run twice in the device's lifetime — migrating only the cheapest sites would leave the file inconsistent without making it better.

**The contract, confirmed.** Async is right when the work is background, fan-out, or event-handler-shaped — the caller doesn't need a return value, latency is invisible to a user, and many calls may be in flight at once. Sync is right when the work is on the synchronous critical path of a user-facing operation and the caller structurally depends on the return value — page render, command dispatch, a dependent chain that completes inside one logical user action. The two files above are not violations of the async-HTTP contract; they are the shapes the contract carves out.

### Fail-soft defaults

For monitoring, diagnostics, and dashboard-style apps, partial data is usually better than a hard endpoint failure. Prefer returning incomplete-but-usable payloads over brittle strictness:

- map-shaped failures should degrade to `null` or `{}`
- list-shaped failures should degrade to `[]`
- text-fetch failures should degrade to `null`

This default does not apply to writes or destructive actions, which should fail loudly.

### Cloud API outages

An integration that logs an error on every failed poll pages the user once per poll for the whole outage, because Log Monitor forwards errors to a notifier. Cloud pollers follow these rules:

- Count consecutive failed polls as one streak, whichever step failed (login, fetch, parse). Commands the user runs still log an error on each failure.
- Below a small threshold of polls, log each failure at warn, since these services should not fail. At the threshold, log one error, then a warn every hour while the outage lasts, and one info line on recovery with the outage length. Log Monitor pages on errors only, so an outage pages once.
- Keep polling at the normal rate. Honor `Retry-After` when the server sends one, with a ceiling so a bad header can't stop polling for good.
- Report health on each child's `healthStatus` attribute (`online`/`offline`). No capability defines it, so drivers declare it as a custom attribute. A child also goes offline when its own data stops while the API is up, and the event's `descriptionText` gives the reason. The `online` event that ends an outage carries the recovery line ("back after 6 h 5 min, 73 polls failed").

Reference implementations: VisiblAirManager (`pollFailed()`/`pollSucceeded()`, threshold 3) and FGLairManager (threshold 5, `noteRetryAfter()`). Home Assistant's [`log-when-unavailable`](https://developers.home-assistant.io/docs/core/integration-quality-scale/rules/log-when-unavailable/) rule takes the same approach.

### Secrets: storage and display

Anyone with admin access to a hub can read app settings, `state`, device preferences and data values, attributes, events and logs, and these also end up in screenshots, forum posts and diagnostics. A secret must not be readable in any of them. It must still be identifiable enough to troubleshoot.

**What is a secret.** A value that grants access on its own or with public information: a password or PIN, an access, refresh, session or bearer token, an API key or secret, a signing or private key, a cookie, or a webhook URL whose path is the credential. Identifiers are not secrets and stay readable everywhere: IP and MAC addresses, hostnames, device, app, hub and account ids, client ids, serial numbers and usernames. Masking an identifier only makes troubleshooting harder.

**Storage.**
- A secret the user types is read once, stored with `encrypt()` in `state`, and its setting removed (`app.removeSetting()` / `device.removeSetting()`). A token received from a service is stored only encrypted.
- `decrypt()` costs 350–830 ms, so the plaintext is cached in a `@Field static ConcurrentHashMap` keyed by app or device id, holding `[ciphertext, plaintext]` so a rewritten value misses. Drop the entry when the value is cleared and in `uninstalled()`.
- Plaintext never goes into `state`, settings, data values, attributes, events, logs or scheduled-job data.
- Values stored in plain text by an earlier version are encrypted on upgrade.
- Keep secret and non-secret parts apart. In a URL only the secret parts are secret: the password in `user:pass@`, and the value of a query parameter named like a credential (`access_token`, `apiKey`, `X-Amz-Signature`, ...). Host, path and other parameters stay readable.

**Display.** Show a stored secret the way WordPress shows an API key ([`_wp_connectors_mask_api_key()`](https://developer.wordpress.org/reference/functions/_wp_connectors_mask_api_key/)):
- **Tokens and API keys** (long random values): bullets, then the last 4 characters, e.g. `access_token=••••••••a1b2`. The 4 characters let you match the value against the issuing side (Maker API's token list, an app's endpoint URL). That matters because an app can hold several OAuth tokens since 2.5.2 and the app id no longer tells them apart. Hubitat's tokens are UUIDs, and 4 hex characters give away 16 of their 122 random bits.
- **Passwords and PINs**: bullets only, `••••••••`. The end of a password is a real leak.
- Logs and events show the same masked form, so a rejected token in the logs can be matched too. Error messages name the secret that failed ("HTTP 401: the stored access_token was rejected; enter the current one").
- A field that shows a stored secret keeps it when saved unchanged, and a new value replaces it, as Node-RED does with its password placeholder ([credentials](https://nodered.org/docs/creating-nodes/credentials)). A titled input says so: "Password (stored, enter only to change it)".
- No command or button reveals a stored secret: an admin could press it.

Prior art: Node-RED keeps stored passwords out of the editor and shows a placeholder; Vercel makes secret variables "write-only after saving" and leaves config readable ([docs](https://vercel.com/docs/environment-variables/sensitive-environment-variables)); `kubectl describe secret` shows sizes instead of values ([docs](https://kubernetes.io/docs/tasks/configmap-secret/managing-secret-using-kubectl/)); OpenTelemetry keeps a URL's query keys and replaces only sensitive values ([url attributes](https://opentelemetry.io/docs/specs/semconv/registry/attributes/url/)).

### Firmware-dependent platform APIs

A platform method whose signature changed between firmware releases throws on a hub that runs the older one. Compare `location.hub.firmwareVersionString` against the first release that has the signature, store the result, and branch on it. Compare the version components as integers, since string comparison puts `2.4.10` before `2.4.9`. Reference: IPReachabilitySensor's `supportsPingTimeout()`, which picks `NetworkUtils.ping(ip, count, timeout)` when the firmware supports it and `NetworkUtils.ping(ip, count)` otherwise.

## Apps

### App lifecycle and subscriptions

Apps follow the common lifecycle skeleton. When post-reboot recovery matters (re-evaluating switch state, re-establishing connections, refreshing devices), subscribe to the system start event:

```groovy
subscribe(location, "systemStart", "systemStartHandler")
```

The handler typically refreshes devices and re-evaluates the app's monitored conditions.

**What a reboot does to scheduled work.** *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior), two reboots)* `runIn` and `schedule` jobs are stored in the hub database. A job that falls due after the hub is back runs on time, and a repeating `schedule` resumes once the hub is up, with one catch-up run for the ticks it missed. A one-shot `runIn` that fell due **while the hub was down** was dropped: it never ran and left the job list. The `systemStart` event arrived about two minutes into uptime, after the first scheduled ticks had already run. What a reboot also breaks is everything outside the job table:

- A handler interrupted mid-run loses its `state` writes (state commits at method exit). A chain that re-arms with `runIn` at the end of its handler dies there.
- In-memory data is gone: `@Field static` values, open sockets, pending async HTTP callbacks.
- Persisted flags now describe the world before the reboot (a stored "connected" mode, an "in progress" marker) and can stop an app from reconnecting or polling.
- Overdue jobs fire while radios and integrations may still be starting.

So handlers must tolerate running early in startup, before `systemStart`, and the `systemStart` handler is the place to clear flags about in-memory or connection state and re-arm chains, including any one-shot `runIn` that may have fallen due during the outage. Community reports that "runIn schedules don't survive a restart" fit the dropped overdue one-shot as well as the stale-flag and interrupted-handler cases. The self-healing pattern below covers all of them.

**Self-healing transient states.** A time-delayed state transition must never
depend solely on a single scheduled callback. Persist the transition's start
timestamp; on *every* evaluation of a transient state — and on `initialize()` —
re-derive due-ness from the timestamp and either complete or re-arm. A callback
lost to a crash mid-handler, a missed schedule, or a code push then self-heals on
the next event instead of stranding the app. Reference: HFC
`servicePendingTransition()`. Contrast with SensorAggregatorDiscreteChild's
stale-sequence approach (let orphan timers no-op and re-derive), which suits apps
where the transition is cheap to recompute from scratch. The sequence must only
count up: a reinit that restarts it at 1 lets a new event reuse a pending orphan's
number, and the orphan then commits early *(observed 2.5.2.134 before
SensorAggregatorDiscreteChild 0.3.6)*.

### OAuth-served HTTP endpoints

App-served UIs and programmatic APIs use Hubitat's per-app OAuth path (`oauth: true` + `mappings { }` + `createAccessToken()`). The architectural property that matters: endpoints reachable via `${getFullLocalApiServerUrl()}/...?access_token=${state.accessToken}` work without an active hub admin session — that is what makes app-served UIs viable for users.

An app can enable OAuth for itself on install, so nobody has to toggle it in the code editor: catch the `createAccessToken()` failure, enable OAuth through the hub's loopback API, and retry. HubInspector's `autoEnableOAuth()` / `checkOAuth()` is the reference implementation.

### Cross-origin (CORS) and multi-hub browser clients

The local OAuth API sends no CORS headers *([verified 2.5.2.129](docs/hubitat-internal-apis.md#app-oauth-endpoint-authentication-bearer-header))*, so a browser page served by one hub cannot read another hub's API response directly. Browser-based multi-hub tools therefore cannot fan out to peer hubs from the client: the cross-hub calls must run server-side on the hub that serves the page, which forwards them and returns the result same-origin.

Such a forwarding route is **not** the "pure passthrough" forbidden under API endpoint design below — that rule assumes the consumer can fetch the target directly under an admin session, which the CORS boundary makes impossible. Keep the forwarder hardened: whitelist the forwarded operations and address peers by index, never by a caller-supplied URL or token.

*(The cloud relay places every hub under one `cloud.hubitat.com` host, which would make browser-direct calls same-origin — at the cost of routing LAN traffic through Hubitat's cloud.)*

### API endpoint design

Apps that expose `/api/*` routes should classify each route into one of three categories:

- **App-owned** — exposes app state, performs a write, runs orchestration, or composes data only the app can produce. Always justified.
- **Aggregator** — fetches multiple hub resources, normalizes them, and serves a consumer-specific contract. Justified by aggregation, normalization, or shared-cache and fail-soft behavior the consumer should not duplicate.
- **Pure passthrough** — a thin wrapper over a single hub endpoint with no aggregation, normalization, or app state. **This category should be empty.** A passthrough route earns no architectural benefit; the consumer can fetch the hub directly under an admin session.

If a new route would be a pure passthrough, do not add it. Fold it into an aggregator, add real normalization, or leave the data for the consumer to fetch directly.

### App and UI version sync

When a Groovy app is paired with a UI artifact (single-page app, dashboard tile, file-manager HTML), the two are version-coupled by design. Any change that alters the API/UI contract or UI behavior must bump **both** `CODE_VERSION` constants — the Groovy one and the HTML one — in lockstep.

### Settings migration

Hub settings persist across code pushes — left-behind settings don't disappear on their own. When changing a settings schema:

1. Detect the prior schema by setting presence (e.g. `if (settings.oldField != null)`).
2. Write the new shape with `app.updateSetting(name, [type:..., value:...])`.
3. Remove the old shape with `app.removeSetting(name)`.
4. Clean obsolete state via `state.remove(...)`.

Run migration once, idempotently, at the top of `initialize()`.

The platform doesn't support nested-Map settings, so multi-instance apps typically encode per-instance fields with prefixed names (e.g. `group${N}.fieldName`) and access them via small accessor helpers.

### Parent/child patterns

The mechanics of nested apps (`app(...)` declaration, `parent: "ns:Name"`) and child devices (`addChildDevice`, `getChildDevices`, `deleteChildDevice`) are platform-standard. The project conventions on top:

- **DNI prefix scheme.** Every parent uses a stable DNI prefix (e.g. `visiblair-${uuid}`) so children are identifiable at a glance and won't collide with hand-created devices.
- **Push from parent, callback from child.** Parents push data via custom child methods (`child.updateSensorData(map)`); children call back via custom parent methods (`parent.refreshSensor(dni)`, `parent.sendFirmwareCommand(uuid, cmd)`). Avoid raw `state` sharing across the boundary.
- **The app does the work, a child device is the handle.** Automation that selects, watches or commands other devices is an app: drivers can't select devices, `subscribe()`, read hub variables or serve `mappings` *([verified 2.5.2.129](docs/hubitat-platform-notes.md#what-only-an-app-can-do))*. When rules, Maker API, dashboards, MCP or voice assistants need to drive or watch one instance, give it a child device whose commands call the parent and whose attributes report its status. The device addresses that one instance and holds no logic. Reference: HVAC Scheduler, one device per program, which fixes the built-in scheduler's ambiguous by-thermostat targeting.
- **Orphan tracking, explicit user action.** When the parent's source-of-truth changes (a sensor unenrolls upstream, etc.), diff the active DNI `Set` against `getChildDevices()` and surface orphans for the user to remove explicitly. Don't auto-delete child devices — they may carry user-edited labels, dashboard pins, or rule references.

### Choosing `singleInstance`

Set `singleInstance: true` when a second instance would duplicate work or split what belongs together:

- **Integration roots** bound to one account or a fixed DNI prefix. A second instance polls the account twice and creates child devices whose DNIs collide, so `addChildDevice` throws. Examples: VisiblAirManager, FGLairManager, BlinkManager.
- **Apps that watch or serve the whole hub.** A second instance subscribes twice and sends duplicate notifications or runs duplicate scans. Examples: HubInspector, StartupShutdownMonitor, LocationEventMapper.
- **Parents that exist to create child apps**, where the parent's page is how the user adds a new child. The admin interface lists child apps nested under their parent, so one parent keeps every child in one group behind one "add" button, and a second parent splits them into two groups. Examples: AttributeLogger, SensorAggregator, HvacScheduler.

Leave it off when each instance is one independent use (HumidityFanController, SwitchMonitor, MirrorSwitch), including apps that only own a child device as their output handle (HvacSeasonManager, IndoorAirQualityController).

## Drivers

### Driver lifecycle

The platform defines what `configure()`, `initialize()`, `refresh()`, and `deviceTypeUpdated()` mean. Two project-specific rules:

- **`initialize()` is for work that must re-execute after hub startup.** The platform calls it on hub start, install, and as part of the `updated()` convergence. Use it for LAN/cloud reconnection, re-arming any housekeeping the hub doesn't already persist (repeating `schedule` jobs survive a reboot, but a one-shot `runIn` that falls due during the outage is dropped; see *What a reboot does to scheduled work*), and idempotent state/counter seeding. For a pure local-radio (Zigbee/Z-Wave) driver with no such startup work, omitting `initialize()` is fine — and is the common case for plugs, switches, sensors, and locks. **Don't add an empty stub or one that only calls `configure()`.** When `initialize()` is omitted, `configure()` becomes the convergence point: `installed()` routes to it (typically via `runInMillis` so it doesn't run inline with the install transaction), `updated()` does its `unschedule(); <preference writes>; configure()` sequence, and `deviceTypeUpdated()` calls `configure()`. When `initialize()` *is* present, call **`refresh()`, not `configure()`** from it — reconfiguring on each hub restart wastes radio bandwidth and can race with other devices joining the mesh.
- **`deviceTypeUpdated()` should always be implemented.** The platform calls it when a device's driver type is switched. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* The convention is to log the change at debug level (`logDebug "driver change detected"`) and *only* call `configure()` when the driver author judges a reconfigure is necessary on a driver change — typically local-radio drivers that must re-apply device-side reporting and defaults. Drivers with nothing to re-apply (virtual, cloud, log/probe helpers) implement the method as a debug-log-only stub.

### Zigbee parse skeleton

`parse(String description)` and `zigbee.parseDescriptionAsMap` are platform-standard. The project shape on top:

1. Outer dispatch on five paths: attribute report (`descMap.attrId != null`), ZDO command (`profileId == "0000"`), ZHA global command (`profileId == "0104"` with no `attrId`), enroll request, and zone status/report. Log unhandled cases at trace level — silent dropping makes new device behavior invisible.
2. Inside the attribute-report path, **always iterate `descMap.additionalAttrs`** alongside the primary report. Zigbee batches related reports, and dropping them produces silent partial updates.
3. Delegate per-cluster work to a `parseAttributeReport(descMap)` helper that outer-switches on `cluster`/`clusterInt`, inner-switches on `attrId`/`attrInt`, builds a `[name, value, unit, descriptionText, type]` map and calls `sendEvent(map)`, directly or through one helper. Don't return event maps from `parse()` for the platform to send: it leaves the event's "Produced by" column empty (see the [platform notes](docs/hubitat-platform-notes.md#platform-behavior)).

### Zigbee command building

Build a `List<String> cmds`, accumulate with the `zigbee.*` helpers, then dispatch with a small wrapper:

```groovy
private void sendZigbeeCommands(List cmds) {
    hubitat.device.HubMultiAction hubAction =
        new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}
```

Manufacturer-specific clusters require `[mfgCode: '0xNNNN']` as the trailing parameter to `writeAttribute` / `readAttribute` / `configureReporting`. Easy to forget; silent failure when omitted.

### Bidirectional setting↔event sync

When a device reports a state change for a value that is also exposed as a preference (display mode, LED color, lockout state, control mode), call `device.updateSetting('prefName', [value:..., type:...])` from the parser to keep the preferences UI consistent with the device. Without this, the UI silently drifts from reality.

### `device.updateDataValue` for device metadata

Use `device.updateDataValue("key", "value")` for non-state metadata that should survive driver swaps: a driver switch keeps data values and clears `state`. *([verified 2.5.2.129](docs/hubitat-platform-notes.md#platform-behavior))* Use it for firmware version, MAC, UUID, runtime-discovered capability flags. Read with `device.getDataValue("key")`.

This is distinct from `state` (scoped to the current driver, cleared when the driver changes, shown in the device page's State Variables card) and from attributes (event-bearing, dashboard-visible).

## Patterns To Avoid

Avoid these unless there is a deliberate, documented exception:

- relying on `value.getClass()` instead of `getObjectClassName(value)`
- in-place mutation of an `atomicState` collection (the change is lost)
- `state` maps keyed by numeric ids (keys come back as Strings)
- using `def` where a concrete type would do
- passing raw ISO offset date strings through to UIs
- caches in `state` with no invalidation story
- pure-passthrough `/api/*` routes that exist only to forward a hub call
- treating Hubitat libraries as architectural module boundaries
- logging an error on every failed background poll of a cloud API
- calling a platform method signature that older firmware lacks without checking `location.hub.firmwareVersionString`
- per-device async fan-out that assumes more than 8 calls (or 5 to one host) run in parallel
- skipping `unschedule()` in `updated()` when a handler name changes across configs, a handler is scheduled from events the new config no longer fires, or a call passes `[overwrite: false]` (produces orphan timers; see *Lifecycle skeleton*)
- a transient state whose only exit is an unrescheduled `runIn` callback
- concurrent callbacks doing a read-modify-write of one map in `state` or `atomicState` (use a `@Field static` concurrent map)
- storing transient per-scan or per-request data in `state` instead of `@Field static`
- omitting `volatile` on `@Field static` fields read by concurrent endpoint handlers
- treating `state` as the place for device metadata that belongs in `updateDataValue`
- omitting `mfgCode` on manufacturer-specific Zigbee cluster operations
- reconfiguring a Zigbee device from `initialize()` on every hub startup

## Per-Project Architecture Guides

Project-level architecture guides inherit everything in this document and add their own specifics — request wrappers, UI primitives, hot paths, accepted tradeoffs, change-design rules, and pre-merge checklists.

When writing or reviewing a project's architecture guide, prefer extending or referencing this document over duplicating its contents. Existing examples:

- [`apps/HubInspector/ARCHITECTURE.md`](apps/HubInspector/ARCHITECTURE.md) — Hub Inspector app and SPA
