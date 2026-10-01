<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Development Architecture Guide

This document captures architectural principles and platform constraints that apply across all Hubitat Groovy development in this repository — apps, drivers, and integrations alike. Per-project guides (for example, `apps/HubDiagnostics/ARCHITECTURE.md`) build on top of this one and add the specifics of their own design.

Treat this guide as the default. Project-level guides may extend specific sections, but the platform constraints below are not negotiable: violating them produces silent failures or lost work.

Conventions apply to new code and to files when they are edited. Existing files adopt them when touched, with no sweep, so an architecture review flags a deviation only in code the change touches.

The guide is organized in three parts: **Common** principles that apply to anything built for Hubitat, then **Apps**-specific guidance, then **Drivers**-specific guidance. A companion file [`ARCHITECTURE_CANDIDATES.md`](ARCHITECTURE_CANDIDATES.md) holds lower-priority observations from the codebase review, kept aside for later reconsideration.

**Hubitat platform reference.** Platform mechanics — lifecycle methods, capabilities, app and driver metadata, OAuth, Zigbee helpers, etc. — are covered authoritatively at <https://docs2.hubitat.com/en/developer>. This guide does not duplicate that material. It focuses on project-specific conventions, workarounds for platform quirks, and failure modes that are easy to miss.

## Common

### Platform constraints

Several standard Groovy and Java patterns are blocked or behave differently in the Hubitat sandbox. A *verified* mark names the firmware a platform fact was last measured on; the evidence is in `docs/hubitat-platform-notes.md`, and `apps/tests/test-architecture-claims.sh` re-checks it. Re-run that test after each major firmware update. Statements without a mark are project conventions.

- **`value.getClass()` is sandbox-blocked.** The hub rejects the code when it is saved, so a `try`/`catch` can't guard it. Use the global `getObjectClassName(value)` to get a runtime class name string. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))*
- **Reassign `state` collections after mutating them.** Use `state.myList = modifiedList` rather than `state.myList << item`. On firmware 2.5.2.124 a probe found in-place deep writes, key puts, and list appends on plain `state` persisting in both an app and a driver, so this is a convention that doesn't depend on the platform's change detection, not a known failure.
- **Pushing source code does not trigger `updated()`.** Updated Groovy takes effect immediately, but `updated()` and `initialize()` are not called. Subscriptions and `state` from the previous version persist until the user re-saves the app's preferences in the hub UI, and `@Field static` values are reset. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* See *Version constants and code-push detection* below for the workaround.
- **`sendEvent()` deduplicates unless someone asks for repeats.** An unchanged value without `isStateChange: true` is not stored and not delivered to default subscribers. A subscriber that passes `[filterEvents: false]` receives every repeat, and the repeats are then stored too. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* A consumer that needs every sample subscribes with `filterEvents: false`. Drivers call `sendEvent` on every report and set `isStateChange: true` only for events that are new by nature, such as button presses.
- **Concurrent async HTTP calls are capped at 8 per app, and at 5 per destination host.** Calls beyond either limit queue and complete later; none are lost. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* Code that fans out one request per device serializes behind the pool at scale. Prefer batched or aggregated endpoints.

### State tiers: `state`, `atomicState`, `@Field static`

Three storage options are available, with very different durability and cost:

- **`state`** — persisted to the hub database; committed when the method exits. Survives hub restarts. The default.
- **`atomicState`** — persisted to the hub database; committed on every write. Survives hub restarts, and a write survives the handler failing afterwards. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* Use for flags another thread must see right away (intentional-disconnect flags, scan progress). It does **not** make a read-modify-write atomic: concurrent callbacks that each read a map, add a key and write it back lose entries in `atomicState` as well as `state`. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))*
- **`@Field static`** — in-memory only, no database I/O. Survives across script executions within the same hub uptime, lost on hub restart, code push, and app/driver reinstall. It is shared by every instance of the app or driver type, so key per-instance entries by `app.id` or a scan ID. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* Use for transient scan/orchestration state, request-scoped caches with a bounded TTL, and fast-path counters where DB writes would dominate the work. HubDiagnostics uses this deliberately, with explanatory comments at the declaration site.

  Mark a `@Field static` field with `volatile` when it may be read or written by concurrent OAuth endpoint handlers. Without `volatile`, readers may see stale values across threads.

**Gathering results from concurrent async callbacks.** Collect them in a `@Field static` `ConcurrentHashMap` keyed by instance, using `put`/`putIfAbsent` (never `get` then `put`), count completions with an `AtomicInteger`, and write the result to `state` once when the last callback arrives. That kept 20 of 20 results where a shared map in `state` or `atomicState` lost entries. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* A value that must survive on its own goes in its own top-level `state` key. The in-memory results are lost on a push or reboot, so the handler must tolerate an unfinished batch. HubDiagnostics' device audit scan is the reference implementation.

Choosing the wrong tier is a real bug source: transient per-scan data in `state` causes unnecessary DB writes; concurrent callbacks sharing one map in `state` or `atomicState` lose entries; long-lived configuration in `@Field static` is lost on every reboot or push.

**What `singleThreaded: true` serializes.** It runs one handler at a time per app or driver **instance**; two instances of the same type still run in parallel. Scheduled handlers and async HTTP callbacks wait for each other: 20 callbacks never overlapped, and a shared-map read-modify-write in plain `state` kept all 20 entries. OAuth endpoints (`mappings`) are dispatched through a different path and only partly take part: an endpoint waits for a running scheduled handler or callback, and a scheduled job waits for a running endpoint, but concurrent endpoint calls usually run alongside each other. A callback that arrives while an endpoint runs sometimes waits and sometimes runs alongside it. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* A direct call from a child device into a singleThreaded parent (`parent.componentX()`) waited for the parent's running scheduled handler on firmware 2.5.2.124, so a slow parent stalls every child that calls into it. Commands, `parse()`, scheduled handlers and event handlers are expected to follow the callback behavior but have no direct measurement yet.

Inside a singleThreaded file, plain `state` is enough for anything written from callbacks, schedules and events, and `atomicState` only adds per-write DB cost. Three things still need care there:

- **Endpoint handlers** can run alongside other endpoints and sometimes alongside callbacks, so an endpoint that writes `state` needs the same treatment as a callback in a file without `singleThreaded`. A slow scheduled handler or callback also delays every endpoint call behind it, which a polled UI feels directly.
- **In-place mutation of `state` collections** (`state.myMap[k] = v`, `state.myList << item`) is a change-detection concern, not a concurrency one, so the read-mutate-reassign convention above still applies.
- **`@Field static` is shared by every instance of the type**, and those instances run in parallel, so a static read across instances still needs `volatile` or a concurrent structure.

**Choosing between `singleThreaded` and concurrent `@Field` structures.**

- **Use `singleThreaded: true`** when durable state in `state` is updated from several handlers (events, schedules, callbacks, commands) and each handler is short. Plain `state` is then safe without extra structure. This suits automation apps and stateful drivers (HumidityFanController, SwitchMonitor, MirrorSwitch, DevicePing).
- **Its cost** is that every handler waits for the one before it. A handler that blocks (sync HTTP, `pauseExecution`, a long loop) delays every event, schedule and callback queued behind it, and child devices that call into the parent wait too.
- **Leave `singleThreaded` off and use `@Field static` concurrent structures** when the app must keep handling work while slow calls are in flight: an app serving a polled UI or API, or one fanning out async calls whose callbacks should not queue behind each other. Keep the shared data in `ConcurrentHashMap`/`AtomicInteger`, change it only through their atomic methods, and write durable results to `state` at one point (see *Gathering results from concurrent async callbacks* above). HubDiagnostics works this way.
- **Concurrent structures hold only in-memory data.** It is lost on a push or reboot and shared across instances. Durable state that several handlers update still needs `singleThreaded`, or one top-level `state` key per writer.
- **Default:** start an automation app or a stateful driver with `singleThreaded: true`. Drop it only when serialization measurably delays something (an endpoint or callback waiting behind slow work), and move the contended data into concurrent structures.

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

**Constants and pure computation.** Declare constants with `@Field static final` — a top-level variable without `@Field` reads as `null` inside methods, with no error. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* Use `@CompileStatic` on pure computation methods that don't access Hubitat dynamic properties (`settings`, `state`, `device`, etc.).

**Capabilities.** Use current capabilities, not deprecated ones. For example, prefer `capability "Refresh"` over the deprecated `capability "Polling"` for pollable devices.

### Lifecycle skeleton

The standard lifecycle methods (`installed`, `updated`, `uninstalled`, `initialize`) are platform-defined; two project conventions matter on top:

- **The lifecycle has a single convergence point.** Both the install path and the preferences-saved path route through it so subscriptions, schedules, and version checks live there exactly once. The convergence method depends on the file shape:
  - **Apps** and **drivers with persistent runtime state** (LAN/cloud sockets, OAuth tokens, reconnect logic): `initialize()`.
  - **Local-radio drivers** (Zigbee, Z-Wave) with no startup work: `configure()`. `initialize()` is omitted — don't add an empty stub or one that only calls `configure()`. `Drivers → Driver lifecycle` expands on this.
- **`updated()` resets before reinitializing** — `unsubscribe(); unschedule(); <convergence>` (drivers omit `unsubscribe()`). Same-handler-name `runIn`/`runInMillis`/`runOnce`/`schedule` calls are self-cancelling because the platform's `options.overwrite` defaults to `true` *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* — so a static handler name re-scheduled in the new config does not need `unschedule()` to avoid accumulation. The defensive `unschedule()` matters in narrower cases: handler names that change across configs (e.g., `"check_${index}"` when the index shifts), handlers scheduled from event handlers that the new config no longer fires, and any call site that passes `[overwrite: false]`. Calling `unschedule()` unconditionally remains the convention because it's cheap and protects against all three.

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

### Logging discipline

Every app and driver should expose three boolean preferences and a small set of gated helpers.

```groovy
input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
`logInfo`/`logDebug` remain emoji-less for uncategorized lines.

Auto-disable debug and trace after about 30 minutes:

```groovy
if (debugEnable) runIn(1800, "logsOff")
```

The `logsOff` handler clears the flags via `device.updateSetting` / `app.updateSetting`.

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

`state` and `atomicState` are JSON-serialized. Live platform proxies — `DeviceWrapper`, `InstalledAppWrapper`, `LocationWrapper`, `HubWrapper`, event/subscription objects — do not survive a serialization round-trip cleanly. They appear to "work" within a single method call (because the in-memory list is read back before commit), but on the next invocation each one comes back as a plain `HashMap` of device properties, and any method call on it (`it.currentValue(...)`, `it.getLabel()`) throws. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))*

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

Canonical example — `apps/utilities/DeviceReplacement.groovy`, `previewPage()`. It gets the apps using a device from `appsUsing()` (sync `fullJson` fallback), then loops over them with sync `httpGet` calls to `statusJson` and `listJson`, and `discoverPageGraph()` reads up to 30 configuration pages per app. A 30-app preview issues well over 60 calls. Sync is the only shape that fits a `dynamicPage`'s render-and-return semantics.

**2. Driver commands that return a value to their caller.** Hubitat invokes driver commands synchronously: `setHeatingSetpoint`, `setMode`, `refresh`. If the command's body calls an external API and acts on the result — fire a device event reflecting the new state, retry on auth failure, decide whether the operation actually succeeded — the simplest correct implementation is a sync HTTP call inside the command. Restructuring around async means continuation chains: token-check callback → token-refresh callback → API-call callback → event-emit, with intermediate state stashed in `state.*` between hops. The sync form has 5 lines and obvious semantics; the async form is a 30-line state machine with new failure modes (callback never fires, state from a previous command bleeds into the next, retries race the timeout). Latency on a user-initiated thermostat command is invisible.

Canonical example — `drivers/EcobeeCompanion.groovy`. `callEcobeeApi(method, path, ...)` returns a `Map` synchronously to every command path and retries once on HTTP 401; `refreshToken()` and `checkAndRefreshToken()` return a `Boolean` that callers check before issuing API calls. The OAuth bootstrap calls in `connect()` and `authorize()` could migrate to async without any of these concerns, but they're one-shot user-triggered calls run twice in the device's lifetime — migrating only the cheapest sites would leave the file inconsistent without making it better.

**The contract, confirmed.** Async is right when the work is background, fan-out, or event-handler-shaped — the caller doesn't need a return value, latency is invisible to a user, and many calls may be in flight at once. Sync is right when the work is on the synchronous critical path of a user-facing operation and the caller structurally depends on the return value — page render, command dispatch, a dependent chain that completes inside one logical user action. The two files above are not violations of the async-HTTP contract; they are the shapes the contract carves out.

### Fail-soft defaults

For monitoring, diagnostics, and dashboard-style apps, partial data is usually better than a hard endpoint failure. Prefer returning incomplete-but-usable payloads over brittle strictness:

- map-shaped failures should degrade to `null` or `{}`
- list-shaped failures should degrade to `[]`
- text-fetch failures should degrade to `null`

This default does not apply to writes or destructive actions, which should fail loudly.

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
where the transition is cheap to recompute from scratch.

### OAuth-served HTTP endpoints

App-served UIs and programmatic APIs use Hubitat's per-app OAuth path (`oauth: true` + `mappings { }` + `createAccessToken()`). The architectural property that matters: endpoints reachable via `${getFullLocalApiServerUrl()}/...?access_token=${state.accessToken}` work without an active hub admin session — that is what makes app-served UIs viable for users.

An app can enable OAuth for itself on install, so nobody has to toggle it in the code editor: catch the `createAccessToken()` failure, enable OAuth through the hub's loopback API, and retry. HubDiagnostics' `autoEnableOAuth()` / `checkOAuth()` is the reference implementation.

### Cross-origin (CORS) and multi-hub browser clients

The local OAuth API sends no CORS headers *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))*, so a browser page served by one hub cannot read another hub's API response directly. Browser-based multi-hub tools therefore cannot fan out to peer hubs from the client: the cross-hub calls must run server-side on the hub that serves the page, which forwards them and returns the result same-origin.

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
- **Orphan tracking, explicit user action.** When the parent's source-of-truth changes (a sensor unenrolls upstream, etc.), diff the active DNI `Set` against `getChildDevices()` and surface orphans for the user to remove explicitly. Don't auto-delete child devices — they may carry user-edited labels, dashboard pins, or rule references.

## Drivers

### Driver lifecycle

The platform defines what `configure()`, `initialize()`, `refresh()`, and `deviceTypeUpdated()` mean. Two project-specific rules:

- **`initialize()` is for work that must re-execute after hub startup.** The platform calls it on hub start, install, and as part of the `updated()` convergence. Use it for LAN/cloud reconnection, re-arming any housekeeping the hub doesn't already persist (repeating `schedule` jobs survive a reboot, but a one-shot `runIn` that falls due during the outage is dropped; see *What a reboot does to scheduled work*), and idempotent state/counter seeding. For a pure local-radio (Zigbee/Z-Wave) driver with no such startup work, omitting `initialize()` is fine — and is the common case for plugs, switches, sensors, and locks. **Don't add an empty stub or one that only calls `configure()`.** When `initialize()` is omitted, `configure()` becomes the convergence point: `installed()` routes to it (typically via `runInMillis` so it doesn't run inline with the install transaction), `updated()` does its `unschedule(); <preference writes>; configure()` sequence, and `deviceTypeUpdated()` calls `configure()`. When `initialize()` *is* present, call **`refresh()`, not `configure()`** from it — reconfiguring on each hub restart wastes radio bandwidth and can race with other devices joining the mesh.
- **`deviceTypeUpdated()` should always be implemented.** The platform calls it when a device's driver type is switched. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* The convention is to log the change at debug level (`logDebug "driver change detected"`) and *only* call `configure()` when the driver author judges a reconfigure is necessary on a driver change — typically local-radio drivers that must re-apply device-side reporting and defaults. Drivers with nothing to re-apply (virtual, cloud, log/probe helpers) implement the method as a debug-log-only stub.

### Zigbee parse skeleton

`parse(String description)` and `zigbee.parseDescriptionAsMap` are platform-standard. The project shape on top:

1. Outer dispatch on five paths: attribute report (`descMap.attrId != null`), ZDO command (`profileId == "0000"`), ZHA global command (`profileId == "0104"` with no `attrId`), enroll request, and zone status/report. Log unhandled cases at trace level — silent dropping makes new device behavior invisible.
2. Inside the attribute-report path, **always iterate `descMap.additionalAttrs`** alongside the primary report. Zigbee batches related reports, and dropping them produces silent partial updates.
3. Delegate per-cluster work to a `parseAttributeReport(descMap)` helper that outer-switches on `cluster`/`clusterInt`, inner-switches on `attrId`/`attrInt`, builds a `[name, value, unit, descriptionText, type]` map, and returns `createEvent(map)`.

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

Use `device.updateDataValue("key", "value")` for non-state metadata that should survive driver swaps: a driver switch keeps data values and clears `state`. *([verified 2.5.2.128](docs/hubitat-platform-notes.md#platform-behavior))* Use it for firmware version, MAC, UUID, runtime-discovered capability flags. Read with `device.getDataValue("key")`.

This is distinct from `state` (scoped to the current driver, cleared when the driver changes, shown in the device page's State Variables card) and from attributes (event-bearing, dashboard-visible).

## Patterns To Avoid

Avoid these unless there is a deliberate, documented exception:

- relying on `value.getClass()` instead of `getObjectClassName(value)`
- in-place mutation of `state` collections without reassignment
- using `def` where a concrete type would do
- passing raw ISO offset date strings through to UIs
- caches in `state` with no invalidation story
- pure-passthrough `/api/*` routes that exist only to forward a hub call
- treating Hubitat libraries as architectural module boundaries
- per-device async fan-out that assumes more than 8 calls (or 5 to one host) run in parallel
- skipping `unschedule()` in `updated()` (produces orphan timers)
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

- [`apps/HubDiagnostics/ARCHITECTURE.md`](apps/HubDiagnostics/ARCHITECTURE.md) — Hub Diagnostics app and SPA
