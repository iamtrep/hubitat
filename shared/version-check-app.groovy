// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: an app notices a code push on its first event after it. initialize() calls
// checkVersion(false) to record the version; event, scheduled and endpoint handlers call
// checkVersion(), which also re-runs updated(). ARCHITECTURE.md, "Version constants and code-push detection".
// An app with migration steps on a new version keeps its own checkVersion.
// See shared/README.md.
// block-version: 1
// requires: CODE_VERSION, logVer, updated

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}
