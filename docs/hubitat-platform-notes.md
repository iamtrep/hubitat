<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Groovy and platform notes

Notes on Hubitat's Groovy sandbox and platform behavior. Reverse-engineered or learned from working in this repo; not Hubitat-official documentation. Endpoints, mechanics, and quirks below may change between firmware versions. A note marked *(verified X)* was last measured on firmware X; `apps/tests/test-architecture-claims.sh` re-checks the notes that name it, so re-run it after each major firmware update and update the marks.

## Groovy coding conventions

- **Static typing**: use explicit types for return values (`void`, `String`, `Map`), parameters (`String command`, `Number level`), and local variables (`String cmd`, `int end`, `Map jsonData`) — avoid `def`
- Leave parameters untyped when the value is genuinely polymorphic and no meaningful type narrows it (e.g., `aValue` passed straight to `sendEvent`). Don't use `Object` as a substitute for `def` — it adds no value.
- Hubitat async callback parameters (`resp`, `data`) stay untyped — platform convention
- Use `capability "Refresh"` (not deprecated `capability "Polling"`) for pollable devices
- `@CompileStatic` on pure computation methods that don't access Hubitat dynamic properties
- `@TypeChecked` is **not** available — the sandbox rejects `import groovy.transform.TypeChecked` at compile time (`Importing [groovy.transform.TypeChecked] is not allowed`, verified on firmware 2.5.0.148). Use `@CompileStatic` instead; it's the same family and is approved.
- **Integer division yields `BigDecimal`**: `Long / Long` (and `int / int`) evaluates to a `BigDecimal` in Groovy, not an integer. This silently breaks numeric-method overload resolution — e.g. `Math.max(0L, someLong / 1000L)` becomes `Math.max(Long, BigDecimal)`, which the platform dispatcher throws on at runtime (`Ambiguous method overloading for method java.lang.Math#max … [double,double] / [float,float]`). Use `.intdiv(1000L)` (returns the integral type) when you want integer division, or cast, so both args share a type.

## Object introspection

Three sandbox-safe ways to look at an object's surface, picked by what you need.

- **Just the runtime class name** — `getObjectClassName(value)` is a platform-injected global that returns the FQN as a String. Use it instead of `value.getClass()`, which the hub rejects when the code is saved (`Expression [MethodCallExpression] is not allowed`), so a `try`/`catch` can't guard it *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. Routine pattern in exception handlers: `"${getObjectClassName(e)}: ${e.message}"`. `hexStrToSignedInt(String)` is a sibling platform-injected global (no import or library needed in driver scope): it parses a big-endian hex string as a two's-complement signed value and returns a `Long`. Verified by behavior, not documented — treat it like other introspection-discovered helpers.
- **Live bean-property snapshot** — `obj.properties.each { k, v -> ... }`. Returns the Groovy bean-accessor map of every readable getter with its current value. Used by `drivers/tests/DeviceInspector.groovy` to dump the full `DeviceWrapper` surface in one line. Best for objects whose useful state IS bean properties (`device`, `location`, `hub`, parsed Maps); much smaller surface for objects whose API is parameterized methods (e.g. `zigbee.properties` returns only 6 entries while reflection finds 115 methods).
- **Full API surface with signatures** — `obj.class.getMethods()` / `getFields()` / `getSuperclass()` / `getInterfaces()`. Needed only when you want overload signatures, parameter types, or static fields. The sandbox blocks several routes here: `Class.forName` is rejected at AST level, `java.lang.Class` can't be imported (which kills `Class cls` type annotations — use `def`), `java.lang.reflect.Modifier.isStatic(...)` is rejected (bitmask directly: `(f.modifiers & 0x08) != 0`), `ArrayDeque` is rejected (use `[]` with `.remove(0)` as a FIFO), and `java.lang.Runtime` is rejected at compile (`Expression [ClassExpression] is not allowed: java.lang.Runtime`) so `Runtime.runtime.availableProcessors()` does not work — use `/hub/cpuInfo` for CPU/core-count data instead. `obj.class` (PropertyExpression) returns the Class object fine, and Class/Method/Field getter chains work from there. Reach related classes via factory methods, not `forName`. See `drivers/tests/ZigbeeIntrospect.groovy` and `docs/hubitat-zigbee-helper.md` for a worked example.

## MarkupBuilder (`mkp`) in the sandbox

- Inside `groovy.xml.MarkupBuilder`, the `mkp` accessor resolves to **null within nested tag closures** — `mkp.yieldUnescaped(...)` / `mkp.yield(...)` throw `NullPointerException`. `mkp` is a property (getter) access the sandbox returns null for, whereas tag *method* calls (`svg`, `path`, `td`, …) route through the builder's `methodMissing` and work. Emit raw markup (e.g. inline SVG) as nested builder tags rather than via `mkp`. Hyphenated attribute names (`stroke-width`, `stroke-linecap`) must be quoted string keys in the attribute map.

## Platform behavior

- `sendEvent()` automatically deduplicates: if the value hasn't changed and `isStateChange` is not set to `true`, the event is filtered out of event history and the *default* subscriber path. But: (a) `device.lastActivity` updates on every `sendEvent()` call regardless of dedup, and (b) subscribers that pass `filterEvents: false` to `subscribe()` receive every call including dedup'd ones: with such a subscriber, every repeat is also stored in the event history, while default subscribers still see only changes *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. So dedup is a UI/default-subscriber convenience, not a "device didn't communicate" signal. **In drivers, just call `sendEvent` on every device report — don't suppress driver-side, and don't override with `isStateChange: true`.** Suppression hides the call from `filterEvents:false` subscribers; forcing `isStateChange:true` removes default subscribers' opt-in to dedup. Apply any value smoothing to the *value* being emitted, not to whether the event fires. Prefer `sendEvent` over `createEvent`: returning a `List` of `createEvent` maps from `parse()` emits events via the platform's return-List path, which leaves the event's **"Produced by" column empty** in device logs, whereas a direct `sendEvent()` from `parse()` or a helper attributes the event to the device.
- `state` object is committed to the database when the method exits (not on each write). `atomicState` commits immediately on each write. Mid-method, another request reads the new `atomicState` value and the old `state` value, and a handler that throws keeps its `atomicState` writes and loses its `state` writes *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*.
- `state.remove(key)` and `atomicState.remove(key)` return the removed value, in apps and in drivers, and `null` for a missing key, so `String v = state.remove("k")` reads and clears in one step *(verified 2.5.2.129, throwaway app and driver probe)*.
- Concurrent async callbacks in an app without `singleThreaded` race on read-modify-write: 20 callbacks each adding a key to one shared map kept 5 to 6 entries in `state` and 14 to 16 in `atomicState`. Separate top-level `state` keys kept all 20, and with `singleThreaded: true` every form kept all 20. To gather callback results, collect them in a `@Field static` `ConcurrentHashMap` keyed by instance, count completions with an `AtomicInteger`, and write the result to `state` once when the last callback arrives (20/20 kept) *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. `computeIfAbsent` with a closure cast to `java.util.function.Function` runs in the sandbox.
- `singleThreaded: true` serializes per instance: two instances of the same type ran in parallel. Within one instance no two executions overlapped, for every pair of entry points: app endpoints (over loopback or the hub's LAN IP), scheduled handlers, async callbacks, device and location events, child device and child app calls into the parent, button handlers, page renders and `updated()`; driver commands from an app or over HTTP, scheduled handlers, callbacks, LAN `parse()` and child calls into the parent. A waiting call started 1 to 15 ms after the running one ended. Without the flag every pair overlapped *(verified 2.5.1.183 and 2.5.2.129, `apps/tests/test-singlethreaded.sh`)*. Radio `parse()`, Maker API commands, cloud-relay requests and cron `schedule()` are not measured.
- Concurrent identical GETs to an app endpoint share one execution: a request that arrives while one with the same path and query is running does not run the handler, and gets that run's response. This holds across clients (different source IP or headers) and with or without `singleThreaded`. Requests that don't overlap run separately. Tests that fire concurrent requests need a unique query parameter per call, and logs or counters show fewer executions than requests *(verified 2.5.1.183 and 2.5.2.129)*.
- An app that writes device attributes and also subscribes to them (setpoints it sends to a thermostat) needs `singleThreaded: true`. Without it, the event echoing the app's own write ran in parallel with the handler that sent it, before that handler's `state` commit, so the app took its own write for a change made by someone else, and the two handlers overwrote each other's `state` *(observed 2.5.2.129)*.
- `sendEvent` with an empty string or `null` value stores no event, even with `isStateChange: true`, so an attribute cannot be cleared that way. Publish a sentinel value such as `none`, or call `deleteCurrentState(name)` on the device; Maker API then reports the attribute as `null` *(observed 2.5.2.129)*.
- Reassign state collections after mutating them (`state.list = modifiedList`). On firmware 2.5.2.124, in-place `state.m.a.x = 2`, `state.m.b = 3`, and `state.list << item` all persisted across executions in an app and a driver, so reassignment is a convention, not a workaround for a known failure.
- `device.lastActivity` advancing on dedup'd events holds for component children too: a parent calling `child.parse([[name: "switch", value: "on"]])` with an unchanged value advanced the child's `lastActivity` while the `switch` state date stayed put. A state date is never newer than `lastActivity`, so taking `max(currentStates.date, lastActivity)` adds nothing.
- An app instance that has never been saved with Done (e.g. created over the API) runs button handlers but not scheduled jobs: its `runIn` calls are silently dropped. Finish setup with a Done POST, or have the page render collect async results.
- **Code push does NOT trigger `updated()`**: pushing new source code takes effect immediately (Groovy is interpreted), but does NOT call `updated()`/`initialize()`. Subscriptions and `state` from the old code persist. Must re-save app preferences to trigger a clean re-init.
- `@Field static` resets on code push (verified on firmware 2.5.0.140; re-verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`). Use `state`/`atomicState` for values that must survive a push. It is also **shared by every instance of the app type**: a value written by one instance is read by another *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*, so key per-instance entries by `app.id`.
- A top-level variable declared without `@Field` (`String X = "top"`) reads as `null` inside methods, with no error *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*.
- A `DeviceWrapper` stored in `state` works within the same call; on the next call it comes back as a plain `java.util.HashMap` of device properties, and methods such as `getLabel()` throw `MissingMethodException` *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. Store device IDs.
- Bare (undeclared, no `def`) variable assignments inside a command method write to the script binding but do **not** persist across separate command invocations (verified on firmware 2.5.0.143 via both `/device/runmethod` and Maker API). Each invocation gets a fresh binding: a bare write in call A is gone by call B, and a bare read then falls through to `settings.<name>` (for preference-named vars) or `null` (for non-preference names). So the common driver idiom of assigning per-message values to bare names (e.g. `priority = customPriority` in a notification driver) does **not** leak state between messages on current firmware — but it relies on undocumented instance lifecycle, so prefer `def`-declared locals + explicit `settings.*` reads for firmware-independence. Within a single invocation, bare write-then-read works (and shadows `settings`).
- Sandbox atomic allow-list: only `AtomicInteger` and `AtomicIntegerArray` are usable. `AtomicLong`, `AtomicReference`, `AtomicBoolean` are sandbox-blocked. Use `AtomicInteger` for counters; otherwise use a `synchronized` block. The broader allowed `java.util.concurrent.*` set is `Semaphore`, `ConcurrentHashMap`, `CopyOnWriteArrayList`, `ConcurrentLinkedQueue`, `SynchronousQueue`, `TimeUnit`, and `com.google.common.util.concurrent.Striped`. The sandbox treats a fully-qualified-name reference as an implicit import, so an FQN to a blocked class is rejected the same way as an `import` (`Importing [...] is not allowed`).
- Hubitat coalesces same-device events that fire <1s apart — multiple Maker API commands in rapid succession can lose events silently. Space them ≥0.5s for filter/debouncer apps.
- `runIn` / `schedule` with the same handler name overwrite by default; rescheduling is self-cancelling (two `runIn` calls leave one job; with `[overwrite: false]` they leave two) *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. `unschedule()` is only required when the same handler name is no longer wanted at all.
- Never echo `"[]"` as the value of a typed setting. Hubitat stores the literal string and Groovy arithmetic on the setting hits string repetition and crashes (`Long.minus(String)`). For unset preferences, omit the field or send its default.
- The async HTTP call pool is capped at 8 concurrent calls per app, and the HTTP client allows at most 5 concurrent connections to one destination host and port. With 20 `asynchttpGet` calls spread over four destinations, 8 were in flight at once; aimed at one destination, 5. The rest queued and all 20 completed, none lost *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. Per-device drilling at scale still serializes behind the pool; batch or rate-limit.
- `com.hubitat.hub.domain.Hub` is the importable type for `location.hubs[0]`. `com.hubitat.app.HubInfo` and `HubWrapper` do not exist.
- The local OAuth API (`/apps/api/<id>/...`) sends **no CORS headers** and does not support preflight: a cross-origin `GET` returns `200 OK` with no `Access-Control-Allow-Origin`, and an `OPTIONS` preflight returns `405 Method Not Allowed` (verified 2026-05-23 on a C-8 Pro; re-verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`). This is browser-enforced, so a page served by one hub cannot *read* another hub's API response, while `curl` and server-side calls are unaffected. The architectural consequence — browser-based multi-hub tools must proxy cross-hub calls server-side — is in `ARCHITECTURE.md` ("Cross-origin (CORS) and multi-hub browser clients").
- A per-app cap on pending events can throw `com.hubitat.app.exception.LimitExceededException` when many `sendEvent` calls batch in one tick. If the throw happens inside a self-rescheduling `runIn` body, the reschedule never runs and the chain dies silently — the app looks alive but its sweep is dead. In periodic sweeps emit only the attribute that changed, cap work per tick, and coalesce repeated same-attribute writes.
- **Reboot and scheduled jobs** *(verified 2.5.2.128 over two reboots, `apps/tests/test-architecture-claims.sh --reboot`)*: a far-future `runIn` survives; a repeating `schedule` resumes about a minute into uptime with one catch-up run about 10 s after the first, then keeps its cadence; a one-shot `runIn` that fell due while the hub was down never ran and was gone from the app's job list. `systemStart` reached a subscriber at about 115 s of uptime, after the first cron ticks. A 5-second heartbeat showed the hub stopped running jobs within 5 s of the reboot request and was back about 50 s later, so the one-shot fell due inside the outage both times.
- **Driver switch:** changing a device's driver calls the new driver's `deviceTypeUpdated()`, keeps the device's data values (`updateDataValue`), and **clears its `state`** *(verified 2.5.2.128, `apps/tests/test-architecture-claims.sh`)*. The device page shows `state` in its "State Variables" card, read from `/device/fullJson` `deviceState`.
- Event subscriptions are created in `initialize()`, which runs on `installed()`/`updated()` (clicking **Done**), and they **survive a code push** (a push does not re-run `initialize()`). A device input assigned on a **sub-page** saves the setting but does not arm its subscription until a Done runs `initialize()`. So a freshly-pushed or sub-page-configured app whose subscription isn't firing almost always never ran `initialize()` with that setting present — not a dropped subscription.
- A firmware update downloads the image into memory before applying, transiently dropping `freeMemory` (into single-digit MB on an otherwise healthy hub) for the duration. A low `freeMemory` sample taken shortly after a `/hub/eventsJson` `name=update` event is that download, not a leak or pre-existing pressure. Attribute a post-action memory dip (firmware update, manual backup/restore, large file upload) to the action before diagnosing hub overload — real memory pressure shows up in trended readings *before* any user action, not in a single post-action sample.

## Location events

- Any app can call `sendLocationEvent(name:, value:, descriptionText:)` with **reserved platform event names** (`systemStart`, `manualReboot`, `manualShutdown`, `sunrise`, `sunset`, `cloudBackup`, …). The dispatcher does not gate by provenance, and a subscriber handler cannot distinguish an app-originated event from a platform one (`evt.source` carries no such tag). Apps that key trust or lifecycle logic off these names can be misled by any other local app emitting the same name. For a tamper-resistant lifecycle signal, derive it from something only the platform controls — e.g. `location.hubs[0].uptime` crossing a threshold — not from a subscribed event name.

## Controlling one app from another

The sandbox gives no direct route between unrelated apps: no call returns a handle to another app or reads its `state`. Every route goes through an event (location, device, or hub variable) or an HTTP call over loopback. The exception is a parent/child family: a child can call `parent.someMethod()`, and a parent can call methods on `getChildApps()` / `getChildAppById()`, synchronously and with return values. As a result, an unrelated custom app can't get a synchronous answer from another app unless that app serves one over HTTP. Platform helper classes are not bound by this: `RMUtils.getRuleList()` reads Rule Machine's rules synchronously, while `RMUtils.sendAction()` goes through a location event.

Four patterns let one piece of automation command an app. They differ in who can call them, whether access is controlled, and whether the caller can read state back.

| Pattern | Custom apps | Rule Machine | Off-hub | Access control | Read-back |
|---|---|---|---|---|---|
| Groovy helper class (`hubitat.helper.RMUtils`) | yes | no | no | none | only what the class exposes |
| Location event (`sendLocationEvent`) | yes | no | no | none | only if the target publishes status events |
| Maker API | yes, over loopback | yes, HTTP actions | yes | per instance: token, selected devices | yes |
| App `mappings` (OAuth endpoints) | yes, over loopback | yes, HTTP actions | yes | per instance: token, plus whatever the app checks | yes, if the app provides it |

- **Helper class.** `RMUtils.getRuleList(version)` and `RMUtils.sendAction(rules, action, appLabel, version)` are documented on-hub; `version` defaults to `'4.1'`, so pass `'5.0'` for current rules. Per the on-hub docs, `sendAction` posts a Rule Machine action event to the location: the helper class wraps a location event with a documented payload. Any app can run any rule; nothing scopes it. Calls are fire-and-forget. Rule ids differ per hub, so resolve them by label through `getRuleList()`. A helper class is the most reusable form: a wrapper app can expose it through its own `mappings`, with its own access checks.
- **Location event.** A broadcast with no target reference and no acknowledgement. Any app can send any name, including reserved ones (see "Location events" above), so a receiver can't trust the sender. Rule Machine only triggers on a fixed list of location events and has no action to send an arbitrary one, so this pattern reaches custom apps only. HSM is the documented example: `hsmSetArm` in, `hsmStatus` out; Rule Machine reaches HSM through dedicated actions.
- **Maker API.** Exposes the devices selected in each instance, plus modes and HSM. It does not run rules or reach app internals; a virtual device that a rule triggers on is the usual bridge. On-hub callers use `http://127.0.0.1:8080`.
- **App `mappings`.** Each OAuth app instance gets its own token, the same as a Maker API instance. The cloud relay serves every app endpoint, so an app meant for local use has to check `request.requestSource` itself.
- **Built-in apps.** Most built-in apps expose no command interface of their own; Rule Machine's actions are the only way in. From a custom app, put the actions in a rule and run it with `RMUtils.sendAction`. Thermostat Scheduler, for example, has no OAuth endpoints, and the `thermSched` location event it subscribes to is undocumented and not visible to user apps while Rule Machine drives it. When several schedulers share a thermostat, the RM action works when it targets the scheduler by app name and silently does nothing when it targets by thermostat (firmware 2.5.2.128). Its restriction switch (*Disable when switch is off*) is a separate, device-based control that needs no rule.

## Locale-aware date/time formatting (firmware 2.5.0.143+)

Hubitat exposes platform-injected helpers that format dates per the user's Settings → Hub Details date/time format. Prefer these over hand-rolled `SimpleDateFormat` patterns for any display-side timestamp in apps or driver attributes:

- `formatActivityDateTime(date)`, `formatActivityDateTimeShort(date)`
- `formatDate(date)`, `formatShortDate(date)`
- `formatTimeHourMinute(date)`, `formatTimeHourMinuteSecond(date)`, `formatTimeHourMinuteSecondMillis(date)`

These methods are firmware 2.5.0.143+. Code shipped to older hubs will throw `MissingMethodException` — either gate on `location.hub.firmwareVersionString` or document a minimum-firmware requirement. Storage and comparisons stay in epoch millis (never persist user-formatted strings).

## App `definition()` flags

- `doNotFocus: true` (firmware 2.5.0.123+) — stops the main page auto-focusing the first input on open. Useful when the first element is a paragraph, status banner, or read-only field (the auto-focus otherwise scrolls past it). Unknown definition keys are ignored on older firmware, so this is safe to set unconditionally.
- `showAppTitle: false` (firmware 2.4.1.x+, default true) — hides the app title from the rendered configuration page. Sibling to `doNotFocus`. Safe to set unconditionally on older firmware (unknown keys ignored).
- `importUrl` only adds a manual **Import** button in the Apps/Drivers code editor that fetches the code from the URL and overwrites the editor (the user then Saves). That is the whole feature — it does not poll the remote, compare versions, or show any "update available" indicator. Stock Hubitat has no native update notification for user apps/drivers; an app that wants to signal a newer version must implement its own remote version poll.

## Driver preferences

- A driver `preferences {}` block supports only `input` elements (and conditional Groovy around them). `paragraph` / `href` / `section` are app-page (`dynamicPage`) primitives and cause a compile error in a driver (`No signature of method: ...paragraph()`). For in-prefs guidance in a driver, use an `input` instead: `input name: "info", type: "paragraph", element: "paragraph", title: "<b>Heading</b>", description: "<i>text</i>"` renders the HTML title and description, and `input name: "info", type: "hidden", title: "<div>text</div>"` renders the HTML title alone. Both occupy one preferences grid cell, not a full row (firmware 2.5.2.124).
- A `capability.*` input compiles in a driver but never yields a device. The hub offers no devices to pick (`options` is empty in `/device/fullJson`), and a value written with `device.updateSetting(name, [type: "capability.thermostat", value: [id]])` reads back as the String `"[18]"`, not a `DeviceWrapper`. A driver can't hold a handle to another device (firmware 2.5.2.129).
- `multiple: true` on an `enum` input is app-only. In a driver it is silently ignored: the picker renders single-select and `settings.<name>` binds a **String** (one value), never a `List` — even though the per-setting metadata may still echo `multiple: true`. Code that assumes a driver enum setting is a List is wrong.

## What only an app can do

Measured with a throwaway driver on firmware 2.5.2.129; the on-hub API reference (`/developer-docs/index.json`) lists each of these methods under app scopes only.

- `subscribe()` does not exist in a driver: `subscribe(location, "mode", …)` throws `MissingMethodException`. A driver can't listen to other devices, location modes, hub variables or `systemStart`.
- `getGlobalVar()` does not exist in a driver (`MissingMethodException`), so a driver can't read hub variables.
- `mappings { }` fails to compile in a driver (`No signature of method: Script1.mappings()`), so a driver has no OAuth HTTP endpoints.
- Device selection: see the `capability.*` input note under *Driver preferences*.

With the preferences limits (no `dynamicPage`, `paragraph` or `href`), a driver can act only on its own device and on what its parent passes it. Logic that watches or commands other devices belongs in an app.

## Capabilities

- Capabilities cannot change at runtime, so a multi-function driver must declare the **union** of every capability it might expose — which forces unused capabilities onto every instance and is hostile to consumers (dashboards, rules, app device-selection filters all key off capabilities). When one upstream system surfaces multiple device types, prefer fanning out into one child driver per type (parent app routes by type) over a single multi-capability driver.
- `capability "RTSPStream"` does not exist on firmware 2.5.2.121 and earlier. A driver that must still compile there can wrap the declaration in `try { capability "RTSPStream" } catch (Exception e) {}` inside `metadata`. The hub serves the stream as MJPEG at `/hub2/videoStream/{deviceId}.mjpg` and checks the source with `POST /hub2/videoStream/{deviceId}/validate` (JSON reply with `success`, `message`, `width`, `height`). Drivers skip streaming when `getNumericHubVersion() < 9`.
- There is no `capability "FirmwareUpdate"` — declaring it fails to compile (`Capability 'FirmwareUpdate' not found`). The convention is a plain `command "updateFirmware"` whose body returns `zigbee.updateFirmware()`.

## Thermostat driver modes

- Canonical `thermostatMode` / `thermostatFanMode` values (off/heat/cool/auto/emergency heat; auto/circulate/on) can be **narrowed** but not extended — non-canonical values break dashboard widget rendering and capability adherence. Expose a non-canonical mode (e.g. `dry`, `fan_only`) through a parallel custom attribute + command pair instead of stuffing it into `thermostatMode`.
- Set the supported-modes list by `sendEvent` of the `supportedThermostatModes` / `supportedThermostatFanModes` attribute with a list of **pre-quoted** strings (`["\"off\"", "\"heat\"", ...]`) so the platform's stringification yields a valid JSON array. Calling `setSupportedThermostatModes(...)` was observed to fail to bind on a custom (user-namespaced) driver (`MissingMethodException`) — version-observed, not guaranteed across firmware.

## Command Retry

- Command Retry is a hub-wide, per-device opt-in and is **protocol-agnostic** (not Zigbee-specific). It surfaces as `commandRetrySelectionEnabled` (boolean) at the top level of `/device/fullJson/{id}`. When enabled and a command fails, the platform retries (up to 5) then emits a warn-level log line (`<label> command "<cmd>()" failed after 5 retries.`). Nothing is persisted — no attribute, counter, or endpoint exposes the retry count. A "failed after N retries" warning is therefore not by itself evidence of a Zigbee mesh problem.

## Scheduler helpers

- `cancelRunIn(handle)` / `cancelRunOnce(handle)` (firmware 2.4.2.119+) — take the `String` handle returned by `runIn` / `runOnce` and cancel that specific pending job. Returns `Boolean`. Use when an app has multiple pending invocations of the same handler that need to be individually cancellable (per-device debouncers all routing through one shared method, etc.). Doesn't replace `unschedule(handlerName)` or the same-handler-name overwrite default — those remain correct for "cancel all" and "always latest wins" patterns respectively.

## Subscription helpers

- `subscribe(dev, attr, handler, [subscriptionData: 'value'])` (firmware 2.4.1.151+) — attaches arbitrary data to a subscription so one shared handler can disambiguate origin without per-device wrappers. Handler-side accessor (likely `evt.subscriptionData`) not yet HAR-verified here.

## Zigbee parse() delivery

- Since firmware 2.5.0.157 the hub answers Time cluster (0x000A) Read Attributes requests itself (release notes: "Complete hub attribute responses for Zigbee Time Cluster (0x000A) read attribute requests"), so devices that poll for the time no longer need a driver-side responder. Whether the reads now also reach `parse()` has not been re-tested. The rest of this bullet describes earlier firmware, and the responder technique still applies to other frames the platform withholds.
- Before 2.5.0.157, inbound Time cluster (0x000A) Read Attributes commands were **not delivered to a driver's `parse()`** — other frames from the same device arrive normally, only the 0x000A reads are withheld, and declaring 0x000A in the fingerprint `inClusters` did not change the routing. A reactive Read-Attributes-Response from `parse()` was therefore impossible. A responder path works instead: a driver can open `ws://127.0.0.1:8080/zigbeeLogsocket` via `interfaces.webSocket.connect()`, watch for inbound 0x000A frames, and send the Read-Attributes-Response with `he raw`.
- `he raw` response format: space-separated bytes, `0x`-prefixed, with a `0x`-prefixed DNI. The ZCL sequence byte must be **echoed from the request payload**, not taken from the websocket log-event counter. A malformed `he raw` (missing `0x` prefix or non-space-separated bytes) is silently dropped, so the format is exact.

## App endpoints and child apps

- A `mappings` handler must return the result of `render(...)`: `def handler() { return render(status: 200, contentType: "application/json", data: json) }`. A `void` handler, or one that does not return the `render` result, answers an empty 200.
- `request.JSON` is blocked in the sandbox; parse POST bodies with `parseJson(request.body)`. A malformed JSON body sent with `Content-Type: application/json` is answered by the platform with an empty 200 before app code runs, with no app log line, so the app cannot return its own 400 for it *(observed 2.5.2.129)*.
- A parent app can create its own child app instances with `addChildApp(namespace, name, label)`, which returns the new child. Test tooling can reach this through a mapping that answers only while the parent's debug logging is on.
- `addChildApp` runs the child's `installed()` before it returns: right after the call the child reports `installed: true` and `getInstallationState() == "COMPLETE"`, its `initialize()` has created its component devices, subscriptions and scheduled jobs, and it is listed in `/hub2/appsList`. Anything the parent then writes into the child (settings, state) is live at once, so it must already be safe to act on; it is not held until someone presses Done. Whether a job scheduled at that point runs before the child's page is first saved has not been checked *(observed 2.5.2.129)*.
- An exception thrown in a child method that the parent calls (`child.someMethod(...)`) does not propagate: the parent gets `null`. Check the returned value, not only an error flag inside it.
- `deleteChildApp(id)` also deletes the child's component devices.
- Calls from a parent into a `singleThreaded` child are serialized with each other: two simultaneous HTTP requests to the parent (which is not `singleThreaded`), each calling the same child method, never overlapped in ten rounds. Whether they are serialized with the child's own scheduled and event handlers has not been measured *(observed 2.5.2.129)*.

## HTTP subsystem

- `httpPost` / `asynchttpPost` (firmware 2.4.1.151+) — accept `gzipBody: true` to gzip-encode the request body. Only useful when the upstream documents/accepts gzip — do not assume.
- The HTTP subsystem reuses connections across calls (2.4.1.151+). Transparent for callers, but it changes timing: subsequent calls to the same host avoid handshake cost. Test assertions about latency that depend on cold-handshake behavior may flake on warm pools. Still subject to the 8-concurrent async-HTTP cap.
- `contentType:` controls how the **response** is parsed, not the request body. A form-encoded `contentType` (e.g. `application/x-www-form-urlencoded`) makes the platform parse a JSON response as form data, silently yielding a malformed `resp.data` whose fields read back `null` — with no error raised. Set the request body type with `requestContentType` and the response type with `contentType`, and keep a `JsonSlurper().parseText` fallback for `resp.data instanceof String` (some firmware still returns text).
- For deferred retry/backoff, do not use `pauseExecution(ms)` + a recursive call — that blocks the platform thread and eats the method's wall-time budget. Use a `runInMillis(ms, "handler", [data: ...])` continuation instead (which requires the call be async/callback-shaped). Before adding inline retry, note that scheduled callers (cron polls, `runEvery*`) already retry on their next tick.
- Always set an explicit `timeout:` (seconds) on `httpGet`/`httpPost`; the default is long enough to hold the thread tens of seconds on a slow upstream.
- An app cannot `httpGet`/`httpPost` its **own** hub's external LAN IP — the call fails as a connection/peer error. For same-hub calls (including reaching another app's OAuth `/apps/api/<id>/...` endpoint) address `http://127.0.0.1:8080/...`. Calls to a **different** hub's LAN IP are normal cross-host HTTP and work fine — this only bites self-referential calls.

## CPU column semantic change

- `freeOSMemoryHistory.csv` / `freeOSMemoryLast.csv` CPU column changed semantics in firmware 2.4.4.129 — from "average load" to "CPU %" (sampled at 1 sec interval). This is a value-meaning change, not a position change — code that parses by header name still gets the right column but its numeric range has shifted (load averages and percentages aren't directly comparable across the boundary).
- Separately, the `freeOSMemory*` CSV column **order** has drifted across firmwares (multiple times), which silently breaks positional parsers (`split(',')[index]`) with no exception — wrong values, not a crash. Parse by header-name→index: build a name→index map from the header row and look up each field by name; warn or bail if an expected header is absent. This likely applies to other `/hub/advanced/*` CSV endpoints too.

## Admin UI icon fonts

- The hub admin UI and **app configuration pages** (`/installedapp/configure/{id}`) load **Font Awesome 6 Pro** and **PrimeIcons** site-wide, so an app `dynamicPage` can use the hub's own `fa-*` / `pi-*` glyph classes and they match the admin UI in shape and size. Standalone HTML served from an app endpoint (`render` / OAuth report pages) or File Manager loads **neither** font — there, inline an SVG rather than relying on icon-font classes (cloud-served variants also can't reach `/ui2/...` asset paths). FA/PrimeIcons version specifics may age across firmware; verify by inspecting the loaded CSS.

## App page rendering (`dynamicPage`)

Verified against the hub's app-page templates (`/ui2/js/appUI.js`) on firmware 2.5.2.124. The firmware version that introduced each option is unknown.

- `paragraph` emits its text **unescaped** whether or not `rawHtml` is set, so `<style>`, `onclick`, and `<a target='_blank'>` work either way. Escape any user, device, or network text before putting it in a paragraph.
- `paragraph rawHtml: true, html` only drops the wrapper `<div style="white-space:pre-wrap; text-align:left">`. Use it for multi-line `"""` HTML, whose newlines and indentation otherwise render as visible whitespace (and stack with any `<br>`), and for flex or centered layouts. Keep plain paragraphs that rely on `\n` line breaks without it.
- `section(sectionClass: "x")` sets the class on the wrapper that holds the section title and its body grid, so CSS on `.x > *` reaches the title and the grid, and the inputs inside the grid are out of its reach. It scopes per-section styling and cannot build a multi-column input layout; inputs go side by side through their own `width:` values on the native grid *(firmware 2.5.2.129)*.
- On `input`, `styleClass:` lands on the cell div and `inputClass:` on the control itself. For buttons, `inputClass: "p-button"`, `"p-button p-button-outlined"`, or `"p-button bg-hubitat-primary-green text-white"` gives the native PrimeVue look. Buttons also accept `disabled:`.
- App pages load PrimeFlex and PrimeVue CSS alongside the icon fonts (see below), so utility classes work in paragraph HTML: layout (`flex align-items-center gap-3`, `border-1 border-round p-3`), color (`bg-green-50`, `text-red-700`, `text-color-secondary`), and native message boxes (`p-message p-message-warn`).
- `window.alertHubitat(html)` is a firmware UI global that opens the native modal dialog. Call it from an `onclick` in paragraph HTML; JSON-encode the HTML, then entity-escape `& ' < >` for a single-quoted attribute. It is alert-only (no confirm result).
- The Done button can be hidden on a confirmation page so the only exits are explicit Cancel and Confirm: `#formApp:has(.my-confirm-section) #fieldsetAppButtons button[value='Done'] { display:none !important; }`. Pair it with a single-use token in the confirm `href` `params` so a refresh or stale link can't repeat the action.
- `dynamicPage(refreshInterval: n)` re-renders the page every `n` seconds; set it only while async work is running (`busy ? 2 : 0`). An app still in initial setup can't run scheduled jobs, so have the page render itself collect the result, and tag each run with a nonce in `state` so callbacks from an abandoned run are ignored.
- `/logs?tab=past&appId=${app.id}` deep-links to the app's past logs.
- Selectors such as `#formApp`, `#fieldsetAppButtons`, `button.hrefElem[name^='_action_href_<name>']`, `.state-incomplete-text`, `.mdl-grid`, and `div.panel-body` style native elements but are internal markup, not API. Prefer PrimeFlex color tokens (`text-color`, `text-color-secondary`) over hard-coded hex so pages follow the theme.

## App label round-trip

- HTML embedded in an app label via `updateLabel()` (e.g. a badge `<span>`) renders in the Apps list but does not always round-trip verbatim: saving the app's config page (the name/label input on Done) can return the label with its **HTML tags stripped**, leaving bare text. A "strip-then-reapply badge" routine anchored to the exact `<span>…</span>` element then fails to match the bare-text remnant and appends a fresh badge on each refresh — it self-stacks (stabilizing at a doubled badge). Strip a label badge by its **text content with optional/repeating markup**, never by exact HTML element.

## Hub hardware

Per bravenel (Hubitat staff, [forum, 2024-01-27](https://community.hubitat.com/t/what-is-c8-pro-soc/132597/6)):

| Model | SoC | CPU | RAM | Z-Wave | Zigbee |
|---|---|---|---|---|---|
| C-5 | Amlogic A113X | Cortex-A53, 1.416 GHz | 1 GB | 500 series, single US frequency (other regions need a dongle) | 1.2 |
| C-7 | Amlogic A113X | Cortex-A53, 1.416 GHz | 1 GB | 700 series, all regions via settings | 1.2 |
| C-8 | Amlogic A113X | Cortex-A53, 1.416 GHz | 1 GB | 800 series, all regions, external antenna | 3.0 |
| C-8 Pro | Amlogic A113X2 | Cortex-A55, 2.016 GHz | 2 GB | 800 series, all regions, external antenna | 3.0 |

Earlier forum speculation that these hubs used the S905X is wrong. Staff gave no storage type and no performance figures beyond "C-8 Pro boots almost twice as fast as C-8", and said they do not know whether that comes from the CPU, memory or storage. A user in the same thread reports the C-8 Pro Ethernet still links at 100 Mb/s.

## File Manager API

On firmware 2.5.2.129 the on-hub developer docs (`/developer-docs/index.json`) list four file methods: `uploadHubFile(String, byte[])`, `downloadHubFile(String)`, `deleteHubFile(String)` and `getHubFiles(String folder = "")`. There is no append and no rename, so adding a line means downloading the whole file and uploading it again. File names accept only ASCII letters, digits, dot, underscore and hyphen. The docs say `downloadHubFile` returns null for an unavailable file, but on a C-7 (2026-10-04) it threw `NoSuchFileException` for a missing file.

Append cost grows with file size (2.5.2.129, a throwaway probe app, median of 5, ms per one-row append):

| File size | C-7 app (String) | C-7 app (bytes) | C-8 Pro app (String) | C-8 Pro app (bytes) | C-8 Pro Rule Machine "Append to local file" |
|---|---|---|---|---|---|
| 1 KB | 12 | 12 | 11 | 17 | 32 |
| 1 MB | 76 | 30 | 25 | 19 | 55 |
| 4 MB | 233 | 124 | 123 | 66 | 155 |
| 8 MB | 392 | 252 | 210 | 196 | 685 |

"String" is the usual logger pattern (`new String(bytes) + row`, `getBytes()`); "bytes" copies the byte arrays only. On the C-7 at 8 MB the String round trip took 234 ms of the 392, download 73 and upload 85. Rule Machine's "Append to local file" (`actSubType` `getAppendLocalFile`) also slows with file size, so it rewrites the whole file as apps must. It appends the text with no newline.

Two writers appending to the same file at once lose rows with no exception: both download the same content and the last upload wins (C-8 Pro, 1 KB to 4 MB, every burst lost all rows but one). `singleThreaded: true` prevents this inside one app only; nothing in the sandbox locks a file across apps.

Appends from several apps to their own files slow each other down (one probe app, not `singleThreaded`, one file per concurrent append, median of 5 bursts, ms per append, no exceptions):

| File size | Files at once | C-8 Pro | C-7 |
|---|---|---|---|
| 1 KB | 1 / 2 / 4 / 8 | 11 / 16 / 28 / 57 | 12 / 14 / 18 / 20 |
| 1 MB | 1 / 2 / 4 / 8 | 44 / 62 / 98 / 206 | 72 / 81 / 162 / 252 |
| 2 MB | 1 / 2 / 4 / 8 | 60 / 102 / 171 / 2004 | 120 / 196 / 320 / 3032 |
| 4 MB | 1 / 2 / 4 / 8 | 124 / 196 / 2014 / 4364 | 224 / 430 / 3105 / 6208 |

On the C-8 Pro the upload step grows with the number of concurrent uploads even at 1 KB (10 to 57 ms), so File Manager writes appear to be serialized hub-wide. Past a few MB in flight the time per append jumps to about 2 s (C-8 Pro) or 3 s (C-7), with single appends up to 8.6 s on the C-8 Pro and 7.1 s on the C-7 at 4 MB x 8. In the slow appends the extra time lands in the String conversion in some runs (up to 97%) and in `uploadHubFile()` in others (up to 92%). The cause (garbage collection, a write lock) is not verified.

## Firmware changelog notes

- The hub-as-HomeKit-**controller** app ("HomeKit Controller", C-8 Pro) was renamed to **"HomeKit Bridge"** in firmware 2.4.2.128 — code matching the literal app-type string should accept both. This is the accessory-controller direction (hub controls HomeKit accessories), distinct from the long-standing HomeKit Integration app that exposes hub devices to HomeKit.
- Firmware 2.5.2.122 added `hubitat.helper.NetworkUtils.startReolinkDiscovery()` (returns a scan id) and `getReolinkDiscoveryStatus(scanId)` (`status` of `running`/`complete`/error, `candidates` of `[host, uid]`, `errorCode` such as `reply_port_in_use` when UDP 3000 is taken). Gate on the firmware version and also catch `MissingMethodException`.
- Shelly and UniFi Network became **built-in** integrations in firmware 2.4.3.122; MQTT (export device data + run commands over MQTT, later an in-hub broker option and Home Assistant discovery) in 2.4.4.151+. A community app of the same name can still coexist, so match integrations by app-type id / built-in flag, not by literal name.
