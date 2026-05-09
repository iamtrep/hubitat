# `hubRequest` Contract Cleanup — Design Spec

**Date:** 2026-05-09
**Addresses:** CODE_REVIEW.md finding #12

---

## Problem

`hubRequestInternal` has three distinct return shapes for "did it work?":

| Scenario | Return value |
|---|---|
| json success | `Map` (or `[:]` if data is null) |
| text success | `String` (or `null` if empty) |
| json exception | `[error: true, message: "..."]` Map |
| text exception | `null` |

Every Map-returning json caller guards with `if (!resp || resp.error) return null` — the error sentinel is mixed into the data channel, so two conditions are always required.

---

## Scope

**In scope:** ~35 Map-returning json callers.

**Out of scope (unchanged):**
- `hubRequest()` and `hubRequestInternal()` — signatures and internal sentinel stay as-is
- Text callers (~25) — `null` already unambiguously means failure; no `.error` problem
- List-returning json callers (6: `fetchUserAppTypes`, `fetchDeviceTypes`, `fetchBundles`, `fetchLibraries`, `fetchLocalBackups`, `fetchZigbeeChannelScan`) — kept on `hubRequest()` with existing `instanceof List` guards

---

## Solution: `hubMapRequest()`

Add one new private method alongside `hubRequest()`:

```groovy
private Map hubMapRequest(String path, String name, int timeout = 30) {
    Object raw = hubRequestInternal(path, name, "json", timeout, true)
    if (raw instanceof Map && ((Map) raw).error) {
        return [ok: false, data: [:], error: (String) ((Map) raw).message]
    }
    return [ok: true, data: (Map)(raw ?: [:]), error: null]
}
```

Return contract: `[ok: boolean, data: Map, error: String?]`. `error` is null when `ok` is true.

Retry logic, timeout, and logging are inherited from `hubRequestInternal` — no duplication.

The `[error: true, message:]` sentinel remains an internal implementation detail; it never appears in the new contract.

---

## Caller migration

Three patterns cover all ~35 call sites.

### Pattern 1 — typical immediate consumer (majority)

```groovy
// Before:
Map resp = (Map) hubRequest(ROOMS_LIST_PATH, "rooms list", "json", 10)
if (!resp || resp.error) return []
return resp.rooms ?: []

// After:
Map wrap = hubMapRequest(ROOMS_LIST_PATH, "rooms list", 10)
if (!wrap.ok) return []
return wrap.data.rooms ?: []
```

### Pattern 2 — shared cache store

```groovy
// Before:
shared.hubData = (Map) hubRequest(HUB_DATA_PATH, "hub data (shared)", "json", 10)

// After:
Map r = hubMapRequest(HUB_DATA_PATH, "hub data (shared)", 10)
shared.hubData = r.ok ? r.data : null
```

Consumers of `shared.hubData` (and other shared entries) already null-check the value; they drop only the `.error` half of their guard:

```groovy
// Before:
if (!hubData || hubData.error) return [:]

// After:
if (!hubData) return [:]
```

### Pattern 3 — return Map up the stack

```groovy
// Before:
Map resp = (Map) hubRequest(FIRMWARE_UPDATE_PATH, "firmware update check", "json", 15)
if (!resp || resp.error) return null
return resp

// After:
Map wrap = hubMapRequest(FIRMWARE_UPDATE_PATH, "firmware update check", 15)
if (!wrap.ok) return null
return wrap.data   // upstream callers are unchanged
```

---

## Edge cases

**Empty-success (`[:]`):** `hubRequestInternal` returns `[:]` when json response data is null. `hubMapRequest` maps this to `[ok: true, data: [:], error: null]`. Callers that access `wrap.data.someKey` receive `null` safely — no behaviour change.

**Snapshot / state consumers:** `state.*` entries are Maps stored by earlier calls. Their read paths have no `hubRequest` calls; nothing to migrate.

---

## Testing

No new tests required. `test-hub-diagnostics-api.sh` validates the full API surface end-to-end. A correct migration produces identical API responses; the existing suite passing is sufficient verification.

---

## Implementation notes

- Migrate all ~35 call sites in a single commit (mechanical but complete)
- Remove `type = "json"` argument from every migrated call site (now implicit)
- After migration, grep for remaining `hubRequest.*"json"` patterns to confirm only List callers remain
- Bump APP_VERSION (Groovy) and UI_VERSION (HTML) together per project convention — even though the HTML has no functional changes, both version constants must move together
