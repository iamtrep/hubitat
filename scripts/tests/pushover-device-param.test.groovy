#!/usr/bin/env groovy
/*
 * pushover-device-param.test.groovy — local unit test for the Pushover driver's
 * `device` parameter resolution (audit bug #1).
 *
 * Bug: the `deviceName` preference is a `multiple: true` enum, so Hubitat stores
 * it as a List. The pre-fix driver did:
 *
 *     if (deviceName == "ALL") deviceName = null        // a List never == "ALL"
 *     if (deviceName) { ... name="device" ... ${deviceName} ... }
 *
 * which (a) never strips "ALL" when it arrives as ["ALL"], and (b) interpolates
 * the List with brackets/spaces, sending Pushover `device=[phone1, phone2]`
 * instead of the required comma list `phone1,phone2`. Any explicit device
 * selection therefore yields an invalid request.
 *
 * The fix extracts a pure helper `resolveDeviceList(deviceName)` in the driver.
 * This test (1) proves the legacy inline logic was broken, and (2) verifies the
 * driver's real `resolveDeviceList` against the expected contract by reading and
 * evaluating it straight out of the driver source — no copy to drift.
 *
 * Run from the repo root:
 *   groovy scripts/tests/pushover-device-param.test.groovy [path-to-driver.groovy]
 * Exit 0 = pass, 1 = fail.
 */

// ---- the contract: input -> Pushover `device` value (null = omit = broadcast) ----
def cases = [
    [name: "single device (List)",     input: ["phone1"],            expect: "phone1"],
    [name: "multiple devices (List)",  input: ["phone1", "phone2"],  expect: "phone1,phone2"],
    [name: "ALL only (List)",          input: ["ALL"],               expect: null],
    [name: "ALL + a device (List)",    input: ["ALL", "phone1"],     expect: "phone1"],
    [name: "empty selection (List)",   input: [],                    expect: null],
    [name: "unset preference (null)",  input: null,                  expect: null],
    [name: "message override (String)",input: "phone1",              expect: "phone1"],
    [name: "override 'all' (String)",  input: "all",                 expect: null],
]

int failures = 0
def fail = { String msg -> failures++; System.err.println "  FAIL: ${msg}" }

// ---- 1. Evidence: the legacy inline logic is broken for List preferences ----
def legacyInline = { deviceName ->
    if (deviceName == "ALL") deviceName = null
    return deviceName ? "${deviceName}".toString() : null   // direct GString interpolation
}
println "Bug evidence (legacy inline device handling):"
[[in: ["phone1"], got: legacyInline(["phone1"])],
 [in: ["phone1", "phone2"], got: legacyInline(["phone1", "phone2"])],
 [in: ["ALL"], got: legacyInline(["ALL"])]].each {
    println "  legacyInline(${it.in}) -> ${it.got}"
}
if (legacyInline(["phone1"]) == "phone1")  fail("legacy unexpectedly correct for [phone1] — bug premise wrong")
if (legacyInline(["ALL"]) == null)         fail("legacy unexpectedly stripped ALL from a List — bug premise wrong")
assert legacyInline(["phone1"]) == "[phone1]" : "legacy should produce the bracketed (invalid) value"
println "  -> confirmed: List preference yields invalid 'device=[...]' and ALL is not stripped\n"

// ---- 2. Load the driver's real resolveDeviceList(), if present ----
List<String> candidates = []
if (this.args && this.args.length > 0) candidates << this.args[0]
candidates += [
    "forks/ogiewon/Drivers/pushover-notifications.src/pushover-notifications.groovy",
    "../forks/ogiewon/Drivers/pushover-notifications.src/pushover-notifications.groovy",
    System.getProperty("user.home") + "/Documents/GitHub/iamtrep/Hubitat-ogiewon/Drivers/pushover-notifications.src/pushover-notifications.groovy",
]
File driver = candidates.collect { new File(it) }.find { it.exists() }
if (driver == null) {
    fail("driver source not found; tried:\n    " + candidates.join("\n    "))
    System.exit(1)
}
println "Driver under test: ${driver.canonicalPath}"

String src = driver.text
def m = (src =~ /(?s)(?:private\s+)?String resolveDeviceList\(.*?\n\}/)
Closure helper = null
if (m.find()) {
    String methodSrc = m.group(0).replaceFirst(/^\s*private\s+/, "")
    def cls = new GroovyShell().evaluate("class _PushoverHelper { ${methodSrc} }\n_PushoverHelper")
    def inst = cls.getDeclaredConstructor().newInstance()
    helper = { arg -> inst.resolveDeviceList(arg) }
} else {
    // Tie the failure directly to the unfixed bug.
    def hit = src.readLines().findIndexOf { it.contains('if (deviceName == "ALL") deviceName = null') }
    if (hit >= 0) {
        fail("driver has no resolveDeviceList(); buggy inline handling still present at line ${hit + 1} — bug #1 UNFIXED")
    } else {
        fail("driver has no resolveDeviceList() and no recognizable device handling")
    }
}

// ---- 3. Verify the driver's helper against the contract ----
if (helper != null) {
    println "Verifying resolveDeviceList against contract:"
    cases.each { c ->
        def got
        try { got = helper(c.input) } catch (Throwable t) { got = "<threw ${t.class.simpleName}: ${t.message}>" }
        boolean ok = (got == c.expect)
        println "  [${ok ? 'ok' : 'XX'}] ${c.name}: ${c.input} -> ${got}"
        if (!ok) fail("${c.name}: expected ${c.expect}, got ${got}")
    }
}

println ""
if (failures == 0) {
    println "pushover-device-param: PASS (${cases.size()} contract cases)"
    System.exit(0)
} else {
    println "pushover-device-param: FAIL (${failures} failure(s))"
    System.exit(1)
}

