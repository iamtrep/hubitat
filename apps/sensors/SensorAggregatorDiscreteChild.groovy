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
    singleThreaded: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorAggregatorDiscreteChild.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/sensors"
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
//import com.hubitat.hub.domain.Attribute // only available from 2.4.3.148 onward
//import com.hubitat.hub.domain.Capability // only available from 2.4.3.148 onward
import com.hubitat.hub.domain.Event

@Field static final String CODE_VERSION = "0.4.1"
@Field static final Integer QUIET_FLOOR_MIN = 30
@Field static final Integer MIN_GAPS = 5
@Field static final Integer KEEP_GAPS = 48

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
            input name: "excludeAfter", type: "number", title: "Exclude a sensor after at least this many minutes without activity (learned gaps can lengthen it)", defaultValue: 60, range: "0..1440", submitOnChange: true
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
            input name: "forgetGaps", type: "button", title: "Forget the sensors' learned gaps"
            if(inputSensors && state.aggregateValue != null) {
                paragraph "Current aggregate value: <b>${state.aggregateValue}</b>"
                paragraph "Included sensors: ${state.includedSensors?.size() ?: 0} of ${inputSensors.size()}"
                if (state.excludedSensors?.size() > 0) {
                    List<String> excludedLinks = inputSensors.findAll { it.id.toString() in state.excludedSensors }.collect {
                        "<a href='/device/edit/${it.id}' target='_blank'>${it.getLabel()}</a>"
                    }
                    paragraph "<span style='color:var(--hubitat-warning-color, #8a5a00)'><b>Excluded sensors:</b> ${excludedLinks.join(', ')}</span>"
                }
            }
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
        section("Notifications") {
            input name: "notificationDevice", type: "capability.notification", title: "Send notifications to:", multiple: false, required: false
            input name: "notifyOnAllExcluded", type: "bool", title: "Notify when all sensors are excluded", defaultValue: true
            input name: "notifyOnFirstExcluded", type: "bool", title: "Notify when any sensor is excluded", defaultValue: false
            input name: "noSensorSwitch", type: "capability.switch", title: "Switch to turn on while no sensor reports", required: false
        }
    }
}

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    unschedule()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")

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

    initialize()
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void uninstalled() {
    logDebug "uninstalled()"
    noSensorSwitch?.off()
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
    checkVersion(false)
    app.removeSetting("logLevel")
    migrateSensorListsToIds()
    // Left by the in-app test suite removed in 0.4.1
    ["testingInProgress", "savedConfig", "testOutputDeviceId", "testResults", "testsTotal",
     "testsPassed", "testsFailed", "failedTests", "lastTestRun"].each { state.remove(it) }
    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }
    if (state.aggregateValue == null) { state.aggregateValue = "" }
    if (state.createChild == null) { state.createChild = false }
    if (state.stuckState == null) { state.stuckState = [:] }
    if (state.pendingSeq == null) { state.pendingSeq = [:] }
    pruneActivity()

    if (!outputSensor && state.createChild) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
        if (attributeName) {
            // Always re-seed stuck state from current live values on init/updated.
            // Sticky timers from before this call must no-op, so every seq advances
            // past them. Restarting at 1 would let a new event reuse an old timer's seq.
            Map<String, String> seededStuck = [:]
            inputSensors.each { sensor ->
                String sid = sensor.id as String
                String liveValue = sensor.currentValue(attributeName) as String
                if (liveValue != null) seededStuck[sid] = liveValue
            }
            state.stuckState = seededStuck
            state.pendingSeq = advanceAllSeqs(state.pendingSeq as Map)

            subscribe(inputSensors, attributeName, sensorEventHandler)
            logTrace "Subscribed to ${attributeName} events for ${inputSensors.collect { it.displayName}}."
        }
    }

    // Silence sends no event: only a timer notices a sensor that stopped reporting.
    schedule("${new Random().nextInt(60)} */15 * ? * *", "checkSensors")

    // Perform initial calculation
    sensorEventHandler()
}

// Reads cached activity and values only; never commands the sensors.
void checkSensors() {
    checkVersion()
    logSched "check"
    publishAggregate(true)
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    // Event handlers evaluate right after this, before updated() runs
    migrateSensorListsToIds()
    if (reinit) runIn(1, "updated")
}

// Before 0.3.7 the sensor lists held labels. Convert them in place rather than clearing:
// the next evaluation diffs against the stored excluded list, and an empty one would
// re-notify about every sensor that was already excluded.
private void migrateSensorListsToIds() {
    state.remove("previousExcludedSensors")  // written, never read
    Map<String, String> idByLabel = (inputSensors ?: []).collectEntries { [(it.getLabel()): it.id.toString()] }
    Set<String> ids = idByLabel.values() as Set
    boolean converted = false
    ["includedSensors", "excludedSensors"].each { String key ->
        List stored = state[key] as List
        if (!stored || stored.every { it?.toString() in ids }) return
        state[key] = stored.collect { String v = it?.toString(); v in ids ? v : idByLabel[v] }.findAll { it != null }
        converted = true
    }
    if (converted) logWarn "Migrated stored sensor lists from labels to device ids"
}

// Stored sensor lists hold device ids; resolve labels at display time so renames show.
private List<String> sensorLabels(List ids) {
    Map<String, String> labelById = (inputSensors ?: []).collectEntries { [(it.id.toString()): it.getLabel()] }
    return (ids ?: []).collect { labelById[it?.toString()] ?: it?.toString() }
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
    checkVersion()
    if (evt == null) {
        // Manual recompute (forceUpdate, initial calculation) — skip sticky filter
        publishAggregate()
        return
    }

    // Defensive — these maps are normally seeded by initialize(), but a fresh code
    // push doesn't fire updated(), so we may receive events on an upgraded instance
    // before re-save. Idempotent.
    if (state.stuckState == null) state.stuckState = [:]
    if (state.pendingSeq == null) state.pendingSeq = [:]

    logTrace "sensorEventHandler() called: ${evt.name} ${evt.getDevice().getLabel()} ${evt.value} ${evt.descriptionText}"

    String sid = evt.device.id as String
    String newValue = evt.value as String
    int seconds = effectiveStaysSeconds(evt.device)

    if (seconds == 0) {
        commitState(sid, newValue)
        return
    }

    Map pending = withNextSeq(state.pendingSeq as Map, sid)
    int seq = pending[sid] as int
    state.pendingSeq = pending
    runIn(seconds, "commitStuckState",
          [data: [sensorId: sid, seq: seq, value: newValue], overwrite: false])
    logSched "Sticky scheduled: ${evt.getDevice().getLabel()} -> ${newValue} in ${seconds}s (seq ${seq})"
}

void commitStuckState(Map data) {
    checkVersion()
    if (state.pendingSeq == null) state.pendingSeq = [:]
    if (state.stuckState == null) state.stuckState = [:]
    String sid = data.sensorId as String
    if (!commitApplies(state.pendingSeq as Map, sid, data.seq)) {
        logTrace "Stale sticky commit ignored: sensor ${sid} seq ${data.seq} (current ${(state.pendingSeq as Map)?.get(sid)})"
        return
    }
    commitState(sid, data.value as String)
}

private void commitState(String sid, String newValue) {
    Map<String, String> current = ((state.stuckState ?: [:]) as Map<String, String>)
    if (current[sid] == newValue) {
        logTrace "Sticky commit no-op: sensor ${sid} already ${newValue}"
        return
    }
    state.stuckState = current + [(sid): newValue]
    logDebug "Sticky committed: sensor ${sid} -> ${newValue}"
    publishAggregate()
}

private void publishAggregate(boolean onlyIfChanged = false) {
    def before = state.aggregateValue
    if (computeAggregateSensorValue() && !(onlyIfChanged && state.aggregateValue == before)) {
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

private int effectiveStaysSeconds(DeviceWrapper sensor) {
    if (sensor == null) return 0
    return staysSecondsFor(settings["staysOverride_${sensor.id}"], settings.defaultStaysSeconds)
}

private String stuckValueFor(DeviceWrapper sensor, String attributeName) {
    String stuck = state.stuckState?.get(sensor.id as String)
    return stuck != null ? stuck : (sensor.currentValue(attributeName) as String)
}

private List<DeviceWrapper> refreshIncludedSensors() {
    Long t = now()
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
    sampleActivity()
    Map all = (state.sensors ?: [:]) as Map

    List<DeviceWrapper> includedSensors = []
    List<DeviceWrapper> excludedSensors = []

    inputSensors.each {
        String id = it.id.toString()
        Long limit = limitFor(id)
        if (!isQuiet(all[id] as Map, t, limit)) {
            if (stuckValueFor(it, attributeName) != null) {
                includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${stuckValueFor(it, attributeName)}) - last activity ${it.getLastActivity()}")
            }
        } else {
            excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${stuckValueFor(it, attributeName)}) - no activity in ${limit.intdiv(60000L)} min (last active ${it.getLastActivity()})")
        }
    }

    // Store previous values for comparison
    List<String> previouslyExcludedIds = state.excludedSensors ?: []
    List<String> currentlyExcludedIds = excludedSensors.collect { it.id.toString() }

    // Check for newly excluded sensors
    List<String> newlyExcluded = currentlyExcludedIds - previouslyExcludedIds
    if (newlyExcluded.size() > 0) {
        String message = "Sensor Aggregator '${app.label}': Sensors excluded due to inactivity: ${sensorLabels(newlyExcluded).join(', ')}"
        if (notificationDevice && notifyOnFirstExcluded) {
            notificationDevice.deviceNotification(message)
        }
        logDebug(message)
    }

    noteNoSensor(includedSensors.isEmpty() && !currentlyExcludedIds.isEmpty(), currentlyExcludedIds)

    // Update state
    state.includedSensors = includedSensors.collect { it.id.toString() }
    state.excludedSensors = currentlyExcludedIds

    return includedSensors
}

// Notifies and logs once on entry and once on exit; the switch follows the state at every evaluation.
private void noteNoSensor(boolean none, List<String> excludedIds) {
    // Before 0.4.0 there was no flag: derive it from the stored lists so an upgrade doesn't re-notify.
    boolean was = state.noSensor != null ? state.noSensor as boolean : (!state.includedSensors && state.excludedSensors)
    state.noSensor = none
    String change = noSensorChange(was, none)
    if (change == 'entered') {
        String message = "Sensor Aggregator '${app.label}': All sensors excluded due to inactivity"
        if (notificationDevice && notifyOnAllExcluded) notificationDevice.deviceNotification(message)
        logWarn(message)
    } else if (change == 'left') {
        String message = "Sensor Aggregator '${app.label}': Sensors reporting again"
        if (notificationDevice && notifyOnAllExcluded) notificationDevice.deviceNotification(message)
        logInfo(message)
    }
    String want = none ? "on" : "off"
    if (noSensorSwitch && noSensorSwitch.currentValue("switch") != want) {
        logCmd "${noSensorSwitch.displayName} ${want}"
        if (none) noSensorSwitch.on() else noSensorSwitch.off()
    }
}

// ── Sensor activity ───────────────────────────────────────────────────

// getLastActivity() counts every event, battery reports and check-ins included.
private void sampleActivity() {
    Map all = (state.sensors ?: [:]) as Map
    boolean changed = false
    (inputSensors ?: []).each { DeviceWrapper d ->
        Date la = d.getLastActivity()
        if (la == null) return
        String id = d.id.toString()
        Map was = all[id] as Map
        Map upd = recordActivity(was, la.time, KEEP_GAPS)
        if (upd != was) { all[id] = upd; changed = true }
    }
    if (changed) state.sensors = all
}

private Long limitFor(String id) {
    Map s = ((state.sensors ?: [:]) as Map)[id] as Map
    Long fallbackMs = ((excludeAfter ?: 60) as Long) * 60000L
    return quietLimitMs((s?.gaps ?: []) as List, fallbackMs, QUIET_FLOOR_MIN * 60000L, MIN_GAPS)
}

private void forgetLearnedGaps() {
    Map all = [:]
    ((state.sensors ?: [:]) as Map).each { k, v -> all[k] = [last: (v as Map)?.last, gaps: []] }
    state.sensors = all
}

private void pruneActivity() {
    List<String> ids = (inputSensors ?: []).collect { it.id.toString() }
    state.sensors = ((state.sensors ?: [:]) as Map).findAll { k, v -> ids.contains(k.toString()) }
}

private boolean computeAggregateSensorValue() {
    List<DeviceWrapper> includedSensors = refreshIncludedSensors()

    Integer n = includedSensors.size()

    if (n < 1) {
        // All quiet is logged by noteNoSensor() on change; otherwise no sensor has a value yet.
        if (!state.noSensor) logDebug "No sensor value to aggregate; aggregate value not updated (${state.aggregateValue})"
        return false
    }

    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
    List<String> possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values
    List<String> sensorValues = includedSensors.collect { stuckValueFor(it, attributeName) }
    String targetValue = attributeValue
    String oppositeValue = possibleValues.find { it != targetValue }

    state.aggregateValue = aggregateOf(sensorValues, aggregationMethod as String, targetValue, oppositeValue, thresholdPercent as Number)
    if (aggregationMethod == "threshold") {
        int countTarget = sensorValues.count { it == targetValue }
        logDebug("Threshold check: ${countTarget}/${n} = ${roundToDecimalPlaces((countTarget / (double) n) * 100.0, 1)}% (threshold: ${thresholdPercent}%)")
    }

    logStatistics()
    return true
}


void appButtonHandler(String buttonName) {
    checkVersion()
    switch (buttonName) {
        case "forgetGaps":
            forgetLearnedGaps()
            logCfg "forgot the sensors' learned gaps"
            break
        case "forceUpdate":
            logInfo("Force update triggered")
            updated()
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
        logDebug("Aggregated sensors: ${sensorLabels(state.includedSensors).join(', ')}")
    } else {
        logDebug("No aggregated sensors!")
    }

    if (state.excludedSensors?.size() > 0) {
        logDebug("Excluded sensors (no recent activity): ${sensorLabels(state.excludedSensors).join(', ')}")
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
    logTrace "${getObjectClassName(obj)} BEGIN"
    obj.properties.each { property, value ->
        logTrace "${property}=${value}"
    }
    logTrace "${getObjectClassName(obj)} END"
}

@CompileStatic
private double roundToDecimalPlaces(double decimalNumber, int decimalPlaces = 2) {
    double scale = Math.pow(10, decimalPlaces)
    return (Math.round(decimalNumber * scale) as double) / scale
}

// ── Core (pure) ───────────────────────────────────────────────────────
// No settings, state, devices, logging or @Field here: tests/test_core.groovy runs this block off-hub.
// Same helpers as Switched Heater Thermostat; keep the copies in step.

Map recordActivity(Map s, Long at, Integer keep) {
    Long last = (s?.last != null) ? (s.last as Long) : null
    List<Long> gaps = []
    for (Object g : (List) (s?.gaps ?: [])) gaps << (g as Long)
    if (at == null || (last != null && at <= last)) return [last: last, gaps: gaps]
    if (last != null) {
        gaps << (at - last)
        while (gaps.size() > keep) gaps.remove(0)
    }
    return [last: at, gaps: gaps]
}

// Twice the second-longest gap: one outage in the history does not stretch the limit.
// The setting is the minimum: event-only sensors (contacts) learn short gaps from bursts.
Long quietLimitMs(List gaps, Long settingMs, Long floorMs, Integer minGaps) {
    Long least = Math.max(settingMs, floorMs)
    if (gaps == null || gaps.size() < minGaps) return least
    List<Long> sorted = []
    for (Object g : gaps) sorted << (g as Long)
    sorted.sort()
    return Math.max(least, 2L * sorted[sorted.size() - 2])
}

// 'entered' or 'left' when the all-quiet state changes, null while it holds.
String noSensorChange(boolean was, boolean none) {
    if (none == was) return null
    return none ? 'entered' : 'left'
}

boolean isQuiet(Map s, Long now, Long limitMs) {
    if (s?.last == null) return true
    return now - (s.last as Long) > limitMs
}

// ── End core ──────────────────────────────────────────────────────────

// ── Discrete core (pure) ──────────────────────────────────────────────
// Same rules as the core above; tests/test_core.groovy runs this block off-hub too.

// The target value when the method's vote passes, else the opposite value.
String aggregateOf(List<String> values, String method, String target, String opposite, Number thresholdPercent) {
    int n = values.size()
    int hits = values.count { it == target } as int
    boolean pass
    switch (method) {
        case "all": pass = hits == n; break
        case "majority": pass = hits > n / 2; break
        case "threshold": pass = hits * 100.0 / n >= (thresholdPercent ?: 50); break
        default: pass = hits > 0
    }
    return pass ? target : opposite
}

// "And stays": each event takes the sensor's next number, and its delayed
// commit applies only while that number is still the sensor's latest.
Map withNextSeq(Map pending, String sid) {
    Map m = [:]
    if (pending) m.putAll(pending)
    m[sid] = ((m[sid] ?: 0) as int) + 1
    return m
}

boolean commitApplies(Map pending, String sid, Object seq) {
    Object current = pending?.get(sid)
    return current != null && seq != null && (current as int) == (seq as int)
}

// After a re-init every number moves on, so no timer set before it applies.
Map advanceAllSeqs(Map pending) {
    Map m = [:]
    (pending ?: [:]).each { k, v -> m[k] = ((v ?: 0) as int) + 1 }
    return m
}

// A per-sensor override wins, 0 included; otherwise the default.
int staysSecondsFor(Object override, Object defaultSeconds) {
    if (override != null) return override as int
    return (defaultSeconds ?: 0) as int
}

// ── End discrete core ─────────────────────────────────────────────────

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }
