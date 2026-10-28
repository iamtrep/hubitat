// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, whole-file variant) for the Fujitsu Mini-Split sensor offsets:
// runs FujitsuMiniSplit.groovy under plain Groovy on a stub of the hub's driver API, feeds it
// polls the way the manager does, and checks the events and writes.
//
// Run: groovy integrations/FGLair/tests/test-offsets.groovy   (a few seconds, no hub)

import org.codehaus.groovy.control.CompilerConfiguration

abstract class StubDriver extends Script {
    Map settings = [:]
    Map state = [:]
    List<Map> events = []
    List<List> writes = []
    String scale = 'C'
    Map dev = [id: 1, deviceNetworkId: 'fglair-unit-TEST', displayName: 'Test Unit']
    Map current = [:]

    def getDevice() {
        def d = new Expando(dev)
        d.currentValue = { String n -> current[n] }
        d.getDataValue = { String n -> null }
        d.updateDataValue = { String n, String v -> }
        d.updateSetting = { String n, Map v -> }
        d.toString = { dev.displayName }
        return d
    }
    def getParent() {
        def p = new Expando()
        p.sendCommand = { String dni, String prop, Integer v -> writes << [prop, v] }
        p.refreshUnit = { String dni -> }
        return p
    }
    def getLog() { [debug: {}, info: {}, warn: {}, error: {}, trace: {}] as Expando }
    String getTemperatureScale() { scale }
    long now() { System.currentTimeMillis() }
    void sendEvent(Map e) { events << e; current[e.name as String] = e.value }
    void runIn(Number s, String h) { "$h"() }
    void unschedule() {}
    def metadata(Closure c) {}
    // On the hub a bare preference name resolves to its setting.
    def propertyMissing(String n) { settings[n] }
}

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile.parentFile, 'FujitsuMiniSplit.groovy')
assert src.exists() : "source not found: ${src}"

CompilerConfiguration cc = new CompilerConfiguration()
cc.scriptBaseClass = StubDriver.name
GroovyShell shell = new GroovyShell(this.class.classLoader, new Binding(), cc)

def fresh = { Map prefs = [:] ->
    def d = shell.parse(src.text)
    d.run()
    d.settings.putAll([txtEnable: false] + prefs)
    d.installed()
    d
}
// A poll as the manager hands it over: Ayla raw values (sensor hundredths °F, setpoint tenths °C).
def poll = { d, int mode, int setpoint, int display ->
    d.updateState([opMode: mode, adjustTemp: setpoint, displayTemp: display, fanSpeed: 3])
}
def last = { d, String name -> d.events.findAll { it.name == name }.last() }
// Forget pending writes, as if the polls that confirm them had come in.
def settle = { d -> d.recentWrites().clear() }

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println "No offset"
def d = fresh()
poll(d, 6, 220, 7200)   // heat, 22.0 °C, 72 °F = 22.2 °C
check "temperature is the unit's reading", d.current.temperature == 22.2
check "setpoint is the unit's setpoint", d.current.thermostatSetpoint == 22.0
check "heat bounds 16-30", d.current.minHeatingSetpoint == 16.0 && d.current.maxHeatingSetpoint == 30.0
d.setHeatingSetpoint(21)
check "21 °C written as 210", d.writes.last() == ['adjust_temperature', 210]

println "Heat offset +2.0 °C"
d = fresh([heatOffset: "2.0", coolOffset: "1.0"])
poll(d, 6, 240, 7200)
check "temperature corrected to 20.2", d.current.temperature == 20.2
check "description names the unit's reading", (last(d, 'temperature').descriptionText as String).contains('unit sensor 22.2')
check "setpoint 24.0 on the unit reads 22.0", d.current.thermostatSetpoint == 22.0 && d.current.heatingSetpoint == 22.0
check "heat bounds shifted to 14-28", d.current.minHeatingSetpoint == 14.0 && d.current.maxHeatingSetpoint == 28.0
check "cool bounds shifted to 17-29", d.current.minCoolingSetpoint == 17.0 && d.current.maxCoolingSetpoint == 29.0
d.setHeatingSetpoint(20)
check "room 20 written as unit 22.0 (220)", d.writes.last() == ['adjust_temperature', 220]
d.setHeatingSetpoint(29)
check "room 29 clamped to 28, unit 30.0", d.writes.last() == ['adjust_temperature', 300]
d.setHeatingSetpoint(14)
check "room 14 allowed in heat, unit 16.0", d.writes.last() == ['adjust_temperature', 160]
poll(d, 6, 230, 7000)   // 23.0 on the unit, 70 °F = 21.1
check "operating state from corrected values (19.1 vs 21.0: heating)", d.current.thermostatOperatingState == "heating"

println "Cool and dry use the cool offset"
settle(d)
poll(d, 3, 250, 7700)   // cool, 25.0, 77 °F = 25.0
check "cool temperature 24.0", d.current.temperature == 24.0
check "cool setpoint 24.0", d.current.thermostatSetpoint == 24.0 && d.current.coolingSetpoint == 24.0
d.setCoolingSetpoint(23)
check "room 23 cool written as 24.0 (240)", d.writes.last() == ['adjust_temperature', 240]
poll(d, 4, 250, 7700)   // dry
check "dry temperature 24.0", d.current.temperature == 24.0

println "Off keeps the last role"
poll(d, 0, 250, 7700)
check "off after cool uses the cool offset", d.current.temperature == 24.0
poll(d, 6, 240, 7200)
poll(d, 0, 240, 7200)
check "off after heat uses the heat offset", d.current.temperature == 20.2

println "Mode change pushes the stored setpoint with that mode's offset"
d = fresh([heatOffset: "2.0", coolOffset: "0.5"])
poll(d, 3, 240, 7500)   // cool first
d.current.heatingSetpoint = 21.0
d.writes.clear()
d.heat()
check "heat preset 21 written as 23.0 (230)", d.writes.contains(['adjust_temperature', 230])

println "Changing the offset resends the room setpoint"
d = fresh([heatOffset: "0.0"])
poll(d, 6, 220, 7200)
d.writes.clear()
d.settings.heatOffset = "2.0"
d.updated()
check "22 now written as 24.0 (240)", d.writes == [['adjust_temperature', 240]]
d.writes.clear()
d.updated()
check "saving again with the same offset writes nothing", d.writes.isEmpty()
d.settings.coolOffset = "1.5"
d.updated()
check "changing the cool offset in heat writes nothing", d.writes.isEmpty()

println "Held setpoint below the cool minimum"
d = fresh([heatOffset: "2.0"])
poll(d, 0, 240, 7500)   // unit off
d.writes.clear()
d.setHeatingSetpoint(16)  // unit 18.0: writable in any mode
check "heating setpoint 16 (unit 18.0) is written while off", d.writes == [['adjust_temperature', 180]]
d.writes.clear()
d.setHeatingSetpoint(15)  // unit 17.0 < 18: held until heat
check "heating setpoint 15 (unit 17.0) is held while off", d.writes.isEmpty() && d.state.heldSetpoint?.value == 15
settle(d)
poll(d, 6, 180, 7200)
check "held 15 sent once the unit reports heat (unit 17.0)", d.writes.contains(['adjust_temperature', 170])

println "Room control bias from the parent"
d = fresh([heatOffset: "2.0"])
poll(d, 6, 210, 7200)   // heat, unit 21.0 = room 19.0, unit reads 22.2
d.writes.clear()
d.setControlBias(4.0)
check "bias replaces the heat offset: 19 written as 23.0 (230)", d.writes == [['adjust_temperature', 230]]
check "controlBias attribute", d.current.controlBias == 4.0
check "heat bounds follow the bias (12-26)", d.current.minHeatingSetpoint == 12.0 && d.current.maxHeatingSetpoint == 26.0
d.setRoomTemperature(18.6)
check "temperature shows the room", d.current.temperature == 18.6
settle(d)
poll(d, 6, 230, 7200)
check "poll keeps the room temperature", d.current.temperature == 18.6
check "unitTemperature and unitSetpoint are the unit's", d.current.unitTemperature == 22.2 && d.current.unitSetpoint == 23.0
check "setpoint in room terms", d.current.thermostatSetpoint == 19.0 && d.current.heatingSetpoint == 19.0
check "operating state from the unit's reading vs written (22.2 vs 23.0: heating)", d.current.thermostatOperatingState == "heating"
d.writes.clear()
d.setHeatingSetpoint(20.5)
check "new target passes through with the bias (24.5)", d.writes == [['adjust_temperature', 245]]
d.writes.clear()
d.clearControlBias()
check "cleared: back to the heat offset (22.5)", d.writes == [['adjust_temperature', 225]]
settle(d)
poll(d, 6, 225, 7200)
check "cleared: temperature is the unit's corrected reading again", d.current.temperature == 20.2

println "Fahrenheit hub"
d = fresh([heatOffset: "2.0"])
d.scale = 'F'
d.initialize()
poll(d, 6, 240, 7200)
check "72.0 °F reads 68.4 °F", d.current.temperature == 68.4
check "24.0 °C unit setpoint (75.2 °F) reads 71.6 °F", d.current.thermostatSetpoint == 71.6
d.setHeatingSetpoint(70)
check "70 °F + 3.6 °F = 73.6 °F snaps to 23.0 °C (230)", d.writes.last() == ['adjust_temperature', 230]

println ""
println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
