// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the pure decisions of ZigbeeRadioWatchdog.groovy: what a radio state
// reading means, and when a new incident may open.
// Run: groovy apps/ZigbeeRadioWatchdog/tests/test-watchdog-core.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../ZigbeeRadioWatchdog.groovy').text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^static\s+[^\n(]*\b${name}\(/
    assert m.find() : "method not found: ${name}"
    int start = m.start()
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String code = ['radioVerdict', 'mayOpenIncident'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("class H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// --- radioVerdict: the payload of /hub/zigbeeDetails/json ---
t('an online, healthy radio is online') { assert H.radioVerdict([enabled: true, networkState: 'ONLINE', healthy: true]) == 'online' }
t('healthy missing counts as healthy') { assert H.radioVerdict([networkState: 'ONLINE']) == 'online' }
t('online but unhealthy is offline') { assert H.radioVerdict([enabled: true, networkState: 'ONLINE', healthy: false]) == 'offline' }
t('an enabled radio that is not online is offline') { assert H.radioVerdict([enabled: true, networkState: 'OFFLINE', healthy: false]) == 'offline' }
t('no network state is offline') { assert H.radioVerdict([:]) == 'offline' }
// What the hub reports after Zigbee is turned off in its settings.
t('Zigbee turned off in settings is disabled, not offline') { assert H.radioVerdict([enabled: false, networkState: 'DISABLED', healthy: false]) == 'disabled' }
t('enabled false alone is disabled') { assert H.radioVerdict([enabled: false, networkState: 'OFFLINE']) == 'disabled' }
t('DISABLED alone is disabled') { assert H.radioVerdict([networkState: 'DISABLED']) == 'disabled' }

// --- mayOpenIncident: no new incident right after one that left the radio offline ---
long H6 = 6 * 3600000L
t('nothing pending: an incident may open') { assert H.mayOpenIncident(null, 1000L, H6) }
t('just after an exhausted escalation: no new incident') { assert !H.mayOpenIncident(1000L, 1000L + 600000L, H6) }
t('one ms before the gap: still no') { assert !H.mayOpenIncident(1000L, 1000L + H6 - 1, H6) }
t('once the gap has passed: yes') { assert H.mayOpenIncident(1000L, 1000L + H6, H6) }

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
