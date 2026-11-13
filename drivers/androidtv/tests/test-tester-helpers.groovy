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
    'Map volumeOf(String msg) {',
    'List<Long> unusualPingGaps(List<Map> lines) {',
    'Map evalPairingOptions(List<Map> lines) {',
    'Map evalLaunch(List<Map> lines, Map steps) {',
    'Map evalRecovery(List<Map> lines, Map steps, TimeZone tz) {',
    'Map evalText(List<Map> lines) {',
    'Map evalMedia(List<Map> lines) {',
    'Map evalHome(List<Map> lines, Map steps) {',
    'Map evalStandby(List<Map> lines, Map steps) {',
    'Map evalPowerCut(List<Map> lines, Map steps, Map attrs, TimeZone tz) {',
    'Map evalWrongCode(List<Map> lines, Map steps) {',
    'Map evalPairing(List<Map> lines) {',
    'Map evalConnect(List<Map> lines) {',
    'boolean socketCloseNoise(String msg) {',
    'boolean hasEvent(List<Map> events, String name, String value) {',
    'String lastEvent(List<Map> events, String name) {',
    'Map evalEvents(int test, List<Map> events, Map f) {',
    'boolean runStalled(Map run, long nowMs) {',
    'Map splitDiffs(List diffs, Map expect) {',
    'String verdictOf(Map r) {',
    'String verdictText(Map r) {',
    'List<Map> testRows(Map run, Map names) {',
    'List<String> differences(Map fields, Map expect) {',
    'Map searchResult(String msg) {',
    'Map evalDiscovery(List<Map> lines, String tvId, Map foundTvs, String tvIP = null) {',
    'private String lastApp(List<Map> lines) {',
    'String formatReport(Map header, List<Map> results) {',
    'String wrongPairingCode(String clientPem, String tvPem) {',
    'byte[] unsignedBytes(BigInteger v) {',
    'int pollGap(int timeoutS, Long lastReadMs) {',
    'Map pickFields(Map st, List names) {',
    'boolean appReported(List<Map> lines, String pkg) {',
    'boolean promptNeeded(String cond, Map steps, List<Map> lines) {',
    'boolean homeChanged(List<Map> lines) {',
    'String textNote(Map f, Map prompts = [:]) {',
    'String mediaNote(Map prompts) {',
    'boolean choseReady(Map prompts, String kind) {',
    'boolean answeredAny(Map prompts, List kinds) {',
    'List<Map> textSteps(Map run) {',
    'List<Map> mediaSteps(Map run) {',
    'Map runOptions(Map p, Map names) {',
    'Map promptSpec(String kind) {',
    'Map newPrompt(String kind, String id, int test, long nowMs, int deadlineS) {',
    'String promptOutcome(Map prompt, long nowMs) {',
    'Map answerPrompt(Map prompt, String id, String choice, long nowMs) {',
    'Map publicPrompt(Map prompt, long nowMs) {',
    'Object pictureMs(Map prompt, Long startMs) {',
    'Map promptFields(int test, Map prompts) {',
    'Map statusRun(Map run, Map names) {',
    'Map padCommand(String name, String key = null, String text = null, String link = null) {',
    'boolean runStartTrace(Map padTrace, Object current) {',
    'List<Map> padLogAppend(List log, Map entry, int cap) {',
    'List<String> padEntryLines(List<Map> lines, String untilTs, int maxLines = 15) {',
    'List<Map> padLogView(List log, int n) {',
    'String padEntryText(Map e, TimeZone tz) {',
    'String padSection(List log, long fromMs, long toMs, TimeZone tz) {',
    'Map profileFields(List<Map> results) {',
]
String IMPORTS = "import java.security.MessageDigest\nimport java.security.cert.CertificateFactory\nimport java.text.SimpleDateFormat\n"
String GAP = "long getPING_GAP_MS() { 12000L }\n"
String DROP = "String getDROP_RE() { '^(remote connect returned|Socket \\\\(|No message from the TV|Connect failed)' }\n"
def h = new GroovyShell().parse(IMPORTS + GAP + DROP + SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

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
check 'unhandled types listed once, in order', h.otherMessages([ln('11:00:00.000', 'rx 51:{1:1}'), ln('11:00:00.100', 'rx 24:{}'),
    ln('11:00:00.200', 'rx 51:{1:2}')]) == '24,51'
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
check 'app at session start', s1.startApp == 'com.google.android.tvlauncher'
check 'no app reported: no start app', h.evalSessionStart([ln('11:00:00.000', 'rx 40:{1:1}')], [:]).startApp == null

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
check 'volume during change, its values and player', s3.volumeDuringChange == true && s3.changeVolumeRange == 15L && s3.changeVolumeLevel == 0L && s3.playerModel == 'EBOX TV'
check 'no ping gaps', s3.pingGaps == 0 && s3.longestPingGapMs == null
Map s3g = h.evalPower(pw + [ln('11:46:13.000', 'ping #1 val1=9 gap=0 ms 8:{1:9 2:1}'), ln('11:46:25.000', 'ping #4 val1=12 gap=11900 ms 8:{1:12 2:1}')],
                      [off: [ok: true, ms: 1L], on: [ok: true, ms: 1L]])
check 'a gap under 12 s is not counted, the longest stays', s3g.pingGaps == 0 && s3g.longestPingGapMs == 11900L
Map s3h = h.evalPower(pw + [ln('11:46:13.000', 'ping #1 val1=9 gap=0 ms 8:{1:9 2:1}'), ln('11:46:25.000', 'ping #4 val1=12 gap=12001 ms 8:{1:12 2:1}'),
                            ln('11:46:30.000', 'ping #5 val1=13 gap=9000 ms 8:{1:13 2:1}'), ln('11:46:31.000', 'ping #6 val1=14 gap=2100 ms 8:{1:14 2:1}')],
                      [off: [ok: true, ms: 1L], on: [ok: true, ms: 1L]])
check 'gap over 12 s counted once, first ping of a session ignored', s3h.pingGaps == 1 && s3h.longestPingGapMs == 12001L
check 'exactly 12 s is not over', h.evalPower([ln('11:46:25.000', 'ping #4 val1=12 gap=12000 ms 8:{1:12 2:1}')], [:]).pingGaps == 0
check 'volume without a range is not a volume report', h.evalPower([ln('11:00:00.000', 'rx 50:{1:2252 2:9 3:"EBOX TV" 4:1}')], [:]).volumeDuringChange == false
check 'drop detected', h.evalPower(pw + [ln('11:46:05.000', 'remote connect returned after 1073 ms')], [off: [ok: true, ms: 1L], on: [ok: true, ms: 1L]]).sessionKept == false

println 'evalVolume'
check 'not reported', h.evalVolume([ln('11:00:00.000', 'tx 10:{1:24 2:3}')]).volumeReported == false
Map s4 = h.evalVolume([ln('11:00:00.000', 'rx 50:{1:2 2:2 3:"X" 4:2 5:0 6:100 7:21 8:0}')])
check 'reported with range', s4.volumeReported && s4.volumeRange == 100L && s4.volumeLevel == 21L && s4.playerModel == 'X'
check 'muted flag', h.volumeOf('rx 50:{6:15 8:1}').muted == true && h.volumeOf('rx 50:{6:15 7:3}').muted == false
check 'range 0 ignored', h.volumeOf('rx 50:{6:0 7:3}') == null

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

println 'evalText'
// From a box that reported no text field: the search key opened the Assistant, the text drew nothing.
Map t1 = h.evalText([ln('11:00:00.000', 'Key 3'), ln('11:00:00.400', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}'),
                     ln('11:00:03.000', 'Key 84'), ln('11:00:03.300', 'rx 20:{1:{12:"com.google.android.katniss"}}'),
                     ln('11:00:07.000', 'Text hubitat'), ln('11:00:07.010', 'tx 21:{1:0 2:0 3:{1:1 2:{1:6 2:6 3:"hubitat"}}}'),
                     ln('11:00:15.000', 'Key 4'), ln('11:00:15.200', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}')])
check 'search app, nothing reported', t1 == [searchApp: 'com.google.android.katniss', fieldReported: false, editReported: false,
                                             editAfterText: false, textSent: true, accepted: true, textEchoed: false, errorCode: null]
Map t2 = h.evalText([ln('11:00:03.000', 'Key 84'), ln('11:00:03.300', 'rx 20:{1:{12:"com.example.search"} 2:{1:2 2:"" 6:"Search"}}'),
                     ln('11:00:03.400', 'rx 21:{1:5 2:2}'), ln('11:00:07.000', 'Text hubitat'), ln('11:00:07.200', 'rx 21:{1:6 2:2}')])
check 'field and edits reported', t2.fieldReported && t2.editReported && t2.editAfterText && t2.searchApp == 'com.example.search'
// From the EBOX with its text field open: the box echoes the field after the text.
Map t4 = h.evalText([ln('11:00:03.000', 'Key 84'), ln('11:00:05.000', 'rx 20:{1:{1:1 2:1 12:"com.google.android.katniss"} 2:{1:134 5:1}}'),
                     ln('11:00:05.030', 'rx 21:{1:1 2:1}'), ln('11:00:07.000', 'Text hubitat'), ln('11:00:07.040', 'rx 22:{1:1 2:{1:137 2:"hubitat" 3:7 4:7 5:1}}')])
check 'text echoed', t4.textEchoed && t4.fieldReported && !t4.editAfterText
Map t3 = h.evalText([ln('11:00:07.000', 'Text hubitat'), ln('11:00:07.050', 'TV rejected 21:{1:0} (error 2); reconnecting')])
check 'text refused', t3.accepted == false && t3.errorCode == '2' && t3.searchApp == null
check 'driver without sendText', h.evalText([ln('11:00:03.000', 'Key 84')]).textSent == false
check 'message 21 is handled, not other', h.otherMessages([ln('11:00:00.000', 'rx 21:{1:5 2:2}')]) == null
check 'messages 22 and 23 are handled', h.otherMessages([ln('11:00:00.000', 'rx 22:{2:{1:5}}'), ln('11:00:01.000', 'rx 23:{2:4 3:4}')]) == null

println 'evalStandby'
Map s9 = h.evalStandby([ln('12:00:00.000', 'rx 40:{1:0}'), ln('12:30:00.000', 'rx 40:{1:1}')], [on: [ok: true, ms: 300L]])
check 'no drops, on worked', s9.drops == 0 && s9.onWorked == true && s9.pingGaps == 0
check 'standby gaps over 12 s only', h.evalStandby([ln('12:00:00.000', 'ping #7 val1=7 gap=30010 ms 8:{1:7 2:1}'), ln('12:01:00.000', 'ping #9 val1=9 gap=2100 ms 8:{1:9 2:1}')], [:])
    .with { it.pingGaps == 1 && it.longestPingGapMs == 30010L }

println 'evalPowerCut'
long cutAt = h.tsMs('2026-10-15 11:47:24.000', utc)
List<Map> cut = [ln('11:47:49.267', 'No message from the TV in 20 s; reconnecting'), ln('11:48:24.870', 'Connect failed after 30448 ms: SocketTimeoutException: connect timed out')]
Map s10 = h.evalPowerCut(cut, [cut: [ok: true, at: cutAt], back: [ok: true, ms: 73000L]], [pairing: 'paired', switch: 'on'], utc)
check 'watchdog noticed it', s10.noticedBy == 'watchdog' && s10.noticeMs == 25267L
check 'back, paired, switch', s10.backMs == 73000L && s10.stillPaired && s10.switchAfterBoot == 'on'
long restoreAt = h.tsMs('2026-10-15 11:47:54.000', utc)
List<Map> boot = [ln('11:47:50.000', 'Connect failed after 30000 ms: SocketTimeoutException: connect timed out'),
                  ln('11:48:30.000', 'Connect failed after 3 ms: ConnectException: Connection refused'),
                  ln('11:48:54.000', 'The TV refused the connection after 40 ms (Connection reset); it may need pairing again'),
                  ln('11:49:34.500', 'remote connect returned after 900 ms'), ln('11:49:40.000', 'remote connect returned after 80 ms')]
Map sb = h.evalPowerCut(boot, [cut: [ok: true, at: cutAt], restore: [ok: true, at: restoreAt], back: [ok: true, ms: 100000L]], [pairing: 'paired', switch: 'on'], utc)
check 'box up between the last refused and the first connect after power returned', sb.lastFailedConnectMs == 60000L && sb.firstConnectMs == 100500L
check 'no restore time, no boot window', s10.lastFailedConnectMs == null && s10.firstConnectMs == null
check 'socket error wins', h.evalPowerCut([ln('11:47:30.000', 'Socket (remote): receive error: Connection reset')], [cut: [ok: true, at: cutAt], back: [ok: false]], [pairing: 'paired', switch: 'off'], utc).noticedBy == 'socket error'

println 'evalWrongCode'
Map s11 = h.evalWrongCode([ln('11:46:44.098', 'Pairing failed with status 402')], [resume: [ok: false], reconnect: [ok: true]])
check 'status, old pairing, no resume', s11.status == 402L && s11.oldPairingWorks && s11.resumedOnOwn == false

println 'evalPairing'
def lv = { String t, String lvl, String m -> [ts: "2026-10-15 ${t}".toString(), level: lvl, msg: m] }
Map pr = h.evalPairing([lv('11:46:00.000', 'trace', 'TV certificate: <pem>'), lv('11:46:01.000', 'trace', 'pair rx 1:2 2:200 11:""'),
                        lv('11:46:02.000', 'info', 'Enter the code shown on the TV with Submit Pairing Code'),
                        lv('11:46:10.000', 'error', 'Pairing failed with status 402')])
String noise = 'java.lang.RuntimeException: java.lang.InterruptedException (method socketStatus)'
Map pr3 = h.evalPairing([lv('11:46:00.000', 'trace', 'TV certificate: <pem>'), lv('11:46:10.000', 'error', 'Pairing failed with status 402'),
                         lv('11:46:10.100', 'error', noise)])
check 'socket-close error after a refused code is not the first error', pr3.wrongCodeStatus == 402L && pr3.firstError == null
check 'connect: socket-close error skipped', h.evalConnect([lv('11:00:00.000', 'error', noise), lv('11:00:31.000', 'warn', 'Connect failed after 30448 ms')]).firstError.startsWith('Connect failed')
check 'all stages, 402, no other error', pr.certRead && pr.pairingReply && pr.codeShown && pr.wrongCodeStatus == 402L && pr.firstError == null
Map pr2 = h.evalPairing([lv('11:46:00.000', 'error', 'java.net.ConnectException: Connection refused to 192.0.2.10 on line 144 (method startPairing)')])
check 'stopped at the certificate, error masked', !pr2.certRead && !pr2.codeShown && pr2.wrongCodeStatus == null && pr2.firstError.contains('<ip>')

println 'evalPairingOptions'
Map po = h.evalPairingOptions([ln('11:46:00.000', 'tx pair 1:2 2:200 20:{1:{1:3 2:6} 3:1}'), ln('11:46:00.100', 'pair rx 1:2 2:200 20:{1:{1:3 2:6} 3:1}')])
check 'mock reply: hexadecimal input, role input', po == [inputEncodings: 'hexadecimal/6', outputEncodings: '', preferredRole: 'input', hexCodeOffered: true]
Map po2 = h.evalPairingOptions([ln('11:46:00.100', 'pair rx 1:2 2:200 20:{1:{1:2 2:4} 2:{1:3 2:6} 2:{2:8} 3:2}')])
check 'several lists, unknown type', po2.inputEncodings == 'numeric/4' && po2.outputEncodings == 'hexadecimal/6,unknown/8' && po2.preferredRole == 'output' && po2.hexCodeOffered
check 'no hexadecimal/6 offered', h.evalPairingOptions([ln('11:46:00.100', 'pair rx 1:2 2:200 20:{1:{1:2 2:4}}')]).with { it.hexCodeOffered == false && it.preferredRole == null }
check 'no reply: all null', h.evalPairingOptions([ln('11:46:00.100', 'pair rx 1:2 2:200 11:{}')]).hexCodeOffered == null
check 'ne rule', h.differences([a: null, b: true, c: false], [a: [ne: false], b: [ne: false], c: [ne: false]]) == ['c']

println 'evalConnect'
Map cn = h.evalConnect([lv('11:00:00.000', 'warn', 'Connect failed after 30448 ms: SocketTimeoutException: connect timed out')])
check 'not connected, first error', !cn.connected && !cn.connectReturned && cn.firstError.startsWith('Connect failed')
check 'connected', h.evalConnect([lv('11:00:00.000', 'trace', 'remote connect returned after 80 ms'), lv('11:00:01.000', 'trace', 'rx 40:{1:1}')]).with { it.connected && it.connectReturned && it.firstError == null }

println 'evalEvents'
def ev = { String n, String v -> [name: n, value: v, at: 1L] }
check 'session start events', h.evalEvents(1, [ev('networkStatus', 'online'), ev('switch', 'on'), ev('currentApp', 'a')], [:]) == [onlineEvent: true, switchEvent: true, appEvent: true]
check 'missing app event', h.evalEvents(1, [ev('networkStatus', 'online'), ev('switch', 'on')], [:]).appEvent == false
check 'power events', h.evalEvents(3, [ev('switch', 'off'), ev('switch', 'on')], [:]).with { it.offEvent && it.onEvent && it.volumeEventOk }
check 'CEC volume around power ignored', h.evalEvents(3, [], [volumeDuringChange: true, volumeTrusted: false]).volumeEventOk == true
check 'CEC volume around power acted on', h.evalEvents(3, [ev('volume', '100')], [volumeDuringChange: true, volumeTrusted: false]).volumeEventOk == false
check 'trusted volume around power', h.evalEvents(3, [ev('volume', '20')], [volumeDuringChange: true, volumeTrusted: true]).volumeEventOk == true
check 'trusted volume dropped', h.evalEvents(3, [], [volumeDuringChange: true, volumeTrusted: true]).volumeEventOk == false
check 'volume reported but no event', h.evalEvents(4, [], [volumeReported: true]).volumeEventMatches == false
check 'no volume, no event', h.evalEvents(4, [], [volumeReported: false]).volumeEventMatches == true
check 'app event matches the box', h.evalEvents(5, [ev('currentApp', 'x'), ev('currentApp', 'yt')], [app: 'yt']) == [appEvent: 'yt', appEventMatches: true]
check 'app event missing', h.evalEvents(6, [], [app: 'yt']).appEventMatches == false
check 'home app event', h.evalEvents(8, [ev('currentApp', 'home')], [homeApp: 'home']).appEventMatches == true
check 'offline then online', h.evalEvents(13, [ev('networkStatus', 'offline'), ev('networkStatus', 'online')], [:]) == [offlineEvent: true, onlineEvent: true]
check 'pairing after', h.evalEvents(15, [ev('pairing', 'pairing'), ev('pairing', 'unpaired')], [:]).pairingAfter == 'unpaired' && h.evalEvents(14, [ev('pairing', 'paired')], [:]).pairingAfter == 'paired'
check 'standby event', h.evalEvents(12, [ev('switch', 'on')], [:]) == [onEvent: true]
check 'old numbers give nothing', h.evalEvents(9, [ev('switch', 'on')], [:]) == [:] && h.evalEvents(11, [ev('pairing', 'paired')], [:]) == [:]

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
check 'step of count before a label is set', h.testRows(running + [stepCount: 3], names)[1].detail == 'step 2 of 3'
check 'settling after the last step', h.testRows(running + [stepCount: 1], names)[1].detail == 'step 1 of 1'
check 'the current step\'s plain label', h.testRows(running + [cur: [test: 2, step: 'connect', label: 'Waiting for the box to answer']], names)[1].detail == 'Waiting for the box to answer'
check 'a label of another test is not shown', h.testRows(running + [cur: [test: 1, step: 'x', label: 'old']], names)[1].detail == 'step 2'
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
check 'device line: not given by default, at the TV no', rep.contains('\ndevice: not given · at the TV: no\nrun: done')
check 'device line: kind and at the TV', h.formatReport([status: 'done', deviceKind: 'box', attended: true], []).contains('device: box plugged into a TV · at the TV: yes') &&
    h.formatReport([status: 'done', deviceKind: 'tv', attended: false], []).contains('device: TV with Android TV built in · at the TV: no')
check 'manual keys after the tests', h.formatReport([status: 'done', manualKeys: 'Manual keys (x)\n00:00:01 home'], [[test: 1, name: 'Session start', observed: [a: 1], diffs: []]]).endsWith('1. Session start — as expected\n   a=1\n\nManual keys (x)\n00:00:01 home')
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

println 'evalMedia'
Map m1 = h.evalMedia([ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}'),
                      ln('11:00:01.000', 'tx 10:{1:126 2:3}'), ln('11:00:04.000', 'tx 10:{1:127 2:3}'),
                      ln('11:00:07.000', 'tx 10:{1:86 2:3}'), ln('11:00:08.000', 'ping #9 val1=9 gap=7400 ms 8:{1:9 2:1}')])
check 'keys sent in order', m1.keysSent == '126,127,86'
check 'no reply, accepted, session kept', m1.replies == null && m1.accepted && m1.errorCode == null && m1.sessionKept
check 'messages before the first key ignored, pings not replies', m1.replies == null
Map m2 = h.evalMedia([ln('11:00:01.000', 'tx 10:{1:126 2:3}'), ln('11:00:01.300', 'rx 20:{1:{12:"com.spotify.tv.android"}}'),
                      ln('11:00:04.000', 'tx 10:{1:127 2:3}'), ln('11:00:04.100', 'rx 51:{1:1}'), ln('11:00:04.200', 'rx 20:{1:{12:"x"}}')])
check 'replies listed once, in order', m2.replies == '20,51'
check 'missing key shows', m2.keysSent == '126,127'
Map m3 = h.evalMedia([ln('11:00:01.000', 'tx 10:{1:86 2:3}'), ln('11:00:01.050', 'TV rejected 10:{1:86 2:3} (error 3); reconnecting'),
                      ln('11:00:02.000', 'remote connect returned after 700 ms')])
check 'refused key', m3.accepted == false && m3.errorCode == '3'
check 'drop seen', m3.sessionKept == false
check 'other keys not counted', h.evalMedia([ln('11:00:00.000', 'tx 10:{1:3 2:3}')]).keysSent == ''
check 'expected run has no differences', h.differences(m1, [keysSent: [sameKeys: '86,126,127'], accepted: [eq: true], sessionKept: [eq: true], replies: [none: true]]) == []

Map m4 = h.evalMedia([ln('11:00:01.000', 'tx 10:{1:127 2:3}'), ln('11:00:04.000', 'tx 10:{1:126 2:3}'), ln('11:00:07.000', 'tx 10:{1:86 2:3}')])
check 'keys sent in the attended order', m4.keysSent == '127,126,86'
println 'sameKeys rule'
check 'same codes in any order', h.differences([keysSent: '127,126,86'], [keysSent: [sameKeys: '86,126,127']]) == [] &&
    h.differences([keysSent: '126,127,86'], [keysSent: [sameKeys: '86,126,127']]) == []
check 'a missing, extra or absent key differs', h.differences([keysSent: '126,127'], [keysSent: [sameKeys: '86,126,127']]) == ['keysSent'] &&
    h.differences([keysSent: '126,127,86,3'], [keysSent: [sameKeys: '86,126,127']]) == ['keysSent'] &&
    h.differences([:], [keysSent: [sameKeys: '86,126,127']]) == ['keysSent'] && h.differences([keysSent: ''], [keysSent: [sameKeys: '86,126,127']]) == ['keysSent']

println 'evalDiscovery'
def dl = { String m -> [ts: '2026-10-17 10:00:00.000', level: 'info', msg: m] }
String id7 = '02:00:00:00:00:07'
check 'search line: one TV, older drivers', h.searchResult('Found Living room at 192.0.2.10') == [count: 1, many: false, other: false]
check 'search line: one TV', h.searchResult('Found a TV at 192.0.2.10') == [count: 1, many: false, other: false]
check 'search line: one TV, not the device\'s', h.searchResult('Found a TV at 192.0.2.11; to use it, clear the IP address and save').other == true
check 'search line: several', h.searchResult('Found 3 TVs: choose one under TV and save') == [count: 3, many: true, other: false]
check 'search line: none', h.searchResult('No Android TV or Google TV found. Check that the TV is on').count == 0
check 'search line: other lines ignored', h.searchResult('rx 40:{1:1}') == null
Map dOn = h.evalDiscovery([dl('Found a TV at 192.0.2.10'), dl('rx 40:{1:0}'), dl('Found a TV at 192.0.2.10')], id7, null)
check 'announced on and off', dOn == [announced: true, tvCount: 1, deviceTvFound: true, ipFound: null, announcedOff: true, idKnown: true]
Map dOff = h.evalDiscovery([dl('Found a TV at 192.0.2.10'), dl('No Android TV or Google TV found. Check that the TV is on')], null, null)
check 'silent while off', dOff.announcedOff == false && dOff.announced == true
check 'id not learned', dOff.idKnown == false
Map dOther = h.evalDiscovery([dl('Found a TV at 192.0.2.11; to use it, clear the IP address and save')], id7, null)
check 'another TV found, not the device\'s', dOther.deviceTvFound == false && dOther.announcedOff == null
check 'several TVs: device\'s among them', h.evalDiscovery([dl('Found 2 TVs: choose one under TV and save')], id7, [(id7): 'Mock TV (192.0.2.10)']).deviceTvFound == true
Map other8 = ['02:00:00:00:00:08': 'Den (192.0.2.10)', '02:00:00:00:00:09': 'Bedroom (192.0.2.12)']
Map dIdMismatch = h.evalDiscovery([dl('Found 2 TVs: choose one under TV and save')], id7, other8, '192.0.2.10')
check 'several TVs: one at the device\'s address under another id', dIdMismatch.deviceTvFound == false && dIdMismatch.ipFound == true
Map dMissing = h.evalDiscovery([dl('Found 2 TVs: choose one under TV and save')], id7, other8, '192.0.2.11')
check 'several TVs: none at the device\'s address', dMissing.deviceTvFound == false && dMissing.ipFound == false
check 'several TVs: no address set, unknown', h.evalDiscovery([dl('Found 2 TVs: choose one under TV and save')], id7, other8, null).ipFound == null
check 'several TVs: list gone, unknown', h.evalDiscovery([dl('Found 2 TVs: choose one under TV and save')], id7, null).deviceTvFound == null
Map dNone = h.evalDiscovery([dl('No Android TV or Google TV found. Check that the TV is on')], id7, null)
check 'nothing announced', dNone.announced == false && dNone.tvCount == 0 && dNone.deviceTvFound == null
check 'no search line at all', h.evalDiscovery([], id7, null).announced == null

println 'pickFields, appReported, promptNeeded'
check 'pickFields: named only, missing and null state', h.pickFields([a: 1, cert: 'x', key: 'secret'], ['a', 'b']) == [a: 1, b: null] && h.pickFields(null, ['a']) == [a: null]
check 'appReported: YouTube in a message 20', h.appReported([ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.youtube.tv"}}')], 'com.google.android.youtube.tv')
check 'appReported: another app, no message', !h.appReported([ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}')], 'com.google.android.youtube.tv') && !h.appReported([], 'x')
check 'app question only without YouTube', h.promptNeeded('noYoutube', [:], []) &&
    !h.promptNeeded('noYoutube', [:], [ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.youtube.tv"}}')])
Map homeKey = ln('11:00:01.000', 'tx 10:{1:3 2:3}')
Map launcher = ln('11:00:01.100', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}')
Map youtube = ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.youtube.tv"}}')
check 'home question when HOME brought no app message', h.promptNeeded('homeUnchanged', [:], [youtube, homeKey])
check 'home question when HOME re-reported the app in front', h.promptNeeded('homeUnchanged', [:],
    [ln('11:00:00.000', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}'), homeKey, launcher])
check 'no home question when HOME changed the app', !h.promptNeeded('homeUnchanged', [:], [youtube, homeKey, launcher])
check 'no home question when nothing was in front before and HOME reported an app', !h.promptNeeded('homeUnchanged', [:], [homeKey, launcher])
check 'homeChanged false without a HOME key', !h.homeChanged([youtube, launcher])

println 'textNote'
check 'plain note when a field was reported', h.textNote([fieldReported: true, editReported: false, searchApp: 'x']) == 'say in your post whether "hubitat" appeared in the search box' &&
    h.textNote([fieldReported: false, editReported: true, searchApp: 'x']).startsWith('say in your post')
check 'search app named when no field was reported', h.textNote([fieldReported: false, editReported: false, searchApp: 'com.google.android.katniss']) ==
    'the search key opened com.google.android.katniss; no text field reported; say in your post whether "hubitat" appeared in the search box'
check 'no search app seen: plain note', h.textNote([fieldReported: false, editReported: false, searchApp: null]).startsWith('say in your post')

check 'a text answer drops the note, no answer and not asked keep it', h.textNote([fieldReported: true], [text: [choice: 'yes']]) == null &&
    h.textNote([fieldReported: true], [text: [choice: 'no']]) == null &&
    h.textNote([fieldReported: true], [text: [choice: null]]).startsWith('say in your post') && h.textNote([fieldReported: true], [field: [notAsked: true]]).startsWith('say in your post')
check 'media note: kept without answers, dropped with any', h.mediaNote([:]).startsWith('say in your post') && h.mediaNote([playing: [notAsked: true]]).startsWith('say in your post') &&
    h.mediaNote([playing: [choice: null]]) != null && h.mediaNote([playing: [choice: 'cannot']]) == null && h.mediaNote([playing: [choice: 'ready'], stopped: [choice: null]]) == null &&
    h.mediaNote([playing: [choice: 'ready'], paused: [choice: 'no']]) == null

println 'step paths of tests 10 and 11'
Closure stepNames = { List<Map> st -> st*.name }
Map fresh = [prompts: [:], ctx: [:]]
check 'test 10: field first, default path until answered', stepNames(h.textSteps(fresh)) == ['field', 'home', 'search', 'text', 'back', 'home2']
check 'test 10: ready answer', stepNames(h.textSteps([prompts: [field: [choice: 'ready']]])) == ['field', 'text', 'textSeen', 'back', 'home2']
check 'test 10: cannot, no answer, not asked take the 0.6.1 path', ['cannot', null].every { stepNames(h.textSteps([prompts: [field: [choice: it]]])) == ['field', 'home', 'search', 'text', 'back', 'home2'] } &&
    stepNames(h.textSteps([prompts: [field: [notAsked: true]]])) == ['field', 'home', 'search', 'text', 'back', 'home2']
check 'test 10: step 0 is the same on both paths, so the index stays put', h.textSteps(fresh)[0] == h.textSteps([prompts: [field: [choice: 'ready']]])[0]
check 'test 10: SEARCH to text keeps its 2 s, ready path has the text prompt', h.textSteps(fresh)[2].after == 2 && h.textSteps(fresh)[3].cmd == 'sendText' &&
    h.textSteps([prompts: [field: [choice: 'ready']]])[2] == [name: 'textSeen', label: 'Waiting for your answer', prompt: 'text', timeout: 30]
check 'test 11: playing first, play pause stop otherwise', stepNames(h.mediaSteps(fresh)) == ['playing', 'play', 'pause', 'stop'] &&
    stepNames(h.mediaSteps([prompts: [playing: [choice: 'cannot']]])) == ['playing', 'play', 'pause', 'stop']
check 'test 11: ready answer', stepNames(h.mediaSteps([prompts: [playing: [choice: 'ready']]])) == ['playing', 'pause', 'pauseSeen', 'play', 'playSeen', 'stop', 'stopSeen']
check 'test 11: prompts 90 s then 20 s each', h.mediaSteps(fresh)[0].timeout == 90 && h.textSteps(fresh)[0].timeout == 90 &&
    h.mediaSteps([prompts: [playing: [choice: 'ready']]]).findAll { it.prompt }.collect { [it.prompt, it.timeout] } == [['playing', 90], ['paused', 20], ['resumed', 20], ['stopped', 20]]
check 'every new step has a label and a deadline', (h.textSteps([prompts: [field: [choice: 'ready']]]) + h.mediaSteps([prompts: [playing: [choice: 'ready']]]) + h.mediaSteps(fresh) + h.textSteps(fresh)).every { it.label && (it.timeout || it.after) }
check 'choseReady', h.choseReady([field: [choice: 'ready']], 'field') && !h.choseReady([field: [choice: 'cannot']], 'field') && !h.choseReady(null, 'field') && !h.choseReady([:], 'field')

println 'runOptions'
Map names16 = [0: 'Connection', 1: 'a', 2: 'b', 15: 'Pairing']
check 'defaults: no prompts, unknown kind, no options', h.runOptions([:], names16) == [ok: true, opts: [tests: null, attended: false, deviceKind: 'unknown', standby: false, standbyMin: 2, powerCut: false, wrongCode: false]]
check 'all parameters', h.runOptions([tests: '1, 2,15', attended: 'true', deviceKind: 'box', standby: '5', cut: 'true', wrongcode: 'true'], names16) ==
    [ok: true, opts: [tests: [1, 2, 15], attended: true, deviceKind: 'box', standby: true, standbyMin: 5, powerCut: true, wrongCode: true]]
check 'attended false', h.runOptions([attended: 'false'], names16).opts.attended == false
check 'cut and wrongcode only on "true"', h.runOptions([cut: '1', wrongcode: 'yes'], names16).opts.with { !it.powerCut && !it.wrongCode }
check 'bad test numbers refused', h.runOptions([tests: '1,x'], names16).error == 'tests must be comma-separated test numbers' &&
    h.runOptions([tests: '0'], names16).error == 'unknown test 0' && h.runOptions([tests: '99'], names16).error == 'unknown test 99' &&
    h.runOptions([tests: '1,3'], names16).error == 'unknown test 3' && !h.runOptions([tests: '1,,'], names16).error
check 'bad attended, kind and standby refused', h.runOptions([attended: 'yes'], names16).error == 'attended must be true or false' &&
    h.runOptions([deviceKind: 'phone'], names16).error == 'deviceKind must be tv, box or unknown' &&
    ['0', '241', 'x', '', '-1'].every { h.runOptions([standby: it], names16).error == 'standby must be minutes from 1 to 240' } && h.runOptions([standby: '240'], names16).opts.standbyMin == 240

println 'prompts'
Map pp = h.newPrompt('picture', 'p7', 3, 1000L, 60)
check 'new prompt: text, choices, deadline', pp.id == 'p7' && pp.kind == 'picture' && pp.test == 3 && pp.deadlineMs == 61000L && pp.askedAtMs == 1000L && pp.answer == null &&
    pp.text == 'Click when the picture is back.' && pp.choices*.id == ['on', 'never']
check 'spec: dark', h.promptSpec('dark').choices == [[id: 'dark', label: 'The screen is dark'], [id: 'on', label: 'It stayed on']]
check 'spec: app and home', h.promptSpec('app').choices*.id == ['opened', 'not'] && h.promptSpec('home').choices*.id == ['yes', 'no']
boolean threw = false
try { h.promptSpec('allow') } catch (IllegalArgumentException e) { threw = true }
check 'unknown prompt kind throws', threw
check 'outcome: open, answered, expired', h.promptOutcome(pp, 5000L) == 'open' && h.promptOutcome(pp + [answer: [choice: 'on']], 5000L) == 'answered' && h.promptOutcome(pp, 61000L) == 'expired'
Map ans = h.answerPrompt(pp, 'p7', 'on', 5000L)
check 'answer taken', ans.ok && ans.prompt.answer == [choice: 'on', atMs: 5000L]
check 'stale id, null prompt, second answer, late answer', h.answerPrompt(pp, 'p6', 'on', 5000L) == [ok: false, error: 'stale prompt'] && h.answerPrompt(null, 'p7', 'on', 5000L).error == 'stale prompt' &&
    h.answerPrompt(ans.prompt, 'p7', 'never', 6000L).error == 'stale prompt' && h.answerPrompt(pp, 'p7', 'on', 61000L).error == 'stale prompt'
check 'unknown choice', h.answerPrompt(pp, 'p7', 'maybe', 5000L).error == 'unknown choice'
check 'page sees open prompts only, without the answer', h.publicPrompt(pp, 5000L).keySet().containsAll(['id', 'test', 'kind', 'text', 'choices', 'askedAtMs', 'deadlineMs']) &&
    !h.publicPrompt(pp, 5000L).containsKey('answer') && h.publicPrompt(ans.prompt, 6000L) == null && h.publicPrompt(pp, 200000L) == null && h.publicPrompt(null, 1L) == null

check 'spec: field, text, playing', h.promptSpec('field').text == "On the TV, open a text field (YouTube's search box works), then click Ready." && h.promptSpec('field').choices*.id == ['ready', 'cannot'] &&
    h.promptSpec('text').text == 'Did hubitat appear in the text field?' && h.promptSpec('text').choices*.id == ['yes', 'no'] &&
    h.promptSpec('playing').text == 'Start playing a video on the TV, then click Ready.' && h.promptSpec('playing').choices*.id == ['ready', 'cannot']
check 'spec: paused, resumed, stopped', h.promptSpec('paused').text == 'Did the video pause?' && h.promptSpec('resumed').text == 'Did it play again?' &&
    h.promptSpec('stopped').text == 'Did it stop?' && ['paused', 'resumed', 'stopped'].every { h.promptSpec(it).choices*.id == ['yes', 'no'] }

println 'pictureMs, promptFields'
check 'seconds from on()', h.pictureMs([choice: 'on', atMs: 5000L], 2000L) == 3000L
check 'never, no answer, not asked, no question', h.pictureMs([choice: 'never', atMs: 1L], 0L) == 'never' && h.pictureMs([choice: null], 0L) == 'no answer' &&
    h.pictureMs([notAsked: true], 0L) == 'not asked' && h.pictureMs(null, 0L) == null
check 'dark: yes, no, no answer, not asked', h.promptFields(3, [dark: [choice: 'dark']]) == [screenDark: 'yes'] && h.promptFields(3, [dark: [choice: 'on']]) == [screenDark: 'no'] &&
    h.promptFields(3, [dark: [choice: null]]) == [screenDark: 'no answer'] && h.promptFields(3, [dark: [notAsked: true]]) == [screenDark: 'not asked']
check 'app and home', h.promptFields(5, [app: [choice: 'opened']]) == [appSeen: 'yes'] && h.promptFields(5, [app: [choice: 'not']]) == [appSeen: 'no'] && h.promptFields(5, [app: [choice: null]]) == [appSeen: 'no answer'] &&
    h.promptFields(8, [home: [choice: 'yes']]) == [homeSeen: 'yes'] && h.promptFields(8, [home: [choice: 'no']]) == [homeSeen: 'no'] && h.promptFields(8, [home: [notAsked: true]]) == [homeSeen: 'not asked']
check 'a question not asked of this box adds nothing; the picture is pictureMs', h.promptFields(5, [:]) == [:] && h.promptFields(8, [:]) == [:] && h.promptFields(3, [picture: [choice: 'on']]) == [:] &&
    h.promptFields(4, [app: [choice: 'opened']]) == [:]
check 'field: ready is yes, cannot is no', h.promptFields(10, [field: [choice: 'ready'], text: [choice: 'yes']]) == [fieldOpened: 'yes', textSeen: 'yes'] &&
    h.promptFields(10, [field: [choice: 'ready'], text: [choice: 'no']]) == [fieldOpened: 'yes', textSeen: 'no'] &&
    h.promptFields(10, [field: [choice: 'cannot']]) == [fieldOpened: 'no', textSeen: 'not asked']
check 'field: no answer and not asked', h.promptFields(10, [field: [choice: null]]) == [fieldOpened: 'no answer', textSeen: 'not asked'] &&
    h.promptFields(10, [field: [notAsked: true]]) == [fieldOpened: 'not asked', textSeen: 'not asked'] && h.promptFields(10, [field: [choice: 'ready'], text: [choice: null]]).textSeen == 'no answer'
check 'media: four answers', h.promptFields(11, [playing: [choice: 'ready'], paused: [choice: 'yes'], resumed: [choice: 'no'], stopped: [choice: null]]) ==
    [playbackStarted: 'yes', pauseSeen: 'yes', playSeen: 'no', stopSeen: 'no answer']
check 'media: cannot, unattended, nothing', h.promptFields(11, [playing: [choice: 'cannot']]) == [playbackStarted: 'no', pauseSeen: 'not asked', playSeen: 'not asked', stopSeen: 'not asked'] &&
    h.promptFields(11, [playing: [notAsked: true]]).playbackStarted == 'not asked' && h.promptFields(11, [:]) == [:] && h.promptFields(10, [:]) == [:]

println 'statusRun'
Map sr = h.statusRun([status: 'running', startedAt: 99L, tests: [0, 1, 3], idx: 2, step: 0,
                      cur: [test: 3, step: 'off', label: 'Waiting for the box to report off', startedAtMs: 500L, deadlineMs: 1500L], results: []], [1: 'Session start', 3: 'Power off/on'])
check 'current step with label and deadline', sr.current == [test: 3, name: 'Power off/on', step: 'off', label: 'Waiting for the box to report off', startedAtMs: 500L, deadlineMs: 1500L]
check 'run fields and rows', sr.status == 'running' && sr.startedAtMs == 99L && sr.rows*.test == [1, 3] && sr.rows*.state == ['not run', 'running']
check 'a step of an earlier test gives a starting label', h.statusRun([status: 'running', tests: [0, 1], idx: 1, step: 0, results: [], cur: [test: 0, step: 'x', label: 'old']], [1: 'S']).current.label == 'Starting'
check 'finished run has no current step', h.statusRun([status: 'done', tests: [0, 1], idx: 2, results: []], [:]).current == null
check 'no run', h.statusRun(null, [:]) == [status: null, reason: null, startedAtMs: null, rows: [], current: null]

println 'padCommand'
check 'a driver command passes by its name', ['on', 'off', 'volumeUp', 'volumeDown', 'play', 'pause', 'stop', 'connect', 'disconnect'].every { h.padCommand(it) == [ok: true, method: it, args: [], label: null] }
check 'the id is case-sensitive and must be listed', !h.padCommand('VOLUMEUP').ok && !h.padCommand('mute').ok && !h.padCommand('startPairing').ok && !h.padCommand('forgetPairing').ok &&
    !h.padCommand('submitPairingCode').ok && !h.padCommand(null).ok && !h.padCommand('').ok && !h.padCommand('sendKey ').ok && h.padCommand('mute').error == 'unknown key'
check 'sendKey passes the name as typed', h.padCommand('sendKey', 'HOME') == [ok: true, method: 'sendKey', args: [[type: 'STRING', value: 'HOME']], label: 'HOME'] &&
    h.padCommand('sendKey', 'keycode_5').args[0].value == 'keycode_5' && h.padCommand('sendKey', '86').ok && h.padCommand('sendKey', 'x' * 40).ok
check 'sendKey refuses odd names', ['', null, 'A B', 'A;B', 'A-B', 'x' * 41, 'A\n', 'DPAD/UP'].every { !h.padCommand('sendKey', it).ok }
check 'sendText: 1 to 200 characters, the log shows the length', h.padCommand('sendText', null, 'hello there') == [ok: true, method: 'sendText', args: [[type: 'STRING', value: 'hello there']], label: '11 characters'] &&
    h.padCommand('sendText', null, 'x' * 200).ok && !h.padCommand('sendText', null, 'x' * 201).ok && !h.padCommand('sendText', null, '').ok && !h.padCommand('sendText').ok
check 'launchApp: a link with a scheme, up to 300 characters', h.padCommand('launchApp', null, null, 'https://www.youtube.com') ==
    [ok: true, method: 'launchApp', args: [[type: 'STRING', value: 'https://www.youtube.com']], label: 'https://www.youtube.com'] &&
    h.padCommand('launchApp', null, null, 'market://launch?id=x').ok
check 'launchApp refuses a bare name, an empty or long link', !h.padCommand('launchApp', null, null, 'com.google.android.youtube.tv').ok && !h.padCommand('launchApp').ok &&
    !h.padCommand('launchApp', null, null, '://x').ok && !h.padCommand('launchApp', null, null, 'https:').ok && !h.padCommand('launchApp', null, null, 'https://' + 'a' * 300).ok &&
    h.padCommand('launchApp', null, null, 'https://' + 'a' * 292).ok
check 'launchApp: the logged link is masked', h.padCommand('launchApp', null, null, 'http://192.0.2.10:8080/x').label == 'http://<ip>:8080/x'
check 'text and link parameters are ignored for other commands', h.padCommand('on', 'HOME', 'x', 'https://x').args == [] && h.padCommand('sendKey', 'HOME', 'x', 'https://x').args[0].value == 'HOME'

println 'pad log'
check 'append keeps the order', h.padLogAppend([[atMs: 1L]], [atMs: 2L], 50)*.atMs == [1L, 2L] && h.padLogAppend(null, [atMs: 1L], 50)*.atMs == [1L]
List big = (1..50).collect { [atMs: it as long] }
check 'append drops the oldest beyond the cap', h.padLogAppend(big, [atMs: 51L], 50).with { it.size() == 50 && it[0].atMs == 2L && it[-1].atMs == 51L } && h.padLogAppend(big.take(10), [atMs: 99L], 50).size() == 11
check 'a run restores the value from before the pad', h.runStartTrace([active: true, prev: false], true) == false && h.runStartTrace([active: true, prev: true], false) == true &&
    h.runStartTrace(null, true) == true && h.runStartTrace(null, false) == false && h.runStartTrace([:], null) == false
def pl = { String t, String lvl, String m -> [ts: "2026-10-15 ${t}".toString(), level: lvl, msg: m] }
List<Map> pl1 = [pl('3:00:01.000', 'info', 'Key 3'), pl('3:00:01.010', 'trace', 'tx 10:{1:3 2:3}'), pl('3:00:01.500', 'trace', 'chunk 10 bytes, mode remote, buffered 0 bytes'),
                 pl('3:00:02.000', 'trace', 'rx 20:{1:{12:"com.google.android.tvlauncher"}}'), pl('3:00:04.500', 'warn', 'Connect failed to 192.0.2.10'), pl('3:00:09.000', 'trace', 'late')]
check 'entry lines: trace, warn and error only, before the cut-off, masked, no chunk lines',
    h.padEntryLines(pl1, '2026-10-15 3:00:04.000') == ['3:00:01.010 tx 10:{1:3 2:3}', '3:00:02.000 rx 20:{1:{12:"com.google.android.tvlauncher"}}'] &&
    h.padEntryLines(pl1, '2026-10-15 3:00:09.000')[-1] == '3:00:04.500 Connect failed to <ip>'
check 'entry lines: info lines left out', !h.padEntryLines(pl1, '2027').any { it.contains('Key 3') }
check 'entry lines: cut to 15 lines, and by size', h.padEntryLines((1..30).collect { pl("3:00:${10 + it}.000", 'trace', 'tx 10:{1:3 2:3}') }, '2027').size() == 15 &&
    h.padEntryLines((1..30).collect { pl("3:00:${10 + it}.000", 'trace', 'tx ' + ('y' * 400)) }, '2027').with { it.size() <= 10 && it.every { s -> s.length() <= 200 } } &&
    h.padEntryLines((1..30).collect { pl("3:00:${10 + it}.000", 'trace', 'tx 10:{1:3 2:3}') }, '2027', 4).size() == 4
List plog = [[atMs: 1000L, since: 'a', button: 'sendKey', key: 'HOME', lines: ['a']], [atMs: 2000L, since: 'b', button: 'sendText', key: '7 characters', lines: []], [atMs: 3000L, since: 'c', button: 'volumeUp', key: null, lines: null]]
check 'view: last n, the page fields only, newest last', h.padLogView(plog, 2)*.atMs == [2000L, 3000L] && h.padLogView(plog, 20).size() == 3 &&
    h.padLogView(plog, 20)[2] == [atMs: 3000L, button: 'volumeUp', key: null, lines: []] && !h.padLogView(plog, 20)[0].containsKey('since') && h.padLogView(null, 20) == []
check 'entry text: time, button, key, indented lines', h.padEntryText(plog[0], utc) == '00:00:01 sendKey HOME\n   a' && h.padEntryText(plog[2], utc) == '00:00:03 volumeUp'
check 'manual keys section: presses after the previous report up to this one', h.padSection(plog, 1000L, 2000L, utc) == 'Manual keys (remote pad presses since the previous report)\n00:00:02 sendText 7 characters' &&
    h.padSection(plog, 0L, 5000L, utc).split('\n').size() == 5
check 'no section without presses', h.padSection(plog, 3000L, 9000L, utc) == '' && h.padSection(null, 0L, 9L, utc) == '' && h.padSection([], 0L, 9L, utc) == ''

println 'profileFields'
List<Map> res = [
    [test: 1, observed: [vendor: 'onn', model: 'onn. 4K Plus Streaming', androidVersion: '14', remoteService: 'svc', remoteServiceVersion: '7.0', features: 639L, firstMessages: '1,2,50,40,20', startApp: 'yt', startMs: 1300L]],
    [test: 3, observed: [volumeDuringChange: true, changeVolumeRange: 25L, changeVolumeLevel: 20L, sessionKept: true, pingGaps: 0, otherMessages: '51']],
    [test: 4, observed: [volumeReported: false, volumeRange: null, volumeLevel: null, playerModel: 'onn. 4K Plus Streaming', otherMessages: '24,51']],
    [test: 6, observed: [accepted: false, errorCode: '7']],
    [test: 8, observed: [homeApp: 'launcher']], [test: 9, observed: [announcedOff: false, announced: true]],
    [test: 10, observed: [searchApp: 'katniss', fieldReported: false]], [test: 15, observed: [inputEncodings: 'hexadecimal/6', outputEncodings: '', preferredRole: 'input']]]
Map pf = h.profileFields(res)
check 'identity, features and first app', pf.vendor == 'onn' && pf.model == 'onn. 4K Plus Streaming' && pf.androidVersion == '14' && pf.remoteService == 'svc' && pf.remoteServiceVersion == '7.0' &&
    pf.features == 639L && pf.firstMessages == '1,2,50,40,20' && pf.startApp == 'yt' && !pf.containsKey('startMs')
check 'volume: the power-change message when the keys brought none', pf.volumeRange == 25L && pf.volumeLevel == 20L && pf.volumeReported == false && pf.volumeDuringChange == true && pf.playerModel == 'onn. 4K Plus Streaming'
check 'volume keys\' message wins', h.profileFields([[test: 3, observed: [changeVolumeRange: 25L, changeVolumeLevel: 1L]], [test: 4, observed: [volumeReported: true, volumeRange: 100L, volumeLevel: 20L]]]).with { it.volumeRange == 100L && it.volumeLevel == 20L }
check 'error code, home, search, announcement, pairing options', pf.errorCode == 7 && pf.homeApp == 'launcher' && pf.searchApp == 'katniss' && pf.announcedOff == false && pf.sessionKept == true &&
    pf.inputEncodings == 'hexadecimal/6' && pf.outputEncodings == '' && pf.preferredRole == 'input'
check 'other messages merged as numbers', pf.extraMessages == [24, 51]
check 'recovery error code when the package launch gave none', h.profileFields([[test: 7, observed: [boxError: '3']]]).errorCode == 3
check 'nothing measured: no profile', h.profileFields([]) == [:] && h.profileFields([[test: 2, observed: [firstPingMs: 100L]]]) == [:]
check 'unmeasured fields left out', !pf.containsKey('closeAfterErrorS') && !pf.containsKey('pingIntervalS')

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
