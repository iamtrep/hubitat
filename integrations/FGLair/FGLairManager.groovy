// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * FGLair Manager — Parent Integration App
 *
 * Authenticates against the Ayla Networks IoT platform (Fujitsu FGLair cloud),
 * discovers Fujitsu indoor units, polls properties on a schedule, and dispatches
 * state to child FujitsuMiniSplit drivers.
 *
 * Protocol facts lifted from ayla-iot-unofficial (MIT) — endpoints, body shapes,
 * integer codes. No source incorporated.
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

definition(
    name: "FGLair Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Fujitsu FGLair mini-split integration (Ayla Networks cloud)",
    menu: "Integrations",
    category: "Climate Control",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/FGLair/FGLairManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String APP_VERSION = "0.1.0"

// Region-specific Ayla endpoints + app credentials, lifted from
// ayla-iot-unofficial/src/ayla_iot_unofficial/const.py and fujitsu_consts.py.
// app_id/app_secret are Fujitsu-FGLair-specific identifiers.
@Field static final Map<String, Map<String, String>> REGION = [
    us: [
        userField : "https://user-field.aylanetworks.com",
        ads       : "https://ads-field.aylanetworks.com",
        appId     : "CJIOSP-id",
        appSecret : "CJIOSP-Vb8MQL_lFiYQ7DKjN0eCFXznKZE"
    ],
    eu: [
        userField : "https://user-field-eu.aylanetworks.com",
        ads       : "https://ads-eu.aylanetworks.com",
        appId     : "FGLair-eu-id",
        appSecret : "FGLair-eu-gpFbVBRoiJ8E3fWljDR_Q74YONQ"
    ]
]

@Field static final String DNI_PREFIX_UNIT = "fglair-unit-"
@Field static final String DRIVER_UNIT = "Fujitsu Mini-Split"

@Field static final long TOKEN_REFRESH_BUFFER_MS = 300_000L
@Field static final int HTTP_TIMEOUT = 15
@Field static final int DEBUG_LOG_TIMEOUT = 1800

preferences {
    page(name: "mainPage")
    page(name: "loginPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "FGLair Manager", install: true, uninstall: true) {
        section("Settings") {
            label title: "Assign a name", required: false
            input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

Map loginPage() {
    dynamicPage(name: "loginPage", title: "FGLair Login", nextPage: "mainPage") {
        section { paragraph "Login UI lands in Task 2." }
    }
}

void installed()   { logDebug "installed"; state.version = APP_VERSION; initialize() }
void updated()     { logDebug "updated"; unsubscribe(); unschedule(); initialize() }
void uninstalled() { logDebug "uninstalled" }
void initialize()  {
    logDebug "initialize"
    if (state.version != APP_VERSION) {
        logWarn "new version: ${APP_VERSION} (was: ${state.version})"
        state.version = APP_VERSION
    }
}

private boolean isAuthenticated() { return state.accessToken != null }

private void logTrace(String msg) { if (settings.traceEnable) log.trace "${app.label} ${msg}" }
private void logDebug(String msg) { if (settings.debugEnable) log.debug "${app.label} ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable)   log.info  "${app.label} ${msg}" }
private void logWarn(String msg)  { log.warn  "${app.label} ${msg}" }
private void logError(String msg) { log.error "${app.label} ${msg}" }
