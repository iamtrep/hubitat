/**
 *  Hydro-Québec Peak Period Manager
 *
 *  Manages thermostats and appliances during Hydro-Québec peak periods
 */

definition(
    name: "Hydro-Québec Peak Period Manager",
    namespace: "custom",
    author: "PJ",
    description: "Manages devices during Hydro-Québec peak periods",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String APP_NAME = "Hydro-Québec Peak Period Manager"
@Field static final String APP_VERSION = "0.0.1"

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

@Field static final Map API_TEST_PARAMS = [
    uri: "http://127.0.0.1:8080",
    path: "/local/testPeakPeriods.json",
    contentType: "application/json"
]

@Field static final Integer REFETCH_DELAY_SECONDS = 5
@Field static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ssXXX"

@Field static final Integer DEFAULT_UPDATE_INTERVAL_HOURS = 1
@Field static final Integer DEFAULT_PRE_EVENT_MINUTES = 60

@Field static final Boolean testMode = false

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${APP_VERSION}", install: true, uninstall: true) {
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
    String cronExpression = "0 0 */${settings.updateInterval} * * ?" // Every X hours
    schedule(cronExpression, fetchPeakPeriods)

    logDebug("Scheduled to check every ${settings.updateInterval} hour(s)")
}

void fetchPeakPeriods() {
    logDebug("Fetching peak period data...")

    try {
        asynchttpGet(handlePeakPeriodsResponse, testMode ? API_TEST_PARAMS : API_PARAMS)
    } catch (Exception e) {
        log.error("Error initiating fetch of peak periods: ${e.message}")
    }
}

private void handlePeakPeriodsResponse(hubitat.scheduling.AsyncResponse response, Map data) {
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
            upcomingEventSwitch.on()

            // Schedule to turn off at the start of the earliest event
            Date earliestStart = upcomingStarts.min()
            runOnce(earliestStart, clearUpcomingEvent)
            logDebug("Scheduled to clear upcoming event switch at ${earliestStart}")
        } else {
            logDebug("No upcoming events - turning off upcoming event switch")
            upcomingEventSwitch.off()
        }
    }

    // Handle active period
    if (activePeriodEnd != null) {
        log.info("Currently in peak period until ${activePeriodEnd}")
        eventSwitch.on()

        // Turn off pre-event switch if it's on
        if (preEventSwitch) {
            preEventSwitch.off()
        }

        // Schedule end of current period
        runOnce(activePeriodEnd, endPeakPeriod)
        logDebug("Scheduled end of peak period at ${activePeriodEnd}")
    } else {
        // No active period, ensure event switch is off
        eventSwitch.off()
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
        logDebug("Scheduled start of peak period at ${nextStart}")

        // Schedule end of peak period
        runOnce(nextEnd, endPeakPeriod)
        logDebug("Scheduled end of peak period at ${nextEnd}")

        // Schedule pre-event if configured
        if (preEventSwitch && settings.preEventMinutes > 0) {
            Integer preEventMins = settings.preEventMinutes as Integer
            long preEventMs = nextStart.time - (preEventMins * 60 * 1000)
            Date preEventTime = new Date(preEventMs)

            if (preEventTime.after(now)) {
                runOnce(preEventTime, startPreEvent)
                logDebug("Scheduled pre-event at ${preEventTime}")

                // Schedule to turn off pre-event switch at peak start
                runOnce(nextStart, endPreEvent)
            } else {
                // Pre-event time is in the past but peak hasn't started yet
                // Turn on pre-event switch now
                logDebug("Pre-event time passed, turning on pre-event switch now")
                preEventSwitch.on()

                // Schedule to turn it off at peak start
                runOnce(nextStart, endPreEvent)
            }
        }
    } else {
        log.info("No upcoming peak periods found")
        // Turn off pre-event switch if no upcoming periods
        if (preEventSwitch) {
            preEventSwitch.off()
        }
    }

    // Update app label based on current state
    updateAppLabel()
}

private void startPeakPeriod() {
    log.info("Peak period starting - turning on event switch")
    eventSwitch.on()

    // Turn off pre-event switch
    if (preEventSwitch) {
        preEventSwitch.off()
    }

    // Update label
    updateAppLabel()

    // Fetch fresh data to reschedule
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
}

private void endPeakPeriod() {
    log.info("Peak period ending - turning off event switch")
    eventSwitch.off()

    // Update label
    updateAppLabel()

    // Fetch fresh data to reschedule
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
}

private void startPreEvent() {
    log.info("Pre-event period starting - turning on pre-event switch")
    if (preEventSwitch) {
        preEventSwitch.on()
    }

    // Update label
    updateAppLabel()
}

private void endPreEvent() {
    logDebug("Pre-event period ending - turning off pre-event switch")
    if (preEventSwitch) {
        preEventSwitch.off()
    }

    // Update label
    updateAppLabel()
}

private void clearUpcomingEvent() {
    logDebug("Peak period starting - clearing upcoming event switch")
    if (upcomingEventSwitch) {
        upcomingEventSwitch.off()
    }

    // Update label
    updateAppLabel()

    // Fetch fresh data to reschedule
    runIn(REFETCH_DELAY_SECONDS, fetchPeakPeriods)
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
