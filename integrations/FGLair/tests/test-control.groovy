// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, whole-file variant) for the FGLair Manager room controller:
// runs FGLairManager.groovy under plain Groovy on a stub of the hub's app API and drives
// controlStep() directly. It is pure, so no hub calls are stubbed beyond loading the file.
//
// Run: groovy integrations/FGLair/tests/test-control.groovy   (a few seconds, no hub)

import org.codehaus.groovy.control.CompilerConfiguration

abstract class StubApp extends Script {
    Map settings = [:]
    Map state = [:]
    def definition(Map m) {}
    def preferences(Closure c) {}
    def getLog() { [debug: {}, info: {}, warn: {}, error: {}, trace: {}] as Expando }
    def propertyMissing(String n) { settings[n] }
}

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile.parentFile, 'FGLairManager.groovy')
assert src.exists() : "source not found: ${src}"

CompilerConfiguration cc = new CompilerConfiguration()
cc.scriptBaseClass = StubApp.name
// The hub's ChildDeviceWrapper isn't on the classpath here; an empty class stands in.
String text = src.text.replace('import com.hubitat.app.ChildDeviceWrapper', '') + '\nclass ChildDeviceWrapper {}\n'
def app = new GroovyShell(this.class.classLoader, new Binding(), cc).parse(text)
app.run()
Map P = app.CONTROL_DEFAULTS

final long MIN = 60_000L
long t0 = 1_800_000_000_000L
// Runs steps every `every` minutes for `n` steps; returns the results.
def run = { Map st, int n, int every, Map inp, Map p = P ->
    List out = []
    Map s = st
    (1..n).each { i ->
        Map r = app.controlStep(s, [now: t0 + i * every * MIN, active: true] + inp, p)
        out << r
        s = r.state
    }
    out
}
def near = { a, b -> Math.abs((a as double) - (b as double)) < 1e-9 }

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println "At target"
def r = app.controlStep([:], [now: t0, active: true, target: 19.0, room: 19.0, outdoor: null], P)
check "bias is b0", near(r.bias, 2.0)
check "no write at b0", r.write == null
check "integral starts at 0", near(r.state.integral, 0)
r = app.controlStep([:], [now: t0, active: true, target: 19.0, room: 19.0, outdoor: -4.0], P)
check "outdoor term adds ke·(target − outdoor)", near(r.bias, 2.0 + 0.03 * 23.0)

println "Room 1 °C under target"
List rs = run([:], 24, 5, [target: 19.0, room: 18.0, outdoor: null])
check "first step: proportional only", near(rs[0].bias, 2.0 + 0.3)
check "integral grows", rs[5].state.integral > rs[1].state.integral
check "integral clamped at iMax/ki", rs.every { (it.state.integral as double) <= 1.5 / 0.3 + 1e-9 }
check "bias at most b0 + kp·e + iMax", rs.every { (it.bias as double) <= 2.0 + 0.3 + 1.5 + 1e-9 }
List writes = rs.findAll { it.write != null }
check "writes on the 0.5 grid", writes.every { near(it.write * 2, Math.round(it.write * 2)) }
check "writes at least 15 min apart", writes.size() < 2 || (1..<writes.size()).every {
    (writes[it].state.lastWriteAt as long) - (writes[it - 1].state.lastWriteAt as long) >= 15 * MIN }
check "bias written upward", writes && writes.last().write > 2.0

println "Write threshold"
r = app.controlStep([lastBias: 2.0], [now: t0, active: true, target: 19.0, room: 18.0, outdoor: null], P)
check "raw 2.3 (0.3 from written): no write", near(r.bias, 2.3) && r.write == null
r = app.controlStep([lastBias: 2.0], [now: t0, active: true, target: 19.0, room: 17.8, outdoor: null], P)
check "raw 2.36: writes 2.5", near(r.write, 2.5)

println "Step limit and rate limit"
r = app.controlStep([lastBias: 2.0], [now: t0, active: true, target: 21.0, room: 15.0, outdoor: null], P)
check "raw 3.8: write limited to +1.0", near(r.bias, 3.8) && near(r.write, 3.0)
Map s2 = r.state
r = app.controlStep(s2, [now: t0 + 10 * MIN, active: true, target: 21.0, room: 15.0, outdoor: null], P)
check "10 min later: no write", r.write == null
r = app.controlStep(s2, [now: t0 + 15 * MIN, active: true, target: 21.0, room: 15.0, outdoor: null], P)
check "15 min later: writes again", r.write != null

println "Clamps"
r = app.controlStep([integral: 5.0], [now: t0, active: true, target: 25.0, room: 10.0, outdoor: -30.0], P)
check "bias capped at biasMax", near(r.bias, 5.0)
r = app.controlStep([integral: -5.0], [now: t0, active: true, target: 15.0, room: 25.0, outdoor: 30.0], P)
check "bias floored at biasMin", near(r.bias, -1.0)

println "Sign change halves the integral"
r = app.controlStep([integral: 2.0, lastAt: t0], [now: t0 + 6 * MIN, active: true, target: 19.0, room: 19.5, outdoor: null], P)
check "I = 2.0/2 − 0.5·0.1", near(r.state.integral, 1.0 - 0.05)
r = app.controlStep([integral: 2.0, lastAt: t0], [now: t0 + 6 * MIN, active: true, target: 19.0, room: 18.5, outdoor: null], P)
check "same sign: I = 2.0 + 0.5·0.1", near(r.state.integral, 2.05)
r = app.controlStep([integral: 0.0, lastAt: t0], [now: t0 + 600 * MIN, active: true, target: 19.0, room: 18.0, outdoor: null], P)
check "a long gap accumulates at most maxDtH", near(r.state.integral, 0.25)

println "Setback coast"
Map day = [integral: 4.0, lastAt: t0, target: 20.5]
r = app.controlStep(day, [now: t0 + 5 * MIN, active: true, target: 19.0, room: 20.0, outdoor: null], P)
check "lowered target: integral held", near(r.state.integral, 4.0) && r.reason == "coast"
r = app.controlStep(r.state, [now: t0 + 60 * MIN, active: true, target: 19.0, room: 19.3, outdoor: null], P)
check "still above the new target: held", near(r.state.integral, 4.0) && r.state.coast
r = app.controlStep(r.state, [now: t0 + 65 * MIN, active: true, target: 19.0, room: 18.9, outdoor: null], P)
check "room reaches target: integrates again", near(r.state.integral, 4.0 + 0.1 * 5 / 60) && !r.state.coast
r = app.controlStep([integral: 1.0, lastAt: t0, target: 19.0], [now: t0 + 6 * MIN, active: true, target: 20.5, room: 19.0, outdoor: null], P)
check "raised target: no coast", !r.state.coast && near(r.state.integral, 1.0 + 1.5 * 0.1)

println "Frozen"
Map before = [integral: 1.2, lastAt: t0, lastBias: 2.5, lastWriteAt: t0]
r = app.controlStep(before, [now: t0 + 30 * MIN, active: false, target: 19.0, room: 15.0, outdoor: null], P)
check "no write", r.write == null && r.bias == null && r.reason == "frozen"
check "integral kept", near(r.state.integral, 1.2)
check "clock advanced, so thawing doesn't integrate the frozen span", r.state.lastAt == t0 + 30 * MIN
check "input state untouched", before.lastAt == t0

println "Room sensor stale"
r = app.controlStep([integral: 3.0, lastAt: t0, lastBias: 4.0, lastWriteAt: t0],
    [now: t0 + 30 * MIN, active: true, target: 19.0, room: null, outdoor: -4.0], P)
check "falls back to b0", near(r.bias, 2.0) && r.reason == "fallback"
check "integral dropped", near(r.state.integral, 0)
check "writes toward b0, one step", near(r.write, 3.0)

println()
println "${pass} passed, ${fail} failed"
if (fail) System.exit(1)
