definition(
    name: "Rule Tracker",
    namespace: "Example",
    author: "iamtrep",
    description: "Track RM rule subscriptions and schedules",
    category: "debugging",
    iconUrl: "",
    iconX2Url: "")

preferences {
}

import hubitat.helper.RMUtils
import groovy.util.XmlSlurper
import groovy.transform.Field

def installed() {
    log.debug "installed()"
}

@Field static final String app_status_url = "http://127.0.0.1:8080/installedapp/status"

def updated() {
    log.debug "updated()"
    def rules = RMUtils.getRuleList("5.0")

    // TODO - create a rule selector

    //log.debug(rules)
    //log.debug(getObjectClassName(rules[0]))

    // For now, hardcode apps to track
    def appIdsToTrack = [167,412]

    def fileToParse = 'response2.html'
    //log.debug("checking file $fileToParse")
    //readAppStatusFromFile(fileToParse)

    appIdsToTrack.each {
        //log.debug("checking app $it status")
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
    log.trace groovy.xml.XmlUtil.escapeXml("looking for table ($startTag) at startIndex=$startIndex")

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
    //log.trace("tableHtml=" + tableHtml)

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

    //log.trace("parsedHtml type = " + getObjectClassName(parsedHtml))
    //log.trace("parsedHtml=" + parsedHtml.text())

    def tableData = parsedHtml.tbody.tr.collect {
        tr -> tr.td.collect {
            td -> td.text()
        }
    }

    //log.debug("table data: $tableData")
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
