// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant): the 3RDP energy-offset capture after resetEnergy.
// Run: groovy drivers/thirdreality/tests/test-3rdp-energy-capture.groovy   (no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
String extract(String text, String signature) {
    int start = text.indexOf(signature)
    assert start >= 0 : "signature not found in shipped source: ${signature}"
    int open = text.indexOf("{", start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        if (text[i] == "{") depth++
        else if (text[i] == "}") { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}
int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

// SRC_OVERRIDE lets the test run against an older copy of the driver
File src = System.getenv("SRC_OVERRIDE") ? new File(System.getenv("SRC_OVERRIDE")) : new File(scriptFile.parentFile.parentFile, "ThirdReality_3RDP01072Z.groovy")
String text = src.text
def h = new GroovyShell().parse(extract(text, "private static List<String> captureEnergyOffset(") + "\nreturn this\n").run()

println "L-5: a second reset captures both endpoints"
Map offsets = [:]
List<String> pending = ["01", "02"]
pending = h.captureEnergyOffset(offsets, pending, "01", 1000L)
check "first reset: EP01 report leaves EP02 pending", pending == ["02"]
pending = h.captureEnergyOffset(offsets, pending, "02", 2000L)
check "first reset: both captured, nothing pending", pending.isEmpty() && offsets == [ "01": 1000L, "02": 2000L ]
// second reset: offsets already hold both keys; the old size test would end capture at the first report
pending = ["01", "02"]
pending = h.captureEnergyOffset(offsets, pending, "01", 1500L)
check "second reset: EP01 report leaves EP02 pending", pending == ["02"]
pending = h.captureEnergyOffset(offsets, pending, "02", 2600L)
check "second reset: EP02 re-captured", offsets["02"] == 2600L && pending.isEmpty()

println "wiring"
String reset = extract(text, "void resetEnergy() {")
String meter = extract(text, "private void handleMeteringCluster(")
check "resetEnergy arms a per-endpoint pending list", reset.contains("state.captureEnergyPending = ENDPOINTS.collect()")
check "metering handler does not use the map size", !meter.contains("offsets.size()")

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
