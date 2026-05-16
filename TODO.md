<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Architecture-Compliance TODO

Worst offenders against [`ARCHITECTURE.md`](ARCHITECTURE.md), ranked by severity-weighted
violation count (errors = Patterns To Avoid items, warnings = convention drift).

Each block lists concrete fixes with file:line references and the doc section that
governs the rule.

---

## 1. `apps/sensors/StickyMotion.groovy`

Small file, violation-dense, brittle by design.

- [ ] **Add `unschedule()` to `updated()`** at `:43`. `motionActiveHandler` schedules `runIn(duration, "checkMotionState_${index}", ...)` — dynamic handler names. If the sensor list changes between saves, old `checkMotionState_N` timers persist (the per-handler-name `overwrite: true` default only protects same-name reschedules). *Rule:* Common → Lifecycle skeleton.
- [ ] **Replace the `checkMotionState_0..9` ladder** at `:76-85` with a single handler that pulls the index from the `data` map (already passed at `:65`). *Judgment call*, but the trailing "Repeat for as many handlers as needed" comment admits the problem.
- [ ] **Add full logging discipline.** Currently no prefs at all; `log.debug "Condition met..."` at `:93` is always-on. Add `txtEnable`/`debugEnable`/`traceEnable`, gated helpers, auto-disable. *Rule:* Logging discipline.
- [ ] **Replace `def` with static types** (24 occurrences). *Rule:* Coding conventions → Static typing.
- [ ] **Add a `state.version` check in `initialize()`.** `child_app_version` is declared at `:23` but never used. *Rule:* Version constants and code-push detection.

---

## 2. `apps/sensors/SensorFilterChild.groovy` ✅ DONE

- [x] In-place `state.valueWindow` mutation replaced with copy+reassign at `:177/184/221`.
- [x] `state.version` check added in `initialize()`; constant renamed to `CHILD_APP_VERSION`.
- [x] Three-pref logging adopted (`txtEnable`/`debugEnable`/`traceEnable`), five private helpers, `disableLogging` renamed to `logsOff`. Kept multi-day `logRetention` auto-disable (intentional design — separate from the standard 30-min debug auto-disable).
- [x] ~29 `def` replaced with static types (one polymorphic local kept untyped per project guidance).

Deployed: maison-pro (app type 597, compile-test only), maison (app type 593, 4 instances live: 839/840 orphans + 843/872 parented), andree (app type 277, 2 orphan instances live). Parent stub installed on maison-pro and andree to satisfy validator.

---

## 3. `apps/utilities/DeviceReplacement.groovy` (mostly done — HTTP migration intentionally deferred)

- [x] `unsubscribe()` added to `updated()`.
- [ ] **Migrate 8 sync `httpGet`/`httpPost` to `asynchttpGet`/`Post`** at `:167, :202, :256, :288, :475, :650, :685, :761`. **Deferred** — `:167/:202/:256/:288` live inside `previewPage()` (sync rendering required) and the rest are sequential dependent calls. See ARCHITECTURE.md *When sync HTTP is the right call*. Re-evaluate only if/when those become a measured bottleneck.
- [x] `state.version` check added in `initialize()`; constant renamed to `APP_VERSION`.
- [x] `logLevel` enum replaced with `txtEnable`/`debugEnable`/`traceEnable` bools; `logsOff` auto-disable added; existing private helpers kept, gates rewritten; new `logTrace` helper added.

Deployed: maison-pro (app type 510 → version 12, 1 active instance).

---

## Honorable mentions

- [x] `apps/sensors/SensorAggregatorDiscreteChild.groovy` — `state.failedTests << testName` at `:760`/`:775` replaced with reassignment. Audit's `updated() missing unschedule()` claim was incorrect: file already has `unsubscribe()` at `:164` and contains no `runIn`/`schedule()` calls. Pushed to maison-pro, published to chalet ✅, maison ✅, andree ❌ (deployment failed — no `Sensor Aggregator` parent type on andree; non-blocking, andree has 0 SADC instances).
- [x] `drivers/EcobeeCompanion.groovy` — `logEnable` migrated to three-bool (`txtEnable`/`debugEnable`/`traceEnable`); new `logTrace` helper added; existing `logsOff` updated to clear both new toggles; `version` constant renamed to `DRIVER_VERSION`; `state.version` check enhanced with warn-on-change. Pushed to maison-pro, published to all three other hubs ✅. **HTTP migration deferred** — 5 sync sites at `:191/:233/:273/:368/:372`; `callApi(...)` returns `Map` synchronously to every command. See ARCHITECTURE.md *When sync HTTP is the right call*.
- ~~`apps/utilities/rlm.groovy`~~ — file removed from the project; item no longer applicable.

---

## Cross-cutting themes

The same failure modes show up across most offenders. When fixing any file, scan for all four:

1. **In-place `state` collection mutation** (`<<`, `.add()`, `.remove()`, `.clear()`) — replace with reassignment.
2. **`def` instead of static types** — return values, parameters, locals.
3. **Version constant declared but `state.version` never checked** — code pushes silently skip reconfiguration.
4. **Missing `unsubscribe()` in `updated()`** — old event subscriptions persist across preference saves.

Note on `unschedule()`: `runIn`, `runInMillis`, `runOnce`, and `schedule` all default to `overwrite: true`, which cancels prior runs of the **same handler name**. So `unschedule()` in `updated()` is only required when (a) handlers are scheduled with dynamic names that change across configs, (b) handlers are scheduled from event handlers and the new config no longer fires those events, or (c) callers pass `[overwrite: false]`.
