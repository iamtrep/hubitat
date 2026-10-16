// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant) for the Android TV Remote Tester's pure helpers:
// brace-extracts them from AndroidTVRemoteTester.groovy and runs them under plain Groovy.
//
// Run: groovy drivers/androidtv/tests/test-tester-helpers.groovy   (about 5 s, no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile, 'AndroidTVRemoteTester.groovy')
assert src.exists() : "source not found: ${src}"
String text = src.text

String extract(String text, String signature) {
    int start = text.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = text.indexOf('{', start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        if (text[i] == '{') depth++
        else if (text[i] == '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

// Every helper signature the tests call; later tasks append to this list.
List<String> SIGNATURES = [
    'Map parseLogLine(String raw, Long devId, String label) {',
    'List<Map> deviceLines(List raw, Long devId, String label, String since) {',
    'Long tsMs(String ts, TimeZone tz) {',
    'String maskLine(String s) {',
    'List<String> traceLines(List<Map> lines) {',
    'String otherMessages(List<Map> lines) {',
    'Map evalSessionStart(List<Map> lines, Map steps) {',
    'Map evalPings(List<Map> lines, TimeZone tz) {',
    'Map evalPower(List<Map> lines, Map steps) {',
    'Map evalVolume(List<Map> lines) {',
    'Map evalLaunch(List<Map> lines, Map steps) {',
    'Map evalRecovery(List<Map> lines, Map steps, TimeZone tz) {',
    'Map evalHome(List<Map> lines, Map steps) {',
    'Map evalStandby(List<Map> lines, Map steps) {',
    'Map evalPowerCut(List<Map> lines, Map steps, Map attrs, TimeZone tz) {',
    'Map evalWrongCode(List<Map> lines, Map steps) {',
    'Map evalPairing(List<Map> lines) {',
    'Map evalConnect(List<Map> lines) {',
    'boolean hasEvent(List<Map> events, String name, String value) {',
    'String lastEvent(List<Map> events, String name) {',
    'Map evalEvents(int test, List<Map> events, Map f) {',
    'boolean runStalled(Map run, long nowMs) {',
    'Map splitDiffs(List diffs, Map expect) {',
    'String verdictOf(Map r) {',
    'String verdictText(Map r) {',
    'List<Map> testRows(Map run, Map names) {',
    'List<String> differences(Map fields, Map expect) {',
    'private String lastApp(List<Map> lines) {',
    'String formatReport(Map header, List<Map> results) {',
    'String wrongPairingCode(String clientPem, String tvPem) {',
    'byte[] unsignedBytes(BigInteger v) {',
    'int pollGap(int timeoutS, Long lastReadMs) {',
]
String IMPORTS = "import java.security.MessageDigest\nimport java.security.cert.CertificateFactory\nimport java.text.SimpleDateFormat\n"
String DROP = "String getDROP_RE() { '^(remote connect returned|Socket \\\\(|No message from the TV|Connect failed)' }\n"
def h = new GroovyShell().parse(IMPORTS + DROP + SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

String L = "Test TV"
String raw1 = "2026-10-15 11:44:55.588\tTRACE\tdev|42|Test TV|🔬 Test TV: rx 3:{1:7 2:{90:{1:\"market://launch?id=x\"}}}"
String raw2 = "2026-10-15 11:44:55.546\tINFO \tdev|42|Test TV|⬆️ Test TV: Launch market://launch?id=x"
String other = "2026-10-15 11:44:56.000\tINFO \tdev|99|Lamp|Lamp: on"
String old = "2026-10-13 09:00:00.000\tINFO \tdev|42|Test TV|Test TV: Paired"
String plainInfo = "2026-10-15 11:46:43.127\tINFO \tdev|42|Test TV|Test TV: Enter the code shown on the TV with Submit Pairing Code"

println 'sandbox-blocked property names'
// The hub refuses these as property names at run time (SecurityException), and neither the
// parse-check nor the lint sees it: keep them out of map keys and dot access in the app.
['fields', 'metaClass', 'methods', 'properties', 'class'].each { String n ->
    check "no .${n} or ${n}: in the app", !(text =~ /\.${n}\b(?!\s*\()|[\[,(]\s*${n}\s*:/)
}

println 'parseLogLine'
Map p = h.parseLogLine(raw1, 42L, L)
check 'ts', p.ts == '2026-10-15 11:44:55.588'
check 'level lower-cased and trimmed', p.level == 'trace'
check 'label prefix stripped', p.msg == 'rx 3:{1:7 2:{90:{1:"market://launch?id=x"}}}'
check 'info line without emoji', h.parseLogLine(plainInfo, 42L, L).msg.startsWith('Enter the code shown')
check 'other device ignored', h.parseLogLine(other, 42L, L) == null
check 'garbage ignored', h.parseLogLine('Possible solutions: foo', 42L, L) == null

println 'deviceLines'
List<Map> d = h.deviceLines([raw1, other, old, raw2], 42L, L, '2026-10-15 00:00:00.000')
check 'filters device and since', d.size() == 2
check 'sorted by time', d[0].msg.startsWith('Launch') && d[1].msg.startsWith('rx 3:')

println 'tsMs'
TimeZone utc = TimeZone.getTimeZone('UTC')
check 'difference in ms', h.tsMs('2026-10-15 11:44:55.588', utc) - h.tsMs('2026-10-15 11:44:55.546', utc) == 42L

println 'maskLine'
String pemLine = 'TV certificate: -----BEGIN CERTIFICATE-----|MIICGTCCAgKgAwIBAgIGAYgES3rw|-----END CERTIFICATE-----'
check 'pem masked', h.maskLine(pemLine) == 'TV certificate: <pem>'
check 'ip masked', h.maskLine('Connecting to 192.0.2.10:6466') == 'Connecting to <ip>:6466'
check 'long hex masked', h.maskLine('tx pair 1:2 2:200 40:{1:0x0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF}') == 'tx pair 1:2 2:200 40:{1:<hex>}'
check 'short hex kept', h.maskLine('TV reported 08071218') == 'TV reported 08071218'
check 'version strings kept', h.maskLine('6:"7.00.956317615"') == '6:"7.00.956317615"'

println 'traceLines'
List<Map> tl = [[ts: '2026-10-15 11:41:26.093', msg: 'ping #1 val1=1 gap=0 ms 8:{1:1 2:6219257044}'],
                [ts: '2026-10-15 11:41:07.293', msg: 'chunk 996 bytes, mode hello, buffered 0 bytes'],
                [ts: '2026-10-15 11:44:55.588', msg: 'Connecting to 192.0.2.10:6466']]
List<String> out = h.traceLines(tl)
check 'chunks left out, pings kept', out.size() == 2 && out[0].startsWith('11:41:26.093 ping #1 ')
check 'time and masked text', out[1] == '11:44:55.588 Connecting to <ip>:6466'
check 'capped at 40', h.traceLines((1..60).collect { [ts: '2026-10-15 11:44:55.588', msg: "rx 20:${it}"] }).size() == 40

def ln = { String t, String m -> [ts: "2026-10-15 ${t}".toString(), level: 'trace', msg: m] }

println 'otherMessages'
check 'handled types only: none', h.otherMessages([ln('11:00:00.000', 'rx 1:{1:639}'), ln('11:00:00.100', 'rx 2:{1:622}'),
    ln('11:00:00.200', 'rx 50:{6:15}'), ln('11:00:00.300', 'rx 40:{1:1}'), ln('11:00:00.400', 'rx 20:{1:{12:"x"}}'), ln('11:00:00.500', 'rx 3:{1:7}')]) == null
check 'unhandled types listed once, in order', h.otherMessages([ln('11:00:00.000', 'rx 51:{1:1}'), ln('11:00:00.100', 'rx 22:{}'),
    ln('11:00:00.200', 'rx 51:{1:2}')]) == '22,51'
check 'pairing and sent messages ignored', h.otherMessages([ln('11:00:00.000', 'pair rx 1:2 2:200 11:{}'), ln('11:00:00.100', 'tx 10:{1:3 2:3}')]) == null
check 'none rule: absent passes', h.differences([:], [otherMessages: [none: true]]) == []
check 'none rule: present differs', h.differences([otherMessages: '22'], [otherMessages: [none: true]]) == ['otherMessages']

println 'evalSessionStart'
List<Map> start = [
    ln('11:44:57.791', 'rx 1:{1:639 2:{1:"EBOX TV" 2:"Technicolor" 3:1 4:"11" 5:"com.google.android.tv.remote.service" 6:"7.00.956317615"} 3:1}'),
    ln('11:44:57.811', 'tx 1:{1:615 2:{3:1 4:"1" 5:"atvremote" 6:"1.0.0"}}'),
    ln('11:44:57.833', 'rx 2:""'),
    ln('11:44:57.889', 'rx 50:{1:2252 2:9 3:"EBOX TV" 4:1}'),
    ln('11:44:57.904', 'rx 40:{1:1}'),
    ln('11:44:57.922', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}'),
    ln('11:45:02.900', 'ping #1 val1=1 gap=0 ms 8:{1:1 2:6219257044}')]
Map s1 = h.evalSessionStart(start, [ready: [ok: true, ms: 1300L]])
check 'model and vendor', s1.model == 'EBOX TV' && s1.vendor == 'Technicolor'
check 'android and service', s1.androidVersion == '11' && s1.remoteServiceVersion == '7.00.956317615'
check 'features', s1.features == 639L
check 'first messages', s1.firstMessages == '1,2,50,40,20'
check 'setup first, power and app', s1.setupFirst && s1.powerReported && s1.appReported
check 'start time from step', s1.startMs == 1300L

println 'evalPings'
List<Map> pings = [ln('11:00:00.000', 'ping #1 val1=1 gap=0 ms 8:{}'), ln('11:00:55.000', 'ping #12 val1=12 gap=5000 ms 8:{}'),
                   ln('11:00:20.000', 'ping #5 val1=5 gap=8594 ms 8:{}')]
List<Map> sess = [ln('11:00:00.000', 'ping #9 val1=9 gap=5000 ms 8:{}'), ln('11:00:01.000', 'rx 40:{1:1}'),
                  ln('11:00:05.900', 'ping #1 val1=1 gap=0 ms 8:{}'), ln('11:00:20.000', 'ping #5 val1=5 gap=8594 ms 8:{}')]
Map s2 = h.evalPings(sess, utc)
check 'first ping measured from the power message', s2.firstPingMs == 4900L
check 'only gaps outside 3-7 s counted', s2.loggedGaps == 1
check 'no ping after the session start', h.evalPings([ln('11:00:01.000', 'rx 40:{1:1}')], utc).firstPingMs == null

println 'evalPower'
List<Map> pw = [ln('11:46:00.000', 'tx 10:{1:26 2:3}'), ln('11:46:00.600', 'rx 40:{1:0}'),
                ln('11:46:00.700', 'rx 50:{1:2 2:2 3:"EBOX TV" 4:2 5:0 6:15 7:0 8:1}')]
Map s3 = h.evalPower(pw, [off: [ok: true, ms: 600L], on: [ok: true, ms: 500L]])
check 'report times', s3.offReportMs == 600L && s3.onReportMs == 500L
check 'session kept', s3.sessionKept == true
check 'volume during change', s3.volumeDuringChange == true
check 'drop detected', h.evalPower(pw + [ln('11:46:05.000', 'remote connect returned after 1073 ms')], [off: [ok: true, ms: 1L], on: [ok: true, ms: 1L]]).sessionKept == false

println 'evalVolume'
check 'not reported', h.evalVolume([ln('11:00:00.000', 'tx 10:{1:24 2:3}')]).volumeReported == false
Map s4 = h.evalVolume([ln('11:00:00.000', 'rx 50:{1:2 2:2 3:"X" 4:2 5:0 6:100 7:21 8:0}')])
check 'reported with range', s4.volumeReported && s4.volumeRange == 100L && s4.volumeLevel == 21L

check 'volume first field after brace', h.evalVolume([ln('11:00:00.000', 'rx 50:{6:100 7:20}')]).volumeRange == 100L
check 'level omitted when zero', h.evalVolume([ln('11:00:00.000', 'rx 50:{6:15 8:1}')]).volumeLevel == 0L
check 'volume during change, brace form', h.evalPower([ln('11:00:00.000', 'rx 50:{6:100 7:20}')], [off: [ok: true, ms: 1L], on: [ok: true, ms: 1L]]).volumeDuringChange == true

println 'evalLaunch'
Map s5 = h.evalLaunch([ln('11:00:00.000', 'tx 90:{1:"https://www.youtube.com"}'), ln('11:00:01.300', 'rx 20:{1:{12:"com.google.android.youtube.tv"}}')], [launch: [ok: true, ms: 1300L]])
check 'accepted and app', s5.accepted && s5.app == 'com.google.android.youtube.tv' && s5.errorCode == null && s5.launchMs == 1300L
Map s6 = h.evalLaunch([ln('11:00:00.000', 'TV rejected 90:{1:"market://launch?id=x"} (error 7); reconnecting')], [launch: [ok: true, ms: 30L]])
check 'rejected with code', s6.accepted == false && s6.errorCode == '7'

println 'evalRecovery'
List<Map> rec = [ln('11:00:00.000', 'TV rejected 90:{1:"x"} (error 7); reconnecting'), ln('11:00:02.200', 'rx 40:{1:1}'),
                 ln('11:00:04.000', 'tx 10:{1:3 2:3}')]
Map s7 = h.evalRecovery(rec, [home: [ok: true, ms: 5L]], utc)
check 'error, reconnect time, next command', s7.boxError == '7' && s7.reconnectMs == 2200L && s7.nextCommandOk == true
check 'send error fails next command', h.evalRecovery(rec + [ln('11:00:04.010', 'Socket (remote): send error: Connection or outbound has closed')], [home: [ok: true, ms: 5L]], utc).nextCommandOk == false
check 'no rejection', h.evalRecovery([ln('11:00:00.000', 'rx 20:{1:{12:"com.android.vending"}}')], [home: [ok: false]], utc).boxError == null

println 'evalHome'
Map s8 = h.evalHome([ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.youtube.tv"}}'), ln('11:00:03.000', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}')], [home: [ok: true, ms: 800L]])
check 'home app and changed', s8.homeApp == 'com.google.android.tvlauncher' && s8.appChanged == true

println 'evalStandby'
Map s9 = h.evalStandby([ln('12:00:00.000', 'rx 40:{1:0}'), ln('12:30:00.000', 'rx 40:{1:1}')], [on: [ok: true, ms: 300L]])
check 'no drops, on worked', s9.drops == 0 && s9.onWorked == true

println 'evalPowerCut'
long cutAt = h.tsMs('2026-10-15 11:47:24.000', utc)
List<Map> cut = [ln('11:47:49.267', 'No message from the TV in 20 s; reconnecting'), ln('11:48:24.870', 'Connect failed after 30448 ms: SocketTimeoutException: connect timed out')]
Map s10 = h.evalPowerCut(cut, [cut: [ok: true, at: cutAt], back: [ok: true, ms: 73000L]], [pairing: 'paired', switch: 'on'], utc)
check 'watchdog noticed it', s10.noticedBy == 'watchdog' && s10.noticeMs == 25267L
check 'back, paired, switch', s10.backMs == 73000L && s10.stillPaired && s10.switchAfterBoot == 'on'
check 'socket error wins', h.evalPowerCut([ln('11:47:30.000', 'Socket (remote): receive error: Connection reset')], [cut: [ok: true, at: cutAt], back: [ok: false]], [pairing: 'paired', switch: 'off'], utc).noticedBy == 'socket error'

println 'evalWrongCode'
Map s11 = h.evalWrongCode([ln('11:46:44.098', 'Pairing failed with status 402')], [resume: [ok: false], reconnect: [ok: true]])
check 'status, old pairing, no resume', s11.status == 402L && s11.oldPairingWorks && s11.resumedOnOwn == false

println 'evalPairing'
def lv = { String t, String lvl, String m -> [ts: "2026-10-15 ${t}".toString(), level: lvl, msg: m] }
Map pr = h.evalPairing([lv('11:46:00.000', 'trace', 'TV certificate: <pem>'), lv('11:46:01.000', 'trace', 'pair rx 1:2 2:200 11:""'),
                        lv('11:46:02.000', 'info', 'Enter the code shown on the TV with Submit Pairing Code'),
                        lv('11:46:10.000', 'error', 'Pairing failed with status 402')])
check 'all stages, 402, no other error', pr.certRead && pr.pairingReply && pr.codeShown && pr.wrongCodeStatus == 402L && pr.firstError == null
Map pr2 = h.evalPairing([lv('11:46:00.000', 'error', 'java.net.ConnectException: Connection refused to 192.0.2.10 on line 144 (method startPairing)')])
check 'stopped at the certificate, error masked', !pr2.certRead && !pr2.codeShown && pr2.wrongCodeStatus == null && pr2.firstError.contains('<ip>')

println 'evalConnect'
Map cn = h.evalConnect([lv('11:00:00.000', 'warn', 'Connect failed after 30448 ms: SocketTimeoutException: connect timed out')])
check 'not connected, first error', !cn.connected && !cn.connectReturned && cn.firstError.startsWith('Connect failed')
check 'connected', h.evalConnect([lv('11:00:00.000', 'trace', 'remote connect returned after 80 ms'), lv('11:00:01.000', 'trace', 'rx 40:{1:1}')]).with { it.connected && it.connectReturned && it.firstError == null }

println 'evalEvents'
def ev = { String n, String v -> [name: n, value: v, at: 1L] }
check 'session start events', h.evalEvents(1, [ev('networkStatus', 'online'), ev('switch', 'on'), ev('currentApp', 'a')], [:]) == [onlineEvent: true, switchEvent: true, appEvent: true]
check 'missing app event', h.evalEvents(1, [ev('networkStatus', 'online'), ev('switch', 'on')], [:]).appEvent == false
check 'power events', h.evalEvents(3, [ev('switch', 'off'), ev('switch', 'on')], [:]) == [offEvent: true, onEvent: true]
check 'volume reported but no event', h.evalEvents(4, [], [volumeReported: true]).volumeEventMatches == false
check 'no volume, no event', h.evalEvents(4, [], [volumeReported: false]).volumeEventMatches == true
check 'app event matches the box', h.evalEvents(5, [ev('currentApp', 'x'), ev('currentApp', 'yt')], [app: 'yt']) == [appEvent: 'yt', appEventMatches: true]
check 'app event missing', h.evalEvents(6, [], [app: 'yt']).appEventMatches == false
check 'home app event', h.evalEvents(8, [ev('currentApp', 'home')], [homeApp: 'home']).appEventMatches == true
check 'offline then online', h.evalEvents(10, [ev('networkStatus', 'offline'), ev('networkStatus', 'online')], [:]) == [offlineEvent: true, onlineEvent: true]
check 'pairing after', h.evalEvents(12, [ev('pairing', 'pairing'), ev('pairing', 'unpaired')], [:]).pairingAfter == 'unpaired'

println 'differences'
check 'eq, max, between', h.differences([a: true, b: 4000L, c: 7000L, d: null],
        [a: [eq: true], b: [max: 5000], c: [between: [4000, 6000]], d: [max: 10]]) == ['c', 'd']
check 'Integer vs Long equal', h.differences([status: 402], [status: [eq: 402L]]) == []

println 'runStalled'
check 'on time', !h.runStalled([status: 'running', nextDueMs: 100000L], 150000L)
check 'a minute late', h.runStalled([status: 'running', nextDueMs: 100000L], 160001L)
check 'finished runs never stall', !h.runStalled([status: 'done', nextDueMs: 1L], 999999L)
check 'no due time yet', !h.runStalled([status: 'running'], 999999L)
check 'no run', !h.runStalled(null, 1L)

println 'splitDiffs, verdicts'
Map ex = [a: [eq: true], b: [eq: true, driver: true], c: [max: 5]]
check 'split by driver mark', h.splitDiffs(['a', 'b', 'c'], ex) == [driverDiffs: ['b'], boxDiffs: ['a', 'c']]
check 'verdict ok', h.verdictOf([diffs: [], driverDiffs: [], boxDiffs: []]) == 'ok' && h.verdictText([diffs: []]) == 'as expected'
check 'verdict box', h.verdictOf([diffs: ['a'], driverDiffs: [], boxDiffs: ['a']]) == 'box'
check 'driver wins', h.verdictOf([diffs: ['a', 'b'], driverDiffs: ['b'], boxDiffs: ['a']]) == 'driver'
check 'both in text', h.verdictText([diffs: ['a', 'b'], driverDiffs: ['b'], boxDiffs: ['a']]) == 'driver differs (b); box differs (a)'
check 'skipped', h.verdictOf([skipped: 'no plug']) == 'skipped' && h.verdictText([skipped: 'no plug']) == 'skipped: no plug'
check 'older result without split reads as box', h.verdictText([diffs: ['a']]) == 'box differs (a)'

println 'testRows'
Map names = [0: 'Connection', 1: 'Session start', 2: 'Pings', 3: 'Power']
Map running = [status: 'running', tests: [0, 1, 2, 3], idx: 2, step: 1,
               results: [[test: 1, diffs: [], driverDiffs: [], boxDiffs: []]]]
List<Map> tr = h.testRows(running, names)
check 'precheck hidden when it passed', tr*.test == [1, 2, 3]
check 'done, running with step, waiting', tr*.state == ['ok', 'running', 'waiting'] && tr[1].detail == 'step 2'
check 'step of count', h.testRows(running + [stepCount: 3], names)[1].detail == 'step 2 of 3'
check 'settling after the last step', h.testRows(running + [stepCount: 1], names)[1].detail == 'step 1 of 1'
Map aborted = [status: 'aborted', tests: [0, 1, 2], idx: 0, results: [[test: 0, diffs: ['connected'], driverDiffs: [], boxDiffs: ['connected']]]]
List<Map> ta = h.testRows(aborted, names)
check 'failed precheck shown, rest not run', ta*.test == [0, 1, 2] && ta*.state == ['box', 'not run', 'not run']

println 'formatReport'
String rep = h.formatReport([appVersion: '0.1.0', driverVersion: '0.0.2', firmware: '2.5.2.138', vendor: 'Technicolor', model: 'EBOX TV',
                             androidVersion: '11', remoteServiceVersion: '7.00.956317615', features: 639L, slowestReadMs: 2140L, status: 'done'],
    [[test: 1, name: 'Session start', observed: [model: 'EBOX TV', startMs: 1300L], diffs: []],
     [test: 6, name: 'Launch by package', observed: [accepted: false, errorCode: '7'], diffs: ['accepted', 'app'], boxDiffs: ['accepted', 'app'], driverDiffs: [], trace: ['11:44:55.588 rx 3:{1:7}']],
     [test: 10, name: 'Power cut', skipped: 'no plug selected']])
check 'title and versions', rep.startsWith('Android TV Remote Tester report\napp 0.1.0 · driver 0.0.2 · hub firmware 2.5.2.138')
check 'as expected line', rep.contains('1. Session start — as expected\n   model=EBOX TV startMs=1300')
check 'box differs line with trace', rep.contains('6. Launch by package — box differs (accepted, app)') && rep.contains('     11:44:55.588 rx 3:{1:7}')
check 'legend line', rep.contains('box differs: the TV does something')
check 'skipped line', rep.contains('10. Power cut — skipped: no plug selected')
check 'note line', h.formatReport([status: 'done'], [[test: 9, name: 'Long standby', observed: [drops: 0], diffs: [], note: 'trace off']]).contains('   drops=0\n   note: trace off')
check 'trace printed without a difference', h.formatReport([status: 'done'], [[test: 1, name: 'Session start', observed: [model: 'X'], diffs: [], trace: ['11:00:00.000 rx 1:{1:639}']]])
    .contains('1. Session start — as expected\n   model=X\n   trace:\n     11:00:00.000 rx 1:{1:639}')
check 'warning on top', h.formatReport([status: 'aborted', warning: 'plug did not turn back on'], []).split('\n')[1] == 'WARNING: plug did not turn back on'

println 'wrongPairingCode'
File tmp = File.createTempDir()
['a', 'b'].each { n ->
    def proc = ['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', "${tmp}/${n}.key", '-out', "${tmp}/${n}.pem",
             '-days', '1', '-subj', "/CN=${n}"].execute(); proc.waitFor()
}
String pemA = new File(tmp, 'a.pem').text, pemB = new File(tmp, 'b.pem').text
String code = h.wrongPairingCode(pemA, pemB)
check 'six hex, suffix 0000', code ==~ /[0-9A-F]{2}0000/
// Independent check: hash the moduli as openssl prints them, exponent 65537.
def modOf = { String n -> String m = ['openssl', 'x509', '-noout', '-modulus', '-in', "${tmp}/${n}.pem"].execute().text.trim().split('=')[1].replaceFirst(/^0+/, ''); m.length() % 2 ? '0' + m : m }
def hx = { String s -> s.decodeHex() }
java.security.MessageDigest md = java.security.MessageDigest.getInstance('SHA-256')
[hx(modOf('a')), hx('010001'), hx(modOf('b')), hx('010001'), hx('0000')].each { md.update(it as byte[]) }
check 'check byte matches an independent hash', code.substring(0, 2) == String.format('%02X', md.digest()[0] & 0xFF)
tmp.deleteDir()

println 'pollGap'
check 'fast hub, short wait: 1 s', h.pollGap(15, 400L) == 1
check 'slow read sets the gap', h.pollGap(15, 3200L) == 4
check 'long wait: at least 10 s', h.pollGap(600, 3200L) == 10
check 'no read yet', h.pollGap(15, null) == 1

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
