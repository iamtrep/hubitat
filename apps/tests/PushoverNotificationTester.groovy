/*
 *  Pushover Notification Tester
 * Bla blah
 *
 *  Description:
 *      A button-driven test app that exercises every feature of the Pushover Notifications driver.
 *      Each test page targets a specific feature area (priorities, formatting, embedded options, etc.)
 *      and sends crafted messages to verify correct behavior.
 */

import groovy.transform.Field

// ========================================
// CONSTANTS
// ========================================
@Field static final String TEST_URL = "https://hubitat.com"
@Field static final String TEST_URL_TITLE = "Hubitat Website"
@Field static final String TEST_IMAGE_URL = "https://placehold.co/100x100.png"
@Field static final String TEST_SOUND = "cosmic"
@Field static final String TEST_SOUND_ALT = "magic"
@Field static final String TEST_DEVICE = "ALL"
@Field static final int TEST_SELFDESTRUCT_SHORT = 30
@Field static final int TEST_SELFDESTRUCT_LONG = 60
@Field static final int TEST_EMERGENCY_RETRY = 30
@Field static final int TEST_EMERGENCY_EXPIRE = 60
@Field static final int TEST_EMERGENCY_RETRY_UNDER_MIN = 10
@Field static final int TEST_EMERGENCY_EXPIRE_OVER_MAX = 99999
@Field static final int TEST_DELAY_SECONDS = 3
// An in-progress run that records no step for this long is treated as dead (stale),
// so the UI doesn't stay locked after a run is interrupted (hub reboot, code save, etc.)
@Field static final long STALE_RUN_MS = 30000

definition(
    name: "Pushover Notification Tester",
    namespace: "ogiewon",
    author: "Dan Ogorchock",
    description: "Systematic test app for the Pushover Notifications driver",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage")
    page(name: "priorityTests")
    page(name: "formattingTests")
    page(name: "bracketOptionTests")
    page(name: "legacyOptionTests")
    page(name: "emergencyTests")
    page(name: "combinationTests")
    page(name: "commandTests")
    page(name: "testResults")
}

// ========================================
// MAIN PAGE
// ========================================
def mainPage() {
    dynamicPage(name: "mainPage", title: "Pushover Notification Tester", install: true, uninstall: true) {
        section("Device Selection") {
            input "pushoverDevice", "capability.notification", title: "Select Pushover Device", required: true, submitOnChange: true
        }
        if (pushoverDevice) {
            section("Automated Test Run") {
                boolean runStale = isRunStale()
                if (state.testRunInProgress && !runStale) {
                    int total = state.testTotal ?: 0
                    int current = state.currentTestIndex ?: 0
                    paragraph "<b>Completed ${current} of ${total} tests...</b>"
                    if (state.currentTestName) {
                        paragraph "Last started: ${state.currentTestName}"
                    }
                    paragraph "<i>This page does not auto-update — tap Refresh Status to see progress.</i>"
                    input "btnRefreshStatus", "button", title: "Refresh Status"
                    input "btnStopTests", "button", title: "Stop Tests"
                } else {
                    if (state.testRunInProgress && runStale) {
                        paragraph "<b>⚠ Previous run did not finish cleanly</b> (no activity for a while). Start a new run, or Stop to reset."
                        input "btnStopTests", "button", title: "Stop Tests"
                    }
                    input "btnRunAll", "button", title: "Run All Tests (Skip Emergency)"
                    input "btnRunAllWithEmergency", "button", title: "Run All Tests (Include Emergency)"
                }
                if (state.testResults) {
                    int passed = state.testResults.count { it.status == "pass" }
                    int failed = state.testResults.count { it.status == "fail" }
                    int skipped = state.testResults.count { it.status == "skipped" }
                    paragraph "<b>Last Run:</b> ${passed} passed, ${failed} failed, ${skipped} skipped"
                    href "testResults", title: "View Detailed Results", description: "See pass/fail for each test"
                }
            }
            section("Test Pages") {
                href "priorityTests", title: "Priority Tests", description: "Test [S], [L], [N], [H], [E], and default priority"
                href "formattingTests", title: "Formatting Tests", description: "Test [HTML], [OPEN]/[CLOSE], newlines, bold/italic/underline/color"
                href "bracketOptionTests", title: "Embedded Options (Bracket Syntax)", description: "Test [TITLE=], [SOUND=], [DEVICE=], [URL=], [URLTITLE=], [IMAGE=], [SELFDESTRUCT=]"
                href "legacyOptionTests", title: "Embedded Options (Legacy Syntax)", description: "Test ^title^, #sound#, *device*, \u00a7url\u00a7, \u00a4urlTitle\u00a4, \u00a8imageUrl\u00a8"
                href "emergencyTests", title: "Emergency Tests", description: "Test [E] with [EM.RETRY=], [EM.EXPIRE=], and legacy \u00a9retry\u00a9, \u2122expire\u2122"
                href "combinationTests", title: "Combination Tests", description: "Test multiple options combined in one message"
                href "commandTests", title: "Command Tests", description: "Test speak(), getMsgLimits(), view attributes"
            }
            section("Last Test Result") {
                if (state.lastTestName) {
                    paragraph "<b>Test:</b> ${state.lastTestName}"
                    paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
                    paragraph "<b>Time:</b> ${state.lastTestTime}"
                } else {
                    paragraph "<i>No tests run yet</i>"
                }
            }
            section("Device Attributes") {
                def attrs = getDeviceAttributes()
                paragraph "<b>notificationText:</b> ${attrs.notificationText ?: 'n/a'}"
                paragraph "<b>messageLimit:</b> ${attrs.messageLimit ?: 'n/a'}"
                paragraph "<b>messagesRemaining:</b> ${attrs.messagesRemaining ?: 'n/a'}"
                paragraph "<b>limitResetDate:</b> ${attrs.limitResetDate ?: 'n/a'}"
                paragraph "<b>limitLastUpdated:</b> ${attrs.limitLastUpdated ?: 'n/a'}"
            }
        }
        section("Settings") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            label title: "Optionally assign a custom name for this app", required: false
        }
    }
}

// ========================================
// PRIORITY TESTS
// ========================================
def priorityTests() {
    dynamicPage(name: "priorityTests", title: "Priority Tests", install: false, uninstall: false) {
        section("Send a test message at each priority level") {
            input "btnPriorityDefault", "button", title: "Default Priority (no prefix)"
            input "btnPriorityLowest", "button", title: "Lowest Priority [S]"
            input "btnPriorityLow", "button", title: "Low Priority [L]"
            input "btnPriorityNormal", "button", title: "Normal Priority [N]"
            input "btnPriorityHigh", "button", title: "High Priority [H]"
            input "btnPriorityEmergency", "button", title: "Emergency Priority [E]"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// FORMATTING TESTS
// ========================================
def formattingTests() {
    dynamicPage(name: "formattingTests", title: "Formatting Tests", install: false, uninstall: false) {
        section("HTML Mode") {
            input "btnHtmlBasic", "button", title: "Basic [HTML] message"
            input "btnHtmlBold", "button", title: "[HTML] Bold text"
            input "btnHtmlItalic", "button", title: "[HTML] Italic text"
            input "btnHtmlUnderline", "button", title: "[HTML] Underline text"
            input "btnHtmlColor", "button", title: "[HTML] Red colored text"
            input "btnHtmlCombined", "button", title: "[HTML] Bold + Italic + Color"
        }
        section("HTML Character Substitution") {
            input "btnHtmlOpenClose", "button", title: "[HTML] with [OPEN]/[CLOSE] tags"
            input "btnHtmlCustomChars", "button", title: "[HTML] with \u2264/\u2265 custom chars"
        }
        section("Newlines") {
            input "btnNewlines", "button", title: "Message with \\n newlines"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// BRACKET OPTION TESTS
// ========================================
def bracketOptionTests() {
    dynamicPage(name: "bracketOptionTests", title: "Embedded Options (Bracket Syntax)", install: false, uninstall: false) {
        section("Individual Options") {
            input "btnBracketTitle", "button", title: "[TITLE=Test Title]"
            input "btnBracketSound", "button", title: "[SOUND=${TEST_SOUND}]"
            input "btnBracketDevice", "button", title: "[DEVICE=${TEST_DEVICE}]"
            input "btnBracketUrl", "button", title: "[URL=${TEST_URL}]"
            input "btnBracketUrlTitle", "button", title: "[URL=...][URLTITLE=${TEST_URL_TITLE}]"
            input "btnBracketImage", "button", title: "[IMAGE=...] (test image)"
            input "btnBracketSelfDestruct", "button", title: "[SELFDESTRUCT=${TEST_SELFDESTRUCT_SHORT}] (auto-delete in ${TEST_SELFDESTRUCT_SHORT}s)"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// LEGACY OPTION TESTS
// ========================================
def legacyOptionTests() {
    dynamicPage(name: "legacyOptionTests", title: "Embedded Options (Legacy Syntax)", install: false, uninstall: false) {
        section("Individual Options") {
            input "btnLegacyTitle", "button", title: "^Test Title^ (legacy title)"
            input "btnLegacySound", "button", title: "#${TEST_SOUND}# (legacy sound)"
            input "btnLegacyDevice", "button", title: "*device* (legacy device)"
            input "btnLegacyUrl", "button", title: "\u00a7url\u00a7 (legacy URL)"
            input "btnLegacyUrlTitle", "button", title: "\u00a7url\u00a7 \u00a4title\u00a4 (legacy URL+title)"
            input "btnLegacyImage", "button", title: "\u00a8imageUrl\u00a8 (legacy image)"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// EMERGENCY TESTS
// ========================================
def emergencyTests() {
    dynamicPage(name: "emergencyTests", title: "Emergency Tests", install: false, uninstall: false) {
        section("<b>WARNING:</b> Emergency messages repeat until acknowledged!") {
            paragraph "These tests send Emergency priority messages. You must acknowledge them in the Pushover app to stop the repeating alerts."
        }
        section("Bracket Syntax") {
            input "btnEmergDefault", "button", title: "[E] Emergency (driver defaults)"
            input "btnEmergRetryExpire", "button", title: "[E] + [EM.RETRY=${TEST_EMERGENCY_RETRY}][EM.EXPIRE=${TEST_EMERGENCY_EXPIRE}]"
            input "btnEmergMinRetry", "button", title: "[E] + [EM.RETRY=${TEST_EMERGENCY_RETRY_UNDER_MIN}] (tests min clamp to 30)"
            input "btnEmergMaxExpire", "button", title: "[E] + [EM.EXPIRE=${TEST_EMERGENCY_EXPIRE_OVER_MAX}] (tests max clamp to 10800)"
        }
        section("Legacy Syntax") {
            input "btnEmergLegacyRetryExpire", "button", title: "[E] + \u00a9${TEST_EMERGENCY_RETRY}\u00a9 + \u2122${TEST_EMERGENCY_EXPIRE}\u2122 (legacy retry/expire)"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// COMBINATION TESTS
// ========================================
def combinationTests() {
    dynamicPage(name: "combinationTests", title: "Combination Tests", install: false, uninstall: false) {
        section("Multiple options in one message") {
            input "btnComboHighHtmlTitle", "button", title: "[H] + [HTML] + [TITLE=Alert] + bold text"
            input "btnComboUrlImageSound", "button", title: "[TITLE=...] + [URL=...] + [URLTITLE=...] + [SOUND=...] + [IMAGE=...]"
            input "btnComboLowSelfDestruct", "button", title: "[L] + [SELFDESTRUCT=${TEST_SELFDESTRUCT_LONG}] + [TITLE=Temp Msg]"
            input "btnComboEverything", "button", title: "Kitchen Sink: [H][HTML][TITLE=][SOUND=][URL=][URLTITLE=][IMAGE=]"
            input "btnComboNewlineHtml", "button", title: "[HTML] + newlines + bold + color"
        }
        section("Mixed Legacy + Bracket") {
            input "btnComboMixedSyntax", "button", title: "[H] + ^title^ + #sound# (mixed syntax)"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// COMMAND TESTS
// ========================================
def commandTests() {
    dynamicPage(name: "commandTests", title: "Command Tests", install: false, uninstall: false) {
        section("speak() Command") {
            input "btnSpeak", "button", title: "Send via speak() instead of deviceNotification()"
            input "btnSpeakWithOptions", "button", title: "speak() with [H][TITLE=Speak Test]"
        }
        section("getMsgLimits() Command") {
            input "btnGetLimits", "button", title: "Call getMsgLimits() to refresh attributes"
        }
        section("Current Device Attributes") {
            def attrs = getDeviceAttributes()
            paragraph "<b>notificationText:</b> ${attrs.notificationText ?: 'n/a'}"
            paragraph "<b>messageLimit:</b> ${attrs.messageLimit ?: 'n/a'}"
            paragraph "<b>messagesRemaining:</b> ${attrs.messagesRemaining ?: 'n/a'}"
            paragraph "<b>limitReset:</b> ${attrs.limitReset ?: 'n/a'}"
            paragraph "<b>limitResetDate:</b> ${attrs.limitResetDate ?: 'n/a'}"
            paragraph "<b>limitLastUpdated:</b> ${attrs.limitLastUpdated ?: 'n/a'}"
        }
        section("Last Test Result") {
            if (state.lastTestName) {
                paragraph "<b>Test:</b> ${state.lastTestName}"
                paragraph "<b>Message Sent:</b> <code>${state.lastTestMessage}</code>"
            } else {
                paragraph "<i>No tests run yet</i>"
            }
        }
    }
}

// ========================================
// TEST RESULTS PAGE
// ========================================
def testResults() {
    dynamicPage(name: "testResults", title: "Test Results", install: false, uninstall: false) {
        if (state.testResults) {
            section("Summary") {
                int passed = state.testResults.count { it.status == "pass" }
                int failed = state.testResults.count { it.status == "fail" }
                int skipped = state.testResults.count { it.status == "skipped" }
                int total = state.testResults.size()
                paragraph "<b>${passed}</b> passed / <b>${failed}</b> failed / <b>${skipped}</b> skipped (${total} total)"
                if (state.testRunStartTime) paragraph "<b>Started:</b> ${state.testRunStartTime}"
                if (state.testRunEndTime) paragraph "<b>Finished:</b> ${state.testRunEndTime}"
            }
            section("Details") {
                state.testResults.each { result ->
                    String statusLabel
                    if (result.status == "pass") {
                        statusLabel = '<font color="green"><b>PASS</b></font>'
                    } else if (result.status == "fail") {
                        statusLabel = '<font color="red"><b>FAIL</b></font>'
                    } else {
                        statusLabel = '<font color="gray"><b>SKIP</b></font>'
                    }
                    String line = "${statusLabel} - ${result.name}"
                    if (result.error) line += " <i>(${result.error})</i>"
                    paragraph line
                }
            }
        } else {
            section {
                paragraph "<i>No test results yet. Run tests from the main page.</i>"
            }
        }
    }
}

// ========================================
// BUTTON HANDLER
// ========================================
void appButtonHandler(String buttonName) {
    if (logEnable) log.debug "appButtonHandler: ${buttonName}"

    switch (buttonName) {
        // Priority tests
        case "btnPriorityDefault":
            sendTest("Default Priority", "Test: default priority (no prefix)")
            break
        case "btnPriorityLowest":
            sendTest("Lowest Priority [S]", "[S]Test: lowest priority (-2)")
            break
        case "btnPriorityLow":
            sendTest("Low Priority [L]", "[L]Test: low priority (-1)")
            break
        case "btnPriorityNormal":
            sendTest("Normal Priority [N]", "[N]Test: normal priority (0)")
            break
        case "btnPriorityHigh":
            sendTest("High Priority [H]", "[H]Test: high priority (1)")
            break
        case "btnPriorityEmergency":
            sendTest("Emergency Priority [E]", "[E]Test: emergency priority (2) - ACK to stop")
            break

        // Formatting tests
        case "btnHtmlBasic":
            sendTest("Basic HTML", "[HTML]Test: <b>HTML mode</b> is active")
            break
        case "btnHtmlBold":
            sendTest("HTML Bold", "[HTML]Test: this is <b>bold text</b>")
            break
        case "btnHtmlItalic":
            sendTest("HTML Italic", "[HTML]Test: this is <i>italic text</i>")
            break
        case "btnHtmlUnderline":
            sendTest("HTML Underline", "[HTML]Test: this is <u>underlined text</u>")
            break
        case "btnHtmlColor":
            sendTest("HTML Color", '[HTML]Test: this is <font color="#FF0000">red text</font>')
            break
        case "btnHtmlCombined":
            sendTest("HTML Combined", '[HTML]Test: <b>bold</b> and <i>italic</i> and <font color="#0000FF">blue</font>')
            break
        case "btnHtmlOpenClose":
            sendTest("HTML [OPEN]/[CLOSE]", "[HTML]Test: [OPEN]b[CLOSE]bold via OPEN/CLOSE[OPEN]/b[CLOSE]")
            break
        case "btnHtmlCustomChars":
            sendTest("HTML \u2264/\u2265 chars", "[HTML]Test: \u2264b\u2265bold via custom chars\u2264/b\u2265")
            break
        case "btnNewlines":
            sendTest("Newlines", "Test: line one\\nline two\\nline three")
            break

        // Bracket option tests
        case "btnBracketTitle":
            sendTest("Bracket [TITLE]", "[TITLE=Test Title]Test: message with custom title")
            break
        case "btnBracketSound":
            sendTest("Bracket [SOUND]", "[SOUND=${TEST_SOUND}]Test: message with ${TEST_SOUND} sound")
            break
        case "btnBracketDevice":
            sendTest("Bracket [DEVICE]", "[DEVICE=${TEST_DEVICE}]Test: message sent to ${TEST_DEVICE}")
            break
        case "btnBracketUrl":
            sendTest("Bracket [URL]", "[URL=${TEST_URL}]Test: message with clickable URL")
            break
        case "btnBracketUrlTitle":
            sendTest("Bracket [URL]+[URLTITLE]", "[URL=${TEST_URL}][URLTITLE=${TEST_URL_TITLE}]Test: URL with display title")
            break
        case "btnBracketImage":
            sendTest("Bracket [IMAGE]", "[IMAGE=${TEST_IMAGE_URL}]Test: message with custom icon")
            break
        case "btnBracketSelfDestruct":
            sendTest("Bracket [SELFDESTRUCT]", "[SELFDESTRUCT=${TEST_SELFDESTRUCT_SHORT}]Test: this message auto-deletes in ${TEST_SELFDESTRUCT_SHORT} seconds")
            break

        // Legacy option tests
        case "btnLegacyTitle":
            sendTest("Legacy ^title^", "^Test Title^Test: message with legacy title syntax")
            break
        case "btnLegacySound":
            sendTest("Legacy #sound#", "#${TEST_SOUND}#Test: message with legacy sound syntax")
            break
        case "btnLegacyDevice":
            sendTest("Legacy *device*", "*${TEST_DEVICE}*Test: message with legacy device syntax")
            break
        case "btnLegacyUrl":
            sendTest("Legacy \u00a7url\u00a7", "\u00a7${TEST_URL}\u00a7Test: message with legacy URL syntax")
            break
        case "btnLegacyUrlTitle":
            sendTest("Legacy \u00a7url\u00a7+\u00a4title\u00a4", "\u00a7${TEST_URL}\u00a7\u00a4${TEST_URL_TITLE}\u00a4Test: legacy URL with title")
            break
        case "btnLegacyImage":
            sendTest("Legacy \u00a8image\u00a8", "\u00a8${TEST_IMAGE_URL}\u00a8Test: legacy image syntax")
            break

        // Emergency tests
        case "btnEmergDefault":
            sendTest("Emergency Default", "[E]Test: emergency with driver defaults - ACK to stop")
            break
        case "btnEmergRetryExpire":
            sendTest("Emergency Retry+Expire", "[E][EM.RETRY=${TEST_EMERGENCY_RETRY}][EM.EXPIRE=${TEST_EMERGENCY_EXPIRE}]Test: retry ${TEST_EMERGENCY_RETRY}s, expire ${TEST_EMERGENCY_EXPIRE}s - ACK to stop")
            break
        case "btnEmergMinRetry":
            sendTest("Emergency Min Retry", "[E][EM.RETRY=${TEST_EMERGENCY_RETRY_UNDER_MIN}]Test: retry=${TEST_EMERGENCY_RETRY_UNDER_MIN} should clamp to 30 - ACK to stop")
            break
        case "btnEmergMaxExpire":
            sendTest("Emergency Max Expire", "[E][EM.EXPIRE=${TEST_EMERGENCY_EXPIRE_OVER_MAX}]Test: expire=${TEST_EMERGENCY_EXPIRE_OVER_MAX} should clamp to 10800 - ACK to stop")
            break
        case "btnEmergLegacyRetryExpire":
            sendTest("Emergency Legacy Retry+Expire", "[E]\u00a9${TEST_EMERGENCY_RETRY}\u00a9\u2122${TEST_EMERGENCY_EXPIRE}\u2122Test: legacy retry ${TEST_EMERGENCY_RETRY}s, expire ${TEST_EMERGENCY_EXPIRE}s - ACK to stop")
            break

        // Combination tests
        case "btnComboHighHtmlTitle":
            sendTest("Combo: High+HTML+Title", "[H][HTML][TITLE=Alert]Test: <b>high priority HTML</b> with custom title")
            break
        case "btnComboUrlImageSound":
            sendTest("Combo: URL+Image+Sound", "[TITLE=Link Test][URL=${TEST_URL}][URLTITLE=${TEST_URL_TITLE}][SOUND=${TEST_SOUND_ALT}][IMAGE=${TEST_IMAGE_URL}]Test: title, URL, sound, and image combined")
            break
        case "btnComboLowSelfDestruct":
            sendTest("Combo: Low+SelfDestruct+Title", "[L][SELFDESTRUCT=${TEST_SELFDESTRUCT_LONG}][TITLE=Temp Msg]Test: low priority, auto-deletes in ${TEST_SELFDESTRUCT_LONG}s")
            break
        case "btnComboEverything":
            sendTest("Combo: Kitchen Sink", "[H][HTML][TITLE=Kitchen Sink][SOUND=${TEST_SOUND}][URL=${TEST_URL}][URLTITLE=${TEST_URL_TITLE}][IMAGE=${TEST_IMAGE_URL}]Test: <b>all options</b> at once")
            break
        case "btnComboNewlineHtml":
            sendTest("Combo: Newline+HTML+Bold+Color", '[HTML]Test: line one\\n[OPEN]b[CLOSE]bold on line two[OPEN]/b[CLOSE]\\n\u2264font color="#FF0000"\u2265red on line three\u2264/font\u2265')
            break
        case "btnComboMixedSyntax":
            sendTest("Combo: Mixed Syntax", "[H]^Mixed Syntax^#${TEST_SOUND}#Test: high priority with legacy title and sound")
            break

        // Command tests
        case "btnSpeak":
            sendSpeakTest("speak() Command", "Test: sent via speak() command")
            break
        case "btnSpeakWithOptions":
            sendSpeakTest("speak() with Options", "[H][TITLE=Speak Test]Test: speak() with high priority and title")
            break
        case "btnGetLimits":
            runGetLimits()
            break

        // Automated test run
        case "btnRunAll":
            runAllTests(false)
            break
        case "btnRunAllWithEmergency":
            runAllTests(true)
            break
        case "btnStopTests":
            stopTests()
            break
        case "btnRefreshStatus":
            // No-op: pressing any button re-renders the page, refreshing the progress display
            break

        default:
            log.warn "Unknown button: ${buttonName}"
    }
}

// ========================================
// TEST HELPERS
// ========================================
private void sendTest(String testName, String message) {
    if (logEnable) log.debug "sendTest(${testName}): ${message}"

    state.lastTestName = testName
    state.lastTestMessage = message
    state.lastTestTime = new Date().format("yyyy-MM-dd HH:mm:ss")

    try {
        pushoverDevice.deviceNotification(message)
        if (logEnable) log.debug "sendTest(${testName}): sent successfully"
    } catch (e) {
        log.error "sendTest(${testName}): error = ${e}"
        state.lastTestName = "${testName} - ERROR"
    }
}

private void sendSpeakTest(String testName, String message) {
    if (logEnable) log.debug "sendSpeakTest(${testName}): ${message}"

    state.lastTestName = testName
    state.lastTestMessage = message
    state.lastTestTime = new Date().format("yyyy-MM-dd HH:mm:ss")

    try {
        pushoverDevice.speak(message)
        if (logEnable) log.debug "sendSpeakTest(${testName}): sent successfully"
    } catch (e) {
        log.error "sendSpeakTest(${testName}): error = ${e}"
        state.lastTestName = "${testName} - ERROR"
    }
}

private void runGetLimits() {
    if (logEnable) log.debug "runGetLimits(): calling getMsgLimits()"

    state.lastTestName = "getMsgLimits()"
    state.lastTestMessage = "Called getMsgLimits() - check attributes"
    state.lastTestTime = new Date().format("yyyy-MM-dd HH:mm:ss")

    try {
        pushoverDevice.getMsgLimits()
        if (logEnable) log.debug "runGetLimits(): called successfully"
    } catch (e) {
        log.error "runGetLimits(): error = ${e}"
        state.lastTestName = "getMsgLimits() - ERROR"
    }
}

private Map getDeviceAttributes() {
    Map attrs = [:]
    if (pushoverDevice) {
        try {
            attrs.notificationText = pushoverDevice.currentValue("notificationText")
            attrs.messageLimit = pushoverDevice.currentValue("messageLimit")
            attrs.messagesRemaining = pushoverDevice.currentValue("messagesRemaining")
            attrs.limitReset = pushoverDevice.currentValue("limitReset")
            attrs.limitResetDate = pushoverDevice.currentValue("limitResetDate")
            attrs.limitLastUpdated = pushoverDevice.currentValue("limitLastUpdated")
        } catch (e) {
            if (logEnable) log.debug "getDeviceAttributes(): ${e}"
        }
    }
    return attrs
}

// ========================================
// AUTOMATED TEST RUN
// ========================================
private List getTestCases() {
    return [
        // Priority tests
        [name: "Default Priority", message: "Test: default priority (no prefix)"],
        [name: "Lowest Priority [S]", message: "[S]Test: lowest priority (-2)"],
        [name: "Low Priority [L]", message: "[L]Test: low priority (-1)"],
        [name: "Normal Priority [N]", message: "[N]Test: normal priority (0)"],
        [name: "High Priority [H]", message: "[H]Test: high priority (1)"],
        [name: "Emergency Priority [E]", message: "[E]Test: emergency priority (2) - ACK to stop", emergency: true],

        // Formatting tests
        [name: "Basic HTML", message: "[HTML]Test: <b>HTML mode</b> is active"],
        [name: "HTML Bold", message: "[HTML]Test: this is <b>bold text</b>"],
        [name: "HTML Italic", message: "[HTML]Test: this is <i>italic text</i>"],
        [name: "HTML Underline", message: "[HTML]Test: this is <u>underlined text</u>"],
        [name: "HTML Color", message: '[HTML]Test: this is <font color="#FF0000">red text</font>'],
        [name: "HTML Combined", message: '[HTML]Test: <b>bold</b> and <i>italic</i> and <font color="#0000FF">blue</font>'],
        [name: "HTML [OPEN]/[CLOSE]", message: "[HTML]Test: [OPEN]b[CLOSE]bold via OPEN/CLOSE[OPEN]/b[CLOSE]"],
        [name: "HTML \u2264/\u2265 chars", message: "[HTML]Test: \u2264b\u2265bold via custom chars\u2264/b\u2265"],
        [name: "Newlines", message: "Test: line one\\nline two\\nline three"],

        // Bracket option tests
        [name: "Bracket [TITLE]", message: "[TITLE=Test Title]Test: message with custom title"],
        [name: "Bracket [SOUND]", message: "[SOUND=${TEST_SOUND}]Test: message with ${TEST_SOUND} sound"],
        [name: "Bracket [DEVICE]", message: "[DEVICE=${TEST_DEVICE}]Test: message sent to ${TEST_DEVICE}"],
        [name: "Bracket [URL]", message: "[URL=${TEST_URL}]Test: message with clickable URL"],
        [name: "Bracket [URL]+[URLTITLE]", message: "[URL=${TEST_URL}][URLTITLE=${TEST_URL_TITLE}]Test: URL with display title"],
        [name: "Bracket [IMAGE]", message: "[IMAGE=${TEST_IMAGE_URL}]Test: message with custom icon"],
        [name: "Bracket [SELFDESTRUCT]", message: "[SELFDESTRUCT=${TEST_SELFDESTRUCT_SHORT}]Test: this message auto-deletes in ${TEST_SELFDESTRUCT_SHORT} seconds"],

        // Legacy option tests
        [name: "Legacy ^title^", message: "^Test Title^Test: message with legacy title syntax"],
        [name: "Legacy #sound#", message: "#${TEST_SOUND}#Test: message with legacy sound syntax"],
        [name: "Legacy *device*", message: "*${TEST_DEVICE}*Test: message with legacy device syntax"],
        [name: "Legacy \u00a7url\u00a7", message: "\u00a7${TEST_URL}\u00a7Test: message with legacy URL syntax"],
        [name: "Legacy \u00a7url\u00a7+\u00a4title\u00a4", message: "\u00a7${TEST_URL}\u00a7\u00a4${TEST_URL_TITLE}\u00a4Test: legacy URL with title"],
        [name: "Legacy \u00a8image\u00a8", message: "\u00a8${TEST_IMAGE_URL}\u00a8Test: legacy image syntax"],

        // Emergency tests
        [name: "Emergency Default", message: "[E]Test: emergency with driver defaults - ACK to stop", emergency: true],
        [name: "Emergency Retry+Expire", message: "[E][EM.RETRY=${TEST_EMERGENCY_RETRY}][EM.EXPIRE=${TEST_EMERGENCY_EXPIRE}]Test: retry ${TEST_EMERGENCY_RETRY}s, expire ${TEST_EMERGENCY_EXPIRE}s - ACK to stop", emergency: true],
        [name: "Emergency Min Retry", message: "[E][EM.RETRY=${TEST_EMERGENCY_RETRY_UNDER_MIN}]Test: retry=${TEST_EMERGENCY_RETRY_UNDER_MIN} should clamp to 30 - ACK to stop", emergency: true],
        [name: "Emergency Max Expire", message: "[E][EM.EXPIRE=${TEST_EMERGENCY_EXPIRE_OVER_MAX}]Test: expire=${TEST_EMERGENCY_EXPIRE_OVER_MAX} should clamp to 10800 - ACK to stop", emergency: true],
        [name: "Emergency Legacy Retry+Expire", message: "[E]\u00a9${TEST_EMERGENCY_RETRY}\u00a9\u2122${TEST_EMERGENCY_EXPIRE}\u2122Test: legacy retry ${TEST_EMERGENCY_RETRY}s, expire ${TEST_EMERGENCY_EXPIRE}s - ACK to stop", emergency: true],

        // Combination tests
        [name: "Combo: High+HTML+Title", message: "[H][HTML][TITLE=Alert]Test: <b>high priority HTML</b> with custom title"],
        [name: "Combo: URL+Image+Sound", message: "[TITLE=Link Test][URL=${TEST_URL}][URLTITLE=${TEST_URL_TITLE}][SOUND=${TEST_SOUND_ALT}][IMAGE=${TEST_IMAGE_URL}]Test: title, URL, sound, and image combined"],
        [name: "Combo: Low+SelfDestruct+Title", message: "[L][SELFDESTRUCT=${TEST_SELFDESTRUCT_LONG}][TITLE=Temp Msg]Test: low priority, auto-deletes in ${TEST_SELFDESTRUCT_LONG}s"],
        [name: "Combo: Kitchen Sink", message: "[H][HTML][TITLE=Kitchen Sink][SOUND=${TEST_SOUND}][URL=${TEST_URL}][URLTITLE=${TEST_URL_TITLE}][IMAGE=${TEST_IMAGE_URL}]Test: <b>all options</b> at once"],
        [name: "Combo: Newline+HTML+Bold+Color", message: '[HTML]Test: line one\\n[OPEN]b[CLOSE]bold on line two[OPEN]/b[CLOSE]\\n\u2264font color="#FF0000"\u2265red on line three\u2264/font\u2265'],
        [name: "Combo: Mixed Syntax", message: "[H]^Mixed Syntax^#${TEST_SOUND}#Test: high priority with legacy title and sound"],

        // Command tests
        [name: "speak() Command", message: "Test: sent via speak() command", type: "speak"],
        [name: "speak() with Options", message: "[H][TITLE=Speak Test]Test: speak() with high priority and title", type: "speak"],
        [name: "getMsgLimits()", type: "command"],
    ]
}

// Returns true when a run is flagged in-progress but appears dead (no recent step) —
// e.g. a hub reboot or code save interrupted the runIn chain. Lets the UI recover.
private boolean isRunStale() {
    return computeRunStale(state.testRunInProgress == true, state.lastStepEpoch as Long, now(), STALE_RUN_MS)
}
private static boolean computeRunStale(boolean inProgress, Long lastStepEpoch, long nowMs, long thresholdMs) {
    if (!inProgress) return false
    if (lastStepEpoch == null) return true
    return (nowMs - lastStepEpoch) > thresholdMs
}

private void runAllTests(boolean includeEmergency) {
    // Defensive: clear any stale schedule left by a prior run that didn't finish cleanly
    unschedule("executeNextTest")

    List testCases = getTestCases()
    if (!includeEmergency) {
        testCases = testCases.findAll { !it.emergency }
    }

    state.testResults = []
    state.currentTestIndex = 0
    state.testTotal = testCases.size()
    state.testRunInProgress = true
    state.includeEmergency = includeEmergency
    state.testRunStartTime = new Date().format("yyyy-MM-dd HH:mm:ss")
    state.testRunEndTime = null
    state.currentTestName = null
    state.lastStepEpoch = now()

    if (logEnable) log.debug "runAllTests(): starting ${testCases.size()} tests (includeEmergency=${includeEmergency})"

    executeNextTest()
}

private void stopTests() {
    if (logEnable) log.debug "stopTests(): aborting test run"
    state.testRunInProgress = false
    state.testRunEndTime = new Date().format("yyyy-MM-dd HH:mm:ss")
    state.currentTestName = null
    unschedule("executeNextTest")
}

void executeNextTest() {
    if (!state.testRunInProgress) return

    List testCases = getTestCases()
    if (!state.includeEmergency) {
        testCases = testCases.findAll { !it.emergency }
    }

    int idx = state.currentTestIndex
    if (idx >= testCases.size()) {
        state.testRunInProgress = false
        state.testRunEndTime = new Date().format("yyyy-MM-dd HH:mm:ss")
        state.currentTestName = null
        if (logEnable) log.debug "executeNextTest(): all tests complete"
        return
    }

    Map testCase = testCases[idx]
    state.currentTestName = testCase.name
    state.lastStepEpoch = now()
    String type = testCase.type ?: "notification"

    if (logEnable) log.debug "executeNextTest(): [${idx + 1}/${testCases.size()}] ${testCase.name}"

    List results = state.testResults ?: []

    try {
        switch (type) {
            case "notification":
                pushoverDevice.deviceNotification(testCase.message)
                break
            case "speak":
                pushoverDevice.speak(testCase.message)
                break
            case "command":
                pushoverDevice.getMsgLimits()
                break
        }
        results << [name: testCase.name, status: "pass"]
        if (logEnable) log.debug "executeNextTest(): ${testCase.name} - PASS"
    } catch (e) {
        results << [name: testCase.name, status: "fail", error: e.message]
        log.error "executeNextTest(): ${testCase.name} - FAIL: ${e.message}"
    }

    state.testResults = results
    state.currentTestIndex = idx + 1

    if (idx + 1 < testCases.size()) {
        runIn(TEST_DELAY_SECONDS, "executeNextTest")
    } else {
        state.testRunInProgress = false
        state.testRunEndTime = new Date().format("yyyy-MM-dd HH:mm:ss")
        state.currentTestName = null
        if (logEnable) log.debug "executeNextTest(): all tests complete"
    }
}

// ========================================
// LIFECYCLE
// ========================================
def installed() {
    log.debug "'installed()' called"
    initialize()
}

def updated() {
    log.debug "'updated()' called"
    initialize()
}

def initialize() {
    if (logEnable) {
        log.info "Debug logging enabled"
        runIn(1800, logsOff)
    } else {
        unschedule(logsOff)
    }
}

void logsOff() {
    log.warn "Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}
