// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: the handler that ends debug and trace logging, run by runIn(1800, "logsOff").
// A driver may lack a traceEnable input: device.updateSetting stores nothing for an
// undeclared name. See shared/README.md.
// block-version: 1
// requires: logWarn

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}
