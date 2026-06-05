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
- `getObjectClassName(value)` — global method injected into script context; use instead of `value.getClass()` (which is sandbox-blocked) to get the runtime class name of any object

## Platform behavior

- `sendEvent()` automatically deduplicates: if the value hasn't changed and `isStateChange` is not set to `true`, the event is filtered out (not fired)
- `state` object is committed to the database when the method exits (not on each write). `atomicState` commits immediately on each write.
- In-place mutation of state values (e.g., `state.list << item`) may go undetected and not persist — always use explicit reassignment (`state.list = modifiedList`) to ensure the change is tracked.
- **Code push does NOT trigger `updated()`**: pushing new source code takes effect immediately (Groovy is interpreted), but does NOT call `updated()`/`initialize()`. Subscriptions and `state` from the old code persist. Must re-save app preferences to trigger a clean re-init.
- `@Field static` resets on code push (verified on firmware 2.5.0.140). Use `state`/`atomicState` for values that must survive a push.
- Bare (undeclared, no `def`) variable assignments inside a command method write to the script binding but do **not** persist across separate command invocations (verified on firmware 2.5.0.143 via both `/device/runmethod` and Maker API). Each invocation gets a fresh binding: a bare write in call A is gone by call B, and a bare read then falls through to `settings.<name>` (for preference-named vars) or `null` (for non-preference names). So the common driver idiom of assigning per-message values to bare names (e.g. `priority = customPriority` in a notification driver) does **not** leak state between messages on current firmware — but it relies on undocumented instance lifecycle, so prefer `def`-declared locals + explicit `settings.*` reads for firmware-independence. Within a single invocation, bare write-then-read works (and shadows `settings`).
- Sandbox atomic allow-list: only `AtomicInteger` and `AtomicIntegerArray` are usable. `AtomicLong`, `AtomicReference`, `AtomicBoolean` are sandbox-blocked. Use `AtomicInteger` for counters; otherwise use a `synchronized` block.
- Hubitat coalesces same-device events that fire <1s apart — multiple Maker API commands in rapid succession can lose events silently. Space them ≥0.5s for filter/debouncer apps.
- `runIn` / `schedule` with the same handler name overwrite by default; rescheduling is self-cancelling. `unschedule()` is only required when the same handler name is no longer wanted at all.
- Never echo `"[]"` as the value of a typed setting. Hubitat stores the literal string and Groovy arithmetic on the setting hits string repetition and crashes (`Long.minus(String)`). For unset preferences, omit the field or send its default.
- The async HTTP call pool is capped at 8 concurrent per app. Per-device drilling at scale exhausts it; batch or rate-limit.
- `com.hubitat.hub.domain.Hub` is the importable type for `location.hubs[0]`. `com.hubitat.app.HubInfo` and `HubWrapper` do not exist.
- The local OAuth API (`/apps/api/<id>/...`) sends **no CORS headers** and does not support preflight: a cross-origin `GET` returns `200 OK` with no `Access-Control-Allow-Origin`, and an `OPTIONS` preflight returns `405 Method Not Allowed` (verified 2026-05-23 on a C-8 Pro). This is browser-enforced, so a page served by one hub cannot *read* another hub's API response, while `curl` and server-side calls are unaffected. The architectural consequence — browser-based multi-hub tools must proxy cross-hub calls server-side — is in `ARCHITECTURE.md` ("Cross-origin (CORS) and multi-hub browser clients").

## Locale-aware date/time formatting (firmware 2.5.0.143+)

Hubitat exposes platform-injected helpers that format dates per the user's Settings → Hub Details date/time format. Prefer these over hand-rolled `SimpleDateFormat` patterns for any display-side timestamp in apps or driver attributes:

- `formatActivityDateTime(date)`, `formatActivityDateTimeShort(date)`
- `formatDate(date)`, `formatShortDate(date)`
- `formatTimeHourMinute(date)`, `formatTimeHourMinuteSecond(date)`, `formatTimeHourMinuteSecondMillis(date)`

These methods are firmware 2.5.0.143+. Code shipped to older hubs will throw `MissingMethodException` — either gate on `location.hub.firmwareVersionString` or document a minimum-firmware requirement. Storage and comparisons stay in epoch millis (never persist user-formatted strings).

## App `definition()` flags

- `doNotFocus: true` (firmware 2.5.0.123+) — stops the main page auto-focusing the first input on open. Useful when the first element is a paragraph, status banner, or read-only field (the auto-focus otherwise scrolls past it). Unknown definition keys are ignored on older firmware, so this is safe to set unconditionally.
- `showAppTitle: false` (firmware 2.4.1.x+, default true) — hides the app title from the rendered configuration page. Sibling to `doNotFocus`. Safe to set unconditionally on older firmware (unknown keys ignored).

## Scheduler helpers

- `cancelRunIn(handle)` / `cancelRunOnce(handle)` (firmware 2.4.2.119+) — take the `String` handle returned by `runIn` / `runOnce` and cancel that specific pending job. Returns `Boolean`. Use when an app has multiple pending invocations of the same handler that need to be individually cancellable (per-device debouncers all routing through one shared method, etc.). Doesn't replace `unschedule(handlerName)` or the same-handler-name overwrite default — those remain correct for "cancel all" and "always latest wins" patterns respectively.

## Subscription helpers

- `subscribe(dev, attr, handler, [subscriptionData: 'value'])` (firmware 2.4.1.151+) — attaches arbitrary data to a subscription so one shared handler can disambiguate origin without per-device wrappers. Handler-side accessor (likely `evt.subscriptionData`) not yet HAR-verified here.

## HTTP subsystem

- `httpPost` / `asynchttpPost` (firmware 2.4.1.151+) — accept `gzipBody: true` to gzip-encode the request body. Only useful when the upstream documents/accepts gzip — do not assume.
- The HTTP subsystem reuses connections across calls (2.4.1.151+). Transparent for callers, but it changes timing: subsequent calls to the same host avoid handshake cost. Test assertions about latency that depend on cold-handshake behavior may flake on warm pools. Still subject to the 8-concurrent async-HTTP cap.

## CPU column semantic change

- `freeOSMemoryHistory.csv` / `freeOSMemoryLast.csv` CPU column changed semantics in firmware 2.4.4.129 — from "average load" to "CPU %" (sampled at 1 sec interval). This is a value-meaning change, not a position change — code that parses by header name still gets the right column but its numeric range has shifted (load averages and percentages aren't directly comparable across the boundary). Also see the column-reordering caveat in the platform-behavior memory file.
