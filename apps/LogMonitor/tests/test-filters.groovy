// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for LogMonitor.groovy's filter matching and its loop-guard decisions:
// matchesFilter (include/exclude), patternError (bad regex refused on save), warnDue (one warn per
// filter per hour) and isLocalBridge (the id guards apply only to a bridge on the local hub).
// Run: groovy apps/LogMonitor/tests/test-filters.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File dir = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '..')
String src = new File(dir, 'LogMonitor.groovy').text

int from = src.indexOf('// Null when the pattern is blank or compiles')
int mf = src.indexOf('private static boolean matchesFilter')
int to = src.indexOf('\n}\n', mf) + 3
assert from > 0 && mf > from && to > mf : 'filter helpers not found'
def L = new GroovyShell().parse("import java.util.concurrent.ConcurrentHashMap\nclass L {\n${src.substring(from, to)}\n}\nreturn L").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

Map entry(String msg, String level = 'error', String type = 'dev', Object id = 12) { [type: type, level: level, msg: msg, id: id] }
Map filt(Map over = [:]) { [monitorDev: true, monitorApp: true, monitorSys: true, levels: ['error'], deviceIds: '', appIds: '', includePattern: '', excludePattern: ''] + over }

// --- matchesFilter
t('include pattern matches anywhere in the message') {
    assert L.matchesFilter(entry('sensor went offline'), filt(includePattern: '(?i)OFFLINE'))
    assert !L.matchesFilter(entry('all good'), filt(includePattern: 'offline'))
}
t('exclude pattern drops a match, keeps the rest') {
    assert !L.matchesFilter(entry('heartbeat ok'), filt(excludePattern: 'heartbeat'))
    assert L.matchesFilter(entry('real failure'), filt(excludePattern: 'heartbeat'))
}
t('include and exclude together') {
    Map f = filt(includePattern: 'fail', excludePattern: 'retry')
    assert L.matchesFilter(entry('fail now'), f)
    assert !L.matchesFilter(entry('fail, will retry'), f)
}
t('level and type gates still apply') {
    assert !L.matchesFilter(entry('x', 'warn'), filt())
    assert !L.matchesFilter(entry('x', 'error', 'app'), filt(monitorApp: false))
}
t('an invalid pattern throws out of matchesFilter, which is why the caller catches per filter') {
    boolean threw = false
    try { L.matchesFilter(entry('abc'), filt(includePattern: '*x')) } catch (java.util.regex.PatternSyntaxException e) { threw = true }
    assert threw
}

// --- patternError
t('blank and valid patterns have no error') {
    assert L.patternError(null) == null
    assert L.patternError('') == null
    assert L.patternError('(?i)offline|battery') == null
}
t('invalid patterns are refused with a reason') {
    assert L.patternError('*error')
    assert L.patternError('(?i')
    assert L.patternError('[abc')
}

// --- warnDue
t('warn once per key per interval') {
    def m = new java.util.concurrent.ConcurrentHashMap<String, Long>()
    assert L.warnDue(m, '1:2', 1000L, 3600000L)
    assert !L.warnDue(m, '1:2', 2000L, 3600000L)
    assert L.warnDue(m, '1:3', 2000L, 3600000L)
    assert !L.warnDue(m, '1:2', 3600999L, 3600000L)
    assert L.warnDue(m, '1:2', 3601000L, 3600000L)
}

// --- isLocalBridge
t('a bridge with a blank hubAddress is local; one with an address is not') {
    List bridges = [[label: 'Local Hub', dni: 'logmon-bridge-0', hubAddress: ''], [label: 'Cottage', dni: 'logmon-bridge-1', hubAddress: '192.0.2.10']]
    assert L.isLocalBridge(bridges, 'logmon-bridge-0')
    assert !L.isLocalBridge(bridges, 'logmon-bridge-1')
}
t('missing hubAddress key is local; unknown bridge and empty list are not') {
    assert L.isLocalBridge([[label: 'L', dni: 'a']], 'a')
    assert !L.isLocalBridge([[label: 'L', dni: 'a', hubAddress: '']], 'zzz')
    assert !L.isLocalBridge([], 'a')
    assert !L.isLocalBridge(null, 'a')
}

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
