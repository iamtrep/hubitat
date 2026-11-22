// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Offline extraction test for BlinkCamera: battery voltage smoothing and percentage.
// Loads the shipped functions by name from BlinkCamera.groovy and runs them on a stub.
// Run: groovy integrations/Blink/tests/test-battery.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../BlinkCamera.groovy').text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^(?:private\s+)?(?:static\s+)?(?:void|int|double)\s+${name}\(/
    assert m.find() : "function not found: ${name}"
    int start = m.start(); int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String constant(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^@Field (static final [^\n]*\b${name}\b[^\n]*)$/
    assert m.find() : "constant not found: ${name}"
    return m.group(1)
}
List<String> consts = ['BATTERY_CURVE_V', 'BATTERY_CURVE_PCT', 'BATTERY_EMA_ALPHA', 'BATTERY_BIG_JUMP_V', 'BATTERY_LOW_MAX_PCT'].collect { constant(it) }
List<String> funcs = ['publishBattery', 'smoothBatteryVoltage', 'batteryPctFromVoltage'].collect { extract(it) }

String harness = """
class H {
    ${consts.join('\n    ')}
    Map state = [:]; List events = []
    void sendEvent(Map e) { events << e }
    void logInfo(String m) {}; void logTrace(String m) {}
    Object lastValue(String name) { events.findAll { it.name == name }.collect { it.value }.with { it ? it[-1] : null } }
${funcs.join('\n')}
}
return H
"""
Class Hc = new GroovyShell(getClass().classLoader).parse(harness).run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('curve ends and middle', {
    def h = Hc.newInstance()
    assert h.batteryPctFromVoltage(1.26d, h.BATTERY_CURVE_V, h.BATTERY_CURVE_PCT) == 0
    assert h.batteryPctFromVoltage(1.80d, h.BATTERY_CURVE_V, h.BATTERY_CURVE_PCT) == 100
    assert h.batteryPctFromVoltage(1.525d, h.BATTERY_CURVE_V, h.BATTERY_CURVE_PCT) == 50
})
t('first reading publishes voltage and percentage', {
    def h = Hc.newInstance()
    h.publishBattery(1.69d, false)
    assert h.lastValue('batteryVoltage') == 1.69d
    assert h.lastValue('battery') == 87
})
t('a higher reading does not raise the percentage', {
    def h = Hc.newInstance()
    h.publishBattery(1.50d, false); int before = h.lastValue('battery')
    h.publishBattery(1.58d, false)
    assert h.lastValue('battery') == before
    assert h.lastValue('batteryVoltage') == 1.58d
})
t('one low reading only nudges it down', {
    def h = Hc.newInstance()
    h.publishBattery(1.60d, false)
    h.publishBattery(1.40d, false)
    int pct = h.lastValue('battery')
    assert pct < 67 && pct > 40 : "pct ${pct}"
})
t('new cells reset it', {
    def h = Hc.newInstance()
    h.publishBattery(1.36d, false)
    h.publishBattery(1.72d, false)
    assert h.lastValue('battery') == 93
})
t('Blink low caps the percentage', {
    def h = Hc.newInstance()
    h.publishBattery(1.50d, true)
    assert h.lastValue('battery') == 10
})

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
