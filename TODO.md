<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Cross-app TODO

Work that spans several apps. Per-project backlogs live next to their code (e.g. `integrations/FGLair/TODO.md`).

## App page UI patterns

Adopt the `dynamicPage` rendering options documented in [App page rendering](docs/hubitat-platform-notes.md#app-page-rendering-dynamicpage). Ordered by value.

### 1. Confirm destructive buttons

These buttons act on a single click straight from `appButtonHandler`:

- `integrations/Blink/BlinkManager.groovy`: "Reset auth state (full wipe)", "Disconnect", "Remove orphaned devices"
- `integrations/visiblair/VisiblAirManager.groovy`: "Remove orphaned devices"

Replace each with an `href` to a confirmation page that lists what will be removed, hides Done, and carries a single-use token in the confirm `href` `params`. `window.alertHubitat` does not fit here; it has no confirm result. `integrations/FGLair/FGLairManager.groovy` (confirmation pages section) implements this.

### 2. BlinkManager diagnostics spacing

`diagnosticsPage` renders a multi-line `"""` paragraph that also uses `<br>`, so pre-wrap doubles every line break. Add `rawHtml: true`.

### 3. DeviceReplacement styling

`apps/utilities/DeviceReplacement.groovy` is the most hand-styled page:

- Inline `color:orange/red/green` spans become `p-message p-message-warn` / `p-message-error` boxes or `text-*-700` classes.
- The inline-styled `#1A77C9` "Edit" links become `p-button` classes.
- The "create a hub backup first" warning becomes a `p-message-warn` box.
- The swap confirmation page hides Done.

### 4. SwitchMonitor group delete

`removeGroupPage` has a confirm button and a Cancel link, but its `nextPage` button is a third exit that does nothing. Hide it, or drop `nextPage`.

### 5. Progress for long-running buttons

If they run async, give these `disabled: busy` plus `refreshInterval: busy ? 2 : 0`, with a nonce in `state` to ignore stale callbacks:

- `apps/sensors/SensorAggregatorDiscreteChild.groovy`: "Run Full Test Suite"
- `apps/utilities/DeviceReplacement.groovy`: "Refresh Scan"

### 6. Status colors (opportunistic)

Inline `color:red/orange/green` appears in HubDiagnostics, WellMonitor, SensorAggregatorDiscreteChild and the integration managers. Switch to PrimeFlex color classes only when already editing those pages.

### Out of scope

- HubDiagnostics and MultiHubInventory keep their real UI in the SPA; their native pages are thin.
- HubDiagnostics paragraphs that rely on `\n` line breaks (the OAuth instructions and version block) must not get `rawHtml`.
