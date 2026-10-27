// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Sensor Aggregator Child

 An app that allows aggregating sensor values and saving the result to a virtual device

 TODO:
 - More device types
 - Figure out Generic Component devices for when child devices are selected
 */
definition(
    name: "Sensor Aggregator Child",
    namespace: "iamtrep",
    parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "Aggregate sensor values and save to a single virtual device",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    // One call per sensor event across many sensors, plus the button: each
    // rewrites the aggregate and stats, so a slower handler could publish a stale value.
    singleThreaded: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorAggregatorChild.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/sensors"
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
//import com.hubitat.hub.domain.Attribute // only available from 2.4.3.148 onward
//import com.hubitat.hub.domain.Capability // only available from 2.4.3.148 onward
import com.hubitat.hub.domain.Event

@Field static final String CODE_VERSION = "0.4.0"
@Field static final Integer QUIET_FLOOR_MIN = 30
@Field static final Integer MIN_GAPS = 5
@Field static final Integer KEEP_GAPS = 48

@Field static final Map<String, String> CAPABILITY_ATTRIBUTES = [
    "capability.carbonDioxideMeasurement"   : [ attribute: "carbonDioxide", driver: "Virtual Omni Sensor" ],
    "capability.illuminanceMeasurement"     : [ attribute: "illuminance", driver: "Virtual Illuminance Sensor" ],
    "capability.relativeHumidityMeasurement": [ attribute: "humidity", driver: "Virtual Humidity Sensor" ],
    "capability.temperatureMeasurement"     : [ attribute: "temperature", driver: "Virtual Temperature Sensor" ]
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
                input name: "outputSensor", type: selectedSensorCapability, title: "Virtual sensor to set as aggregation output", multiple: false, required: false, submitOnChange: true
                if (!outputSensor) {
                    input "createChildSensorDevice", "bool", title: "Create Child Device if no Output Device selected", defaultValue: state.createChild, required: true, submitOnChange: true
                    state.createChild = createChildSensorDevice
                    paragraph "<a href='/device/addDevice' target='_blank'>Create a new virtual device</a>"
                }
            }
        }
        section("Aggregation") {
            input name: "aggregationMethod", type: "enum", options: ["average", "median", "min", "max"], title: "Select aggregation method", defaultValue: "average", required: true, submitOnChange: true
            input name: "excludeAfter", type: "number", title: "Exclude a sensor after this many minutes without activity, until the app has learned its usual gaps", defaultValue: 60, range: "0..1440", submitOnchange: true
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
    initialize()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void initialize() {
    checkVersion(false)
    app.removeSetting("logLevel")
    migrateSensorListsToIds()
    logDebug "initialize()"

    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }

    if (state.aggregateValue == null) { state.aggregateValue = 0.0 }
    if (state.avgSensorValue == null) { state.avgSensorValue = 0.0 }
    if (state.minSensorValue == null) { state.minSensorValue = 0.0 }
    if (state.maxSensorValue == null) { state.maxSensorValue = 0.0 }
    if (state.medianSensorValue == null) { state.medianSensorValue = 0.0 }
    if (state.createChild == null) { state.createChild = false }
    pruneActivity()

    if (!outputSensor && state.createChild) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
        if (attributeName) {
            subscribe(inputSensors, attributeName, sensorEventHandler)
            logTrace "Subscribed to ${attributeName} events for ${inputSensors.collect { it.displayName}}."
        }
    }

    // Silence sends no event: only a timer notices a sensor that stopped reporting.
    schedule("${new Random().nextInt(60)} */5 * ? * *", "checkSensors")
    sensorEventHandler()
}

// Reads cached activity and values only; never commands the sensors.
void checkSensors() {
    checkVersion()
    logSched "check"
    def before = state.aggregateValue
    if (computeAggregateSensorValue() && state.aggregateValue != before) publishAggregate()
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

void uninstalled() {
    logDebug "uninstalled()"
    noSensorSwitch?.off()
}

void appButtonHandler(String buttonName) {
    checkVersion()
    switch (buttonName) {
        case "forgetGaps":
            forgetLearnedGaps()
            logCfg "forgot the sensors' learned gaps"
            break
        case "forceUpdate":
        default:
            //sensorEventHandler()
            updated()
            break
    }
}

void sensorEventHandler(Event evt=null) {
    checkVersion()
    if (evt != null) logTrace "sensorEventHandler() called: ${evt?.name} ${evt?.getDevice().getLabel()} ${evt?.value} ${evt?.descriptionText}"

	if (computeAggregateSensorValue()) publishAggregate()
}

private void publishAggregate() {
    DeviceWrapper sensorDevice = outputSensor
    if (!sensorDevice) {
        sensorDevice = fetchChildDevice()
    }
    if (!sensorDevice) {
        logError("No output device to update")
        return
    }
    sensorDevice.sendEvent(name: CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute,
                           value: state.aggregateValue,
                           unit: getAttributeUnits(selectedSensorCapability),
                           descriptionText:"${sensorDevice.displayName} was set to ${state.aggregateValue}${getAttributeUnits(selectedSensorCapability)}"
                           /* , isStateChange: true // let platform filter this event as needed */)
}

private String getAttributeUnits(String capability) {
    switch ( capability) {
        case "capability.carbonDioxideMeasurement":
        	return "ppm"
    case "capability.illuminanceMeasurement":
        return "lux"
    case "capability.relativeHumidityMeasurement":
        return "%"
        case "capability.temperatureMeasurement":
        return getTemperatureScale()
        default:
            break
    }
    return ""
}

private ChildDeviceWrapper fetchChildDevice() {
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
            if (it.currentValue(attributeName) != null) {
                includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last activity ${it.getLastActivity()}")
            }
        } else {
            excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - no activity in ${limit.intdiv(60000L)} min (last active ${it.getLastActivity()})")
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

// Notifies once on entry and once on exit; the switch follows the state at every evaluation.
private void noteNoSensor(boolean none, List<String> excludedIds) {
    // Before 0.4.0 there was no flag: derive it from the stored lists so an upgrade doesn't re-notify.
    boolean was = state.noSensor != null ? state.noSensor as boolean : (!state.includedSensors && state.excludedSensors)
    state.noSensor = none
    if (none) {
        String message = "Sensor Aggregator '${app.label}': All sensors excluded due to inactivity"
        if (!was && notificationDevice && notifyOnAllExcluded) notificationDevice.deviceNotification(message)
        logWarn(message)
    } else if (was) {
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
        logError "No sensors available for aggregation... aggregate value not updated (${state.aggregateValue})"
        return false
    }

    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
    List sensorValues = includedSensors.collect { it.currentValue(attributeName) }
    state.minSensorValue = roundToDecimalPlaces(sensorValues.min())
    state.maxSensorValue = roundToDecimalPlaces(sensorValues.max())

    Number sum = (Number) sensorValues.sum()
    state.avgSensorValue = roundToDecimalPlaces(sum / sensorValues.size(),1)

    Number variance = (Number) (sensorValues.collect { (it - state.avgSensorValue) ** 2 }.sum()) / n
    state.standardDeviation = roundToDecimalPlaces(Math.sqrt(variance),1)

    sensorValues.sort()
    logDebug "sorted values: $sensorValues"
    if (n % 2 == 0) {
        // Even number of elements, average the two middle values
        state.medianSensorValue = (sensorValues[n.intdiv(2) - 1] + sensorValues[n.intdiv(2)]) / 2.0
    } else {
        // Odd number of elements, take the middle value
        state.medianSensorValue = sensorValues[n.intdiv(2)]
    }

    //aggregationMethod", type: "enum", options: ["average", "median", "min", "max"
    switch (aggregationMethod) {
        case "min":
            state.aggregateValue = state.minSensorValue
            break

        case "max":
            state.aggregateValue = state.maxSensorValue
            break

        case "median":
            state.aggregateValue = state.medianSensorValue
            break

        case "average":
        default:
            state.aggregateValue = state.avgSensorValue
            break
    }

    logStatistics()
    return true
}

private void logStatistics() {
    logInfo("${CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute} ${aggregationMethod} (${state.includedSensors.size()}/${inputSensors.size()}): ${state.aggregateValue} ${getAttributeUnits(selectedSensorCapability)}")
    logInfo("Avg: ${state.avgSensorValue} Stdev: ${state.standardDeviation} Min: ${state.minSensorValue} Max: ${state.maxSensorValue} Median: ${state.medianSensorValue}")
    if (state.includedSensors.size() > 0) {
        logDebug("Aggregated sensors (${sensorLabels(state.includedSensors).join(', ')})")
    } else {
        logDebug("No aggregated sensors!")
    }
    if (state.excludedSensors.size() > 0) logDebug("Rejected sensors with no recent activity: ${sensorLabels(state.excludedSensors).join(', ')}")
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
Long quietLimitMs(List gaps, Long fallbackMs, Long floorMs, Integer minGaps) {
    if (gaps == null || gaps.size() < minGaps) return fallbackMs
    List<Long> sorted = []
    for (Object g : gaps) sorted << (g as Long)
    sorted.sort()
    return Math.max(floorMs, 2L * sorted[sorted.size() - 2])
}

boolean isQuiet(Map s, Long now, Long limitMs) {
    if (s?.last == null) return true
    return now - (s.last as Long) > limitMs
}

// ── End core ──────────────────────────────────────────────────────────


@CompileStatic
private double roundToDecimalPlaces(double decimalNumber, int decimalPlaces = 2) {
    double scale = Math.pow(10, decimalPlaces)
    return (Math.round(decimalNumber * scale) as double) / scale
}

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
