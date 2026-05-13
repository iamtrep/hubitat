# Architecture-Compliance TODO

Worst offenders against [`ARCHITECTURE.md`](ARCHITECTURE.md), ranked by severity-weighted
violation count (errors = Patterns To Avoid items, warnings = convention drift).

Each block lists concrete fixes with file:line references and the doc section that
governs the rule.

---

## 1. `apps/utilities/ruletracker.groovy`

Looks like an early prototype that never got rewritten.

- [ ] **Add `unsubscribe()` to `updated()`** at `:94`. (`unschedule()` is already handled inside `updateScheduledCheck()` at `:278`.) *Rule:* Common → Lifecycle skeleton.
- [ ] **Add a version constant and `state.version` check.** No `@Field static final String APP_VERSION` exists. *Rule:* Version constants and code-push detection.
- [ ] **Adopt the three-pref logging convention.** Rename `debugLogs`/`traceLogs` to `debugEnable`/`traceEnable`, add `txtEnable`, define private helpers, add `runIn(1800, "logsOff")`. *Rule:* Logging discipline.
- [ ] **Replace `def` with static types** (34 occurrences — return types, parameters, locals like `def String tableHtml`, `def int eventsStartIndex`). *Rule:* Coding conventions → Static typing.
- [ ] **Consider switching the sync `httpGet` at `:242` to `asynchttpGet`** with the three-step response check. *Rule:* Async HTTP callback contract.
- [ ] **Decide the fate of the file.** The top TODO block hints the design was never finished — either complete it or delete it.

---

## 2. `apps/sensors/StickyMotion.groovy`

Small file, violation-dense, brittle by design.

- [ ] **Add `unschedule()` to `updated()`** at `:43`. `motionActiveHandler` schedules `runIn(duration, "checkMotionState_${index}", ...)` — dynamic handler names. If the sensor list changes between saves, old `checkMotionState_N` timers persist (the per-handler-name `overwrite: true` default only protects same-name reschedules). *Rule:* Common → Lifecycle skeleton.
- [ ] **Replace the `checkMotionState_0..9` ladder** at `:76-85` with a single handler that pulls the index from the `data` map (already passed at `:65`). *Judgment call*, but the trailing "Repeat for as many handlers as needed" comment admits the problem.
- [ ] **Add full logging discipline.** Currently no prefs at all; `log.debug "Condition met..."` at `:93` is always-on. Add `txtEnable`/`debugEnable`/`traceEnable`, gated helpers, auto-disable. *Rule:* Logging discipline.
- [ ] **Replace `def` with static types** (24 occurrences). *Rule:* Coding conventions → Static typing.
- [ ] **Add a `state.version` check in `initialize()`.** `child_app_version` is declared at `:23` but never used. *Rule:* Version constants and code-push detection.

---

## 3. `apps/sensors/SensorFilterChild.groovy`

Cleaner shape than the others, but still trips multiple Patterns To Avoid.

- [ ] **Replace in-place `state.valueWindow` mutation with reassignment.** *Rule:* Common → State tiers.
  - `:177` — `state.valueWindow.add(value)`
  - `:184` — `state.valueWindow.remove(0)`
  - `:221` — `state.valueWindow.remove(0)`
  - Pattern: copy the list, mutate, reassign.
- [ ] **Add a `state.version` check in `initialize()`.** `child_app_version` declared at `:31` but never compared. *Rule:* Version constants and code-push detection.
- [ ] **Adopt the three-pref logging convention.** Add `txtEnable`, rename `logEnable` → `debugEnable`, add private `logTrace`/`logDebug`/... helpers, add the standard `runIn(1800, "logsOff")` auto-disable (the custom `logRetention` window is a different mechanism). *Rule:* Logging discipline.
- [ ] **Replace `def` with static types** (29 occurrences). *Rule:* Coding conventions → Static typing.

---

## 4. `apps/utilities/DeviceReplacement.groovy`

Hub-API helper. Doesn't follow lifecycle or async conventions.

- [ ] **Add `unsubscribe()` to `updated()`.** Currently `logDebug "updated()"; initialize()`. (The file has no `runIn`/`schedule` calls, so `unschedule()` is not strictly needed.) *Rule:* Common → Lifecycle skeleton.
- [ ] **Migrate 8 sync `httpGet`/`httpPost` call sites to `asynchttpGet`/`Post`** with the three-step response check (`hasError`, status, then body). *Rule:* Async HTTP callback contract.
  - `:167`, `:202`, `:256`, `:288`, `:475`, `:650`, `:685`, `:761`
- [ ] **Add a `state.version` check.** `app_version` declared at `:28` but never compared. *Rule:* Version constants and code-push detection.
- [ ] **Switch the `logLevel` enum to the three-boolean convention** (`txtEnable`/`debugEnable`/`traceEnable`) and add `runIn(1800, "logsOff")` auto-disable. Existing private helpers can stay. *Rule:* Logging discipline.

---

## Honorable mentions

Not in the top 5, but worth queuing.

- [ ] `apps/sensors/SensorAggregatorDiscreteChild.groovy:760,775` — `state.failedTests << testName` (in-place mutation); `updated()` missing `unschedule()`.
- [ ] `drivers/EcobeeCompanion.groovy` — 5 sync `httpGet`/`Post`, no `txtEnable`/`debugEnable`/`traceEnable`, no gated log helpers.
- [ ] `apps/utilities/rlm.groovy` — `updated()` missing `unsubscribe()`/`unschedule()`; only `debugEnable`, no log helpers; document the sync `httpGet` calls used for OAuth bootstrap so future readers know they're deliberate.

---

## Cross-cutting themes

The same failure modes show up across most offenders. When fixing any file, scan for all four:

1. **In-place `state` collection mutation** (`<<`, `.add()`, `.remove()`, `.clear()`) — replace with reassignment.
2. **`def` instead of static types** — return values, parameters, locals.
3. **Version constant declared but `state.version` never checked** — code pushes silently skip reconfiguration.
4. **Missing `unsubscribe()` in `updated()`** — old event subscriptions persist across preference saves.

Note on `unschedule()`: `runIn`, `runInMillis`, `runOnce`, and `schedule` all default to `overwrite: true`, which cancels prior runs of the **same handler name**. So `unschedule()` in `updated()` is only required when (a) handlers are scheduled with dynamic names that change across configs, (b) handlers are scheduled from event handlers and the new config no longer fires those events, or (c) callers pass `[overwrite: false]`.
