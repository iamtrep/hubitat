// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Offline test for Reconnect Offline Devices' restart path. BRACE-EXTRACTS initialize and
// systemStartHandler from the shipped ReconnectOfflineDevices.groovy and checks that the app
// subscribes to the hub's systemStart event and that the handler re-arms the check, since a
// one-shot runIn that fell due during an outage is dropped.
//
// Run: groovy apps/utilities/tests/test-reconnect-offline-devices-startup.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'ReconnectOfflineDevices.groovy')
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

println 'Reconnect Offline Devices: restart path'
List<List> subs = []
int armed = 0
Binding b = new Binding()
b.setVariable('state', [:])
b.setVariable('settings', [devices: []])
b.setVariable('subscribe', { Object... a -> subs << a.toList() })
b.setVariable('checkVersion', { Object... a -> })
b.setVariable('watchAttr', { -> 'networkStatus' })
b.setVariable('watchValue', { -> 'offline' })
b.setVariable('reviveCommand', { -> 'initialize' })
b.setVariable('retryEnabled', { -> true })
b.setVariable('logWarn', { Object m -> })
b.setVariable('logCfg', { Object m -> })
b.setVariable('logSched', { Object m -> })
b.setVariable('fileLog', { Object m -> })
b.setVariable('scheduleCheck', { -> armed++ })
b.setVariable('location', 'LOCATION')
Script s = new GroovyShell(b).parse(
    extract(src, 'void initialize() {') + '\n' + extract(src, 'void systemStartHandler(evt) {') + '\ninitialize()')
s.run()
check 'initialize subscribes location systemStart to systemStartHandler',
    subs.any { it.size() == 3 && it[0] == 'LOCATION' && it[1] == 'systemStart' && it[2] == 'systemStartHandler' }
int before = armed
s.invokeMethod('systemStartHandler', [[name: 'systemStart']] as Object[])
check 'systemStartHandler re-arms the check', armed == before + 1

println "\n${pass}/${pass + fail} passed${fail ? ", ${fail} failed" : ''}"
System.exit(fail ? 1 : 0)
