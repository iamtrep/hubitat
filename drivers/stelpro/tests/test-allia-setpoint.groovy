// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant) for the Stelpro Allia setpoint helpers: brace-extracts them
// and the setThermostatMode body from StelproAllia.groovy and runs them under plain Groovy.
//
// Run: groovy drivers/stelpro/tests/test-allia-setpoint.groovy   (no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile.parentFile, 'StelproAllia.groovy')
assert src.exists() : "source not found: ${src}"
String text = src.text

String extract(String text, String signature) {
    int start = text.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = text.indexOf('{', start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        if (text[i] == '{') depth++
        else if (text[i] == '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

List<String> SIGNATURES = [
    'private static int hexToSignedInt16(String hex) {',
    'private static int setpointHundredths(BigDecimal celsius) {',
    'private static BigDecimal celsiusFromScale(BigDecimal degrees, String scale) {',
    'private static BigDecimal reportedSetpointCelsius(String hex) {',
    'private static BigDecimal restoreSetpointCelsius(Object stored, BigDecimal roomCelsius, BigDecimal min, BigDecimal max) {',
]
def h = new GroovyShell().parse(SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'K-2: remembered setpoint is Celsius and keeps half degrees'
check '20.5 C restored as 20.5', h.restoreSetpointCelsius(20.5G, 19.0G, 5.0G, 30.0G) == 20.5G
check 'stored Float 21.11 restored as 21.11', h.restoreSetpointCelsius(21.11f, null, 5.0G, 30.0G) == 21.11G
check 'stored 70 (old Fahrenheit value) ignored, room temp used', h.restoreSetpointCelsius(70.0G, 19.2G, 5.0G, 30.0G) == 19.0G
check 'room 19.3 C rounds to 19.5', h.restoreSetpointCelsius(null, 19.3G, 5.0G, 30.0G) == 19.5G
check 'room temp clamped to the range', h.restoreSetpointCelsius(null, 35.0G, 5.0G, 30.0G) == 30.0G
check 'nothing known gives null', h.restoreSetpointCelsius(null, null, 5.0G, 30.0G) == null
check '70 F is 21.11 C', h.celsiusFromScale(70.0G, 'F') == 21.11G
check 'C scale unchanged', h.celsiusFromScale(20.5G, 'C') == 20.5G
check '20.5 C writes 2050', h.setpointHundredths(20.5G) == 2050
check '21.11 C writes 2111', h.setpointHundredths(21.11G) == 2111
check 'report 0x0802 is 20.50 C', h.reportedSetpointCelsius('0802') == 20.5G
check 'report 01F4 (5 C, the off value) is not remembered', h.reportedSetpointCelsius('01F4') == null
check 'report 01f4 lower case likewise', h.reportedSetpointCelsius('01f4') == null
check 'report 8000 sentinel is not remembered', h.reportedSetpointCelsius('8000') == null

println 'K-1: off() and heat() do not go through the hub-scale setHeatingSetpoint'
String body = extract(text, 'void setThermostatMode(String thermostatMode) {').readLines().collect { it.replaceFirst(/\/\/.*$/, '') }.join("\n")   // code only, comments may name the function
check 'setThermostatMode never calls setHeatingSetpoint', !body.contains('setHeatingSetpoint(')
check 'off writes the 5 C constant in Celsius', body.contains('writeSetpointCelsius(constHeatOffSetpoint as BigDecimal)')
check '5 C is 500 hundredths', h.setpointHundredths(5 as BigDecimal) == 500

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
