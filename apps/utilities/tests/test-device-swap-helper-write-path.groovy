// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Offline test for Device Swap Helper's write path. BRACE-EXTRACTS swapInput and executeSwap from the
// shipped DeviceSwapHelper.groovy and runs them with stubbed hub calls:
//   - a verify read that throws after a save that landed is reported as saved, with an undo row
//   - the audit line and undo row of each input are written before the next input is swapped
//
// Run: groovy apps/utilities/tests/test-device-swap-helper-write-path.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'DeviceSwapHelper.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String extract(String src, String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = src.indexOf('{', start)
    int depth = 0
    for (int i = open; i < src.length(); i++) {
        if (src[i] == '{') depth++
        else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'Device Swap Helper: swapInput, verify throws after a landed write'
Binding b1 = new Binding()
List<String> warns = []
int writes = 0
b1.setVariable('logWarn', { Object m -> warns << m.toString() })
b1.setVariable('logError', { Object m -> })
b1.setVariable('fetchConfig', { int id -> [settings: [sw: [1]], configPage: [name: 'mainPage']] })
b1.setVariable('findMainInput', { Map cfg, String n -> [name: n, type: 'capability.switch'] })
b1.setVariable('deviceIds', { Object v -> (v ?: []) as List<Integer> })
b1.setVariable('hasInputCapability', { int id, String t -> true })
b1.setVariable('writeDeviceInput', { int a, Map c, Map i, List l -> writes++; null })
b1.setVariable('checkWrite', { int a, String n, List e, Map s -> throw new RuntimeException('read timed out') })
b1.setVariable('capabilityRefusal', { String t -> 'x' })
b1.setVariable('deviceIdsOf', { int a, String n -> [] })
Script s1 = new GroovyShell(b1).parse(extract(src, 'private Map swapInput(Map entry, int sourceId, int targetId) {') +
    "\nswapInput([appId: 7, appLabel: 'App', inputName: 'sw', inputType: 'capability.switch'], 1, 2)")
Map r1 = s1.run() as Map
check 'the write was sent once', writes == 1
check 'reported as success so Undo can restore it', r1.success == true
check 'before and after recorded', r1.before == [1] && r1.after == [2]
check 'message says it could not be verified', r1.message.contains('could not verify')

println 'Device Swap Helper: executeSwap writes the record per input'
Binding b2 = new Binding()
Map state = [:]
List<String> audit = []
List<Map> snapshots = []
int call = 0
b2.setVariable('state', state)
b2.setVariable('sourceDevice', [id: 1, displayName: 'Old'])
b2.setVariable('targetDevice', [id: 2, displayName: 'New'])
b2.setVariable('logCmd', { Object m -> })
b2.setVariable('logWarn', { Object m -> })
b2.setVariable('selectedPending', { -> [[appId: 10], [appId: 11], [appId: 12]] })
b2.setVariable('swapInput', { Map e, int a, int b ->
    call++
    if (call == 3) throw new IllegalStateException('render interrupted')
    [appId: e.appId, appLabel: 'A', inputName: 'sw', success: true, before: [1], after: [2], message: 'ok']
})
b2.setVariable('auditLine', { String a, String d, Map r -> "${a} ${r.appId}".toString() })
b2.setVariable('appendAudit', { List<String> l -> audit.addAll(l); null })
b2.setVariable('checkApps', { List p, List r, int t, String n -> })
Script s2 = new GroovyShell(b2).parse(extract(src, 'private void executeSwap() {') + "\nexecuteSwap()")
boolean threw = false
try { s2.run() } catch (IllegalStateException e) { threw = true }
check 'the third input interrupted the run', threw
check 'audit lines for the two swapped inputs exist', audit == ['swap 10', 'swap 11']
check 'undo record holds the two swapped inputs', ((state.lastSwap as Map)?.results as List)?.size() == 2

println "\n${pass}/${pass + fail} passed${fail ? ", ${fail} failed" : ''}"
System.exit(fail ? 1 : 0)
