// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

definition(
    name: "Sample Application",
    namespace: "Example",
    author: "Hubitat Example",
    description: "A skeleton sample app for HE",
    menu: "Apps", // new in platform 2.5.0
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
}

void installed() {
    log.debug "installed()"
}

void updated() {
    log.debug "updated()"
}

void uninstalled() {}
