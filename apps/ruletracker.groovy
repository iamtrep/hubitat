definition(
    name: "Rule Tracker",
    namespace: "Example",
    author: "iamtrep",
    description: "Track RM rule subscriptions and schedules",
    category: "debugging",
    iconUrl: "",
    iconX2Url: "")

import hubitat.helper.RMUtils
import groovy.util.XmlSlurper
import groovy.transform.Field

@Field static final String app_status_url = "http://127.0.0.1:8080/installedapp/status"


preferences {
    page(name: "mainPage", title: "App parameters", install: true, uninstall: true) {
        section("Rule settings to track") {
            def rules = RMUtils.getRuleList("5.0")
            input name: "appIdsToTrack", type: "enum", title: "Select rules to monitor", options: rules, required: true, multiple: true, submitOnChange: true
        }

        section(hideable:true, hidden:true, "Operational parameters") {
            def scheduleOptions = [[0:"disabled"],[1:"1 min"],[5:"5 min"], [15:"15 min"], [30:"30 min"], [60: "1 hour"], [180:"3 hours"]]
            input name: "scheduledCheck", type: "enum", title: "Rule check frequency", defaultValue: 5, options: scheduleOptions, required: true, submitOnChange: true
            input name: "debugLogs", type: "bool", title: "Enable debug logging?", defaultValue: false, required: true, submitOnChange: true
            input name: "traceLogs", type: "bool", title: "Enable trace logging?", defaultValue: false, required: true, submitOnChange: true
            input("hubSecurity", "bool", title: "Hub Security Enabled", defaultValue: false, submitOnChange: true, width:4)
            if (hubSecurity) {
                input("hubSecurityUsername", "string", title: "Hub Security Username", required: false, width:4)
                input("hubSecurityPassword", "password", title: "Hub Security Password", required: false, width:4)
            }
        }

        section("Actions") {
            input name: "refreshButton", type: "button", title: "Run check now"
            input name: "stopButton", type: "button", title: "Remove schedule"
            input name: "testButton", type: "button", title: "Run App Tests"
        }

        section(hideable:true, "Rule Status Check - Results") {
            paragraph(state.checkResults)
        }

        section(hideable:true, hidden:true, "App Name") {
            input "thisName", "text", title: "Name this Rule Tracker", submitOnChange: true
            if(thisName) app.updateLabel("$thisName")
        }
    }
}


def appButtonHandler(btn) {
    switch(btn) {
        case "refreshButton":
            runRuleTracker()
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


def installed() {
    if (debugLogs) log.debug "installed()"
}


def updated() {
    if (debugLogs) log.debug "updated()"

    updateScheduledCheck()
}


def uninstalled() {
    if (debugLogs) log.debug "uninstalled()"
}


// The app status page has specific headers for each table
@Field static final String settingsSectionTag = '<h5>Settings</h5>'
@Field static final String eventsSectionTag = '<h5>Event Subscriptions</h5>'
@Field static final String appStateSectionTag = '<h5>Application State</h5>'
@Field static final String scheduledJobsSectionTag = '<h5>Scheduled Jobs</h5>'

// Two of four tables have a table ID
@Field static final String settingsTableId = 'settings-table'
@Field static final String eventsTableId = 'event-table'
@Field static final String appStateTableId = null
@Field static final String scheduledJobsTableId = null


def runRuleTracker() {
    state.checkResults = ""

    appIdsToTrack.each {
        if (debugLogs) log.debug("checking app $it status")
        readAppStatus(it.toInteger())
    }
}


def testRuleTracker() {
    def fileToParse = 'sampleAppStatus.html'
    if (debugLogs) log.debug("checking file $fileToParse")
    readAppStatusFromFile(fileToParse)
}


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


def extractScheduledJobs(String html, int startIndex = 1)
{
    //int settingsStartIndex = html.indexOf(settingsSectionTag)
    //if (settingsStartIndex == -1) log.warn("could not find settings section")
    //int eventsStartIndex = html.indexOf(eventsSectionTag, settingsStartIndex)
    //if (eventsStartIndex == -1) log.warn("could not find event subscriptions section")
    //int appStateStartIndex = html.indexOf(appStateSectionTag, eventsStartIndex)
    //if (appStateStartIndex == -1) log.warn("could not find application state section")
    //int scheduledJobStartIndex = html.indexOf(scheduledJobsSectionTag,appStateStartIndex)
    //if (scheduledJobStartIndex == -1) log.warn("could not find scheduled job section")

    def int scheduledJobStartIndex = html.indexOf(scheduledJobsSectionTag, startIndex)
    if (scheduledJobStartIndex == -1) log.warn("could not find scheduled job section")

    // it's the last table in the file
    def String tableHtml = extractTable(html, scheduledJobStartIndex)

    if (tableHtml) {
        return parseTable(tableHtml)
    }

    return []
}


def extractEventSubscriptions(String html, int startIndex = 1)
{
    //int settingsStartIndex = html.indexOf(settingsSectionTag)
    //if (settingsStartIndex == -1) log.warn("could not find settings section")
    //int eventsStartIndex = html.indexOf(eventsSectionTag, settingsStartIndex)
    //if (eventsStartIndex == -1) log.warn("could not find event subscriptions section")
    //int appStateStartIndex = html.indexOf(appStateSectionTag, eventsStartIndex)
    //if (appStateStartIndex == -1) log.warn("could not find application state section")
    //int scheduledJobStartIndex = html.indexOf(scheduledJobsSectionTag)
    //if (scheduledJobStartIndex == -1) log.warn("could not find scheduled job section")

    def int eventsStartIndex = html.indexOf(eventsSectionTag, startIndex)
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
                    def result = "App $appId event subscriptions (${tableData.size()}) : $tableData"
                    log.info(result)
                    state.checkResults += "$result\n"

                    tableData = extractScheduledJobs(rawHtml)
                    result = "App $appId scheduled jobs (${tableData.size()}) : $tableData"
                    log.info(result)
                    state.checkResults += "$result\n"
                }
            }
        )
    } catch (Exception e) {
        log.warn "Call failed: $e"
    }
}

// For testing purposes.
def readAppStatusFromFile(String fileName) {
    def contents = downloadHubFile(fileName)
    def html = new String(contents)

    def tableData = extractEventSubscriptions(html)
    log.info("$fileName event subscriptions (${tableData.size()}) : $tableData")

    tableData = extractScheduledJobs(html)
    log.info("$fileName scheduled jobs (${tableData.size()}) : $tableData")
}


def updateScheduledCheck() {
    unschedule()
    switch (scheduledCheck.toInteger()) {
        case 1:
        runEvery1Minute("runRuleTracker")
        break

        case 5:
        runEvery5Minutes("runRuleTracker")
        break

        case 10:
        runEvery10Minutes("runRuleTracker")
        break

        case 15:
        runEvery15Minutes("runRuleTracker")
        break

        case 30:
        runEvery30Minutes("runRuleTracker")
        break

        case 60:
        runEvery1Hour("runRuleTracker")
        break

        case 180:
        runEvery3Hours("runRuleTracker")
        break

        case 0:
        default:
            break
    }
}


String getCookie(){
    if (!hubSecurity) return ""

    try{
  	  httpPost(
		[
		uri: "http://127.0.0.1:8080",
		path: "/login",
		query: [ loginRedirect: "/" ],
		body: [
			username: hubSecurityUsername,
			password: hubSecurityPassword,
			submit: "Login"
			]
		]
	  ) { resp ->
		cookie = ((List)((String)resp?.headers?.'Set-Cookie')?.split(';'))?.getAt(0)
        if(traceLogs) log.trace "$cookie"
	  }
    } catch (e){
        cookie = ""
    }
    return "$cookie"
}
