// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: the handler that ends debug and trace logging, run by runIn(1800, "logsOff").
// It checks the version first, like every scheduled handler. The app needs a traceEnable
// input: app.updateSetting stores an undeclared name. See shared/README.md.
// block-version: 1
// requires: checkVersion, logWarn

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}
