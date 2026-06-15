// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.1.0"
@Field static final Integer SCORING_SCHEMA_VERSION = 1

definition(
    name: "Bathroom Lighting Shadow",
    namespace: "iamtrep",
    author: "pj",
    singleThreaded: true,
    description: "Runs multiple lighting-control policies in parallel against shared sensors, drives a virtual switch per policy, and scores each policy without touching real lights.",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/BathroomLightingShadow.groovy",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Bathroom Lighting Shadow v${CODE_VERSION}", install: true, uninstall: true) {
        section { paragraph "Configure sensors and policies (added in later tasks)." }
    }
}

void installed() { logDebug "installed()"; initialize() }
void updated()   { logDebug "updated()"; unsubscribe(); unschedule(); initialize() }
void uninstalled() { logDebug "uninstalled()" }

void initialize() {
    logDebug "initialize()"
    checkVersion()
}

private void checkVersion() {
    if (state.version != CODE_VERSION) {
        logInfo "version ${state.version} -> ${CODE_VERSION}"
        state.version = CODE_VERSION
    }
    if (state.scoringSchemaVersion != SCORING_SCHEMA_VERSION) {
        logInfo "scoring schema ${state.scoringSchemaVersion} -> ${SCORING_SCHEMA_VERSION} — resetting scores"
        state.scores = [:]
        state.scoringSchemaVersion = SCORING_SCHEMA_VERSION
    }
}

private void logDebug(String msg) { if (debugEnable) log.debug msg }
private void logInfo(String msg)  { log.info msg }
