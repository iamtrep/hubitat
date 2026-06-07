<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Development Architecture Guide

This document captures architectural principles and platform constraints that apply across all Hubitat Groovy development in this repository — apps, drivers, and integrations alike. Per-project guides (for example, `apps/HubDiagnostics/ARCHITECTURE.md`) build on top of this one and add the specifics of their own design.

Treat this guide as the default. Project-level guides may extend specific sections, but the platform constraints below are not negotiable: violating them produces silent failures or lost work.

The guide is organized in three parts: **Common** principles that apply to anything built for Hubitat, then **Apps**-specific guidance, then **Drivers**-specific guidance. A companion file [`ARCHITECTURE_CANDIDATES.md`](ARCHITECTURE_CANDIDATES.md) holds lower-priority observations from the codebase review, kept aside for later reconsideration.

**Hubitat platform reference.** Platform mechanics — lifecycle methods, capabilities, app and driver metadata, OAuth, Zigbee helpers, etc. — are covered authoritatively at <https://docs2.hubitat.com/en/developer>. This guide does not duplicate that material. It focuses on project-specific conventions, workarounds for platform quirks, and failure modes that are easy to miss.

## Common

### Platform constraints

Several standard Groovy and Java patterns are blocked or behave differently in the Hubitat sandbox.

- **`value.getClass()` is sandbox-blocked.** Use the global `getObjectClassName(value)` instead to get a runtime class name string.
- **In-place mutation of `state` may not persist.** Writing `state.myList << item` is not reliably detected as a change by the platform. Always use explicit reassignment: `state.myList = modifiedList`.
- **Pushing source code does not trigger `updated()`.** Updated Groovy takes effect immediately, but `updated()` and `initialize()` are not called. Subscriptions and `state` from the previous version persist until the user re-saves the app's preferences in the hub UI. See *Version constants and code-push detection* below for the workaround.
- **`sendEvent()` deduplicates silently.** If the value hasn't changed and `isStateChange` is not set to `true`, the event is filtered out and not fired. Set `isStateChange: true` explicitly when an event must fire even with an unchanged value (button presses, repeated identical commands, forced state ticks).
- **Concurrent async HTTP calls are capped at 8 per app.** Code that fans out one request per device will silently lose calls at scale. Prefer batched or aggregated endpoints, or serialize work behind a small worker pool.

### State tiers: `state`, `atomicState`, `@Field static`

Three storage options are available, with very different durability and cost:

- **`state`** — persisted to the hub database; committed when the method exits. Survives hub restarts. The default.
- **`atomicState`** — persisted to the hub database; committed on every write. Survives hub restarts. Use when fields are written from async callbacks, WebSocket handlers, or anywhere the surrounding method has likely already exited (intentional-disconnect flags, scan counters, log counters). Plain `state` writes from those paths can silently vanish.
- **`@Field static`** — in-memory only, no database I/O. Survives across script executions within the same hub uptime, lost on hub restart or app/driver reinstall. Use for transient scan/orchestration state, request-scoped caches with a bounded TTL, and fast-path counters where DB writes would dominate the work. RuleLoggingManager and HubDiagnostics use this deliberately; both files have explanatory comments at the declaration site.

  Mark a `@Field static` field with `volatile` when it may be read or written by concurrent OAuth endpoint handlers. Without `volatile`, readers may see stale values across threads.

Choosing the wrong tier is a real bug source: transient per-scan data in `state` causes unnecessary DB writes; async-written plain `state` silently loses writes; long-lived configuration in `@Field static` is lost on every reboot.

**`singleThreaded: true` makes `atomicState` unnecessary — within that file.** When `definition()` declares `singleThreaded: true`, the platform serializes every handler invocation in that app/driver: commands, `parse()`, scheduled callbacks, event handlers, OAuth endpoint dispatches all run one-at-a-time. The race `atomicState` protects against — a callback firing while an earlier method is still in flight and the two clobbering each other's `state` writes — cannot occur in a singleThreaded file, so plain `state` is sufficient and `atomicState` only adds per-write DB cost. **This relaxation applies *only* inside a singleThreaded definition.** In any file that does not declare it (the default), the bullet above still holds — async-callback writes must use `atomicState` or they may silently vanish.

Two narrower scopes the relaxation does NOT cover, even inside a singleThreaded file:

- **In-place mutation of `state` collections** (`state.myMap[k] = v`, `state.myList << item`) is a Hubitat change-detection quirk, not a concurrency one — the read-mutate-reassign pattern still applies regardless of threading mode.
- **`@Field static volatile`** — `singleThreaded` serializes Groovy handler dispatch, but `@Field static` lives in the JVM and can still be touched by concurrent threads outside that dispatch (e.g. async HTTP callback threads). `volatile` is still required where concurrent reads are possible.

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

**Constants and pure computation.** Declare constants with `@Field static final` — top-level Groovy fields aren't usable as constants in the sandbox. Use `@CompileStatic` on pure computation methods that don't access Hubitat dynamic properties (`settings`, `state`, `device`, etc.).

**Capabilities.** Use current capabilities, not deprecated ones. For example, prefer `capability "Refresh"` over the deprecated `capability "Polling"` for pollable devices.

### Lifecycle skeleton

The standard lifecycle methods (`installed`, `updated`, `uninstalled`, `initialize`) are platform-defined; two project conventions matter on top:

- **The lifecycle has a single convergence point.** Both the install path and the preferences-saved path route through it so subscriptions, schedules, and version checks live there exactly once. The convergence method depends on the file shape:
  - **Apps** and **drivers with persistent runtime state** (LAN/cloud sockets, OAuth tokens, reconnect logic): `initialize()`.
  - **Local-radio drivers** (Zigbee, Z-Wave) with no startup work: `configure()`. `initialize()` is omitted — don't add an empty stub or one that only calls `configure()`. `Drivers → Driver lifecycle` expands on this.
- **`updated()` resets before reinitializing** — `unsubscribe(); unschedule(); <convergence>` (drivers omit `unsubscribe()`). Same-handler-name `runIn`/`runInMillis`/`runOnce`/`schedule` calls are self-cancelling because the platform's `options.overwrite` defaults to `true` — so a static handler name re-scheduled in the new config does not need `unschedule()` to avoid accumulation. The defensive `unschedule()` matters in narrower cases: handler names that change across configs (e.g., `"check_${index}"` when the index shifts), handlers scheduled from event handlers that the new config no longer fires, and any call site that passes `[overwrite: false]`. Calling `unschedule()` unconditionally remains the convention because it's cheap and protects against all three.

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

Implement private `logTrace`/`logDebug`/`logInfo`/`logWarn`/`logError` that check the corresponding preference and prefix `${device}` (drivers) or `${app.label}` (apps).

Auto-disable debug and trace after about 30 minutes:

```groovy
if (debugEnable) runIn(1800, "logsOff")
```

The `logsOff` handler clears the flags via `device.updateSetting` / `app.updateSetting`.

### Date handling

Hubitat hub endpoints return ISO 8601 strings with numeric timezone offsets, e.g. `"2026-05-05T23:07:43.088-0400"`. This format is **not consistently parsed by `new Date()` in browsers** — Safari/WebKit in particular fails silently or returns `Invalid Date`.

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

`state` and `atomicState` are JSON-serialized. Live platform proxies — `DeviceWrapper`, `InstalledAppWrapper`, `LocationWrapper`, `HubWrapper`, event/subscription objects — do not survive a serialization round-trip cleanly. They appear to "work" within a single method call (because the in-memory list is read back before commit), but on the next invocation the values come back as garbled blobs and any method call on them (`it.currentValue(...)`, `it.getLabel()`) breaks.

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

The async-HTTP contract above is the project default. It is the right tool for **background work** — scheduled polls, event-handler reactions, fan-out queries across many devices — where the caller has nothing to wait on, the workload may run concurrent with other async work, and the platform's 8-concurrent-call ceiling needs headroom.

Two cases legitimately call for sync HTTP. Both are present in this repo.

**1. Inside `dynamicPage` rendering.** Hubitat `dynamicPage` builds and returns the page Map in a single synchronous method call; there is no platform mechanism to suspend rendering and await an async callback mid-render. Any data the page needs from hub APIs has to be in hand by return time. The async escape hatch — "kick off the call, render placeholders, force a page reload when the result lands" — replaces a short blocking call with a multi-reload state machine that the user can interrupt by clicking away. It is worse, not better. Compounding the constraint: pages that iterate (one call per app/device) can issue dozens of HTTP calls per render, far past the per-app cap of 8 concurrent async; the excess would silently drop and corrupt the preview.

Canonical example — `apps/utilities/DeviceReplacement.groovy:167,202,256,288`. Four sync `httpGet` sites in `previewPage()`. The two loop sites (`:202`, `:256`) iterate over the per-app list returned by `:167`, producing 60+ calls on a 30-app preview. Sync is the only shape that fits a `dynamicPage`'s render-and-return semantics.

**2. Driver commands that return a value to their caller.** Hubitat invokes driver commands synchronously: `setHeatingSetpoint`, `setMode`, `refresh`. If the command's body calls an external API and acts on the result — fire a device event reflecting the new state, retry on auth failure, decide whether the operation actually succeeded — the simplest correct implementation is a sync HTTP call inside the command. Restructuring around async means continuation chains: token-check callback → token-refresh callback → API-call callback → event-emit, with intermediate state stashed in `state.*` between hops. The sync form has 5 lines and obvious semantics; the async form is a 30-line state machine with new failure modes (callback never fires, state from a previous command bleeds into the next, retries race the timeout). Latency on a user-initiated thermostat command is invisible.

Canonical example — `drivers/EcobeeCompanion.groovy:273,368,372`. `callApi(method, path, ...)` returns `Map result` synchronously to every command path; `refreshAccessToken()` returns `Boolean success` that callers check before issuing API calls. OAuth bootstrap sites at `:191,:233` could migrate to async without any of these concerns, but they're one-shot user-triggered calls run twice in the device's lifetime — migrating only the cheapest sites would leave the file inconsistent without making it better.

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

### OAuth-served HTTP endpoints

App-served UIs and programmatic APIs use Hubitat's per-app OAuth path (`oauth: true` + `mappings { }` + `createAccessToken()`). The architectural property that matters: endpoints reachable via `${getFullLocalApiServerUrl()}/...?access_token=${state.accessToken}` work without an active hub admin session — that is what makes app-served UIs viable for users.

The `/hubitat-oauth` skill in this repo enables OAuth on a Groovy app without manual hub UI steps.

### Cross-origin (CORS) and multi-hub browser clients

The local OAuth API sends no CORS headers (see `docs/hubitat-platform-notes.md` for the measured behavior), so a browser page served by one hub cannot read another hub's API response directly. Browser-based multi-hub tools therefore cannot fan out to peer hubs from the client: the cross-hub calls must run server-side on the hub that serves the page, which forwards them and returns the result same-origin.

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

- **`initialize()` is for work that must re-execute after hub startup.** The platform calls it on hub start, install, and as part of the `updated()` convergence. Use it for LAN/cloud reconnection, re-arming any housekeeping the hub doesn't already persist (most `schedule`/`runIn` calls already survive reboot), and idempotent state/counter seeding. For a pure local-radio (Zigbee/Z-Wave) driver with no such startup work, omitting `initialize()` is fine — and is the common case for plugs, switches, sensors, and locks. **Don't add an empty stub or one that only calls `configure()`.** When `initialize()` is omitted, `configure()` becomes the convergence point: `installed()` routes to it (typically via `runInMillis` so it doesn't run inline with the install transaction), `updated()` does its `unschedule(); <preference writes>; configure()` sequence, and `deviceTypeUpdated()` calls `configure()`. When `initialize()` *is* present, call **`refresh()`, not `configure()`** from it — reconfiguring on each hub restart wastes radio bandwidth and can race with other devices joining the mesh.
- **`deviceTypeUpdated()` should warn and reconfigure.** The convention is `logWarn "driver change detected"; configure()`, so that switching a device's driver type re-applies device-side reporting and defaults.

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

Use `device.updateDataValue("key", "value")` for non-state metadata that should survive driver swaps and be visible in the device edit page: firmware version, MAC, UUID, runtime-discovered capability flags. Read with `device.getDataValue("key")`.

This is distinct from `state` (driver-instance scoped, not visible in the device edit page) and from attributes (event-bearing, dashboard-visible).

## Patterns To Avoid

Avoid these unless there is a deliberate, documented exception:

- relying on `value.getClass()` instead of `getObjectClassName(value)`
- in-place mutation of `state` collections without reassignment
- using `def` where a concrete type would do
- passing raw ISO offset date strings through to UIs
- caches in `state` with no invalidation story
- pure-passthrough `/api/*` routes that exist only to forward a hub call
- treating Hubitat libraries as architectural module boundaries
- per-device async fan-out that exceeds the 8-call concurrency ceiling
- skipping `unschedule()` in `updated()` (produces orphan timers)
- writing async-callback state with `state` instead of `atomicState`
- storing transient per-scan or per-request data in `state` instead of `@Field static`
- omitting `volatile` on `@Field static` fields read by concurrent endpoint handlers
- treating `state` as the place for device metadata that belongs in `updateDataValue`
- omitting `mfgCode` on manufacturer-specific Zigbee cluster operations
- reconfiguring a Zigbee device from `initialize()` on every hub startup

## Per-Project Architecture Guides

Project-level architecture guides inherit everything in this document and add their own specifics — request wrappers, UI primitives, hot paths, accepted tradeoffs, change-design rules, and pre-merge checklists.

When writing or reviewing a project's architecture guide, prefer extending or referencing this document over duplicating its contents. Existing examples:

- [`apps/HubDiagnostics/ARCHITECTURE.md`](apps/HubDiagnostics/ARCHITECTURE.md) — Hub Diagnostics app and SPA
