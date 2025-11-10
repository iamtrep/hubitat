/**
 *  Hydro-Québec Peak Period Manager
 *
 *  Manages thermostats and appliances during Hydro-Québec peak periods
 */

import groovy.transform.CompileStatic
import groovy.transform.Field
import groovy.json.JsonOutput

@Field static final String APP_NAME = "Hydro-Québec Peak Period Manager"
@Field static final String APP_VERSION = "0.0.1"

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

@Field static final Integer DEFAULT_UPDATE_INTERVAL_HOURS = 1
@Field static final Integer DEFAULT_PRE_EVENT_MINUTES = 60

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

    // Clear any existing schedules
    unschedule()

    // Fetch data immediately
    fetchPeakPeriods()

    // Schedule periodic updates
    String cronExpression = "7 13 */${settings.updateInterval} * * ?" // Every X hours
    schedule(cronExpression, fetchPeakPeriods)

    logDebug("Scheduled to check every ${settings.updateInterval} hour(s)")
}

void fetchPeakPeriods() {
    logDebug("Fetching peak period data...")

    // In test mode, regenerate test file before fetching to keep it current
    if (settings.testMode) {
        generateTestFile()
    }

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
    Date activePeriodStart = null
    Date activePeriodEnd = null
    List<Date> upcomingStarts = []
    List<Date> upcomingEnds = []

    // Clear existing peak-related schedules
    unschedule(startPeakPeriod)
    unschedule(endPeakPeriod)
    unschedule(startPreEvent)
    unschedule(endPreEvent)
    unschedule(clearUpcomingEvent)

    // Clear stored schedule times
    state.scheduledPeakEnd = null
    state.scheduledPeakStart = null
    state.scheduledPreEventStart = null

    records.each { Map record ->
        String dateDebut = record.datedebut
        String dateFin = record.datefin

        if (!dateDebut || !dateFin) {
            logDebug("Skipping record with missing dates")
            return
        }

        try {
            Date startTime = parseIsoDate(dateDebut)
            Date endTime = parseIsoDate(dateFin)

            logDebug("Period: ${startTime} to ${endTime}")

            // Skip if period is entirely in the past
            if (endTime.before(now)) {
                logDebug("Skipping past period ending at ${endTime}")
                return
            }

            // Check if we're currently in a peak period
            if (startTime.before(now) && endTime.after(now)) {
                activePeriodStart = startTime
                activePeriodEnd = endTime
                logDebug("Found active period: ${startTime} to ${endTime}")
            }
            // Check if period is in the future
            else if (startTime.after(now)) {
                upcomingStarts << startTime
                upcomingEnds << endTime
                logDebug("Found upcoming period: ${startTime} to ${endTime}")
            }
        } catch (Exception e) {
            log.error("Error parsing dates for record: ${e.message}")
        }
    }

    // Handle upcoming event switch
    if (upcomingEventSwitch) {
        if (upcomingStarts.size() > 0) {
            logDebug("Upcoming events detected - turning on upcoming event switch")
            if (upcomingEventSwitch.currentValue("switch") == "off") upcomingEventSwitch.on()

            // Schedule to turn off at the start of the earliest event
            Date earliestStart = upcomingStarts.min()
            runOnce(earliestStart, clearUpcomingEvent)
            logDebug("Scheduled to clear upcoming event switch at ${earliestStart}")
        } else {
            logDebug("No upcoming events - turning off upcoming event switch")
            if (upcomingEventSwitch.currentValue("switch") == "on") upcomingEventSwitch.off()
        }
    }

    // Handle active period
    if (activePeriodEnd != null) {
        log.info("Currently in peak period until ${activePeriodEnd}")
        if (eventSwitch.currentValue("switch") == "off") eventSwitch.on()

        // Turn off pre-event switch if it's on
        if (preEventSwitch?.currentValue("switch") == "on") preEventSwitch.off()

        // Schedule end of current period
        runOnce(activePeriodEnd, endPeakPeriod)
        state.scheduledPeakEnd = activePeriodEnd.time
        logDebug("Scheduled end of peak period at ${activePeriodEnd}")
    } else {
        // No active period, ensure event switch is off
        if (eventSwitch.currentValue("switch") == "on") eventSwitch.off()
    }

    // Schedule upcoming periods
    if (upcomingStarts.size() > 0) {
        // Find the earliest upcoming period
        Date nextStart = upcomingStarts.min()
        int index = upcomingStarts.indexOf(nextStart)
        Date nextEnd = upcomingEnds[index]

        log.info("Next peak period: ${nextStart} to ${nextEnd}")

        // Schedule start of peak period
        runOnce(nextStart, startPeakPeriod)
        state.scheduledPeakStart = nextStart.time
        logDebug("Scheduled start of peak period at ${nextStart}")

        // Schedule end of peak period
        runOnce(nextEnd, endPeakPeriod)
        if (state.scheduledPeakEnd == null) {
            state.scheduledPeakEnd = nextEnd.time
        }
        logDebug("Scheduled end of peak period at ${nextEnd}")

        // Schedule pre-event if configured
        if (preEventSwitch && settings.preEventMinutes > 0) {
            Integer preEventMins = settings.preEventMinutes as Integer
            long preEventMs = nextStart.time - (preEventMins * 60 * 1000)
            Date preEventTime = new Date(preEventMs)

            if (preEventTime.after(now)) {
                runOnce(preEventTime, startPreEvent)
                state.scheduledPreEventStart = preEventTime.time
                logDebug("Scheduled pre-event at ${preEventTime}")

                // Schedule to turn off pre-event switch at peak start
                runOnce(nextStart, endPreEvent)
            } else {
                // Pre-event time is in the past but peak hasn't started yet
                // Turn on pre-event switch now
                logDebug("Pre-event time passed, turning on pre-event switch now")
                if (preEventSwitch?.currentValue("switch") == "off") preEventSwitch.on()

                // Schedule to turn it off at peak start
                runOnce(nextStart, endPreEvent)
            }
        }
    } else {
        log.info("No upcoming peak periods found")
        // Turn off pre-event switch if no upcoming periods
        if (preEventSwitch?.currentValue("switch") == "on") preEventSwitch.off()
    }

    // Update app label based on current state
    updateAppLabel()

    // Track last update time
    state.lastUpdate = now.time
}

void startPeakPeriod() {
    log.info("Peak period starting - turning on event switch")
    eventSwitch.on()

    // Turn off pre-event switch
    if (preEventSwitch?.currentValue("switch") == "on") {
        log.info("Peak period starting - turning off pre-event switch")
        preEventSwitch.off()
    }

    // Update label
    updateAppLabel()

    // Fetch fresh data to reschedule
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
}

void endPeakPeriod() {
    log.info("Peak period ending - turning off event switch")
    eventSwitch.off()

    // Update label
    updateAppLabel()

    // Fetch fresh data to reschedule
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
}

void startPreEvent() {
    log.info("Pre-event period starting - turning on pre-event switch")
    if (preEventSwitch?.currentValue("switch") == "off") preEventSwitch.on()

    // Update label
    updateAppLabel()
}

void endPreEvent() {
    logDebug("Pre-event period ending - turning off pre-event switch")
    if (preEventSwitch?.currentValue("switch") == "on") preEventSwitch.off()

    // Update label
    updateAppLabel()
}

void clearUpcomingEvent() {
    logDebug("Peak period starting - clearing upcoming event switch")
    if (upcomingEventSwitch?.currentValue("switch") == "on") upcomingEventSwitch.off()

    // Update label
    updateAppLabel()

    // Fetch fresh data to reschedule
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
}

// private helpers

private void generateTestFile() {
    try {
        Date now = new Date()

        // Create event starting 5 minutes from now, lasting 5 minutes
        Date eventStart = new Date(now.time + (5 * 60 * 1000))
        Date eventEnd = new Date(eventStart.time + (5 * 60 * 1000))

        // Format dates in ISO 8601 format with timezone
        String startStr = eventStart.format("yyyy-MM-dd'T'HH:mm:ssXXX")
        String endStr = eventEnd.format("yyyy-MM-dd'T'HH:mm:ssXXX")

        // Create test data structure matching Hydro-Québec API format
        Map testData = [
            total_count: 1,
            results: [
                [
                    datedebut: startStr,
                    datefin: endStr,
                    offre: "CPC-D"
                ]
            ]
        ]

        // Convert to JSON
        String jsonContent = JsonOutput.toJson(testData)

        // Upload to hub
        uploadHubFile(API_TEST_FILE_NAME, jsonContent.bytes)

        logDebug("Generated test file: event from ${startStr} to ${endStr}")
    } catch (Exception e) {
        log.error("Error generating test file: ${e}")
    }
}

private String getStatusText() {
    StringBuilder status = new StringBuilder()

    // Check event switch
    if (eventSwitch?.currentValue("switch") == "on") {
        status.append("<b style='color:red'>⚡ PEAK EVENT IN PROGRESS</b><br/>")
        if (state.scheduledPeakEnd) {
            status.append("Event ends at: ${new Date(state.scheduledPeakEnd as Long).format('yyyy-MM-dd HH:mm:ss')}<br/>")
        }
    } else if (preEventSwitch?.currentValue("switch") == "on") {
        status.append("<b style='color:orange'>⏰ Pre-Event Warning Active</b><br/>")
        if (state.scheduledPeakStart) {
            status.append("Peak event starts at: ${new Date(state.scheduledPeakStart as Long).format('yyyy-MM-dd HH:mm:ss')}<br/>")
        }
    } else if (upcomingEventSwitch?.currentValue("switch") == "on") {
        status.append("<b style='color:blue'>📅 Event Scheduled</b><br/>")
        Long nextEventTime = null
        if (state.scheduledPreEventStart) {
            nextEventTime = state.scheduledPreEventStart as Long
        }
        if (state.scheduledPeakStart) {
            Long peakStart = state.scheduledPeakStart as Long
            if (nextEventTime == null || peakStart < nextEventTime) {
                nextEventTime = peakStart
            }
        }
        if (nextEventTime) {
            status.append("Next event activity at: ${new Date(nextEventTime).format('yyyy-MM-dd HH:mm:ss')}<br/>")
        }
    } else {
        status.append("<b style='color:green'>✓ No Events Scheduled</b><br/>")
    }

    // Add last update time
    if (state.lastUpdate) {
        status.append("<br/><small>Last checked: ${new Date(state.lastUpdate as Long).format('yyyy-MM-dd HH:mm:ss')}</small>")
    }

    return status.toString()
}

private void updateAppLabel() {
    String label = APP_NAME

    // Determine current state and add colored status
    if (eventSwitch?.currentValue("switch") == "on") {
        label = "${label} <span style='color:red'>⚡ PEAK EVENT IN PROGRESS</span>"
    } else if (preEventSwitch?.currentValue("switch") == "on") {
        label = "${label} <span style='color:orange'>⏰ Pre-Event Warning</span>"
    } else if (upcomingEventSwitch?.currentValue("switch") == "on") {
        label = "${label} <span style='color:blue'>📅 Event Scheduled</span>"
    } else {
        label = "${label} <span style='color:green'>✓ No Events</span>"
    }

    app.updateLabel(label)
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
