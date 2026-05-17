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

def installed() {
    log.debug "installed()"
}

def updated() {
    log.debug "updated()"
}

def uninstalled() {}
