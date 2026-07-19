<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Groovy and platform notes

Notes on Hubitat's Groovy sandbox and platform behavior. Reverse-engineered or learned from working in this repo; not Hubitat-official documentation. Endpoints, mechanics, and quirks below may change between firmware versions.

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

- **Just the runtime class name** — `getObjectClassName(value)` is a platform-injected global that returns the FQN as a String. Use it instead of `value.getClass()`, which is sandbox-blocked as a `MethodCallExpression`. Routine pattern in exception handlers: `"${getObjectClassName(e)}: ${e.message}"`. `hexStrToSignedInt(String)` is a sibling platform-injected global (no import or library needed in driver scope): it parses a big-endian hex string as a two's-complement signed value and returns a `Long`. Verified by behavior, not documented — treat it like other introspection-discovered helpers.
- **Live bean-property snapshot** — `obj.properties.each { k, v -> ... }`. Returns the Groovy bean-accessor map of every readable getter with its current value. Used by `drivers/tests/DeviceInspector.groovy` to dump the full `DeviceWrapper` surface in one line. Best for objects whose useful state IS bean properties (`device`, `location`, `hub`, parsed Maps); much smaller surface for objects whose API is parameterized methods (e.g. `zigbee.properties` returns only 6 entries while reflection finds 115 methods).
- **Full API surface with signatures** — `obj.class.getMethods()` / `getFields()` / `getSuperclass()` / `getInterfaces()`. Needed only when you want overload signatures, parameter types, or static fields. The sandbox blocks several routes here: `Class.forName` is rejected at AST level, `java.lang.Class` can't be imported (which kills `Class cls` type annotations — use `def`), `java.lang.reflect.Modifier.isStatic(...)` is rejected (bitmask directly: `(f.modifiers & 0x08) != 0`), `ArrayDeque` is rejected (use `[]` with `.remove(0)` as a FIFO), and `java.lang.Runtime` is rejected at compile (`Expression [ClassExpression] is not allowed: java.lang.Runtime`) so `Runtime.runtime.availableProcessors()` does not work — use `/hub/cpuInfo` for CPU/core-count data instead. `obj.class` (PropertyExpression) returns the Class object fine, and Class/Method/Field getter chains work from there. Reach related classes via factory methods, not `forName`. See `drivers/tests/ZigbeeIntrospect.groovy` and `docs/hubitat-zigbee-helper.md` for a worked example.

## MarkupBuilder (`mkp`) in the sandbox

- Inside `groovy.xml.MarkupBuilder`, the `mkp` accessor resolves to **null within nested tag closures** — `mkp.yieldUnescaped(...)` / `mkp.yield(...)` throw `NullPointerException`. `mkp` is a property (getter) access the sandbox returns null for, whereas tag *method* calls (`svg`, `path`, `td`, …) route through the builder's `methodMissing` and work. Emit raw markup (e.g. inline SVG) as nested builder tags rather than via `mkp`. Hyphenated attribute names (`stroke-width`, `stroke-linecap`) must be quoted string keys in the attribute map.

## Platform behavior

- `sendEvent()` automatically deduplicates: if the value hasn't changed and `isStateChange` is not set to `true`, the event is filtered out of event history and the *default* subscriber path. But: (a) `device.lastActivity` updates on every `sendEvent()` call regardless of dedup, and (b) subscribers that pass `filterEvents: false` to `subscribe()` receive every call including dedup'd ones. So dedup is a UI/default-subscriber convenience, not a "device didn't communicate" signal. **In drivers, just call `sendEvent` on every device report — don't suppress driver-side, and don't override with `isStateChange: true`.** Suppression hides the call from `filterEvents:false` subscribers; forcing `isStateChange:true` removes default subscribers' opt-in to dedup. Apply any value smoothing to the *value* being emitted, not to whether the event fires. Prefer `sendEvent` over `createEvent`: returning a `List` of `createEvent` maps from `parse()` emits events via the platform's return-List path, which leaves the event's **"Produced by" column empty** in device logs, whereas a direct `sendEvent()` from `parse()` or a helper attributes the event to the device.
- `state` object is committed to the database when the method exits (not on each write). `atomicState` commits immediately on each write.
- In-place mutation of state values (e.g., `state.list << item`) may go undetected and not persist — always use explicit reassignment (`state.list = modifiedList`) to ensure the change is tracked.
- **Code push does NOT trigger `updated()`**: pushing new source code takes effect immediately (Groovy is interpreted), but does NOT call `updated()`/`initialize()`. Subscriptions and `state` from the old code persist. Must re-save app preferences to trigger a clean re-init.
- `@Field static` resets on code push (verified on firmware 2.5.0.140). Use `state`/`atomicState` for values that must survive a push.
- Bare (undeclared, no `def`) variable assignments inside a command method write to the script binding but do **not** persist across separate command invocations (verified on firmware 2.5.0.143 via both `/device/runmethod` and Maker API). Each invocation gets a fresh binding: a bare write in call A is gone by call B, and a bare read then falls through to `settings.<name>` (for preference-named vars) or `null` (for non-preference names). So the common driver idiom of assigning per-message values to bare names (e.g. `priority = customPriority` in a notification driver) does **not** leak state between messages on current firmware — but it relies on undocumented instance lifecycle, so prefer `def`-declared locals + explicit `settings.*` reads for firmware-independence. Within a single invocation, bare write-then-read works (and shadows `settings`).
- Sandbox atomic allow-list: only `AtomicInteger` and `AtomicIntegerArray` are usable. `AtomicLong`, `AtomicReference`, `AtomicBoolean` are sandbox-blocked. Use `AtomicInteger` for counters; otherwise use a `synchronized` block. The broader allowed `java.util.concurrent.*` set is `Semaphore`, `ConcurrentHashMap`, `CopyOnWriteArrayList`, `ConcurrentLinkedQueue`, `SynchronousQueue`, `TimeUnit`, and `com.google.common.util.concurrent.Striped`. The sandbox treats a fully-qualified-name reference as an implicit import, so an FQN to a blocked class is rejected the same way as an `import` (`Importing [...] is not allowed`).
- Hubitat coalesces same-device events that fire <1s apart — multiple Maker API commands in rapid succession can lose events silently. Space them ≥0.5s for filter/debouncer apps.
- `runIn` / `schedule` with the same handler name overwrite by default; rescheduling is self-cancelling. `unschedule()` is only required when the same handler name is no longer wanted at all.
- Never echo `"[]"` as the value of a typed setting. Hubitat stores the literal string and Groovy arithmetic on the setting hits string repetition and crashes (`Long.minus(String)`). For unset preferences, omit the field or send its default.
- The async HTTP call pool is capped at 8 concurrent per app. Per-device drilling at scale exhausts it; batch or rate-limit.
- `com.hubitat.hub.domain.Hub` is the importable type for `location.hubs[0]`. `com.hubitat.app.HubInfo` and `HubWrapper` do not exist.
- The local OAuth API (`/apps/api/<id>/...`) sends **no CORS headers** and does not support preflight: a cross-origin `GET` returns `200 OK` with no `Access-Control-Allow-Origin`, and an `OPTIONS` preflight returns `405 Method Not Allowed` (verified 2026-05-23 on a C-8 Pro). This is browser-enforced, so a page served by one hub cannot *read* another hub's API response, while `curl` and server-side calls are unaffected. The architectural consequence — browser-based multi-hub tools must proxy cross-hub calls server-side — is in `ARCHITECTURE.md` ("Cross-origin (CORS) and multi-hub browser clients").
- A per-app cap on pending events can throw `com.hubitat.app.exception.LimitExceededException` when many `sendEvent` calls batch in one tick. If the throw happens inside a self-rescheduling `runIn` body, the reschedule never runs and the chain dies silently — the app looks alive but its sweep is dead. In periodic sweeps emit only the attribute that changed, cap work per tick, and coalesce repeated same-attribute writes.
- Event subscriptions are created in `initialize()`, which runs on `installed()`/`updated()` (clicking **Done**), and they **survive a code push** (a push does not re-run `initialize()`). A device input assigned on a **sub-page** saves the setting but does not arm its subscription until a Done runs `initialize()`. So a freshly-pushed or sub-page-configured app whose subscription isn't firing almost always never ran `initialize()` with that setting present — not a dropped subscription.
- A firmware update downloads the image into memory before applying, transiently dropping `freeMemory` (into single-digit MB on an otherwise healthy hub) for the duration. A low `freeMemory` sample taken shortly after a `/hub/eventsJson` `name=update` event is that download, not a leak or pre-existing pressure. Attribute a post-action memory dip (firmware update, manual backup/restore, large file upload) to the action before diagnosing hub overload — real memory pressure shows up in trended readings *before* any user action, not in a single post-action sample.

## Location events

- Any app can call `sendLocationEvent(name:, value:, descriptionText:)` with **reserved platform event names** (`systemStart`, `manualReboot`, `manualShutdown`, `sunrise`, `sunset`, `cloudBackup`, …). The dispatcher does not gate by provenance, and a subscriber handler cannot distinguish an app-originated event from a platform one (`evt.source` carries no such tag). Apps that key trust or lifecycle logic off these names can be misled by any other local app emitting the same name. For a tamper-resistant lifecycle signal, derive it from something only the platform controls — e.g. `location.hubs[0].uptime` crossing a threshold — not from a subscribed event name.

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

- A driver `preferences {}` block supports only `input` elements (and conditional Groovy around them). `paragraph` / `href` / `section` are app-page (`dynamicPage`) primitives and cause a compile error in a driver (`No signature of method: ...paragraph()`). For in-prefs guidance in a driver, use command descriptions (`command "name", [[name: "hint"]]`, which render under the command buttons) or `logInfo`.
- `multiple: true` on an `enum` input is app-only. In a driver it is silently ignored: the picker renders single-select and `settings.<name>` binds a **String** (one value), never a `List` — even though the per-setting metadata may still echo `multiple: true`. Code that assumes a driver enum setting is a List is wrong.

## Capabilities

- Capabilities cannot change at runtime, so a multi-function driver must declare the **union** of every capability it might expose — which forces unused capabilities onto every instance and is hostile to consumers (dashboards, rules, app device-selection filters all key off capabilities). When one upstream system surfaces multiple device types, prefer fanning out into one child driver per type (parent app routes by type) over a single multi-capability driver.
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

- Inbound Time cluster (0x000A) Read Attributes commands are **not delivered to a driver's `parse()`** — other frames from the same device arrive normally, only the 0x000A reads are withheld, and declaring 0x000A in the fingerprint `inClusters` does not change the routing. A reactive Read-Attributes-Response from `parse()` is therefore impossible. A responder path works instead: a driver can open `ws://127.0.0.1:8080/zigbeeLogsocket` via `interfaces.webSocket.connect()`, watch for inbound 0x000A frames, and send the Read-Attributes-Response with `he raw`.
- `he raw` response format: space-separated bytes, `0x`-prefixed, with a `0x`-prefixed DNI. The ZCL sequence byte must be **echoed from the request payload**, not taken from the websocket log-event counter. A malformed `he raw` (missing `0x` prefix or non-space-separated bytes) is silently dropped, so the format is exact.

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

## App label round-trip

- HTML embedded in an app label via `updateLabel()` (e.g. a badge `<span>`) renders in the Apps list but does not always round-trip verbatim: saving the app's config page (the name/label input on Done) can return the label with its **HTML tags stripped**, leaving bare text. A "strip-then-reapply badge" routine anchored to the exact `<span>…</span>` element then fails to match the bare-text remnant and appends a fresh badge on each refresh — it self-stacks (stabilizing at a doubled badge). Strip a label badge by its **text content with optional/repeating markup**, never by exact HTML element.

## Firmware changelog notes

- The hub-as-HomeKit-**controller** app ("HomeKit Controller", C-8 Pro) was renamed to **"HomeKit Bridge"** in firmware 2.4.2.128 — code matching the literal app-type string should accept both. This is the accessory-controller direction (hub controls HomeKit accessories), distinct from the long-standing HomeKit Integration app that exposes hub devices to HomeKit.
- Shelly and UniFi Network became **built-in** integrations in firmware 2.4.3.122; MQTT (export device data + run commands over MQTT, later an in-hub broker option and Home Assistant discovery) in 2.4.4.151+. A community app of the same name can still coexist, so match integrations by app-type id / built-in flag, not by literal name.
