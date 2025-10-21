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

 Sensor Aggregator Discrete Child

 An app that allows aggregating discrete sensor values and saving the result to a virtual device

 */


definition(
    name: "Sensor Aggregator Discrete Child",
    namespace: "iamtrep",
    parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "Aggregate discrete sensor values (contact, motion, tilt, etc) and save to a single virtual device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/SensorAggregatorDiscreteChild.groovy"
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.Event

@Field static final String child_app_version = "0.3.0"

@Field static final Map<String, String> CAPABILITY_ATTRIBUTES = [
    "capability.accelerationSensor"  : [ attribute: "acceleration", values: ["inactive", "active"], driver: "Virtual Acceleration Sensor" ],
    "capability.contactSensor"       : [ attribute: "contact", values: ["closed", "open"], driver: "Virtual Contact Sensor" ],
    "capability.motionSensor"        : [ attribute: "motion", values: ["inactive","active"], driver: "Virtual Motion Sensor" ],
    "capability.presenceSensor"      : [ attribute: "presence", values: ["not present","present"], driver: "Virtual Presence Sensor" ],
    "capability.shockSensor"         : [ attribute: "shock", values: ["clear", "detected"], driver: "Virtual Shock Sensor" ],
    "capability.waterSensor"         : [ attribute: "water", values: ["dry", "wet"], driver: "Virtual Moisture Sensor" ]
]


preferences {
	page(name: "mainPage")
}

Map mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Configuration") {
            input "appName", "text", title: "Name this sensor aggregator app", submitOnChange: true
		    if(appName) app.updateLabel("$appName")
        }
        section("Sensors") {
            input name: "selectedSensorCapability", type: "enum", options: CAPABILITY_ATTRIBUTES.keySet(), title: "Select sensor capability to aggregate", required: true, submitOnChange:true
            if (selectedSensorCapability) {
                input name: "inputSensors", type: selectedSensorCapability, title: "Sensors to aggregate", multiple:true, required: true, showFilter: true, submitOnChange: true
                if (inputSensors) {
                    def attributePossibleValues = getAttributePossibleValues(inputSensors[0], selectedSensorCapability)
                    input name: "attributeValue", type: "enum", options: attributePossibleValues, title: "Attribute value to monitor", multiple:false, required:true
                }
                input name: "outputSensor", type: selectedSensorCapability, title: "Virtual sensor to set as aggregation output", multiple: false, required: false, submitOnChange: true
                if (!outputSensor) {
                    input "createChildSensorDevice", "bool", title: "Create Child Device if no Output Device selected", defaultValue: state.createChild, required: true, submitOnChange: true
                    state.createChild = createChildSensorDevice
                }
            }
        }
        section("Aggregation") {
            input name: "aggregationMethod", type: "enum", options: ["any", "all", "majority", "threshold"], title: "Select aggregation method", defaultValue: "any", required: true, submitOnChange: true
            if (aggregationMethod == "threshold") {
                input name: "thresholdPercent", type: "number", title: "Threshold percentage (1-100)", defaultValue: 50, range: "1..100", required: true
            }
            input name: "excludeAfter", type: "number", title: "Exclude sensor when inactive for this many minutes:", defaultValue: 60, range: "0..1440", submitOnChange: true
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update aggregate value"
            if(inputSensors && state.aggregateValue != null) {
                def possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values
                paragraph "Current aggregate value: <b>${state.aggregateValue}</b>"
                paragraph "Included sensors: ${state.includedSensors?.size() ?: 0} of ${inputSensors.size()}"
                if (state.excludedSensors?.size() > 0) {
                    // Build links to excluded sensor device pages
                    List<String> excludedLinks = []
                    inputSensors.each { sensor ->
                        if (state.excludedSensors?.contains(sensor.getLabel())) {
                            excludedLinks << "<a href='/device/edit/${sensor.id}' target='_blank'>${sensor.getLabel()}</a>"
                        }
                    }
                    paragraph "<span style='color:orange'><b>Excluded sensors:</b> ${excludedLinks.join(', ')}</span>"
                }
            }
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            log.info("${logLevel} logging enabled")
        }
        section("Notifications") {
            input name: "notificationDevice", type: "capability.notification", title: "Send notifications to:", multiple: false, required: false
            input name: "notifyOnAllExcluded", type: "bool", title: "Notify when all sensors are excluded", defaultValue: true
            input name: "notifyOnFirstExcluded", type: "bool", title: "Notify when any sensor is excluded", defaultValue: false
            input name: "notifyOnValueChange", type: "bool", title: "Notify when aggregate value changes", defaultValue: false
        }
        section(title: "Testing", hideable: true, hidden: true) {
            paragraph "<b>Automated Testing</b>"
            paragraph "Run automated tests to verify the aggregation logic. This will create test devices, run tests, and clean up automatically."
            input name: "runSmokeTests", type: "button", title: "Run Quick Smoke Tests (3 tests)"
            input name: "runAllTests", type: "button", title: "Run Full Test Suite (11 tests)"
            input name: "cleanupTestDevices", type: "button", title: "Cleanup Test Devices Only"

            if (state.lastTestRun) {
                paragraph "<hr>"
                paragraph "<b>Last Test Run:</b> ${state.lastTestRun}"
                paragraph "<b>Results:</b> ${state.testsPassed} / ${state.testsTotal} passed"
                if (state.testsFailed > 0) {
                    paragraph "<span style='color:red'><b>Failed:</b> ${state.testsFailed}</span>"
                    paragraph "<b>Failed Tests:</b><br>${state.failedTests?.join('<br>')}"
                } else if (state.testsTotal > 0) {
                    paragraph "<span style='color:green'><b>All tests passed!</b></span>"
                }
            }
        }
    }
}

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"

    // Validate settings (skip validation during testing)
    if (!state.testingInProgress) {
        if (!selectedSensorCapability) {
            logError("No sensor capability selected")
            return
        }
        if (!inputSensors || inputSensors.size() == 0) {
            logError("No input sensors selected")
            return
        }
        if (!attributeValue) {
            logError("No attribute value selected")
            return
        }
    }

    unsubscribe()
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
    // Clean up child devices
    getChildDevices()?.each {
        try {
            deleteChildDevice(it.deviceNetworkId)
            logDebug("Deleted child device: ${it.deviceNetworkId}")
        } catch (Exception e) {
            logError("Failed to delete child device ${it.deviceNetworkId}: ${e.message}")
        }
    }
}

void initialize() {
    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }
    if (state.aggregateValue == null) { state.aggregateValue = "" }
    if (state.createChild == null) { state.createChild = false }
    if (state.previousExcludedSensors == null) { state.previousExcludedSensors = [] }

    if (!outputSensor && state.createChild && !state.testingInProgress) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
        if (attributeName) {
            subscribe(inputSensors, attributeName, sensorEventHandler)
            logTrace "Subscribed to ${attributeName} events for ${inputSensors.collect { it.displayName}}."
        }
    }

    // Perform initial calculation
    if (!state.testingInProgress) {
        sensorEventHandler()
    }
}

ChildDeviceWrapper fetchChildDevice() {
    String driverName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.driver
    if (!driverName) {
        logError "No driver found for capability: ${selectedSensorCapability}"
        return null
    }
    String deviceName = "${app.id}-${driverName}"
    ChildDeviceWrapper cd = getChildDevice(deviceName)
    if (!cd) {
        try {
            cd = addChildDevice("hubitat", driverName, deviceName, [name: "${app.label} ${driverName}"])
            if (cd) {
                logDebug("Child device ${cd.id} created with driver: ${driverName}.")
                app.updateSetting("outputSensor", [type: selectedSensorCapability, value: cd.id])
            } else {
                logError("Could not create child device")
            }
        } catch (Exception e) {
            logError("Failed to create child device: ${e.message}")
        }
    }
    return cd
}

void sensorEventHandler(Event evt=null) {
    if (evt != null) logTrace "sensorEventHandler() called: ${evt?.name} ${evt?.getDevice().getLabel()} ${evt?.value} ${evt?.descriptionText}"

	if (computeAggregateSensorValue()) {
        DeviceWrapper sensorDevice = outputSensor
        if (!sensorDevice) {
            sensorDevice = fetchChildDevice()
        }
        if (!sensorDevice) {
            logError("No output device to update")
            return
        }

        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability].attribute
        sensorDevice.sendEvent(
            name: attributeName,
            value: state.aggregateValue,
            descriptionText: "${sensorDevice.displayName} was set to ${state.aggregateValue}"
        )
        logDebug("Updated ${sensorDevice.displayName} ${attributeName} to ${state.aggregateValue}")
    }
}


boolean computeAggregateSensorValue() {
    Date now = new Date()
    Date timeAgo = new Date(now.time - excludeAfter * 60 * 1000)
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
    def possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values

    List<DeviceWrapper> includedSensors = []
    List<DeviceWrapper> excludedSensors = []

    inputSensors.each {
        Date lastActivity = it.getLastActivity()
        if (lastActivity > timeAgo) {
            if (it.currentValue(attributeName) != null) {
                includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last activity ${lastActivity}")
            }
        } else {
            excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - no activity since $timeAgo (last active ${lastActivity})")
        }
    }

    int n = includedSensors.size()

    // Store previous values for comparison
    String previousValue = state.aggregateValue
    List<String> previouslyExcludedLabels = state.excludedSensors ?: []
    List<String> currentlyExcludedLabels = excludedSensors.collect { it.getLabel() }

    // Check for newly excluded sensors
    List<String> newlyExcluded = currentlyExcludedLabels - previouslyExcludedLabels
    if (newlyExcluded.size() > 0 && notificationDevice && notifyOnFirstExcluded) {
        String message = "Sensor Aggregator '${app.label}': Sensors excluded due to inactivity: ${newlyExcluded.join(', ')}"
        notificationDevice.deviceNotification(message)
        logWarn(message)
    }

    if (n < 1) {
        if (notificationDevice && notifyOnAllExcluded && previouslyExcludedLabels.size() < currentlyExcludedLabels.size()) {
            String message = "Sensor Aggregator '${app.label}': All sensors excluded due to inactivity"
            notificationDevice.deviceNotification(message)
            logWarn(message)
        }
        logError "No sensors available for aggregation... aggregate value not updated (${state.aggregateValue})"
        state.excludedSensors = currentlyExcludedLabels
        return false
    }

    def sensorValues = includedSensors.collect { it.currentValue(attributeName) }
    def targetValue = attributeValue
    def oppositeValue = possibleValues.find { it != targetValue }

    // Compute aggregate value based on method
    switch (aggregationMethod) {
        case "all":
            state.aggregateValue = sensorValues.every { it == targetValue } ? targetValue : oppositeValue
            break

        case "majority":
            int countTarget = sensorValues.count { it == targetValue }
            state.aggregateValue = (countTarget > n / 2) ? targetValue : oppositeValue
            break

        case "threshold":
            int countTarget = sensorValues.count { it == targetValue }
            double percentage = (countTarget / (double)n) * 100.0
            state.aggregateValue = (percentage >= thresholdPercent) ? targetValue : oppositeValue
            logDebug("Threshold check: ${countTarget}/${n} = ${roundToDecimalPlaces(percentage, 1)}% (threshold: ${thresholdPercent}%)")
            break

        case "any":
        default:
            state.aggregateValue = sensorValues.any { it == targetValue } ? targetValue : oppositeValue
            break
    }

    state.includedSensors = includedSensors.collect { it.getLabel() }
    state.excludedSensors = currentlyExcludedLabels

    // Check if value changed
    boolean valueChanged = (previousValue != state.aggregateValue)
    if (valueChanged) {
        logInfo("Aggregate value changed: ${previousValue} → ${state.aggregateValue}")
        if (notificationDevice && notifyOnValueChange) {
            String message = "Sensor Aggregator '${app.label}': Value changed from ${previousValue} to ${state.aggregateValue}"
            notificationDevice.deviceNotification(message)
        }
    }

    logStatistics()
    return valueChanged
}

void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "forceUpdate":
            logInfo("Force update triggered")
            updated()
            break
        case "runSmokeTests":
            runSmokeTests()
            break
        case "runAllTests":
            runAllTests()
            break
        case "cleanupTestDevices":
            cleanupTestDevices()
            break
    }
}

void logStatistics() {
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability].attribute
    int includedCount = state.includedSensors?.size() ?: 0
    int totalCount = inputSensors?.size() ?: 0

    String methodDisplay = aggregationMethod
    if (aggregationMethod == "threshold") {
        methodDisplay = "${aggregationMethod} (${thresholdPercent}%)"
    }

    logInfo("${attributeName} ${methodDisplay} (${includedCount}/${totalCount}): ${state.aggregateValue}")

    if (includedCount > 0) {
        logDebug("Aggregated sensors: ${state.includedSensors.join(', ')}")
    } else {
        logDebug("No aggregated sensors!")
    }

    if (state.excludedSensors?.size() > 0) {
        logDebug("Excluded sensors (no activity in last ${excludeAfter} min): ${state.excludedSensors.join(', ')}")
    }
}

private List<String> getAttributePossibleValues(deviceWrapper, capability) {
    List capabilities = deviceWrapper.getCapabilities()
    def targetCapability = capabilities.find { it.name.toLowerCase() == capability.substring("capability.".length()).toLowerCase() }
    def targetAttribute = deviceWrapper.getSupportedAttributes().find { it.name == targetCapability.attributes[0].name }
    return targetAttribute.getValues()
}

private void logObjectProperties(obj) {
    if (obj == null) return
    log.debug "${getObjectClassName(obj)} BEGIN"
    obj.properties.each { property, value ->
        log.debug "${property}=${value}"
    }
    log.debug "${getObjectClassName(obj)} END"
}

@CompileStatic
private double roundToDecimalPlaces(double decimalNumber, int decimalPlaces = 2) {
    double scale = Math.pow(10, decimalPlaces)
    return (Math.round(decimalNumber * scale) as double) / scale
}

private void logError(Object... args) {
    log.error(args)
}

private void logWarn(Object... args) {
    log.warn(args)
}

private void logInfo(Object... args) {
    if (logLevel in ["info","debug","trace"]) log.info(args)
}

private void logDebug(Object... args) {
    if (logLevel in ["debug","trace"]) log.debug(args)
}

private void logTrace(Object... args) {
    if (logLevel in ["trace"]) log.trace(args)
}

// ============================================================================
// TESTING FRAMEWORK
// ============================================================================

void runSmokeTests() {
    logInfo "=" * 60
    logInfo "STARTING SMOKE TESTS"
    logInfo "=" * 60

    if (!setupTestEnvironment()) {
        logError "Failed to setup test environment"
        return
    }

    initializeTestRun()

    // Run essential tests
    test_ANY_OneOpen()
    test_ALL_AllMatch()
    test_Majority_60Percent()

    finalizeTestRun("SMOKE TESTS")
    teardownTestEnvironment()
}

void runAllTests() {
    logInfo "=" * 60
    logInfo "STARTING FULL TEST SUITE"
    logInfo "=" * 60

    if (!setupTestEnvironment()) {
        logError "Failed to setup test environment"
        return
    }

    initializeTestRun()

    // Suite 1: Basic Aggregation
    logInfo ""
    logInfo "-" * 60
    logInfo "TEST SUITE 1: Basic Aggregation Methods"
    logInfo "-" * 60
    test_ANY_AllClosed()
    test_ANY_OneOpen()
    test_ALL_NotAll()
    test_ALL_AllMatch()

    // Suite 2: Voting Methods
    logInfo ""
    logInfo "-" * 60
    logInfo "TEST SUITE 2: Voting Methods"
    logInfo "-" * 60
    test_Majority_60Percent()
    test_Majority_40Percent()
    test_Threshold_AtThreshold()
    test_Threshold_Below()

    // Suite 3: Value Changes
    logInfo ""
    logInfo "-" * 60
    logInfo "TEST SUITE 3: Value Change Detection"
    logInfo "-" * 60
    test_ValueChanges()

    finalizeTestRun("FULL TEST SUITE")
    teardownTestEnvironment()
}

boolean setupTestEnvironment() {
    logInfo "Setting up test environment..."

    state.testingInProgress = true

    // Save current configuration
    state.savedConfig = [
        selectedSensorCapability: selectedSensorCapability,
        inputSensors: inputSensors?.collect { it.id },
        outputSensor: outputSensor?.id,
        attributeValue: attributeValue,
        aggregationMethod: aggregationMethod,
        thresholdPercent: thresholdPercent,
        excludeAfter: excludeAfter
    ]

    // Create test devices
    if (!createTestDevices()) {
        return false
    }

    // Configure app for testing
    app.updateSetting("selectedSensorCapability", [type: "enum", value: "capability.contactSensor"])
    app.updateSetting("attributeValue", [type: "enum", value: "open"])
    app.updateSetting("excludeAfter", [type: "number", value: 60])

    // Set input sensors to the test devices
    List<String> testInputIds = []
    for (int i = 1; i <= 5; i++) {
        String dni = "test-input-${app.id}-${i}"
        def device = getChildDevice(dni)
        if (device) {
            testInputIds << device.id.toString()
        }
    }

    if (testInputIds.size() != 5) {
        logError "Failed to get all test input device IDs"
        return false
    }

    app.updateSetting("inputSensors", [type: "capability.contactSensor", value: testInputIds])

    // Create test output device
    String outputDni = "test-output-${app.id}"
    def existingOutput = getChildDevice(outputDni)
    if (existingOutput) {
        deleteChildDevice(outputDni)
        pauseExecution(500)
    }

    def outputDevice = addChildDevice("hubitat", "Virtual Contact Sensor", outputDni,
                                      [name: "Test Output", label: "Test Output"])
    if (!outputDevice) {
        logError "Failed to create test output device"
        return false
    }

    outputDevice.close()
    app.updateSetting("outputSensor", [type: "capability.contactSensor", value: outputDevice.id.toString()])

    state.testOutputDeviceId = outputDevice.id

    pauseExecution(1000)

    logInfo "Test environment setup complete"
    return true
}

void teardownTestEnvironment() {
    logInfo "Tearing down test environment..."

    // Restore original configuration
    if (state.savedConfig) {
        app.updateSetting("selectedSensorCapability", [type: "enum", value: state.savedConfig.selectedSensorCapability])

        if (state.savedConfig.inputSensors) {
            app.updateSetting("inputSensors", [type: state.savedConfig.selectedSensorCapability,
                                                value: state.savedConfig.inputSensors])
        }

        if (state.savedConfig.outputSensor) {
            app.updateSetting("outputSensor", [type: state.savedConfig.selectedSensorCapability,
                                                value: state.savedConfig.outputSensor])
        }

        if (state.savedConfig.attributeValue) {
            app.updateSetting("attributeValue", [type: "enum", value: state.savedConfig.attributeValue])
        }

        if (state.savedConfig.aggregationMethod) {
            app.updateSetting("aggregationMethod", [type: "enum", value: state.savedConfig.aggregationMethod])
        }

        if (state.savedConfig.thresholdPercent) {
            app.updateSetting("thresholdPercent", [type: "number", value: state.savedConfig.thresholdPercent])
        }

        if (state.savedConfig.excludeAfter) {
            app.updateSetting("excludeAfter", [type: "number", value: state.savedConfig.excludeAfter])
        }
    }

    // Clean up test devices
    cleanupTestDevices()

    state.testingInProgress = false
    state.remove('savedConfig')
    state.remove('testOutputDeviceId')

    // Reinitialize with original config
    updated()

    logInfo "Test environment teardown complete"
}

boolean createTestDevices() {
    logInfo "Creating test devices..."

    try {
        // Create 5 test input sensors
        for (int i = 1; i <= 5; i++) {
            String dni = "test-input-${app.id}-${i}"
            def existingDevice = getChildDevice(dni)

            if (!existingDevice) {
                def newDevice = addChildDevice(
                    "hubitat",
                    "Virtual Contact Sensor",
                    dni,
                    [name: "Test Input ${i}", label: "Test Input ${i}"]
                )

                if (newDevice) {
                    newDevice.close() // Initialize to closed
                    logInfo "Created: Test Input ${i}"
                } else {
                    logError "Failed to create Test Input ${i}"
                    return false
                }
            } else {
                existingDevice.close()
                logInfo "Using existing: Test Input ${i}"
            }
        }

        pauseExecution(1000)
        logInfo "Test device creation complete"
        return true

    } catch (Exception e) {
        logError "Failed to create test devices: ${e.message}"
        e.printStackTrace()
        return false
    }
}

void cleanupTestDevices() {
    logInfo "Cleaning up test devices..."

    try {
        // Delete input sensors
        for (int i = 1; i <= 5; i++) {
            String dni = "test-input-${app.id}-${i}"
            def testDevice = getChildDevice(dni)

            if (testDevice) {
                deleteChildDevice(dni)
                logInfo "Deleted: Test Input ${i}"
            }
        }

        // Delete output sensor
        String outputDni = "test-output-${app.id}"
        def outputDevice = getChildDevice(outputDni)
        if (outputDevice) {
            deleteChildDevice(outputDni)
            logInfo "Deleted: Test Output"
        }

        logInfo "Test device cleanup complete"

    } catch (Exception e) {
        logError "Failed to cleanup test devices: ${e.message}"
    }
}

void initializeTestRun() {
    state.testsPassed = 0
    state.testsFailed = 0
    state.testsTotal = 0
    state.failedTests = []
    state.testResults = [:]
}

void finalizeTestRun(String suiteName) {
    logInfo "=" * 60
    logInfo "${suiteName} COMPLETE"
    logInfo "Total Tests: ${state.testsTotal}"
    logInfo "Passed: ${state.testsPassed}"
    logInfo "Failed: ${state.testsFailed}"

    if (state.testsFailed > 0) {
        logWarn "Failed Tests:"
        state.failedTests.each { logWarn "  - ${it}" }
    }

    logInfo "=" * 60

    state.lastTestRun = new Date().format("yyyy-MM-dd HH:mm:ss")
}

void configureForTest(Map config) {
    if (config.aggregationMethod) {
        app.updateSetting("aggregationMethod", [type: "enum", value: config.aggregationMethod])
    }
    if (config.thresholdPercent != null) {
        app.updateSetting("thresholdPercent", [type: "number", value: config.thresholdPercent])
    }

    pauseExecution(500)
    initialize()
    pauseExecution(500)
}

void setTestInputSensors(List<Integer> openIndices) {
    List<ChildDeviceWrapper> devices = []

    for (int i = 1; i <= 5; i++) {
        String dni = "test-input-${app.id}-${i}"
        def device = getChildDevice(dni)
        if (device) {
            devices << device
        }
    }

    if (devices.size() != 5) {
        logError "Could not find all test input devices"
        return
    }

    // Set all to closed first
    devices.each { it.close() }
    pauseExecution(500)

    // Open specified sensors
    openIndices.each { index ->
        if (index >= 0 && index < devices.size()) {
            devices[index].open()
        }
    }

    pauseExecution(1500) // Allow events to propagate and aggregation to compute
}

boolean assertAggregateValue(String expected, String testName, boolean countAsTest = true) {
    pauseExecution(500)

    def outputDevice = getChildDevice("test-output-${app.id}")
    if (!outputDevice) {
        logError "Test output device not found!"
        if (countAsTest) {
            state.testsFailed++
            state.failedTests << testName
        }
        return false
    }

    String actual = outputDevice.currentValue("contact")
    boolean passed = (actual == expected)

    if (countAsTest) {
        state.testsTotal++
        if (passed) {
            state.testsPassed++
            logInfo "✓ PASS: ${testName} - Output: ${actual}"
        } else {
            state.testsFailed++
            state.failedTests << testName
            logError "✗ FAIL: ${testName} - Expected: ${expected}, Got: ${actual}"
            logDebug "  State aggregateValue: ${state.aggregateValue}"
            logDebug "  Aggregation method: ${aggregationMethod}"
        }

        state.testResults[testName] = [
            passed: passed,
            expected: expected,
            actual: actual
        ]
    } else {
        // Pre-condition check
        if (passed) {
            logDebug "Pre-condition verified: Output is '${actual}' as expected"
        } else {
            logWarn "Pre-condition check failed: Expected '${expected}', got '${actual}'"
        }
    }

    return passed
}

void verifyInitialCondition(List<Integer> sensorConfig, String expectedValue) {
    setTestInputSensors(sensorConfig)
    assertAggregateValue(expectedValue, "Pre-condition", false)
}

// ============================================================================
// INDIVIDUAL TEST CASES
// ============================================================================

void test_ANY_AllClosed() {
    String testName = "Test 1.1: ANY Method - All Closed"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "any"])

    // Verify initial condition should give opposite result
    verifyInitialCondition([0], "open")

    // Now test the actual condition
    setTestInputSensors([])
    assertAggregateValue("closed", testName)
}

void test_ANY_OneOpen() {
    String testName = "Test 1.2: ANY Method - One Open"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "any"])

    // Verify initial condition should give opposite result
    verifyInitialCondition([], "closed")

    // Now test the actual condition
    setTestInputSensors([0])
    assertAggregateValue("open", testName)
}

void test_ALL_NotAll() {
    String testName = "Test 1.3: ALL Method - Not All Match"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "all"])

    // Verify initial condition should give opposite result
    verifyInitialCondition([0, 1, 2, 3, 4], "open")

    // Now test the actual condition
    setTestInputSensors([0, 1, 2, 3])
    assertAggregateValue("closed", testName)
}

void test_ALL_AllMatch() {
    String testName = "Test 1.4: ALL Method - All Match"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "all"])

    // Verify initial condition should give opposite result
    verifyInitialCondition([0, 1, 2], "closed")

    // Now test the actual condition
    setTestInputSensors([0, 1, 2, 3, 4])
    assertAggregateValue("open", testName)
}

void test_Majority_60Percent() {
    String testName = "Test 2.1: MAJORITY - 3 of 5 Open (60%)"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "majority"])

    // Verify initial condition (minority)
    verifyInitialCondition([0, 1], "closed")

    // Now test the actual condition (majority)
    setTestInputSensors([0, 1, 2])
    assertAggregateValue("open", testName)
}

void test_Majority_40Percent() {
    String testName = "Test 2.2: MAJORITY - 2 of 5 Open (40%)"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "majority"])

    // Verify initial condition (majority)
    verifyInitialCondition([0, 1, 2], "open")

    // Now test the actual condition (minority)
    setTestInputSensors([0, 1])
    assertAggregateValue("closed", testName)
}

void test_Threshold_AtThreshold() {
    String testName = "Test 2.3: THRESHOLD - At 60% Threshold"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "threshold", thresholdPercent: 60])

    // Verify initial condition (below threshold)
    verifyInitialCondition([0, 1], "closed")

    // Now test the actual condition (at threshold)
    setTestInputSensors([0, 1, 2])
    assertAggregateValue("open", testName)
}

void test_Threshold_Below() {
    String testName = "Test 2.4: THRESHOLD - Below 80% Threshold"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "threshold", thresholdPercent: 80])

    // Verify initial condition (at threshold)
    verifyInitialCondition([0, 1, 2, 3], "open")

    // Now test the actual condition (below threshold)
    setTestInputSensors([0, 1, 2])
    assertAggregateValue("closed", testName)
}

void test_ValueChanges() {
    String testName = "Test 3.1: Value Changes"
    logInfo ""
    logInfo "Running: ${testName}"

    configureForTest([aggregationMethod: "any"])

    // Verify initial condition
    verifyInitialCondition([0], "open")

    // Test transitions
    setTestInputSensors([])
    boolean step1 = assertAggregateValue("closed", "${testName} - Change to Closed")

    setTestInputSensors([0])
    boolean step2 = assertAggregateValue("open", "${testName} - Change to Open")

    setTestInputSensors([])
    boolean step3 = assertAggregateValue("closed", "${testName} - Change to Closed Again")

    if (!step1 || !step2 || !step3) {
        logWarn "Overall test had failures in one or more steps"
    }
}
