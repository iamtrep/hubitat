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

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Hydro-Québec Peak Period Manager", install: true, uninstall: true) {
        section("Peak Period Switches") {
            input "eventSwitch", "capability.switch", title: "Event Switch (ON during peak period)", required: true
            input "preEventSwitch", "capability.switch", title: "Pre-Event Switch (ON before peak period)", required: false
        }

        section("Pre-Event Warning") {
            input "preEventMinutes", "number", title: "Minutes before peak to turn on pre-event switch", defaultValue: 60, required: true
        }

        section("Update Schedule") {
            input "updateInterval", "number", title: "Check for updates every X hours", defaultValue: 1, required: true, range: "1..24"
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

    Map params = [
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

    try {
        asynchttpGet(handlePeakPeriodsResponse, params)
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

void processPeakPeriods(Map data) {
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
}

void startPeakPeriod() {
    log.info("Peak period starting - turning on event switch")
    eventSwitch.on()

    // Turn off pre-event switch
    if (preEventSwitch) {
        preEventSwitch.off()
    }

    // Fetch fresh data to reschedule
    runIn(5, fetchPeakPeriods)
}

void endPeakPeriod() {
    log.info("Peak period ending - turning off event switch")
    eventSwitch.off()

    // Fetch fresh data to reschedule
    runIn(5, fetchPeakPeriods)
}

void startPreEvent() {
    log.info("Pre-event period starting - turning on pre-event switch")
    if (preEventSwitch) {
        preEventSwitch.on()
    }
}

void endPreEvent() {
    logDebug("Pre-event period ending - turning off pre-event switch")
    if (preEventSwitch) {
        preEventSwitch.off()
    }
}

Date parseIsoDate(String isoTimestamp) {
    // Parse ISO 8601 format: "2025-01-06T11:00:00+00:00"
    return Date.parse("yyyy-MM-dd'T'HH:mm:ssXXX", isoTimestamp)
}

void logDebug(String msg) {
    if (settings.enableDebug) {
        log.debug(msg)
    }
}
