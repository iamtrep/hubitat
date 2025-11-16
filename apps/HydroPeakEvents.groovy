/*
 MIT License

 Copyright (c) 2025 pj

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.

 Hydro-Québec Peak Period Manager

 A simple state machine-based app to help Manage thermostats and appliances during Hydro-Québec peak periods

 */

import groovy.transform.CompileStatic
import groovy.transform.Field
import groovy.json.JsonOutput
import java.text.SimpleDateFormat

@Field static final String APP_NAME = "Hydro-Québec Peak Period Manager"
@Field static final String APP_VERSION = "0.2.0"

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Manages devices during Hydro-Québec peak periods",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HydroPeakEvents.groovy"
)

@Field static final Map API_PARAMS = [
    uri: "https://donnees.hydroquebec.com",
    path: "/api/explore/v2.1/catalog/datasets/evenements-pointe/records",
    query: [
        order_by: "datedebut",
        limit: 100,
        timezone: "America/New_York",
        refine: "offre:\"CPC-D\""
    ],
    contentType: "application/json"
]

@Field static final String API_TEST_FILE_NAME = "testPeakPeriods.json"

@Field static final Map API_TEST_PARAMS = [
    uri: "http://127.0.0.1:8080",
    path: "/local/testPeakPeriods.json",
    contentType: "application/json"
]

@Field static final Integer REFETCH_DELAY_SECONDS = 5
@Field static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ssXXX"
@Field static final String DATE_FORMAT_HUBITAT = "yyyy-MM-dd'T'HH:mm:ss.sssXX"
@Field static final String DATE_FORMAT_DISPLAY = 'yyyy-MM-dd HH:mm:ss'

@Field static final Integer DEFAULT_UPDATE_INTERVAL_HOURS = 1
@Field static final Integer DEFAULT_PRE_EVENT_MINUTES = 60

// State machine states
@Field static final String STATE_NO_EVENTS = "NO_EVENTS"
@Field static final String STATE_EVENT_SCHEDULED = "EVENT_SCHEDULED"
@Field static final String STATE_PRE_EVENT = "PRE_EVENT"
@Field static final String STATE_EVENT_ACTIVE = "EVENT_ACTIVE"

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${APP_VERSION}", install: true, uninstall: true) {
        section("Current Status") {
            paragraph getStatusText()
        }

        section("Peak Period Switches") {
            input "eventSwitch", "capability.switch", title: "Event Switch (ON during peak period)", required: true
            input "preEventSwitch", "capability.switch", title: "Pre-Event Switch (ON before peak period)", required: false
            input "upcomingEventSwitch", "capability.switch", title: "Upcoming Event Switch (ON when any event is scheduled)", required: false
        }

        section("Pre-Event Warning") {
            input "preEventMinutes", "number", title: "Minutes before peak to turn on pre-event switch", defaultValue: DEFAULT_PRE_EVENT_MINUTES, required: true
        }

        section("Update Schedule") {
            input "updateInterval", "number", title: "Check for updates every X hours", defaultValue: DEFAULT_UPDATE_INTERVAL_HOURS, required: true, range: "1..24"
        }

        section("Hub Variables") {
            input "eventStartVariableName", "enum", title: "Hub variable for next event start time (dateDebut)",
                options: getGlobalVarsByType("datetime").keySet().sort(), required: false
            input "eventEndVariableName", "enum", title: "Hub variable for next event end time (dateFin)",
                options: getGlobalVarsByType("datetime").keySet().sort(), required: false
            paragraph "<small>Variables will be set to datetime strings in Hubitat format</small>"
            paragraph "<small>Variables will be cleared (set to empty string) when no events are scheduled</small>"
            paragraph "<small>Note: Create datetime hub variables in Settings → Hub Variables if none appear above</small>"
        }

        section("Debug") {
            input "testMode", "bool", title: "Enable test mode (uses local test file)", defaultValue: false
            paragraph "<small>When enabled, creates a test event 5 minutes from now</small>"

            input "enableDebug", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

void installed() {
    logDebug("Installed with settings: ${settings}")
    initialize()
}

void updated() {
    logDebug("Updated with settings: ${settings}")
    unschedule()
    initialize()
}

void initialize() {
    logDebug("Initializing...")
    unschedule()

    // Initialize state if needed
    if (!state.currentState) {
        state.currentState = STATE_NO_EVENTS
    }

    // Register hub variables as in use
    registerHubVariables()

    // Generate test file once if in test mode
    if (settings.testMode) {
        generateTestFile()
    }

    // Fetch data immediately
    fetchPeakPeriods()

    // Schedule periodic updates
    String cronExpression = "7 13 */${settings.updateInterval} * * ?"
    schedule(cronExpression, fetchPeakPeriods)

    logDebug("Scheduled to check every ${settings.updateInterval} hour(s)")
}

void fetchPeakPeriods() {
    logDebug("Fetching peak period data...")

    try {
        asynchttpGet(handlePeakPeriodsResponse, settings.testMode ? API_TEST_PARAMS : API_PARAMS)
    } catch (Exception e) {
        log.error("Error initiating fetch of peak periods: ${e.message}")
    }
}

void handlePeakPeriodsResponse(hubitat.scheduling.AsyncResponse response, Map data) {
    try {
        if (response.status == 200) {
            logDebug("Successfully fetched data")
            String jsonText = response.data
            Map responseData = parseJson(jsonText)
            processPeakPeriods(responseData)
        } else {
            log.error("Failed to fetch data: HTTP ${response.status}")
        }
    } catch (Exception e) {
        log.error("Error handling peak periods response: ${e.message}")
    }
}

private void processPeakPeriods(Map data) {
    List records = data.results ?: []
    logDebug("Processing ${records.size()} peak period records")

    Date now = new Date()
    Date nextEventStart = null
    Date nextEventEnd = null

    // Find the next relevant event (active or upcoming)
    records.each { Map record ->
        String dateDebut = record.datedebut
        String dateFin = record.datefin

        if (!dateDebut || !dateFin) {
            return
        }

        try {
            Date startTime = parseIsoDate(dateDebut)
            Date endTime = parseIsoDate(dateFin)

            // Skip if entirely in the past
            if (endTime.before(now)) {
                return
            }

            // This is the next relevant event
            if (nextEventStart == null) {
                nextEventStart = startTime
                nextEventEnd = endTime
            }
        } catch (Exception e) {
            log.error("Error parsing dates: ${e.message}")
        }
    }

    // Update hub variables with next event times
    updateHubVariables(nextEventStart, nextEventEnd)

    // Determine what state we should be in
    String targetState = determineTargetState(now, nextEventStart, nextEventEnd)

    // Transition to target state if needed
    if (targetState != state.currentState) {
        transitionToState(targetState, nextEventStart, nextEventEnd)
    } else {
        logDebug("Already in correct state: ${targetState}")
    }

    state.lastUpdate = now.time
    updateAppLabel()
}

private void updateHubVariables(Date eventStart, Date eventEnd) {
    try {
        if (settings.eventStartVariableName) {
            if (eventStart) {
                String formattedDate = formatDateForHubitat(eventStart)
                setGlobalVar(settings.eventStartVariableName, formattedDate)
                logDebug("Set hub variable '${settings.eventStartVariableName}' to: ${formattedDate}")
            } else {
                setGlobalVar(settings.eventStartVariableName, "")
                logDebug("Cleared hub variable '${settings.eventStartVariableName}'")
            }
        }

        if (settings.eventEndVariableName) {
            if (eventEnd) {
                String formattedDate = formatDateForHubitat(eventEnd)
                setGlobalVar(settings.eventEndVariableName, formattedDate)
                logDebug("Set hub variable '${settings.eventEndVariableName}' to: ${formattedDate}")
            } else {
                setGlobalVar(settings.eventEndVariableName, "")
                logDebug("Cleared hub variable '${settings.eventEndVariableName}'")
            }
        }
    } catch (Exception e) {
        log.error("Error updating hub variables: ${e.message}")
    }
}

@CompileStatic
private String formatDateForHubitat(Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_HUBITAT)
    sdf.setTimeZone(TimeZone.getTimeZone("America/Montreal"))
    return sdf.format(date)
}

private String determineTargetState(Date now, Date eventStart, Date eventEnd) {
    if (eventStart == null) {
        return STATE_NO_EVENTS
    }

    // Check if event is currently active
    if (eventStart.before(now) && eventEnd.after(now)) {
        return STATE_EVENT_ACTIVE
    }

    // Check if we're in pre-event period
    if (preEventSwitch && settings.preEventMinutes > 0) {
        Integer preEventMins = settings.preEventMinutes as Integer
        long preEventMs = eventStart.time - (preEventMins * 60 * 1000)
        Date preEventTime = new Date(preEventMs)

        if (preEventTime.before(now) && eventStart.after(now)) {
            return STATE_PRE_EVENT
        }
    }

    // Event is in the future
    if (eventStart.after(now)) {
        return STATE_EVENT_SCHEDULED
    }

    return STATE_NO_EVENTS
}

private void transitionToState(String newState, Date eventStart, Date eventEnd) {
    log.info("State transition: ${state.currentState} -> ${newState}")

    // Exit current state
    exitState(state.currentState)

    // Enter new state
    enterState(newState, eventStart, eventEnd)

    state.currentState = newState
}

private void exitState(String oldState) {
    logDebug("Exiting state: ${oldState}")

    // Clear all scheduled transitions
    unschedule(transitionToPreEvent)
    unschedule(transitionToEventActive)
    unschedule(transitionToNoEvents)

    // Clear state data
    state.remove('eventStart')
    state.remove('eventEnd')
    state.remove('preEventStart')
}

private void enterState(String newState, Date eventStart, Date eventEnd) {
    logDebug("Entering state: ${newState}")

    switch (newState) {
        case STATE_NO_EVENTS:
            enterNoEventsState()
            break

        case STATE_EVENT_SCHEDULED:
            enterEventScheduledState(eventStart, eventEnd)
            break

        case STATE_PRE_EVENT:
            enterPreEventState(eventStart, eventEnd)
            break

        case STATE_EVENT_ACTIVE:
            enterEventActiveState(eventEnd)
            break
    }
}

private void enterNoEventsState() {
    log.info("STATE: No events scheduled")

    // Turn off all switches
    if (eventSwitch?.currentValue("switch") == "on") eventSwitch.off()
    if (preEventSwitch?.currentValue("switch") == "on") preEventSwitch.off()
    if (upcomingEventSwitch?.currentValue("switch") == "on") upcomingEventSwitch.off()
}

private void enterEventScheduledState(Date eventStart, Date eventEnd) {
    log.info("STATE: Event scheduled from ${eventStart} to ${eventEnd}")

    // Store event times
    state.eventStart = eventStart.time
    state.eventEnd = eventEnd.time

    // Set switches
    if (preEventSwitch?.currentValue("switch") == "on") preEventSwitch.off()
    if (eventSwitch?.currentValue("switch") == "on") eventSwitch.off()
    if (upcomingEventSwitch?.currentValue("switch") == "off") upcomingEventSwitch.on()

    // Schedule transition to pre-event or event active
    if (preEventSwitch && settings.preEventMinutes > 0) {
        Integer preEventMins = settings.preEventMinutes as Integer
        Date preEventTime = new Date(eventStart.time - (preEventMins * 60 * 1000))
        state.preEventStart = preEventTime.time

        runOnce(preEventTime, transitionToPreEvent)
        logDebug("Scheduled transition to PRE_EVENT at ${preEventTime}")
    } else {
        runOnce(eventStart, transitionToEventActive)
        logDebug("Scheduled transition to EVENT_ACTIVE at ${eventStart}")
    }
}

private void enterPreEventState(Date eventStart, Date eventEnd) {
    log.info("STATE: Pre-event period, event starts at ${eventStart}")

    // Store event times
    state.eventStart = eventStart.time
    state.eventEnd = eventEnd.time

    // Set switches
    if (eventSwitch?.currentValue("switch") == "on") eventSwitch.off()
    if (upcomingEventSwitch?.currentValue("switch") == "on") upcomingEventSwitch.off()
    if (preEventSwitch?.currentValue("switch") == "off") preEventSwitch.on()

    // Schedule transition to event active
    runOnce(eventStart, transitionToEventActive)
    logDebug("Scheduled transition to EVENT_ACTIVE at ${eventStart}")
}

private void enterEventActiveState(Date eventEnd) {
    log.info("STATE: Event active until ${eventEnd}")

    // Store event end time
    state.eventEnd = eventEnd.time

    // Set switches
    if (upcomingEventSwitch?.currentValue("switch") == "on") upcomingEventSwitch.off()
    if (preEventSwitch?.currentValue("switch") == "on") preEventSwitch.off()
    if (eventSwitch?.currentValue("switch") == "off") eventSwitch.on()

    // Schedule transition back to no events (or refetch will find next event)
    runOnce(eventEnd, transitionToNoEvents)
    logDebug("Scheduled transition to NO_EVENTS at ${eventEnd}")
}

// State transition handlers (called by scheduler)
void transitionToPreEvent() {
    log.info("Transition handler: Moving to PRE_EVENT")

    Date eventStart = state.eventStart ? new Date(state.eventStart as Long) : null
    Date eventEnd = state.eventEnd ? new Date(state.eventEnd as Long) : null

    if (eventStart && eventEnd) {
        transitionToState(STATE_PRE_EVENT, eventStart, eventEnd)
        updateAppLabel()
    } else {
        log.warn("Missing event data during transition to PRE_EVENT, refetching")
        fetchPeakPeriods()
    }
}

void transitionToEventActive() {
    log.info("Transition handler: Moving to EVENT_ACTIVE")

    Date eventEnd = state.eventEnd ? new Date(state.eventEnd as Long) : null

    if (eventEnd) {
        transitionToState(STATE_EVENT_ACTIVE, null, eventEnd)
        updateAppLabel()
    } else {
        log.warn("Missing event data during transition to EVENT_ACTIVE, refetching")
        fetchPeakPeriods()
    }
}

void transitionToNoEvents() {
    log.info("Transition handler: Moving to NO_EVENTS")
    transitionToState(STATE_NO_EVENTS, null, null)
    updateAppLabel()

    // Refetch to see if there are more events
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
}

// Helper methods

private void registerHubVariables() {
    // Remove all previously registered variables
    removeAllInUseGlobalVar()

    // Register currently configured variables
    List<String> varsInUse = []
    if (settings.eventStartVariableName) {
        varsInUse.add(settings.eventStartVariableName)
    }
    if (settings.eventEndVariableName) {
        varsInUse.add(settings.eventEndVariableName)
    }

    if (varsInUse) {
        addInUseGlobalVar(varsInUse)
        logDebug("Registered hub variables as in use: ${varsInUse}")
    }
}

void renameVariable(String oldName, String newName) {
    logDebug("Hub variable renamed: ${oldName} -> ${newName}")

    // Update settings if one of our variables was renamed
    if (settings.eventStartVariableName == oldName) {
        app.updateSetting("eventStartVariableName", newName)
        log.info("Updated eventStartVariableName setting to: ${newName}")
    }

    if (settings.eventEndVariableName == oldName) {
        app.updateSetting("eventEndVariableName", newName)
        log.info("Updated eventEndVariableName setting to: ${newName}")
    }
}

private void generateTestFile() {
    try {
        Date now = new Date()

        Date eventStart = new Date(now.time + (5 * 60 * 1000))
        Date eventEnd = new Date(eventStart.time + (5 * 60 * 1000))

        Date nextEventStart = new Date(eventEnd.time + (24 * 60 * 60 * 1000))
        Date nextEventEnd = new Date(nextEventStart.time + (5 * 60 * 1000))

        Map testData = [
            total_count: 2,
            results: [
                [
                    datedebut: eventStart.format(DATE_FORMAT_ISO8601),
                    datefin: eventEnd.format(DATE_FORMAT_ISO8601),
                    offre: "CPC-D"
                ],
                [
                    datedebut: nextEventStart.format(DATE_FORMAT_ISO8601),
                    datefin: nextEventEnd.format(DATE_FORMAT_ISO8601),
                    offre: "CPC-D"
                ]
            ]
        ]

        String jsonContent = JsonOutput.toJson(testData)
        uploadHubFile(API_TEST_FILE_NAME, jsonContent.bytes)

        logDebug("Generated test file")
    } catch (Exception e) {
        log.error("Error generating test file: ${e}")
    }
}

private String getStatusText() {
    StringBuilder status = new StringBuilder()

    String currentState = state.currentState ?: STATE_NO_EVENTS

    switch (currentState) {
        case STATE_EVENT_ACTIVE:
            status.append("<b style='color:red'>⚡ PEAK EVENT IN PROGRESS</b><br/>")
            if (state.eventEnd) {
                status.append("Event ends at: ${new Date(state.eventEnd as Long).format(DATE_FORMAT_DISPLAY)}<br/>")
            }
            break

        case STATE_PRE_EVENT:
            status.append("<b style='color:orange'>⏰ Pre-Event Warning Active</b><br/>")
            if (state.eventStart) {
                status.append("Peak event starts at: ${new Date(state.eventStart as Long).format(DATE_FORMAT_DISPLAY)}<br/>")
            }
            break

        case STATE_EVENT_SCHEDULED:
            status.append("<b style='color:blue'>📅 Event Scheduled</b><br/>")
            if (state.preEventStart) {
                status.append("Pre-event warning at: ${new Date(state.preEventStart as Long).format(DATE_FORMAT_DISPLAY)}<br/>")
            } else if (state.eventStart) {
                status.append("Event starts at: ${new Date(state.eventStart as Long).format(DATE_FORMAT_DISPLAY)}<br/>")
            }
            break

        case STATE_NO_EVENTS:
        default:
            status.append("<b style='color:green'>✓ No Events Scheduled</b><br/>")
            break
    }

    if (state.lastUpdate) {
        status.append("<br/><small>Last checked: ${new Date(state.lastUpdate as Long).format(DATE_FORMAT_DISPLAY)}</small>")
    }

    return status.toString()
}

private void updateAppLabel() {
	app.updateLabel(getAppLabelFromState(state.currentState))
}

@CompileStatic
private String getAppLabelFromState(String currentState) {
    String label = APP_NAME

    switch (currentState) {
        case STATE_EVENT_ACTIVE:
            label = "${label} <span style='color:red'>⚡ PEAK EVENT IN PROGRESS</span>"
            break
        case STATE_PRE_EVENT:
            label = "${label} <span style='color:orange'>⏰ Pre-Event Warning</span>"
            break
        case STATE_EVENT_SCHEDULED:
            label = "${label} <span style='color:blue'>📅 Event Scheduled</span>"
            break
        case STATE_NO_EVENTS:
        default:
            label = "${label} <span style='color:green'>✓ No Events</span>"
            break
    }

    return label
}

@CompileStatic
private Date parseIsoDate(String isoTimestamp) {
    return Date.parse(DATE_FORMAT_ISO8601, isoTimestamp)
}

private void logDebug(String msg) {
    if (settings.enableDebug) {
        log.debug(msg)
    }
}
