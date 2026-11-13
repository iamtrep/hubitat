// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction test: loads the shipped functions from HubInspector.groovy and runs them with the hub
// calls stubbed. No hub needed.
//  - A-1  enrichDevices: a failed /device/fullJson read is not cached ("ctSrc: device" entries are
//         trusted forever); a successful read is.
//  - A-3  analyzeDevices / analyzeApps flag a failed fetch, snapshotFetchFailure names it, and
//         createSnapshot stops before it builds or saves a snapshot.
//  - A-5  reportSaveError: only the SPA's own report file name is accepted, size is capped.
//  - A-8  fetchFirmwareUpdate remembers a failed check for a short time; the alert path's Z-Wave
//         read does not retry.
// Run: groovy apps/HubInspector/tests/test-snapshot-and-report-guards.groovy

import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0

@Field String src = new File(new File(new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile.parentFile, 'HubInspector.groovy').path).text

// A top-level function ends at the first closing brace in column 0.
String extract(String sig) {
    int start = src.indexOf(sig)
    assert start >= 0 : "signature not found: ${sig}"
    int end = src.indexOf('\n}\n', start)
    assert end > start : "end not found: ${sig}"
    return src.substring(start, end + 2)
}
void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

String connConstants = src.readLines().findAll { it.startsWith('@Field static final String CONN_') }.join('\n')
// The helpers added by the fix may be absent when this test runs against older code: the calls then fail.
String extractOpt(String sig) { src.contains(sig) ? extract(sig) : '' }
String harness = """
import java.util.concurrent.ConcurrentHashMap
import groovy.transform.Field
@groovy.transform.Field static final ConcurrentHashMap<String, Map> TTL_CACHE = new ConcurrentHashMap<>()
@groovy.transform.Field static final long FW_UPDATE_CACHE_TTL_MS = 3600000L
@groovy.transform.Field static final long FW_UPDATE_FAIL_TTL_MS = 300000L
@groovy.transform.Field static final String FIRMWARE_UPDATE_PATH = "/fw"
@groovy.transform.Field static final String DEVICES_LIST_PATH = "/devices"
long now() { System.currentTimeMillis() }
@groovy.transform.Field static final String FULL_JSON_PATH_PREFIX = "/device/fullJson/"
@groovy.transform.Field static final Set<String> VALID_CONN = ['cloud', 'lan_direct'] as Set
@groovy.transform.Field Map state = [:]
@groovy.transform.Field ConcurrentHashMap<String, Object> testCache = new ConcurrentHashMap<>()
${extract('private Object cachedFetch(String key, long ttlMs, Closure fetch) {')}
${extract('private Object cachePeek(String key, long ttlMs) {')}
${extract('private void cachePut(String key, Object data) {')}
${extract('Map fetchFirmwareUpdate() {')}
${extract('Map enrichDevices(Map uncertainDevices, Set communityAppTypeNames = [] as Set) {')}
${extract('private String fullJsonControllerType(Map fj) {')}
${extract('String safeToString(value, String defaultValue = "") {')}
${extract('Map analyzeDevices(boolean deep = true, Map prefetchedDevices = null) {')}
${extract('Map analyzeApps(boolean deep = true) {')}
${connConstants}
${extract('Map getEmptyDeviceStats() {')}
${extract('Map getEmptyAppStats() {')}
${extractOpt('static String snapshotFetchFailure(Map devices, Map apps) {')}
${extractOpt('static String reportSaveError(String filename, String html) {')}
this
"""
def app = new GroovyShell().evaluate(harness)

// ---- A-1 enrichDevices
Map fullResult = [ok: false, data: [:]]
int fullCalls = 0
app.metaClass.hubMapRequest = { String path, String label, int timeout = 30, boolean retry = true -> fullCalls++; fullResult }
app.metaClass.controllerTypeCache = { -> app.testCache }
app.metaClass.logNet = { String m -> }
app.metaClass.logWarn = { String m -> }
app.metaClass.classifyFromParentApp = { String n, Object a, boolean lan, Boolean b -> [connectionType: lan ? 'lan_direct' : 'cloud', integration: n, builtin: b] }
app.metaClass.cleanIntegrationName = { String n -> n }
app.metaClass.getCONTROLLER_TYPE_CONN = { -> [ZGB: 'zigbee'] }
app.metaClass.getCONN_OTHER = { -> 'other' }
app.metaClass.getCONN_LAN_DIRECT = { -> 'lan_direct' }

t('A-1 failed fullJson read leaves the cache empty') {
    app.testCache.clear(); fullCalls = 0
    fullResult = [ok: false, data: [:], error: 'timeout']
    Map r = app.enrichDevices(['42': [type: 'x']])
    assert app.testCache.isEmpty() : "cache wrote ${app.testCache}"
    assert r == [:]
}
t('A-1 the next pass fetches again') {
    int before = fullCalls
    app.enrichDevices(['42': [type: 'x']])
    assert fullCalls == before + 1
}
t('A-1 a successful read is cached and trusted') {
    app.testCache.clear(); fullCalls = 0
    fullResult = [ok: true, data: [parentApp: [appType: [name: 'Cloud App', user: false]], device: [controllerType: 'LNK']]]
    Map r = app.enrichDevices(['42': [type: 'x']])
    assert app.testCache['42']?.ctSrc == 'device'
    assert app.testCache['42'].parentAppTypeName == 'Cloud App'
    assert r['42'] != null
    app.enrichDevices(['42': [type: 'x']])
    assert fullCalls == 1 : 'a trusted entry must not refetch'
}
t('A-1 a successful read of a parentless device is still cached') {
    app.testCache.clear()
    fullResult = [ok: true, data: [device: [controllerType: 'ZGB']]]
    app.enrichDevices(['7': [type: 'x']])
    assert app.testCache['7']?.ctSrc == 'device'
}

// ---- A-3 analyzers flag failure; helper names it; createSnapshot stops
t('A-3 analyzeDevices flags a failed devices fetch') {
    fullResult = [ok: false, data: [:]]
    Map d = app.analyzeDevices()
    assert d.fetchFailed == true
    assert d.totalDevices == 0
}
t('A-3 analyzeApps flags an empty apps list') {
    app.metaClass.fetchAppsList = { -> null }
    assert app.analyzeApps().fetchFailed == true
    assert app.analyzeApps(false).fetchFailed == null   // the quick count is not stored in snapshots
}
t('A-3 snapshotFetchFailure: failed devices') { assert app.snapshotFetchFailure([fetchFailed: true], [totalApps: 3]) }
t('A-3 snapshotFetchFailure: failed apps') { assert app.snapshotFetchFailure([totalDevices: 3], [fetchFailed: true]) }
t('A-3 snapshotFetchFailure: both real') { assert app.snapshotFetchFailure([totalDevices: 3], [totalApps: 3]) == null }
t('A-3 snapshotFetchFailure: null input') { assert app.snapshotFetchFailure(null, [:]) }
t('A-3 createSnapshot checks before it saves') {
    String body = extract('void createSnapshot() {')
    int chk = body.indexOf('snapshotFetchFailure(')
    int save = body.indexOf('saveSnapshots(')
    assert chk > 0 : 'createSnapshot does not call snapshotFetchFailure'
    assert chk < save
    assert body.indexOf('return', chk) < body.indexOf('Map snapshot = [')
}

// ---- A-5 report save name and size
String good = 'hub_diagnostics_report_20260328_142000.html'
t('A-5 the SPA report name is accepted') { assert app.reportSaveError(good, '<html>') == null }
t('A-5 the UI file is refused') { assert app.reportSaveError('hub_diagnostics_ui.html', '<html>') }
t('A-5 another app .html is refused') { assert app.reportSaveError('OtherApp.html', '<html>') }
t('A-5 a prefix match with a suffix is refused') { assert app.reportSaveError('hub_diagnostics_report_x.html', '<html>') }
t('A-5 trailing text is refused') { assert app.reportSaveError(good + 'x', '<html>') }
t('A-5 a path is refused') { assert app.reportSaveError('../' + good, '<html>') }
t('A-5 a newline before .html is refused') { assert app.reportSaveError('hub_diagnostics_report_20260328_142000\n.html', '<html>') }
t('A-5 oversize body is refused') { assert app.reportSaveError(good, 'x' * 5_000_001) }
t('A-5 a body at the cap is accepted') { assert app.reportSaveError(good, 'x' * 5_000_000) == null }

// ---- A-8
int fwCalls = 0
Map fwResult = [ok: false, data: [:]]
app.metaClass.hubMapRequest = { String path, String label, int timeout = 30, boolean retry = true -> fwCalls++; fwResult }
app.metaClass.getHubFirmwareVersion = { -> '2.5.3.1' }
app.metaClass.fetchDiagToolVersions = { -> [:] }
t('A-8 a failed firmware check is not repeated within the failure window') {
    app.TTL_CACHE.clear(); fwCalls = 0
    app.fetchFirmwareUpdate(); app.fetchFirmwareUpdate(); app.fetchFirmwareUpdate()
    assert fwCalls == 1 : "fetched ${fwCalls} times"
}
t('A-8 the failure marker expires') {
    app.TTL_CACHE['fwUpdateFailed'] = [data: [failed: true], at: System.currentTimeMillis() - 400_000L]
    int before = fwCalls
    app.fetchFirmwareUpdate()
    assert fwCalls == before + 1
}
t('A-8 a good check is cached for the long TTL') {
    app.TTL_CACHE.clear(); fwCalls = 0
    fwResult = [ok: true, data: [version: '2.5.4.1', upgrade: true, status: 'ok']]
    assert app.fetchFirmwareUpdate().availableVersion == '2.5.4.1'
    app.fetchFirmwareUpdate()
    assert fwCalls == 1
}
t('A-8 the alert-path Z-Wave read does not retry') {
    String body = extract("Map getAlertSignals(Map shared = [:]) {")
    int at = body.indexOf('hubMapRequest(ZWAVE_DETAILS_PATH')
    assert at > 0
    String call = body.substring(at, body.indexOf('\n', at))
    assert call.trim().endsWith(', 8, false)') : call
}

println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
