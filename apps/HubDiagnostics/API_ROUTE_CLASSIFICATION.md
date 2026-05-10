# API Route Classification

This document classifies every current `HubDiagnostics` `/api/*` route by whether it must remain in the Groovy layer, is thin but still justified there, or could plausibly move to direct browser-to-hub fetches in a future admin-session mode.

## Assumption

This classification assumes that "direct browser fetch" means a future admin-session UI that can call hub admin endpoints directly and is willing to do more client-side composition. Under the current OAuth app URL model, all routes remain app-mediated for auth reasons.

## Architectural Context

Currently, the Groovy layer acts as an application server for the dashboard by providing:

1. Auth stability via OAuth app endpoints, so the dashboard does not require an active Hubitat admin session.
2. App-owned persistence for snapshots, checkpoints, generated reports, and scan caches.
3. Aggregation of multiple internal hub requests into narrower UI-facing payloads.
4. Normalization of Hubitat-specific payload quirks, compatibility differences, and fail-soft behavior.

## Must Stay Groovy

- `/api/snapshots` - App-owned persisted data.
- `/api/snapshot/view` - Reads app-owned snapshot storage and shapes diff/view payloads.
- `/api/snapshot/diff` - App-owned comparison logic.
- `/api/snapshot/create` - Stateful write owned by the app.
- `/api/snapshot/delete` - Stateful write owned by the app.
- `/api/snapshots/clear` - Stateful write owned by the app.
- `/api/performance` - Uses runtime stats plus app-maintained checkpoints, caches, and app-specific shaping.
- `/api/checkpoint/create` - App-owned persisted checkpoint.
- `/api/checkpoint/delete` - App-owned persisted checkpoint.
- `/api/checkpoints/clear` - App-owned persisted checkpoint management.
- `/api/performance/compare` - App-side comparison logic over checkpoint and baseline data.
- `/api/settings` `GET` - App settings are owned by the Groovy app.
- `/api/settings` `POST` - App settings mutation.
- `/api/cache/clear` - Clears app-owned caches.
- `/api/reports` - App-owned generated report inventory.
- `/api/report/generate` - Report generation is app orchestration plus persistence.
- `/api/forum/data` - Export payload is app-specific composition and formatting.
- `/api/ui/sync` - App-managed UI file lifecycle.
- `/api/version/check` - App/version sync semantics are app-owned.
- `/api/audit/start` - Long-running app orchestration with async scan state.
- `/api/audit/status` - Reads app-owned scan state.
- `/api/audit/data` - Reads app-owned audit result and report state.
- `/api/network/test` - Stateful or side-effectful operation initiated through the app.
- `/api/network/zigbee/scan` - Explicit action, long-running, and cached in app state.
- `/api/stats` - Exposes app-owned API timing telemetry.
- `/api/code` - Includes `getAllGlobalVars()` and normalized joins over several sources, not just a raw proxy.

## Thin But Justified

- `/api/dashboard` - Aggregates multiple hub resources, applies shared-cache and fail-soft behavior, and serves a dashboard-specific contract.
- `/api/devices` - Device classification and enrichment are non-trivial and use joins plus fallback enrichment from `fullJson`.
- `/api/apps` - Joins apps list with runtime stats to surface platform-only apps and parent/child structure.
- `/api/network` - Normalizes several hub resources into one contract and adds mesh and health derivations.
- `/api/health` - Cross-resource health summary with normalization and alert shaping.
- `/api/health/history` - Parses a text endpoint into a stable structured payload.
- `/api/live` - Consolidates hot-path polling into one request and keeps fail-soft semantics centralized.

## Candidate For Direct Browser Fetch In A Future Admin-Session Mode

- `/api/live` - Best candidate; mostly a convenience bundle over direct hub metrics endpoints.
- `/api/health/history` - The browser could fetch and parse directly if client-side parsing of the text payload is acceptable.
- `/api/network` - Candidate if the future UI is willing to own more shaping and compatibility handling.
- `/api/health` - Candidate in part, but it would push more aggregation and alert logic into the browser.
- `/api/dashboard` - Candidate only as a substantial architectural shift where the browser becomes a real backend-composition client.
- `/api/devices` - Candidate only if classification and enrichment logic move client-side.
- `/api/apps` - Candidate only if app and runtime joins move client-side.
- `/api/code` - Partial candidate only; the browser could fetch some code inventory endpoints directly, but hub variables still argue for a Groovy slice unless an equivalent direct endpoint exists.

## Bottom Line

The clean boundary is not "Groovy vs browser" but "app-owned logic vs direct hub data."

Routes that expose app state, app writes, long-running orchestration, exports, comparisons, and Hubitat-only APIs must stay Groovy. Routes that merely aggregate hub diagnostics are the only realistic future browser-fetch candidates, with `/api/live` and parts of `/api/network` and `/api/health/history` being the best first targets.
