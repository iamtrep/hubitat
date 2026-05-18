// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Sensor Aggregator Discrete Child

 An app that allows aggregating discrete sensor values and saving the result to a virtual device
 */
definition(
    name: "Sensor Aggregator Discrete Child",
    namespace: "iamtrep",
    parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "Aggregate discrete sensor values (contact, motion, tilt, etc) and save to a single virtual device",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorAggregatorDiscreteChild.groovy"
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
//import com.hubitat.hub.domain.Attribute // only available from 2.4.3.148 onward
//import com.hubitat.hub.domain.Capability // only available from 2.4.3.148 onward
import com.hubitat.hub.domain.Event

@Field static final String child_app_version = "0.3.2"

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
                    List<String> attributePossibleValues = getAttributePossibleValues(inputSensors[0], selectedSensorCapability)
                    input name: "attributeValue", type: "enum", options: attributePossibleValues, title: "Attribute value to monitor", multiple:false, required:true
                }
                input name: "outputSensor", type: selectedSensorCapability, title: "Virtual sensor to set as aggregation output", multiple: false, required: false, submitOnChange: true
                if (!outputSensor) {
                    input "createChildSensorDevice", "bool", title: "Create Child Device if no Output Device selected", defaultValue: state.createChild, required: true, submitOnChange: true
                    state.createChild = createChildSensorDevice
                    paragraph "<a href='/device/addDevice' target='_blank'>Create a new virtual device</a>"
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
        section(title: "Sticky trigger (\"and stays\")", hideable: true, hidden: true) {
            paragraph "Mirrors Rule Machine's per-trigger 'And stays?' option, applied per sensor before aggregation. A sensor's state change must persist for the configured window before being committed (symmetric — applies in both directions). Composing this filter across multiple sensors and an aggregation method is the gap RM cannot bridge."
            input name: "defaultStaysSeconds", type: "number", title: "Default \"and stays\" window (seconds, 0 = disabled)", defaultValue: 0, range: "0..3600", required: true, submitOnChange: true
            if (defaultStaysSeconds && (defaultStaysSeconds as int) > 0 && inputSensors) {
                paragraph "<i>Per-sensor overrides (leave blank to use the default; set to 0 to disable for that sensor):</i>"
                inputSensors.each { sensor ->
                    input name: "staysOverride_${sensor.id}", type: "number", title: "Override for ${sensor.displayName} (seconds)", required: false, range: "0..3600"
                }
            }
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update aggregate value"
            if(inputSensors && state.aggregateValue != null) {
                paragraph "Current aggregate value: <b>${state.aggregateValue}</b>"
                paragraph "Included sensors: ${state.includedSensors?.size() ?: 0} of ${inputSensors.size()}"
                if (state.excludedSensors?.size() > 0) {
                    Map<String, Long> sensorMap = inputSensors.collectEntries { [(it.getLabel()): it.id] }
                    List<String> excludedLinks = state.excludedSensors.collect { label ->
                        "<a href='/device/edit/${sensorMap[label]}' target='_blank'>${label}</a>"
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
        }
        section(title: "Testing", hideable: true, hidden: true) {
            paragraph "<b>Automated Testing</b>"
            paragraph "Run automated tests to verify the aggregation logic. This will create test devices, run tests, and clean up automatically."
            input name: "runSmokeTests", type: "button", title: "Run Quick Smoke Tests (3 tests)"
            input name: "runAllTests", type: "button", title: "Run Full Test Suite (~40 tests)"
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
    if (atomicState.stuckState == null) { atomicState.stuckState = [:] }
    if (atomicState.pendingSeq == null) { atomicState.pendingSeq = [:] }

    if (!outputSensor && state.createChild && !state.testingInProgress) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
        if (attributeName) {
            // Always re-seed stuck state from current live values on init/updated.
            // Any pending sticky timers from the previous configuration will fire and
            // find a stale seq (we reset pendingSeq) — they'll no-op.
            Map<String, String> seededStuck = [:]
            inputSensors.each { sensor ->
                String sid = sensor.id as String
                String liveValue = sensor.currentValue(attributeName) as String
                if (liveValue != null) seededStuck[sid] = liveValue
            }
            atomicState.stuckState = seededStuck
            atomicState.pendingSeq = [:]

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
    if (evt == null) {
        // Manual recompute (forceUpdate, initial calculation) — skip sticky filter
        publishAggregate()
        return
    }

    // Defensive — these maps are normally seeded by initialize(), but a fresh code
    // push doesn't fire updated(), so we may receive events on an upgraded instance
    // before re-save. Idempotent.
    if (atomicState.stuckState == null) atomicState.stuckState = [:]
    if (atomicState.pendingSeq == null) atomicState.pendingSeq = [:]

    logTrace "sensorEventHandler() called: ${evt.name} ${evt.getDevice().getLabel()} ${evt.value} ${evt.descriptionText}"

    String sid = evt.device.id as String
    String newValue = evt.value as String
    int seconds = effectiveStaysSeconds(evt.device)

    if (seconds == 0) {
        commitState(sid, newValue)
        return
    }

    Map pending = (atomicState.pendingSeq as Map) ?: [:]
    int seq = ((pending[sid] ?: 0) as int) + 1
    atomicState.pendingSeq = pending + [(sid): seq]
    runIn(seconds, "commitStuckState",
          [data: [sensorId: sid, seq: seq, value: newValue], overwrite: false])
    logDebug "Sticky scheduled: ${evt.getDevice().getLabel()} -> ${newValue} in ${seconds}s (seq ${seq})"
}

void commitStuckState(Map data) {
    if (atomicState.pendingSeq == null) atomicState.pendingSeq = [:]
    if (atomicState.stuckState == null) atomicState.stuckState = [:]
    String sid = data.sensorId as String
    Integer expectedSeq = (atomicState.pendingSeq as Map)?.get(sid) as Integer
    if (expectedSeq == null || (data.seq as int) != expectedSeq) {
        logTrace "Stale sticky commit ignored: sensor ${sid} seq ${data.seq} (current ${expectedSeq})"
        return
    }
    commitState(sid, data.value as String)
}

private void commitState(String sid, String newValue) {
    Map<String, String> current = ((atomicState.stuckState ?: [:]) as Map<String, String>)
    if (current[sid] == newValue) {
        logTrace "Sticky commit no-op: sensor ${sid} already ${newValue}"
        return
    }
    atomicState.stuckState = current + [(sid): newValue]
    logDebug "Sticky committed: sensor ${sid} -> ${newValue}"
    publishAggregate()
}

private void publishAggregate() {
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

private int effectiveStaysSeconds(sensor) {
    if (sensor == null) return 0
    Integer override = settings["staysOverride_${sensor.id}"] as Integer
    if (override != null) return override as int
    return (settings.defaultStaysSeconds ?: 0) as int
}

private String stuckValueFor(DeviceWrapper sensor, String attributeName) {
    String stuck = atomicState.stuckState?.get(sensor.id as String)
    return stuck != null ? stuck : (sensor.currentValue(attributeName) as String)
}

private List<DeviceWrapper> refreshIncludedSensors() {
    Date now = new Date()
    int excludeAfterMin = ((excludeAfter ?: 60) as Integer)
    Date timeAgo = new Date(now.time - excludeAfterMin * 60 * 1000)
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute

    List<DeviceWrapper> includedSensors = []
    List<DeviceWrapper> excludedSensors = []

    inputSensors.each {
        Date lastActivity = it.getLastActivity()
        if (lastActivity > timeAgo) {
            if (stuckValueFor(it, attributeName) != null) {
                includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${stuckValueFor(it, attributeName)}) - last activity ${lastActivity}")
            }
        } else {
            excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${stuckValueFor(it, attributeName)}) - no activity since $timeAgo (last active ${lastActivity})")
        }
    }

    // Store previous values for comparison
    List<String> previouslyExcludedLabels = state.excludedSensors ?: []
    List<String> currentlyExcludedLabels = excludedSensors.collect { it.getLabel() }

    // Check for newly excluded sensors
    List<String> newlyExcluded = currentlyExcludedLabels - previouslyExcludedLabels
    if (newlyExcluded.size() > 0) {
        String message = "Sensor Aggregator '${app.label}': Sensors excluded due to inactivity: ${newlyExcluded.join(', ')}"
        if (notificationDevice && notifyOnFirstExcluded) {
            notificationDevice.deviceNotification(message)
        }
        logDebug(message)
    }

    // Notify if all sensors excluded
    if (includedSensors.size() < 1) {
        String message = "Sensor Aggregator '${app.label}': All sensors excluded due to inactivity"
        if (notificationDevice && notifyOnAllExcluded && previouslyExcludedLabels.size() < currentlyExcludedLabels.size()) {
            notificationDevice.deviceNotification(message)
        }
        logWarn(message)
    }

    // Update state
    state.includedSensors = includedSensors.collect { it.getLabel() }
    state.excludedSensors = currentlyExcludedLabels

    return includedSensors
}

private boolean computeAggregateSensorValue() {
    List<DeviceWrapper> includedSensors = refreshIncludedSensors()

    Integer n = includedSensors.size()

    if (n < 1) {
        logError "No sensors available for aggregation... aggregate value not updated (${state.aggregateValue})"
        return false
    }

    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
    List<String> possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values
    List<Object> sensorValues = includedSensors.collect { stuckValueFor(it, attributeName) }
    String targetValue = attributeValue
    String oppositeValue = possibleValues.find { it != targetValue }

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

    logStatistics()
    return true
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

private List<String> getAttributePossibleValues(DeviceWrapper deviceWrapper, String capability) {
    String capName = capability?.substring("capability.".length())?.toLowerCase()
    String attrName = deviceWrapper.getCapabilities()?.find {
        it.name.toLowerCase() == capName
    }?.attributes?.getAt(0)?.name
    if (!attrName) return null
    return deviceWrapper.getSupportedAttributes().find { it.name == attrName }?.getValues()
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
    test_AttributeValue_TargetClosed()
    test_AllSensorsExcluded()

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

    // Suite 4: Sticky Trigger
    runStaysTests()

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
        excludeAfter: excludeAfter,
        defaultStaysSeconds: settings.defaultStaysSeconds
    ]

    // Zero out sticky filter during the suite — sticky tests opt in via prepareStickyTest()
    app.updateSetting("defaultStaysSeconds", [type: "number", value: 0])

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
        ChildDeviceWrapper device = getChildDevice(dni)
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
    ChildDeviceWrapper existingOutput = getChildDevice(outputDni)
    if (existingOutput) {
        deleteChildDevice(outputDni)
        pauseExecution(500)
    }

    ChildDeviceWrapper outputDevice = addChildDevice("hubitat", "Virtual Contact Sensor", outputDni,
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

        // Restore sticky setting (may be null/0 — both valid)
        Integer savedStays = state.savedConfig.defaultStaysSeconds as Integer
        if (savedStays != null) {
            app.updateSetting("defaultStaysSeconds", [type: "number", value: savedStays])
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
            ChildDeviceWrapper existingDevice = getChildDevice(dni)

            if (!existingDevice) {
                ChildDeviceWrapper newDevice = addChildDevice(
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
            ChildDeviceWrapper testDevice = getChildDevice(dni)

            if (testDevice) {
                deleteChildDevice(dni)
                logInfo "Deleted: Test Input ${i}"
            }
        }

        // Delete output sensor
        String outputDni = "test-output-${app.id}"
        ChildDeviceWrapper outputDevice = getChildDevice(outputDni)
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
    if (config.attributeValue) {
        app.updateSetting("attributeValue", [type: "enum", value: config.attributeValue])
    }
    if (config.containsKey('defaultStaysSeconds')) {
        app.updateSetting("defaultStaysSeconds", [type: "number", value: (config.defaultStaysSeconds ?: 0)])
    }
    // staysOverrides: Map<Integer testSensorIndex (1..5), Integer seconds or null to clear>
    if (config.staysOverrides != null) {
        for (int i = 1; i <= 5; i++) {
            String dni = "test-input-${app.id}-${i}"
            ChildDeviceWrapper d = getChildDevice(dni)
            if (d == null) continue
            String key = "staysOverride_${d.id}"
            if ((config.staysOverrides as Map).containsKey(i)) {
                Integer v = (config.staysOverrides as Map)[i] as Integer
                if (v == null) {
                    app.removeSetting(key)
                } else {
                    app.updateSetting(key, [type: "number", value: v])
                }
            } else {
                app.removeSetting(key)
            }
        }
    }
    if (config.clearStuckState) {
        atomicState.stuckState = [:]
        atomicState.pendingSeq = [:]
    }
    if (config.containsKey('excludeAfter')) {
        app.updateSetting("excludeAfter", [type: "number", value: config.excludeAfter])
    }

    pauseExecution(500)
    initialize()
    pauseExecution(500)
}

ChildDeviceWrapper getTestSensor(int oneBasedIndex) {
    return getChildDevice("test-input-${app.id}-${oneBasedIndex}")
}

void setStaysSeconds(int seconds) {
    app.updateSetting("defaultStaysSeconds", [type: "number", value: seconds])
}

void setTestInputSensors(List<Integer> openIndices) {
    List<ChildDeviceWrapper> devices = []

    for (int i = 1; i <= 5; i++) {
        String dni = "test-input-${app.id}-${i}"
        ChildDeviceWrapper device = getChildDevice(dni)
        if (device) {
            devices << device
        }
    }

    if (devices.size() != 5) {
        logError "Could not find all test input devices"
        return
    }

    // Set all to closed first. Pace events apart — real-world sensor activity is
    // never sub-millisecond, and bunching events triggers concurrent handler races
    // (multiple commitState calls reading the same atomicState snapshot).
    devices.each { it.close(); pauseExecution(150) }
    pauseExecution(400)

    // Open specified sensors (same pacing).
    openIndices.each { index ->
        if (index >= 0 && index < devices.size()) {
            devices[index].open()
            pauseExecution(150)
        }
    }

    pauseExecution(1200) // Allow remaining events to propagate and aggregation to settle
}

boolean assertAggregateValue(String expected, String testName, boolean countAsTest = true) {
    pauseExecution(500)

    ChildDeviceWrapper outputDevice = getChildDevice("test-output-${app.id}")
    if (!outputDevice) {
        logError "Test output device not found!"
        if (countAsTest) {
            state.testsFailed++
            state.failedTests = state.failedTests + [testName]
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
            state.failedTests = state.failedTests + [testName]
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

// ============================================================================
// STICKY TRIGGER ("AND STAYS") TEST SUITE
// ============================================================================

void runStaysTests() {
    logInfo ""
    logInfo "-" * 60
    logInfo "TEST SUITE 4: Sticky Trigger (and stays)"
    logInfo "-" * 60

    test_Stays_BypassWhenZero()
    test_Stays_BasicStick()
    test_Stays_FlickerSuppressed()
    test_Stays_RapidFireResetsTimer()
    test_Stays_OverrideTrumpsDefault()
    test_Stays_OverrideZeroDisablesPerSensor()
    test_Stays_BothDirectionsStick()
    test_Stays_OnePerSensorIndependent()
    test_Stays_RemovedSensorIsPruned()
    test_Stays_ExcludeAfterStillWins()
    test_Stays_MajorityWithSticky()
    test_Stays_ThresholdWithSticky()
    test_Stays_OverrideOnlyDefaultZero()
    test_Stays_PendingTimerSurvivesReinit()
    // test_Stays_SameDirectionRepeatDedup intentionally removed:
    // Hubitat's sendEvent dedupes same-value emissions (no isStateChange:true), so the
    // "fire same value twice in a row" scenario can't occur in production. Seq supersession
    // for alternating values is already covered by test_Stays_FlickerSuppressed (4.3) and
    // test_Stays_RapidFireResetsTimer (4.4).
}

private void prepareStickyTest(int defaultSeconds, Map<Integer,Integer> overrides=[:]) {
    // Turn off sticky and reset all sensors to closed via bypass path
    app.updateSetting("defaultStaysSeconds", [type: "number", value: 0])
    pauseExecution(200)
    setTestInputSensors([])  // all closed; bypass=0; aggregate becomes "closed"
    pauseExecution(300)

    // Clear sticky internal state and overrides
    atomicState.stuckState = [:]
    atomicState.pendingSeq = [:]
    for (int i = 1; i <= 5; i++) {
        ChildDeviceWrapper d = getTestSensor(i)
        if (d) app.removeSetting("staysOverride_${d.id}")
    }
    overrides.each { idx, secs ->
        ChildDeviceWrapper d = getTestSensor(idx as int)
        if (d) {
            if (secs == null) {
                app.removeSetting("staysOverride_${d.id}")
            } else {
                app.updateSetting("staysOverride_${d.id}", [type: "number", value: secs])
            }
        }
    }

    // Configure aggregation method and default stays window
    app.updateSetting("aggregationMethod", [type: "enum", value: "any"])
    app.updateSetting("defaultStaysSeconds", [type: "number", value: defaultSeconds])
    pauseExecution(300)
    initialize()
    pauseExecution(500)
}

private void fireSensor(int oneBasedIndex, String newValue) {
    ChildDeviceWrapper d = getTestSensor(oneBasedIndex)
    if (d == null) return
    if (newValue == "open") d.open()
    else if (newValue == "closed") d.close()
}

void test_Stays_BypassWhenZero() {
    String testName = "Test 4.1: Stays - Bypass when 0"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(0)
    fireSensor(1, "open")
    pauseExecution(800)
    assertAggregateValue("open", testName)
}

void test_Stays_BasicStick() {
    String testName = "Test 4.2: Stays - Basic stick"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(3)
    fireSensor(1, "open")
    pauseExecution(1000)
    assertAggregateValue("closed", "${testName} - not yet stuck @1s")
    pauseExecution(3000)
    assertAggregateValue("open", "${testName} - stuck after 4s")
}

void test_Stays_FlickerSuppressed() {
    String testName = "Test 4.3: Stays - Flicker suppressed"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(3)
    fireSensor(1, "open")         // seq=1, fires at +3s
    pauseExecution(1000)
    fireSensor(1, "closed")        // seq=2 (supersedes seq=1), fires at +4s
    pauseExecution(4000)           // wait through both timer fire points
    // seq=1 timer found stale and no-oped; seq=2 timer committed "closed" but value didn't change
    assertAggregateValue("closed", testName)
}

void test_Stays_RapidFireResetsTimer() {
    String testName = "Test 4.4: Stays - Rapid fire, latest seq wins"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(3)
    fireSensor(1, "open")           // t=0, seq=1, fires at t=3
    pauseExecution(1500)
    fireSensor(1, "closed")          // t=1.5, seq=2, fires at t=4.5
    pauseExecution(500)
    fireSensor(1, "open")           // t=2, seq=3, fires at t=5
    pauseExecution(2000)
    // t=4: seq=1 fired at t=3 (stale); seq=2 fires at t=4.5 (still pending); seq=3 fires at t=5
    assertAggregateValue("closed", "${testName} - pre-final-commit")
    pauseExecution(2500)
    // t=6.5: seq=2 fired stale, seq=3 committed "open"
    assertAggregateValue("open", "${testName} - latest seq committed")
}

void test_Stays_OverrideTrumpsDefault() {
    String testName = "Test 4.5: Stays - Override > default"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2, [1: 5])
    fireSensor(1, "open")
    pauseExecution(3000)
    assertAggregateValue("closed", "${testName} - override 5s not yet elapsed at 3s")
    pauseExecution(3000)
    assertAggregateValue("open", "${testName} - committed after override elapsed")
}

void test_Stays_OverrideZeroDisablesPerSensor() {
    String testName = "Test 4.6: Stays - Override 0 disables filter for that sensor"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(5, [1: 0])
    fireSensor(1, "open")
    pauseExecution(800)
    assertAggregateValue("open", testName)
}

void test_Stays_BothDirectionsStick() {
    String testName = "Test 4.7: Stays - Symmetric, both directions"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2)
    fireSensor(1, "open")
    pauseExecution(2500)
    assertAggregateValue("open", "${testName} - open committed")
    fireSensor(1, "closed")
    pauseExecution(1000)
    assertAggregateValue("open", "${testName} - close not yet stuck @1s")
    pauseExecution(2000)
    assertAggregateValue("closed", "${testName} - close stuck after 3s")
}

void test_Stays_OnePerSensorIndependent() {
    String testName = "Test 4.8: Stays - Per-sensor independent timers"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2)
    // Switch to 'all' so the aggregate is sensitive to the last-sticking sensor
    app.updateSetting("aggregationMethod", [type: "enum", value: "all"])
    pauseExecution(300)
    // Open sensors 1..4 at t≈0 (paced 150ms apart to avoid concurrent-handler races —
    // production sensors never fire this close together anyway).
    [1, 2, 3, 4].each { fireSensor(it, "open"); pauseExecution(150) }
    // We are now ~0.6s in; the 2s sticky windows for sensors 1..4 will commit ~t≈2.0–2.6s.
    pauseExecution(400)  // total ~1.0s elapsed
    // Open sensor 5 at t≈1.0
    fireSensor(5, "open")
    pauseExecution(1500)
    // t≈2.5: sensors 1..4 committed (~2.1..2.6s); sensor 5 commits at t≈3.0
    assertAggregateValue("closed", "${testName} - all method: 4/5 stuck, sensor 5 pending")
    pauseExecution(1500)
    // t≈4.0: sensor 5 committed at t≈3.0 -> all open
    assertAggregateValue("open", "${testName} - all method: all 5 stuck")
}

void test_Stays_RemovedSensorIsPruned() {
    String testName = "Test 4.9: Stays - Removed sensor pruned"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2)
    fireSensor(1, "open")
    pauseExecution(2500)
    ChildDeviceWrapper s1 = getTestSensor(1)
    String s1id = s1?.id as String

    // Verify the stuck entry exists pre-prune
    state.testsTotal++
    if ((atomicState.stuckState as Map)?.containsKey(s1id)) {
        state.testsPassed++
        logInfo "✓ PASS: ${testName} - precondition: s1 in stuckState"
    } else {
        state.testsFailed++
        state.failedTests = state.failedTests + ["${testName} - precondition"]
        logError "✗ FAIL: ${testName} - s1 (${s1id}) missing from stuckState: ${atomicState.stuckState}"
    }

    // Drop sensor 1 from inputSensors and re-init
    List<String> remainingIds = []
    for (int i = 2; i <= 5; i++) {
        ChildDeviceWrapper d = getTestSensor(i)
        if (d) remainingIds << (d.id as String)
    }
    app.updateSetting("inputSensors", [type: "capability.contactSensor", value: remainingIds])
    pauseExecution(500)
    initialize()
    pauseExecution(500)

    // Verify pruning
    state.testsTotal++
    boolean stuckPruned = !((atomicState.stuckState as Map)?.containsKey(s1id))
    boolean seqPruned = !((atomicState.pendingSeq as Map)?.containsKey(s1id))
    if (stuckPruned && seqPruned) {
        state.testsPassed++
        logInfo "✓ PASS: ${testName} - s1 pruned from stuckState and pendingSeq"
    } else {
        state.testsFailed++
        state.failedTests = state.failedTests + [testName]
        logError "✗ FAIL: ${testName} - prune failed (stuck=${stuckPruned}, seq=${seqPruned}, stuckState=${atomicState.stuckState}, pendingSeq=${atomicState.pendingSeq})"
    }

    // Restore all 5 sensors for subsequent tests
    List<String> allIds = []
    for (int i = 1; i <= 5; i++) {
        ChildDeviceWrapper d = getTestSensor(i)
        if (d) allIds << (d.id as String)
    }
    app.updateSetting("inputSensors", [type: "capability.contactSensor", value: allIds])
    pauseExecution(500)
    initialize()
    pauseExecution(500)
}

void test_Stays_ExcludeAfterStillWins() {
    String testName = "Test 4.10: Stays - excludeAfter orthogonal"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2)
    // Set excludeAfter to 0 minutes — any non-immediate activity is "stale"
    app.updateSetting("excludeAfter", [type: "number", value: 0])
    pauseExecution(300)
    fireSensor(1, "open")
    pauseExecution(3000)
    // Sensor 1 is "stuck open" in atomicState.stuckState BUT excludeAfter=0 drops it from aggregation
    // computeAggregateSensorValue returns false (no sensors included), aggregate value unchanged from prep ("closed")
    assertAggregateValue("closed", testName)
    // Restore for subsequent tests
    app.updateSetting("excludeAfter", [type: "number", value: 60])
    pauseExecution(300)
}

// ============================================================================
// STICKY TRIGGER COVERAGE EXTENSIONS (4.11 .. 4.15)
// ============================================================================

void test_Stays_MajorityWithSticky() {
    String testName = "Test 4.11: Stays - Majority aggregation uses stuck values"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2)
    app.updateSetting("aggregationMethod", [type: "enum", value: "majority"])
    pauseExecution(300)
    // Open sensors 1 and 2 (2/5 — under majority of >50%)
    fireSensor(1, "open"); pauseExecution(150)
    fireSensor(2, "open")
    pauseExecution(2500)  // both stuck
    assertAggregateValue("closed", "${testName} - 2/5 stuck, below majority")
    // Open sensor 3 — 3/5 once stuck
    fireSensor(3, "open")
    pauseExecution(2500)  // sensor 3 stuck
    assertAggregateValue("open", "${testName} - 3/5 stuck, majority")
}

void test_Stays_ThresholdWithSticky() {
    String testName = "Test 4.12: Stays - Threshold aggregation uses stuck values"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(2)
    app.updateSetting("aggregationMethod", [type: "enum", value: "threshold"])
    app.updateSetting("thresholdPercent", [type: "number", value: 60])
    pauseExecution(300)
    // 2/5 = 40% (under 60%)
    fireSensor(1, "open"); pauseExecution(150)
    fireSensor(2, "open")
    pauseExecution(2500)
    assertAggregateValue("closed", "${testName} - 2/5 (40%) below threshold")
    // 3/5 = 60% (at threshold)
    fireSensor(3, "open")
    pauseExecution(2500)
    assertAggregateValue("open", "${testName} - 3/5 (60%) at threshold")
}

void test_Stays_OverrideOnlyDefaultZero() {
    String testName = "Test 4.13: Stays - Override opts a sensor in when default is 0"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(0, [1: 3])
    // Sensor 2 has default (=0) → immediate commit
    fireSensor(2, "open")
    pauseExecution(800)
    assertAggregateValue("open", "${testName} - sensor 2 (default 0) immediate")
    fireSensor(2, "closed")  // reset state cleanly
    pauseExecution(800)
    assertAggregateValue("closed", "${testName} - sensor 2 back to closed")
    // Sensor 1 has override=3 → must wait 3 s
    fireSensor(1, "open")
    pauseExecution(1000)
    assertAggregateValue("closed", "${testName} - sensor 1 (override 3s) not yet stuck @1s")
    pauseExecution(2500)
    assertAggregateValue("open", "${testName} - sensor 1 stuck after 3.5s")
}

void test_Stays_PendingTimerSurvivesReinit() {
    String testName = "Test 4.15: Stays - Pending sticky timer no-ops after initialize()"
    logInfo ""
    logInfo "Running: ${testName}"
    prepareStickyTest(3)
    // Open then close sensor 1 — leaves two pending sticky commits (seq=1 'open', seq=2 'closed')
    fireSensor(1, "open")
    pauseExecution(500)
    fireSensor(1, "closed")
    pauseExecution(500)
    // T≈1: device is back to "closed". Force re-init.
    initialize()
    pauseExecution(500)
    // initialize() reseeds stuckState[241]="closed" and resets pendingSeq=[:].
    // Both pending runIns (T≈3 and T≈3.5) will find expectedSeq=null → stale → no-op.
    pauseExecution(3500)
    assertAggregateValue("closed", testName)
}

// ============================================================================
// PRE-EXISTING FEATURE COVERAGE EXTENSIONS
// ============================================================================

void test_AttributeValue_TargetClosed() {
    // Existing tests all target "open" — this exercises the inverted path
    String testName = "Test 1.5: Aggregation with target value 'closed' (inverted target)"
    logInfo ""
    logInfo "Running: ${testName}"
    configureForTest([aggregationMethod: "any", attributeValue: "closed"])
    // 4 open + 1 closed: 'any closed' is true → output "closed"
    setTestInputSensors([0, 1, 2, 3])  // sensors 1-4 open, sensor 5 closed
    assertAggregateValue("closed", "${testName} - any=closed: 1 sensor matches")
    // All 5 open: no sensor matches "closed" → output is opposite "open"
    setTestInputSensors([0, 1, 2, 3, 4])
    assertAggregateValue("open", "${testName} - any=closed: 0 sensors match")
    // Restore target back to "open" for downstream tests
    app.updateSetting("attributeValue", [type: "enum", value: "open"])
    pauseExecution(200)
}

void test_AllSensorsExcluded() {
    // With excludeAfter=0, all sensors are stale by definition.
    // computeAggregateSensorValue should return false; output should NOT update.
    String testName = "Test 1.6: All sensors excluded by excludeAfter=0 → no output update"
    logInfo ""
    logInfo "Running: ${testName}"
    configureForTest([aggregationMethod: "any"])
    // Seed a known output value first
    setTestInputSensors([0])
    assertAggregateValue("open", "${testName} - precondition: output 'open'")
    // Now exclude everything by setting excludeAfter to 0 minutes
    app.updateSetting("excludeAfter", [type: "number", value: 0])
    pauseExecution(300)
    // Trigger an event that WOULD transition aggregate to "closed" if any sensor were included
    fireSensor(1, "closed")  // sensor 1 commits closed (sticky bypass)
    pauseExecution(1500)
    // refreshIncludedSensors excludes all (all stale). computeAggregateSensorValue returns false.
    // state.aggregateValue is NOT updated. Output device retains last value ("open").
    assertAggregateValue("open", "${testName} - output unchanged when all excluded")
    // Restore excludeAfter to default for downstream tests
    app.updateSetting("excludeAfter", [type: "number", value: 60])
    pauseExecution(300)
}
