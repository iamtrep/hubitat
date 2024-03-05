definition(
    name: "Rule Tracker",
    namespace: "Example",
    author: "iamtrep",
    description: "Track RM rule subscriptions and schedules",
    category: "debugging",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage", title: "App parameters", install: true, uninstall: true) {
        section("Apps to track") {
            def rules = RMUtils.getRuleList("5.0")
            input name: "appIdsToTrack", type: "enum", options: rules, required: true, multiple: true, submitOnChange: true
        }
        section("Operational parameters") {
            input name: "scheduleCheck", type: "number", title: "Run check every N minutes", defaultValue: 5, required: true, submitOnChange: true
            input name: "debugLogs", type: "bool", title: "Enable debug logging?", defaultValue: false, required: true, submitOnChange: true
            input name: "traceLogs", type: "bool", title: "Enable trace logging?", defaultValue: false, required: true, submitOnChange: true
            input name: "refreshButton", type: "button", title: "Refresh now"
            input name: "stopButton", type: "button", title: "Remove schedule"
            input name: "testButton", type: "button", title: "Run App Tests"
        }
    }
}

def appButtonHandler(btn) {
    switch(btn) {
        case "refreshButton":
            appIdsToTrack.each {
                if (debugLogs) log.debug("checking app $it status")
                readAppStatus(it.toInteger())
            }
            break

        case "testButton":
            testRuleTracker()
            break

        case "stopButton":
        default:
            unschedule()
            break
      }
}

import hubitat.helper.RMUtils
import groovy.util.XmlSlurper
import groovy.transform.Field

def installed() {
    if (debugLogs) log.debug "installed()"
}

@Field static final String app_status_url = "http://127.0.0.1:8080/installedapp/status"

def updated() {
    if (debugLogs) log.debug "updated()"

    // TODO
}

def testRuleTracker() {

    // For now, hardcode apps to track
    def testAppIdsToTrack = [167,412]

    def fileToParse = 'response2.html'
    if (debugLogs) log.debug("checking file $fileToParse")
    //readAppStatusFromFile(fileToParse)

    testAppIdsToTrack.each {
        if (debugLogs) log.debug("checking app $it status")
        readAppStatus(it)
    }
}

def uninstalled() {}

@Field static final String settingsSectionTag = '<h5>Settings</h5>'
@Field static final String eventsSectionTag = '<h5>Event Subscriptions</h5>'
@Field static final String appStateSectionTag = '<h5>Application State</h5>'
@Field static final String scheduledJobsSectionTag = '<h5>Scheduled Jobs</h5>'

@Field static final String settingsTableId = 'settings-table'
@Field static final String eventsTableId = 'event-table'
@Field static final String appStateTableId = null
@Field static final String scheduledJobsTableId = null

String extractTable(String html, int startIndex = 1, String tableId = null)
{
    def startTag = '<table class="'
    if (tableId != null) {
        startTag = '<table id="' + tableId + '"'
    }
    if (traceLogs) log.trace groovy.xml.XmlUtil.escapeXml("looking for table ($startTag) at startIndex=$startIndex")

    def endTag = '</table>'

    def tableIndex = html.indexOf(startTag, startIndex)
    if (tableIndex == -1) {
        // table not found - nothing subscribed, scheduled, or other.
        return null
    }

    def endIndex = html.indexOf(endTag, tableIndex) + endTag.length()
    if (endIndex == -1) {
        log.warn("table end marker not found")
        return null
    }

    // Extract the table from the HTML string
    def String tableHtml = html.substring(tableIndex, endIndex)
    if (traceLogs) log.trace("tableHtml=" + tableHtml)

    return tableHtml
}

def extractScheduledJobs(String html)
{
    //int settingsStartIndex = html.indexOf(settingsSectionTag)
    //if (settingsStartIndex == -1) log.warn("could not find settings section")
    //int eventsStartIndex = html.indexOf(eventsSectionTag, settingsStartIndex)
    //if (eventsStartIndex == -1) log.warn("could not find event subscriptions section")
    //int appStateStartIndex = html.indexOf(appStateSectionTag, eventsStartIndex)
    //if (appStateStartIndex == -1) log.warn("could not find application state section")
    //int scheduledJobStartIndex = html.indexOf(scheduledJobsSectionTag,appStateStartIndex)
    //if (scheduledJobStartIndex == -1) log.warn("could not find scheduled job section")

    def int scheduledJobStartIndex = html.indexOf(scheduledJobsSectionTag)
    if (scheduledJobStartIndex == -1) log.warn("could not find scheduled job section")

    // it's the last table in the file
    def String tableHtml = extractTable(html, scheduledJobStartIndex)

    if (tableHtml) {
        return parseTable(tableHtml)
    }

    return []
}


def extractEventSubscriptions(String html)
{
    //int settingsStartIndex = html.indexOf(settingsSectionTag)
    //if (settingsStartIndex == -1) log.warn("could not find settings section")
    //int eventsStartIndex = html.indexOf(eventsSectionTag, settingsStartIndex)
    //if (eventsStartIndex == -1) log.warn("could not find event subscriptions section")
    //int appStateStartIndex = html.indexOf(appStateSectionTag, eventsStartIndex)
    //if (appStateStartIndex == -1) log.warn("could not find application state section")
    //int scheduledJobStartIndex = html.indexOf(scheduledJobsSectionTag)
    //if (scheduledJobStartIndex == -1) log.warn("could not find scheduled job section")

    def int eventsStartIndex = html.indexOf(eventsSectionTag)
    if (eventsStartIndex == -1) log.warn("could not find event subscriptions section")

    // it's the last table in the file
    def String tableHtml = extractTable(html, eventsStartIndex, eventsTableId)

    if (tableHtml) {
        return parseTable(tableHtml)
    }

    return []
}



def parseTable(String tableHtml)
{
    //def parser = new XmlSlurper(false,false,true)
    def parser = new XmlSlurper()
    def parsedHtml = parser.parseText(tableHtml)

    def tableData = parsedHtml.tbody.tr.collect {
        tr -> tr.td.collect {
            td -> td.text()
        }
    }

    if (traceLogs) log.trace("table data: $tableData")
    return tableData
}

def readAppStatus(int appId) {

    def params = [
        uri: "$app_status_url/$appId",
        contentType: "text/html",
        textParser: true
    ]

    try {
        httpGet(
            params, { resp ->
                if (resp.success) {
                    def rawHtml = resp.data.text

                    def tableData = extractEventSubscriptions(rawHtml)
                    log.info("App $appId event subscriptions (${tableData.size()}) : $tableData")

                    tableData = extractScheduledJobs(rawHtml)
                    log.info("App $appId scheduled jobs (${tableData.size()}) : $tableData")
                }
            }
        )
    } catch (Exception e) {
        log.warn "Call failed: $e"
    }
}

def readAppStatusFromFile(String fileName) {
    def contents = downloadHubFile(fileName)
    def html = new String(contents)

    def tableData = extractEventSubscriptions(html)
    log.info("$fileName event subscriptions (${tableData.size()}) : $tableData")

    tableData = extractScheduledJobs(html)
    log.info("$fileName scheduled jobs (${tableData.size()}) : $tableData")
}
