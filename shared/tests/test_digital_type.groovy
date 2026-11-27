// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for shared/digital-type.groovy, run against the shared source with the clock and device stubbed.
//   groovy shared/tests/test_digital_type.groovy

import java.util.concurrent.ConcurrentHashMap

here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

STUBS = '''
long now() { clock[0] }
'''
Map store = new ConcurrentHashMap<String, Map>()
Script load(Map store, String id = "7") {
    Script s = new GroovyShell().parse(STUBS + new File(here.parentFile, "digital-type.groovy").text)
    s.binding.setVariable("clock", clock)
    s.binding.setVariable("DIGITAL_EXPECTED", store)
    s.binding.setVariable("device", [id: id])
    return s
}
clock = [1000L]
Script s = load(store)

check("no command: physical", s.reportType("switch", "on"), "physical")

s.expectDigital("switch", "on")
check("routine report of the old value: physical", s.reportType("switch", "off"), "physical")
check("record survives a non-matching report", store.size(), 1)
check("report of the asked value: digital", s.reportType("switch", "on"), "digital")
check("matching report clears the record", store.size(), 0)
check("next report: physical", s.reportType("switch", "on"), "physical")

// A command that changes nothing: its read-back matches and clears the record.
s.expectDigital("switch", "on")
check("read-back of a no-op command: digital", s.reportType("switch", "on"), "digital")
check("a press right after: physical", s.reportType("switch", "off"), "physical")

// Someone undoes the command at the device before it reports.
s.expectDigital("valve", "closed")
check("opposite value: physical", s.reportType("valve", "open"), "physical")
check("then the asked value: digital", s.reportType("valve", "closed"), "digital")

// The deadline.
s.expectDigital("switch", "off", 5000L)
clock[0] += 5000L
check("at the deadline: physical", s.reportType("switch", "off"), "physical")
check("expired record is dropped", store.size(), 0)

// The second of two quick commands replaces the first.
s.expectDigital("switch", "on"); s.expectDigital("switch", "off")
check("value of the first command: physical", s.reportType("switch", "on"), "physical")
check("value of the second: digital", s.reportType("switch", "off"), "digital")

// Values compare as text.
s.expectDigital("level", 80)
check("level 80 matches \"80\"", s.reportType("level", "80"), "digital")

// No value: a fade; every step is digital until the deadline, and steps don't clear it.
s.expectDigital("level", null, 12000L)
check("fade step: digital", s.reportType("level", 35), "digital")
check("later fade step: digital", s.reportType("level", 60), "digital")
clock[0] += 12000L
check("after the fade: physical", s.reportType("level", 60), "physical")

// Attributes and endpoints are apart.
s.expectDigital("switch", "on", 5000L, "01")
check("other endpoint: physical", s.reportType("switch", "on", "02"), "physical")
check("no endpoint given: physical", s.reportType("switch", "on"), "physical")
check("same endpoint: digital", s.reportType("switch", "on", "01"), "digital")
s.expectDigital("switch", "on")
check("other attribute: physical", s.reportType("valve", "on"), "physical")

// Devices share the store but not their records; clearDigital drops only its own.
Script other = load(store, "8")
other.expectDigital("switch", "on")
check("other device's record is not mine", s.reportType("switch", "off"), "physical")
s.expectDigital("level", 50)
s.clearDigital()
check("clearDigital drops this device only", store.keySet().sort(), ["8/switch"])
check("other device still digital", other.reportType("switch", "on"), "digital")

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
