// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Offline extraction test for BlinkManager: homescreen 401 limit, minimum poll gap, clip cursor.
// Loads the shipped functions by name from BlinkManager.groovy and runs them on a stub.
// Run: groovy integrations/Blink/tests/test-poll-guards.groovy      (no hub)
// SRC overrides the source file (used to run the test against an older copy).
class ChildDeviceWrapper {
    String deviceNetworkId; String blink; List updates = []
    String getDataValue(String k) { blink }
    void handleCameraUpdate(Map u) { updates << u }
}
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String srcPath = System.getenv('SRC') ?: new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../BlinkManager.groovy').path
@Field String src = new File(srcPath).text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^(?:private\s+)?(?:static\s+)?(?:void|boolean|long)\s+${name}\(/
    assert m.find() : "function not found: ${name}"
    int start = m.start(); int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String constant(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^@Field (static final [^\n]*\b${name}\b[^\n]*)$/
    return m.find() ? m.group(1) : ''   // a missing constant (older source) is left out
}
List<String> consts = ['HOMESCREEN_MIN_GAP_MS', 'HOMESCREEN_401_REFRESH_LIMIT', 'CLIP_INITIAL_WINDOW_SEC', 'DNI_PREFIX_CAMERA', 'HTTP_TIMEOUT'].collect { constant(it) }
List<String> funcs = ['pollHomescreen', 'handleHomescreenResponse', 'recentClipsResponse', 'isTransientStatus'].collect { extract(it) }
// An older source has no pollHomescreenNow: its pollHomescreen is the immediate poll.
funcs << (src.contains('void pollHomescreenNow(') ? extract('pollHomescreenNow') : 'void pollHomescreenNow() { pollHomescreen() }')
if (src =~ /(?m)^(?:private\s+)?static\s+long\s+nextClipCursor\(/) funcs << extract('nextClipCursor')

String harness = """
class H {
    ${consts.join('\n    ')}
    Map state = [:]; Map atomicState = [:]; long clock = 1_000_000_000L
    boolean authed = true; boolean refreshOk = true
    List calls = []; List<ChildDeviceWrapper> kids = []
    long now() { clock }
    void checkVersion() {}
    void logSched(String m) {}; void logNet(String m) {}; void logTrace(String m) {}; void logWarn(String m) { calls << 'warn' }
    void logError(String m) { calls << 'error' }; void logInfo(String m) {}; void logDebug(String m) {}
    boolean isAuthenticated() { authed }
    void fetchTierInfo(boolean a) {}
    void ensureValidToken() {}
    Map bearerHeaders() { [:] }
    void asynchttpGet(String cb, Map p, Map d = null) { calls << "get:" + cb }
    void runIn(int s, String m) { calls << "runIn:" + m }
    void runInMillis(long ms, String m, Map o = null) { calls << "runInMillis:" + m }
    boolean refreshAccessToken() { calls << 'refresh'; refreshOk }
    void clearTokensOnly() { calls << 'clearTokens' }
    void markChildrenOffline(String r) { calls << 'offline' }
    void noteRetryAfter(Object r) {}
    void noteCloudFailure(String m) { calls << 'cloudFailure' }
    String clearCloudFailures() { null }
    void processHomescreen(Map j, String r = null) { calls << 'process' }
    String describeHttpBody(Object r) { '' }
    List getChildDevices() { kids }
${funcs.join('\n')}
}
return H
"""
Class Hc = new GroovyShell(getClass().classLoader).parse(harness).run()

class Resp {
    int status; boolean error = false; String msg = ''; Object json = null
    int getStatus() { status }
    boolean hasError() { error || status >= 300 || status == 0 }
    String getErrorMessage() { msg }
    Object getJson() { json }
}

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// N-2: homescreen 401
t('first 401 spends one refresh and retries in 3 s', {
    def h = Hc.newInstance()
    h.handleHomescreenResponse(new Resp(status: 401), [:])
    assert h.calls.count('refresh') == 1
    assert h.calls.contains('runIn:pollHomescreenNow')
})
t('repeated 401s stop spending refreshes and sign out', {
    def h = Hc.newInstance()
    // The session is signed out once tokens are cleared, so no further polls arrive after that.
    int n = 0
    while (n < 10 && !h.calls.contains('clearTokens')) { h.handleHomescreenResponse(new Resp(status: 401), [:]); n++ }
    assert h.calls.contains('clearTokens') : "still refreshing after ${n} 401s"
    assert h.calls.count('refresh') == 1 : "refreshes: ${h.calls.count('refresh')}"
})
t('a failed refresh does not schedule the 3 s retry', {
    def h = Hc.newInstance(); h.refreshOk = false
    h.handleHomescreenResponse(new Resp(status: 401), [:])
    assert !h.calls.any { it.toString().startsWith('runIn') }
})
t('a successful homescreen resets the 401 budget', {
    def h = Hc.newInstance()
    h.handleHomescreenResponse(new Resp(status: 401), [:])
    h.handleHomescreenResponse(new Resp(status: 200, json: [networks: [[id: 1]]]), [:])
    h.calls.clear()
    h.handleHomescreenResponse(new Resp(status: 401), [:])
    assert h.calls.count('refresh') == 1 && !h.calls.contains('clearTokens')
})

// N-5: minimum gap
t('refresh calls inside the gap send nothing and share one deferred run', {
    def h = Hc.newInstance(); h.state.accountId = 'a'; h.state.tier = 'prod'
    h.pollHomescreen()
    5.times { h.clock += 2000L; h.pollHomescreen() }
    assert h.calls.count('get:handleHomescreenResponse') == 1 : "sent: ${h.calls.count('get:handleHomescreenResponse')}"
    assert h.calls.contains('runInMillis:pollHomescreen')
})
t('a call after the gap sends', {
    def h = Hc.newInstance(); h.state.accountId = 'a'; h.state.tier = 'prod'
    h.pollHomescreen(); h.clock += 31_000L; h.pollHomescreen()
    assert h.calls.count('get:handleHomescreenResponse') == 2
})
t('a scheduled tick 60 s apart, a little early, is not deferred', {
    def h = Hc.newInstance(); h.state.accountId = 'a'; h.state.tier = 'prod'
    h.pollHomescreen(); h.clock += 59_900L; h.pollHomescreen()
    assert h.calls.count('get:handleHomescreenResponse') == 2
})

// N-3: clips
Closure camera = { String name -> def c = new ChildDeviceWrapper(deviceNetworkId: 'blink-camera-1', blink: name); c }
t('a timeout keeps the cursor and sends no motion state', {
    def h = Hc.newInstance(); h.state.tier = 'prod'; h.kids = [camera('Porch')]
    h.atomicState.lastClipFetchTime = 1_000_000L
    h.recentClipsResponse(new Resp(status: 408, error: true, msg: 'timeout'), [sentAt: 1_000_030L])
    assert h.atomicState.lastClipFetchTime == 1_000_000L : "cursor ${h.atomicState.lastClipFetchTime}"
    assert h.kids[0].updates.isEmpty() : "dispatched ${h.kids[0].updates}"
})
t('a 5xx keeps the cursor', {
    def h = Hc.newInstance(); h.state.tier = 'prod'; h.kids = [camera('Porch')]
    h.atomicState.lastClipFetchTime = 1_000_000L
    h.recentClipsResponse(new Resp(status: 503), [sentAt: 1_000_030L])
    assert h.atomicState.lastClipFetchTime == 1_000_000L
})
t('a long outage bounds the lookback', {
    def h = Hc.newInstance(); h.state.tier = 'prod'
    h.atomicState.lastClipFetchTime = 1_000_000L
    h.recentClipsResponse(new Resp(status: 503), [sentAt: 1_100_000L])
    assert h.atomicState.lastClipFetchTime == 1_100_000L - 3600L
})
t('a 4xx moves the cursor on', {
    def h = Hc.newInstance(); h.state.tier = 'prod'
    h.atomicState.lastClipFetchTime = 1_000_000L
    h.recentClipsResponse(new Resp(status: 400), [sentAt: 1_000_030L])
    assert h.atomicState.lastClipFetchTime == 1_000_030L
})
t('success moves the cursor to the send time, not the callback time', {
    def h = Hc.newInstance(); h.state.tier = 'prod'; h.kids = [camera('Porch')]
    h.clock = 1_000_040_000L
    h.atomicState.lastClipFetchTime = 1_000_000L
    h.recentClipsResponse(new Resp(status: 200, json: [media: []]), [sentAt: 1_000_030L])
    assert h.atomicState.lastClipFetchTime == 1_000_030L : "cursor ${h.atomicState.lastClipFetchTime}"
    assert h.kids[0].updates == [[motion: 'inactive']]
})

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
