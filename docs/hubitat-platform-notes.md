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
- `getObjectClassName(value)` — global method injected into script context; use instead of `value.getClass()` (which is sandbox-blocked) to get the runtime class name of any object

## Platform behavior

- `sendEvent()` automatically deduplicates: if the value hasn't changed and `isStateChange` is not set to `true`, the event is filtered out (not fired)
- `state` object is committed to the database when the method exits (not on each write). `atomicState` commits immediately on each write.
- In-place mutation of state values (e.g., `state.list << item`) may go undetected and not persist — always use explicit reassignment (`state.list = modifiedList`) to ensure the change is tracked.
- **Code push does NOT trigger `updated()`**: pushing new source code takes effect immediately (Groovy is interpreted), but does NOT call `updated()`/`initialize()`. Subscriptions and `state` from the old code persist. Must re-save app preferences to trigger a clean re-init.
- `@Field static` resets on code push (verified on firmware 2.5.0.140). Use `state`/`atomicState` for values that must survive a push.
- Sandbox atomic allow-list: only `AtomicInteger` and `AtomicIntegerArray` are usable. `AtomicLong`, `AtomicReference`, `AtomicBoolean` are sandbox-blocked. Use `AtomicInteger` for counters; otherwise use a `synchronized` block.
- Hubitat coalesces same-device events that fire <1s apart — multiple Maker API commands in rapid succession can lose events silently. Space them ≥0.5s for filter/debouncer apps.
- `runIn` / `schedule` with the same handler name overwrite by default; rescheduling is self-cancelling. `unschedule()` is only required when the same handler name is no longer wanted at all.
- Never echo `"[]"` as the value of a typed setting. Hubitat stores the literal string and Groovy arithmetic on the setting hits string repetition and crashes (`Long.minus(String)`). For unset preferences, omit the field or send its default.
- The async HTTP call pool is capped at 8 concurrent per app. Per-device drilling at scale exhausts it; batch or rate-limit.
- `com.hubitat.hub.domain.Hub` is the importable type for `location.hubs[0]`. `com.hubitat.app.HubInfo` and `HubWrapper` do not exist.
