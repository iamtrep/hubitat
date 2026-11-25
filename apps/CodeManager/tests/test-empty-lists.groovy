// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for CodeManager.groovy: an empty but valid hub answer ([] for a hub with no libraries,
// no bundles or no user drivers) is a successful read, not a failed one. httpGet is stubbed; no hub.
// Run: groovy apps/CodeManager/tests/test-empty-lists.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../CodeManager.groovy').text

String body(String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "not found: ${signature}"
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String code = [
    'private Object hubRequest(', 'private Map hubMapRequest(', 'private Object hubRequestInternal(',
    'private Semaphore gateFor(', 'private void countFetch(', 'private Object sharedRead(',
    'private List readAppTypes(', 'private List readDriverTypes(', 'private List readBundles(',
    'private List readLibrariesFromHub(', 'private boolean libFailActive(', 'private String readLibrariesNow(',
].collect { body(it) }.join('\n')

// The harness stands in for the app script: the constants and statics the extracted methods use, and stubs for the
// platform calls. `answer` is what the hub's JSON parses to; a Throwable is thrown from httpGet instead.
String harness = '''
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class H {
    static final String HUB_BASE = "http://127.0.0.1:8080"
    static final String USER_APP_TYPES_PATH = "/hub2/userAppTypes"
    static final String USER_DEVICE_TYPES_PATH = "/hub2/userDeviceTypes"
    static final String USER_LIBRARIES_PATH = "/hub2/userLibraries"
    static final String BUNDLE_LIST_PATH = "/bundle/list/json"
    static final long BULK_TTL_MS = 30_000L
    static final long BULK_WAIT_MS = 20_000L
    static final int LIB_TIMEOUT_SEC = 30
    ConcurrentHashMap BULK_CACHE = new ConcurrentHashMap()
    ConcurrentHashMap BULK_GATES = new ConcurrentHashMap()
    ConcurrentHashMap BULK_FETCHES = new ConcurrentHashMap()
    Map LIB_SNAPSHOT = [list: null, asOf: null, error: null, attemptedAt: 0L]
    boolean simulateLibFail = false
    Map settings = [:]
    Object answer
    long clock = 1_000_000L
    long now() { return clock }
    void httpGet(Map p, Closure c) { if (answer instanceof Throwable) throw (Throwable) answer; c([success: true, data: answer]) }
    String getObjectClassName(Object o) { return o.getClass().name }
    void logTrace(String s) {}
    void logDebug(String s) {}
    void logError(String s) {}
''' + code + '\n}\nreturn H'
Class H = new GroovyShell().parse(harness).run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// --- the request helper hands back what the hub answered ---
t('an empty JSON list comes back as an empty list') { def h = H.newInstance(); h.answer = []; def r = h.hubRequestInternal('/x', 'x', 'json', 5, false); assert r instanceof List && r.isEmpty() }
t('an empty JSON map comes back as an empty map') { def h = H.newInstance(); h.answer = [:]; def r = h.hubRequestInternal('/x', 'x', 'json', 5, false); assert r instanceof Map && r.isEmpty() }
t('a JSON zero comes back as zero') { def h = H.newInstance(); h.answer = 0; assert h.hubRequestInternal('/x', 'x', 'json', 5, false) == 0 }
t('no answer at all is still an empty map') { def h = H.newInstance(); h.answer = null; def r = h.hubRequestInternal('/x', 'x', 'json', 5, false); assert r instanceof Map && r.isEmpty() }
t('a failed request is still an error map') { def h = H.newInstance(); h.answer = new IOException('boom'); def r = h.hubRequestInternal('/x', 'x', 'json', 5, false); assert r instanceof Map && r.error == true }

// --- hubMapRequest keeps its contract ---
t('hubMapRequest: a map answer is its data') { def h = H.newInstance(); h.answer = [name: 'n']; def w = h.hubMapRequest('/x', 'x'); assert w.ok && w.data.name == 'n' }
t('hubMapRequest: no answer is ok with empty data') { def h = H.newInstance(); h.answer = null; def w = h.hubMapRequest('/x', 'x'); assert w.ok && w.data == [:] }
t('hubMapRequest: a list answer does not throw and gives empty data') { def h = H.newInstance(); h.answer = [[a: 1]]; def w = h.hubMapRequest('/x', 'x'); assert w.data == [:] }
t('hubMapRequest: a failed request is not ok') { def h = H.newInstance(); h.answer = new IOException('boom'); def w = h.hubMapRequest('/x', 'x'); assert !w.ok && w.error == 'boom' }

// --- a hub with no libraries ---
t('an empty library list is accepted as an empty list') { def h = H.newInstance(); h.answer = []; def r = h.readLibrariesFromHub(); assert r instanceof List && r.isEmpty() }
t('an empty library list gives a fresh asOf and no error') {
    def h = H.newInstance(); h.answer = []
    h.LIB_SNAPSHOT = [list: null, asOf: null, error: 'library list could not be read', attemptedAt: 1L]
    assert h.readLibrariesNow(true) == null
    assert h.LIB_SNAPSHOT.list == [] && h.LIB_SNAPSHOT.asOf == h.clock && h.LIB_SNAPSHOT.error == null
}
t('a failed library read still records the error and keeps the last good list') {
    def h = H.newInstance(); h.answer = new IOException('boom')
    h.LIB_SNAPSHOT = [list: [[id: '1']], asOf: 5L, error: null, attemptedAt: 5L]
    assert h.readLibrariesNow(true) == 'library list could not be read'
    assert h.LIB_SNAPSHOT.list == [[id: '1']] && h.LIB_SNAPSHOT.asOf == 5L && h.LIB_SNAPSHOT.error
}
t('a non-empty library list is still read') {
    def h = H.newInstance(); h.answer = [[id: 7, name: 'L', namespace: 'ns', version: 1, usedByAppTypes: 'A, B', usedByDeviceTypes: '']]
    def r = h.readLibrariesFromHub(); assert r.size() == 1 && r[0].id == '7' && r[0].usedByCount == 2
}

// --- a hub with no bundles or no user drivers: the empty list is read, cached and fresh (inventory reconciles it) ---
t('an empty bundle list is read and cached') {
    def h = H.newInstance(); h.answer = []
    def r = h.sharedRead('bundles', H.BULK_TTL_MS) { h.readBundles() }
    assert r == [] && h.BULK_CACHE['bundles']?.data == [] && h.BULK_CACHE['bundles'].at == h.clock
}
t('an empty driver list is read') { def h = H.newInstance(); h.answer = []; assert h.readDriverTypes() == [] }
t('an empty app list is read') { def h = H.newInstance(); h.answer = []; assert h.readAppTypes() == [] }
t('a failed bundle read is not cached') {
    def h = H.newInstance(); h.answer = new IOException('boom')
    assert h.sharedRead('bundles', H.BULK_TTL_MS) { h.readBundles() } == null && h.BULK_CACHE['bundles'] == null
}

println "${pass} passed, ${fail} failed"
if (fail) System.exit(1)
