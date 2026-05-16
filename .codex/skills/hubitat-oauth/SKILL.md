---
name: hubitat-oauth
description: "Use when the user wants a Hubitat Groovy app to auto-enable OAuth instead of requiring a manual Apps Code toggle. Inspect and edit the local Groovy app, ensure definition() includes oauth: true, add the loopback helper methods needed to enable OAuth through the hub's internal endpoints, and wire the check into install, update, and mapped endpoint paths."
---

# Hubitat OAuth

Use this skill to add self-enabling OAuth bootstrapping to a Hubitat app.

## Workflow

1. Work on app source files only. This pattern is for Hubitat apps, not drivers.
2. Ensure the app's `definition()` block includes `oauth: true`.
3. Extract the exact app `name:` value from `definition()`.
4. Add helper methods equivalent to:
   - a lookup for the app type ID via `GET http://127.0.0.1:8080/hub2/userAppTypes`
   - a fetch of the current code version via `GET http://127.0.0.1:8080/app/ajax/code?id={typeId}`
   - a loopback `POST http://127.0.0.1:8080/app/edit/update` sending `id`, `version`, `oauthEnabled=true`, and `_action_update=Update`
   - a wrapper that calls `createAccessToken()`, catches the not-enabled failure, auto-enables OAuth, and retries token creation
5. Call the wrapper from `installed()`, `updated()`, and any mappings handlers or pages that need a token available.
6. If the app exposes a UI page for OAuth-gated URLs, add a clear manual fallback message when automatic enablement fails.

## Constraints

- Use `127.0.0.1:8080` only. This works from Groovy on the hub, not from external scripts.
- The `version` field in the `/app/edit/update` POST is mandatory.
- The lookup name must exactly match the `definition()` `name:` value.
- `createAccessToken()` is Hubitat-provided. Do not redefine it.

## Reporting

Describe where the OAuth bootstrapping was inserted, any assumptions made about lifecycle methods, and any spots that still require manual review.
