// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for shared/send-changed.groovy, run against the shared source with the device stubbed.
//   groovy shared/tests/test_send_changed.groovy

here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

// The hub stores an event value as text; an undeclared attribute never gets a current state.
STUBS = '''
void sendEvent(Map e) { sent << e; if (!(e.name in undeclared)) current[e.name] = e.value.toString() }
void logWarn(m) { logs << "warn: ${m}".toString() }
void logInfo(m) { logs << "info: ${m}".toString() }
'''
Script load() {
    Script s = new GroovyShell().parse(STUBS + new File(here.parentFile, "send-changed.groovy").text)
    Map current = [:]
    s.binding.setVariable("current", current)
    s.binding.setVariable("sent", [])
    s.binding.setVariable("logs", [])
    s.binding.setVariable("undeclared", [])
    s.binding.setVariable("device", [displayName: "Dev",
        currentState: { String n -> current[n] == null ? null : [value: current[n]] },
        currentValue: { String n -> current[n] }])
    return s
}

Script s = load()
check("first value is sent", s.sendChanged(name: "temperature", value: 21.5), true)
check("same value is not sent", s.sendChanged(name: "temperature", value: 21.5), false)
check("same value as text is not sent", s.sendChanged(name: "temperature", value: "21.5"), false)
check("new value is sent", s.sendChanged(name: "temperature", value: 21.6), true)
check("null is not sent", s.sendChanged(name: "temperature", value: null), false)
check("two events went out", s.sent.size(), 2)

s = load()
s.undeclared << "extra"
s.sendChanged(name: "extra", value: "a"); s.sendChanged(name: "extra", value: "a")
check("undeclared attribute is sent each time", s.sent.size(), 2)

s = load()
s.updateHealth("online")
check("first health is sent without a log", [s.sent.size(), s.logs], [1, []])
s.updateHealth("online")
check("unchanged health is not sent", s.sent.size(), 1)
s.updateHealth("offline", "cloud unreachable")
check("offline is sent with the reason", s.sent[-1].descriptionText, "Dev is offline: cloud unreachable")
check("offline logs a warning", s.logs, ["warn: offline: cloud unreachable"])
s.updateHealth("offline", "another reason")
check("still offline is not sent again", s.sent.size(), 2)
s.updateHealth("online")
check("recovery logs info", s.logs[-1], "info: back online")

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
