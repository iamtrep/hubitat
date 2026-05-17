// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field
import java.util.concurrent.atomic.AtomicInteger

definition(
    name: "Field Static Persistence Test",
    namespace: "tests",
    author: "PJ",
    description: "Probe app for verifying @Field static survives Hubitat code pushes (2.5.0.x+).",
    menu: "Apps", // new in platform 2.5.0
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

@Field static AtomicInteger fspCounter = new AtomicInteger(0)

preferences {
    page(name: "mainPage", title: "Field Static Persistence Test", install: true, uninstall: true) {
        section {
            paragraph "Counter (live): ${fspCounter.get()}"
            input name: "btnIncrement", type: "button", title: "Increment"
            input name: "btnReset",     type: "button", title: "Reset"
            input name: "btnReport",    type: "button", title: "Report"
        }
    }
}

void installed()   { log.debug "installed()" }
void updated()     { log.debug "updated()" }
void uninstalled() {}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnIncrement":
            int v = fspCounter.incrementAndGet()
            log.info "FSP_COUNTER=${v} after=increment"
            break
        case "btnReset":
            fspCounter.set(0)
            log.info "FSP_COUNTER=0 after=reset"
            break
        case "btnReport":
            int v = fspCounter.get()
            log.info "FSP_COUNTER=${v} after=report"
            break
    }
}
