// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, whole-file variant) for the report-time rule in the Fujitsu
// Mini-Split: runs FujitsuMiniSplit.groovy on a stub of the hub's driver API and feeds polls that
// carry each property's cloud update time (updatedAt). A newer time sends the property's events
// changed or not; the same time sends only a changed value; commands still send on change.
//
// Run: groovy integrations/FGLair/tests/test-report-time.groovy   (a few seconds, no hub)

import org.codehaus.groovy.control.CompilerConfiguration

abstract class StubDriver extends Script {
    Map settings = [:]
    Map state = [:]
    List<Map> events = []
    List<List> writes = []
    long clock = 1_800_000_000_000L
    Map current = [:]

    def getDevice() {
        def d = new Expando(id: 1, deviceNetworkId: 'fglair-unit-TEST', displayName: 'Test Unit')
        d.currentValue = { String n -> current[n] }
        d.currentState = { String n -> current[n] == null ? null : [value: current[n].toString()] }
        d.getDataValue = { String n -> null }
        d.updateDataValue = { String n, String v -> }
        d.updateSetting = { String n, Map v -> }
        d.toString = { 'Test Unit' }
        return d
    }
    def getParent() {
        def p = new Expando()
        p.sendCommand = { String dni, String prop, Integer v -> writes << [prop, v] }
        p.refreshUnit = { String dni -> }
        return p
    }
    def getLog() { [debug: {}, info: {}, warn: {}, error: {}, trace: {}] as Expando }
    String getTemperatureScale() { 'C' }
    long now() { clock }
    void sendEvent(Map e) { events << e; current[e.name as String] = e.value }
    void runIn(Number s, String h) { "$h"() }
    void unschedule() {}
    def metadata(Closure c) {}
    def propertyMissing(String n) { settings[n] }
}

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(System.getenv('FGLAIR_CHILD_SRC') ?: new File(scriptFile.parentFile.parentFile, 'FujitsuMiniSplit.groovy').path)
assert src.exists() : "source not found: ${src}"

CompilerConfiguration cc = new CompilerConfiguration()
cc.scriptBaseClass = StubDriver.name
GroovyShell shell = new GroovyShell(this.class.classLoader, new Binding(), cc)
// Each parse is a new class, so the static LAST_REPORT map starts empty, as after a code push.
def fresh = {
    def d = shell.parse(src.text)
    d.run()
    d.settings.putAll([txtEnable: false])
    d.installed()
    d.events.clear()
    d
}
final List<String> KEYS = ['opMode', 'fanSpeed', 'adjustTemp', 'displayTemp', 'outdoorTemp', 'errorCode', 'opStatus']
Map base = [opMode: 6, adjustTemp: 220, displayTemp: 7000, fanSpeed: 4, outdoorTemp: 5500, errorCode: 0, opStatus: 0]
// Every property stamped with the same time unless overridden.
def poll = { d, Map vals = [:], String t = '2026-11-24T12:00:00Z', Map times = [:] ->
    Map at = KEYS.collectEntries { [(it): t] } + times
    d.updateState(base + vals + [updatedAt: at])
}
def sent = { d, int from -> d.events.drop(from).collect { it.name as String } }

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}
final List<String> ALL = ['unitTemperature', 'unitSetpoint', 'temperature', 'thermostatSetpoint', 'heatingSetpoint',
                          'coolingSetpoint', 'fujitsuMode', 'thermostatMode', 'thermostatOperatingState',
                          'fanSpeed', 'thermostatFanMode', 'outdoorTemperature', 'errorCode', 'opStatus']

println "First poll with an empty memory"
def d = fresh()
poll(d)
List<String> names = sent(d, 0)
check "every polled attribute sent: ${names}", ALL.every { it in names }

println "Repeat poll: same times, same values"
int n = d.events.size()
poll(d)
check "nothing sent: ${sent(d, n)}", sent(d, n).isEmpty()

println "New time on display_temperature, unchanged value"
n = d.events.size()
poll(d, [:], '2026-11-24T12:00:00Z', [displayTemp: '2026-11-24T12:05:00Z'])
names = sent(d, n)
check "temperature and unitTemperature sent", names.containsAll(['temperature', 'unitTemperature'])
check "operating state follows its source", 'thermostatOperatingState' in names
check "nothing else sent: ${names}", (names - ['temperature', 'unitTemperature', 'thermostatOperatingState']).isEmpty()

println "New times on the other properties, unchanged values"
n = d.events.size()
poll(d, [:], '2026-11-24T12:10:00Z', [displayTemp: '2026-11-24T12:05:00Z'])
names = sent(d, n)
check "setpoints, mode, fan, outdoor, errorCode, opStatus sent",
      names.containsAll(['unitSetpoint', 'thermostatSetpoint', 'heatingSetpoint', 'fujitsuMode', 'thermostatMode',
                         'thermostatOperatingState', 'fanSpeed', 'thermostatFanMode', 'outdoorTemperature',
                         'errorCode', 'opStatus'])
check "temperature (same time) not sent", !('temperature' in names) && !('unitTemperature' in names)
check "coolingSetpoint stays a preset outside cool", !('coolingSetpoint' in names)

println "Changed value, unchanged time"
n = d.events.size()
poll(d, [displayTemp: 7200, fanSpeed: 3], '2026-11-24T12:10:00Z', [displayTemp: '2026-11-24T12:05:00Z'])
names = sent(d, n)
check "changed temperature still sent", 'temperature' in names && d.current.temperature == 22.2
check "changed fan speed still sent", 'fanSpeed' in names && d.current.fanSpeed == 'high'
check "unchanged values with old times not sent: ${names}", !('outdoorTemperature' in names) && !('fujitsuMode' in names)

println "An older time is a repeat"
n = d.events.size()
poll(d, [displayTemp: 7200, fanSpeed: 3], '2026-11-24T11:00:00Z')
check "nothing sent: ${sent(d, n)}", sent(d, n).isEmpty()

println "No time at all: on change only"
d = fresh()
d.updateState(base)
check "first poll without times sends (attributes empty)", 'temperature' in sent(d, 0)
n = d.events.size()
d.updateState(base)
check "repeat without times sends nothing", sent(d, n).isEmpty()

println "Commands still send on change only"
d = fresh()
poll(d)
n = d.events.size()
d.setFanSpeed('auto')                        // already auto
check "no event for an unchanged optimistic fan speed", !('fanSpeed' in sent(d, n))
d.setFanSpeed('low')
check "fan speed command sends the new value", d.current.fanSpeed == 'low' && d.writes.contains(['fan_speed', 1])
n = d.events.size()
d.setThermostatMode('cool')
check "mode command sends the new mode", d.current.thermostatMode == 'cool' && d.writes.contains(['operation_mode', 3])

println "A value held by the settle window does not use up its new time"
d = fresh()
poll(d)
d.setThermostatMode('off')
d.clock += 5_000
n = d.events.size()
poll(d, [:], '2026-11-24T12:01:00Z')            // still heat (6), new time
check "stale mode ignored", d.current.thermostatMode == 'off'
d.clock += 5_000
poll(d, [opMode: 0], '2026-11-24T12:01:00Z')    // the write landed, same time as the ignored poll
check "confirming value accepted", d.current.fujitsuMode == 'off'
n = d.events.size()
poll(d, [opMode: 0], '2026-11-24T12:01:00Z')
check "then a repeat sends nothing: ${sent(d, n)}", sent(d, n).isEmpty()

println ""
println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
