// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, whole-file variant) for the Fujitsu Mini-Split write-settle
// window: runs FujitsuMiniSplit.groovy on a stub of the hub's driver API with a controllable
// clock, writes a property, then feeds polls that still carry the old value inside and outside
// WRITE_SETTLE_MS, and after a failed write result. test-offsets.groovy clears the window before
// each poll, so this is the only place the stale-poll path runs.
//
// Run: groovy integrations/FGLair/tests/test-settle.groovy   (a few seconds, no hub)

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
def fresh = {
    def d = shell.parse(src.text)
    d.run()
    d.settings.putAll([txtEnable: false])
    d.installed()
    d
}
def poll = { d, int mode -> d.updateState([opMode: mode, adjustTemp: 220, displayTemp: 7200, fanSpeed: 3]) }
// Did the polls since `from` publish this mode? The write itself publishes an optimistic
// event, so the stale-poll checks look at events after the poll, not at the current value.
def published = { d, int from, String mode -> d.events.drop(from).any { it.name == 'thermostatMode' && it.value == mode } }

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}
final long WINDOW = 30_000L

println "Stale poll inside the window"
def d = fresh()
poll(d, 3)                                     // cool
check "starts in cool", d.current.thermostatMode == "cool"
d.setThermostatMode("off")
check "off written as operation_mode 0", d.writes.contains(['operation_mode', 0])
d.clock += 5_000
int n = d.events.size()
poll(d, 3)                                     // cloud still says cool
check "old value ignored: no cool event, mode not reverted", !published(d, n, "cool") && d.current.thermostatMode != "cool"
d.clock += 5_000
poll(d, 0)                                     // the write landed
check "new value accepted", d.current.thermostatMode == "off"
d.clock += 5_000
poll(d, 3)                                     // a real change made on the unit later
check "after confirmation the window is closed: a later poll is taken as is", d.current.thermostatMode == "cool"

println "Old value after the window"
d = fresh()
poll(d, 3)
d.setThermostatMode("off")
d.clock += WINDOW + 1_000
poll(d, 3)
check "poll past the window is trusted", d.current.thermostatMode == "cool"

println "Just inside the window"
d = fresh()
poll(d, 3)
d.setThermostatMode("off")
d.clock += WINDOW - 1_000
n = d.events.size()
poll(d, 3)
check "29 s: still ignored", !published(d, n, "cool") && d.current.thermostatMode != "cool"

println "Failed write result"
d = fresh()
poll(d, 3)
d.setThermostatMode("off")
d.commandResult("operation_mode", false)
check "commandStatus failed", d.current.commandStatus == "failed"
d.clock += 5_000
poll(d, 3)
check "failed write: the poll's old value is the unit's real state", d.current.thermostatMode == "cool"

println "Successful write result keeps the window"
d = fresh()
poll(d, 3)
d.setThermostatMode("off")
d.commandResult("operation_mode", true)
check "commandStatus ok", d.current.commandStatus == "ok"
d.clock += 5_000
n = d.events.size()
poll(d, 3)
check "ok result does not reopen the stale poll", !published(d, n, "cool") && d.current.thermostatMode != "cool"

println "A newer write survives the confirming poll of an older one"
d = fresh()
poll(d, 3)
d.setThermostatMode("off")
d.clock += 2_000
d.setThermostatMode("auto")                    // replaces the entry for operation_mode
d.clock += 2_000
poll(d, 2)                                     // auto = 2 confirms the newer write
check "newest write confirmed", d.current.thermostatMode == "auto"

println ""
println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
