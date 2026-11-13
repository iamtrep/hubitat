// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// The pause-with-off and resume-restore mode writes must go through sendWrites, so they are
// verified and set lastApply like every other write. Extracts applyTarget from the shipped
// program (or the file named by -Dprogram) and runs it with stubs.

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
File prog = new File(System.getProperty('program') ?: new File(here.parentFile, 'HvacSchedulerProgram.groovy').path)
String src = prog.text
int a = src.indexOf('void applyTarget(')
int b = src.indexOf('\n}\n', a)
if (a < 0 || b < a) { println '[FAIL] applyTarget not found'; System.exit(2) }
String body = src.substring(a, b + 3)

failed = 0
void check(String name, Object got, Object want) {
    if (got == want) println "[PASS] ${name}"
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

String script = body + '''
class DeviceWrapper {}
class Stat extends DeviceWrapper { String id; String mode; Object currentValue(String n) { mode }; String getDisplayName() { "t" + id } }
thermostats = [new Stat(id: '1', mode: 'heat'), new Stat(id: '2', mode: 'off'), new Stat(id: '3', mode: 'cool')]
batches = []; direct = []
void sendWrites(List writes, Map rt, Map cfg) { batches << writes }
boolean send(Object d, String c, Object v) { direct << [d.id, c, v]; true }
void remember(Map rt, String id, String attr, Object v) {}
void logCmd(String m) {}
void logCfg(String m) {}
boolean sameTarget(Map x, Map y) { true }
List thermostatStates(Map m) { [] }
List planWrites(Map t, List s, Map o) { [] }
Map cfg = [options: [whilePaused: 'off', onResume: 'restore']]
Map rt = [pausedApplied: false, recordedModes: [:], sent: [:], manual: []]
applyTarget(cfg, rt, [layer: 'paused'], false, null)
pauseBatches = batches.collect { it }; batches.clear()
pauseModes = rt.recordedModes
applyTarget(cfg, rt, [layer: 'none'], false, null)
return [pause: pauseBatches, modes: pauseModes, resume: batches, direct: direct]
'''
Map r = new GroovyShell().evaluate(script) as Map
check('pause: one verified batch turns off only the thermostats that are not off',
      r.pause, [[[id: '1', command: 'setThermostatMode', value: 'off'], [id: '3', command: 'setThermostatMode', value: 'off']]])
check('pause: previous modes recorded', r.modes, ['1': 'heat', '3': 'cool'])
check('resume: restore goes through sendWrites',
      r.resume.take(1), [[[id: '1', command: 'setThermostatMode', value: 'heat'], [id: '3', command: 'setThermostatMode', value: 'cool']]])
check('no mode write bypasses sendWrites', r.direct, [])
System.exit(failed ? 1 : 0)
