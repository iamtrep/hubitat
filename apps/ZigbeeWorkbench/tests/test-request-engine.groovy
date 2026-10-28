// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction unit test for the Zigbee Workbench request engine: sequence numbers, pending-request
// keys, reply matching, deadlines, table paging and target resolution. Brace-extracts the codec and
// engine regions from the shipped ZigbeeWorkbench.groovy and runs them under plain groovy; no hub.
//
// Run: groovy apps/ZigbeeWorkbench/tests/test-request-engine.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'ZigbeeWorkbench.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String region(String text, String name) {
    int b = text.indexOf("// ==== ${name} begin ====")
    int e = text.indexOf("// ==== ${name} end ====")
    assert b >= 0 && e > b : "signature not found: ${name} region markers"
    return text.substring(b, e)
}

String harness = """
import groovy.transform.CompileStatic
import groovy.transform.Field
${region(src, 'Codec')}
${region(src, 'Engine')}
this
"""
def app = new GroovyShell().evaluate(harness)

int passed = 0
int failed = 0
def check = { String name, Closure c ->
    boolean ok
    String why = ''
    try { ok = c() } catch (Throwable t) { ok = false; why = " (${t.class.simpleName}: ${t.message})" }
    if (ok) { passed++; println "[PASS] ${name}" } else { failed++; println "[FAIL] ${name}${why}" }
}

// ---- nextSeq ----
check('nextSeq(255) wraps to 1') { app.nextSeq(255) == 1 }
check('nextSeq(0) is 1, never 0') { app.nextSeq(0) == 1 }
check('nextSeq(41) is 42') { app.nextSeq(41) == 42 }
check('nextSeq out of range restarts at 1') { app.nextSeq(-3) == 1 && app.nextSeq(300) == 1 }

// ---- pendingKey ----
check('pendingKey upper-cases and pads the seq') { app.pendingKey('1A2B', '8031', 7) == '1A2B:8031:07' }
check('pendingKey normalizes case') { app.pendingKey('ab12', '000a', 255) == 'AB12:000A:FF' }

// ---- matchPending ----
// The hub overwrites the sequence byte of a `he raw` frame, so replies carry a sequence number
// unrelated to ours: every reply below uses a different one than the request.
long t0 = 1_000_000L
Map zdoReq = [addr: '1A2B', cluster: '8031', seq: 5, zdo: true, start: 0, sent: true, sentAt: t0, deadline: t0 + 25000]
Map zdoFrame = [addr: '1A2B', clusterId: '8031', srcEp: '00', dstEp: '00', payload: ['6F', '00', '02', '00', '00']]
Map pending = [(app.pendingKey('1A2B', '8031', 5)): zdoReq]
def m1 = app.matchPending(pending, zdoFrame, t0 + 1000)
check('ZDO reply matches its request whatever its sequence number') { m1 != null && m1.key == '1A2B:8031:05' && m1.request.is(zdoReq) }
check('matched request leaves pending') { pending.isEmpty() }
check('duplicate reply is not applied twice') { app.matchPending(pending, zdoFrame, t0 + 1100) == null }

// Two ZCL requests on one device, replies in reverse order.
Map readReq = [addr: '1A2B', cluster: '0000', seq: 10, zdo: false, cmd: 0x00, attrs: ['0004', '0005'], sent: true, sentAt: t0, deadline: t0 + 25000, expectCmds: [0x01, 0x0B]]
Map discReq = [addr: '1A2B', cluster: '0006', seq: 11, zdo: false, cmd: 0x0C, start: 0, sent: true, sentAt: t0 + 10, deadline: t0 + 25000, expectCmds: [0x0D, 0x0B]]
Map pending2 = [(app.pendingKey('1A2B', '0000', 10)): readReq, (app.pendingKey('1A2B', '0006', 11)): discReq]
Map discRsp = [addr: '1A2B', clusterId: '0006', srcEp: '01', dstEp: '01', payload: ['18', '7C', '0D', '01', '00', '00', '10']]
Map readRsp = [addr: '1A2B', clusterId: '0000', srcEp: '01', dstEp: '01', payload: ['18', '7B', '01', '04', '00', '86', '05', '00', '86']]
def r2 = app.matchPending(pending2, discRsp, t0 + 500)
def r1 = app.matchPending(pending2, readRsp, t0 + 600)
check('later request answered first matches its own key') { r2?.key == '1A2B:0006:0B' && r2.request.is(discReq) }
check('earlier request answered second matches its own key') { r1?.key == '1A2B:0000:0A' && r1.request.is(readReq) }
check('both requests resolved') { pending2.isEmpty() }

// An attribute report on the cluster being read is not taken for the read reply.
Map readReq2 = [addr: '1A2B', cluster: '0006', seq: 12, zdo: false, cmd: 0x00, attrs: ['0000'], sent: true, sentAt: t0, deadline: t0 + 25000, expectCmds: [0x01, 0x0B]]
Map pending3 = [(app.pendingKey('1A2B', '0006', 12)): readReq2]
Map report = [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['18', '0C', '0A', '00', '00', '10', '01']]
check('attribute report is not taken for the read reply') { app.matchPending(pending3, report, t0) == null && pending3.size() == 1 }
check('read reply for other attributes is not taken') {
    app.matchPending(pending3, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['18', '33', '01', '02', '40', '86']], t0) == null
}
check('default response to the read matches') {
    Map p = [(app.pendingKey('1A2B', '0006', 12)): readReq2]
    app.matchPending(p, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['08', '44', '0B', '00', '86']], t0)?.key == '1A2B:0006:0C'
}
check('default response to another command does not match') {
    Map p = [(app.pendingKey('1A2B', '0006', 12)): readReq2]
    app.matchPending(p, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['08', '44', '0B', '01', '00']], t0) == null
}
check('ZCL frame does not match a ZDO request on the same address and cluster') {
    Map p = [(app.pendingKey('1A2B', '0006', 12)): [addr: '1A2B', cluster: '0006', seq: 12, zdo: true, sent: true, deadline: t0 + 25000]]
    app.matchPending(p, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['18', '0C', '01', '00', '00', '86']], t0) == null
}
check('other address does not match') {
    Map p = [(app.pendingKey('1A2B', '8031', 5)): zdoReq]
    app.matchPending(p, zdoFrame + [addr: '1234'], t0) == null && p.size() == 1
}
check('reply after the deadline does not match') {
    Map p = [(app.pendingKey('1A2B', '8031', 5)): zdoReq]
    app.matchPending(p, zdoFrame, t0 + 26000) == null
}
check('a request not sent yet does not match') {
    Map p = [(app.pendingKey('1A2B', '8031', 5)): zdoReq + [sent: false, deadline: null]]
    app.matchPending(p, zdoFrame, t0) == null
}
check('table page with another start index does not match') {
    Map p = [(app.pendingKey('1A2B', '8031', 5)): zdoReq + [start: 2]]
    app.matchPending(p, zdoFrame, t0) == null && app.matchPending(p, zdoFrame + [payload: ['70', '00', '04', '02', '00']], t0) != null
}
check('ZDO failure status matches whatever the content') {
    Map p = [(app.pendingKey('1A2B', '8032', 6)): [addr: '1A2B', cluster: '8032', seq: 6, zdo: true, start: 0, sent: true, deadline: t0 + 25000]]
    app.matchPending(p, [addr: '1A2B', clusterId: '8032', srcEp: '00', payload: ['21', '84']], t0) != null
}
check('simple descriptor for another endpoint does not match') {
    Map req = [addr: '1A2B', cluster: '8004', seq: 7, zdo: true, ep: '01', sent: true, deadline: t0 + 25000]
    Map p = [(app.pendingKey('1A2B', '8004', 7)): req]
    List<String> ep2 = ['70', '00', '2B', '1A', '0A', '02', '04', '01', '51', '00', '01', '00', '00']
    app.matchPending(p, [addr: '1A2B', clusterId: '8004', srcEp: '00', payload: ep2], t0) == null &&
        app.matchPending(p, [addr: '1A2B', clusterId: '8004', srcEp: '00', payload: ep2.withIndex().collect { b, i -> i == 5 ? '01' : b }], t0) != null
}
check('the oldest of two fitting requests wins') {
    Map a = zdoReq + [seq: 1, sentAt: t0 + 5]
    Map b = zdoReq + [seq: 2, sentAt: t0]
    Map p = [(app.pendingKey('1A2B', '8031', 1)): a, (app.pendingKey('1A2B', '8031', 2)): b]
    app.matchPending(p, zdoFrame, t0 + 10)?.request?.is(b)
}
check('empty or short payload does not throw') {
    Map p = [(app.pendingKey('1A2B', '0006', 12)): readReq2]
    app.matchPending(p, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: []], t0) == null &&
        app.matchPending(p, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['18']], t0) == null &&
        app.matchPending(p, [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['1C', '01']], t0) == null &&
        app.matchPending(p, [addr: '1A2B', clusterId: '8031', srcEp: '00', payload: []], t0) == null
}
check('lower-case frame fields match') {
    Map p = [(app.pendingKey('AB12', '8005', 0x2A)): [addr: 'AB12', cluster: '8005', seq: 0x2A, zdo: true, sent: true, deadline: t0 + 25000]]
    app.matchPending(p, [addr: 'ab12', clusterId: '8005', srcEp: '00', payload: ['63', '00', '12', 'ab', '01', '01']], t0)?.key == 'AB12:8005:2A'
}

// ---- buildRequestFrame ----
// Frames carry sequence byte 00 whatever the request's own number: the hub writes its own there.
check('neighbour table page frame') { app.buildRequestFrame([action: 'neighbors', addr: '1A2B', reqCluster: '0031', seq: 3, start: 2]) == 'he raw 0x1A2B 0 0 0x0031 {00 02} {0x0000}' }
check('simple descriptor frame') { app.buildRequestFrame([action: 'simple', addr: '1A2B', reqCluster: '0004', seq: 9, ep: '01']) == 'he raw 0x1A2B 0 0 0x0004 {00 2B 1A 01} {0x0000}' }
check('read attributes frame') {
    app.buildRequestFrame([action: 'read', addr: '1A2B', ep: '01', cluster: '0000', seq: 5, attrs: ['0004', '0005']]) == 'he raw 0x1A2B 1 0x01 0x0000 {00 00 00 04 00 05 00}'
}

// ---- expirePending ----
Map pending4 = [a: [deadline: t0 + 25000], b: [deadline: t0 + 40000]]
check('nothing expires before the deadline') { app.expirePending(pending4, t0 + 24999).isEmpty() }
check('the key expires once its 25 s deadline passes') { app.expirePending(pending4, t0 + 25001) == ['a'] }
check('expirePending does not remove keys itself') { pending4.size() == 2 }

// ---- nextPageStart ----
check('next page after 2 of 22') { app.nextPageStart([start: 0, total: 22, entries: ['a', 'b']]) == 2 }
check('last page returns null') { app.nextPageStart([start: 20, total: 22, entries: ['a', 'b']]) == null }
check('empty page returns null') { app.nextPageStart([start: 4, total: 22, entries: []]) == null }
check('missing entries returns null') { app.nextPageStart([start: 0, total: 3]) == null }

// ---- resolveTarget ----
Map details = [devices: [
    [id: 1, name: 'Plug A', zigbeeId: '00124B0000000001', shortZigbeeId: '1A2B'],
    [id: 2, name: 'Thermostat', zigbeeId: '00124B0000000002', shortZigbeeId: '3C4D']]]
def t = app.resolveTarget('00124B0000000001', details)
check('resolveTarget finds the device') { t?.ieee == '00124B0000000001' && t.nwk == '1A2B' && t.name == 'Plug A' && t.ep == '01' }
Map moved = [devices: [[id: 1, name: 'Plug A', zigbeeId: '00124B0000000001', shortZigbeeId: 'a1b2']]]
check('resolveTarget returns the new nwk after it changes') { app.resolveTarget('00124B0000000001', moved)?.nwk == 'A1B2' }
check('resolveTarget matches the IEEE in any case') { app.resolveTarget('00124b0000000001', details)?.nwk == '1A2B' }
check('resolveTarget returns null for an IEEE missing from the map') { app.resolveTarget('0000000000000001', details) == null }
check('resolveTarget returns null for a null IEEE or map') { app.resolveTarget(null, details) == null && app.resolveTarget('00124B0000000001', null) == null }

// ---- failure results ----
Map sentReq = [ieee: '00124B0000000001', kind: 'node', frame: 'he raw 0x1A2B 0 0 0x0002 {00 2B 1A} {0x0000}']
check('timeout result keeps the frame and says why') {
    app.timeoutEntry(sentReq, 5L) == [time: 5L, status: 'timeout', error: 'No reply within 10 seconds. A battery device answers only when awake.', frame: sentReq.frame]
}
check('refused result keeps the raw reply and status name') {
    Map e = app.failureEntry(sentReq, 'refused', 'The device answered NOT_SUPPORTED.', ['01', '84'], 'NOT_SUPPORTED', 6L)
    e.status == 'refused' && e.raw == ['01', '84'] && e.statusName == 'NOT_SUPPORTED' && e.frame == sentReq.frame
}
check('a paged failure keeps what was read') { app.failureEntry(sentReq + [acc: [[nwk: '3C4D']]], 'timeout', 'x', null, null, 1L).partial == [[nwk: '3C4D']] }

// ---- failAll (failAllPending) ----
Map pend5 = [k1: sentReq, k2: sentReq + [kind: 'power']]
List lost = app.failAll(pend5, 'Lost the Zigbee log connection', 9L)
check('failAll reports every pending request as lost') {
    lost.size() == 2 && lost.every { it.ieee == '00124B0000000001' && it.entry.status == 'lost' && it.entry.error == 'Lost the Zigbee log connection' && it.entry.frame == sentReq.frame } &&
        (lost.collect { it.kind } as Set) == ['node', 'power'] as Set
}
check('failAll empties pending') { pend5.isEmpty() }
check('failAll a second time does nothing') { app.failAll(pend5, 'Lost the Zigbee log connection', 10L) == [] && app.failAll(null, 'x', 1L) == [] }

// ---- watch list and radioDown ----
check('watch list is the pending addresses, sorted, once each') {
    app.watchFor([a: [addr: '3c4d'], b: [addr: '1A2B'], c: [addr: '3C4D']]) == ['1A2B', '3C4D']
}

// ---- classifyPick ----
check('Zigbee device keeps its IEEE and endpoint') { app.classifyPick('00124b0000000001', '2') == [zigbee: true, ieee: '00124B0000000001', ep: '02'] }
check('missing endpoint falls back to 01') { app.classifyPick('00124B0000000001', null).ep == '01' }
check('no zigbeeId: not a Zigbee device') { app.classifyPick(null, null) == [zigbee: false, note: 'Not a Zigbee device'] }
check('malformed zigbeeId: not a Zigbee device') { app.classifyPick('1A2B', '01').zigbee == false }

// ---- pickSendable ----
Map q = [
    a: [addr: '1A2B', cluster: '8031', sent: false, queuedAt: 1],
    b: [addr: '1A2B', cluster: '8005', sent: false, queuedAt: 2],
    c: [addr: '3C4D', cluster: '8031', sent: false, queuedAt: 3],
    d: [addr: '1A2B', cluster: '8031', sent: false, queuedAt: 4]]
check('one request per device per batch, other devices go too') { app.pickSendable(q).keys == ['a', 'c'] }
check('a device with a request on the air gets no other, on any cluster') {
    Map q2 = [a: [addr: '1A2B', cluster: '8031', sent: true], d: [addr: '1A2B', cluster: '8031', sent: false, queuedAt: 4],
              b: [addr: '1a2b', cluster: '8005', sent: false, queuedAt: 5], c: [addr: '3C4D', cluster: '8005', sent: false, queuedAt: 6]]
    app.pickSendable(q2).keys == ['c']
}
check('at most MAX_IN_FLIGHT requests on the air across devices, oldest first') {
    Map q3 = [:]
    (1..6).each { i -> q3["k${i}".toString()] = [addr: String.format('%04X', i), cluster: '8031', sent: false, queuedAt: i] }
    app.MAX_IN_FLIGHT == 4 && app.pickSendable(q3).keys == ['k1', 'k2', 'k3', 'k4']
}
check('requests already on the air count toward MAX_IN_FLIGHT') {
    Map q4 = [s1: [addr: '0001', cluster: '8031', sent: true], s2: [addr: '0002', cluster: '8031', sent: true],
              s3: [addr: '0003', cluster: '8031', sent: true],
              n1: [addr: '0004', cluster: '8031', sent: false, queuedAt: 1], n2: [addr: '0005', cluster: '8031', sent: false, queuedAt: 2]]
    app.pickSendable(q4).keys == ['n1']
}

// ---- flushDecision ----
check('a request failed that way is lost with the link text') {
    app.failureEntry([frame: 'he raw 0x1A2B 0 0 0x0005 {00 2B 1A} {0x0000}'], 'lost', app.LOST_LINK, null, null, 3L) ==
        [time: 3L, status: 'lost', error: 'Lost the Zigbee log connection', frame: 'he raw 0x1A2B 0 0 0x0005 {00 2B 1A} {0x0000}']
}

// ==== Task 5: writes, commands, binding, leave, firmware notify ====

// ---- confirmation tokens (single use, 120 s) ----
Map tokens = [:]
app.tokenIssue(tokens, 'tok1', 'leave', '00124B0000000001', [rejoin: false], t0)
check('a token is accepted once') {
    app.tokenTake(tokens, 'tok1', t0 + 1000) == [action: 'leave', ieee: '00124B0000000001', args: [rejoin: false], issued: t0]
}
check('the same token is refused the second time') { app.tokenTake(tokens, 'tok1', t0 + 2000) == null }
app.tokenIssue(tokens, 'tok2', 'ota', '00124B0000000001', [:], t0)
check('a token 119.999 s old is accepted') { app.tokenTake(tokens, 'tok2', t0 + 119_999)?.action == 'ota' }
app.tokenIssue(tokens, 'tok3', 'leave', '00124B0000000001', [:], t0)
check('a token older than 120 s is refused') { app.tokenTake(tokens, 'tok3', t0 + 120_001) == null }
check('a refused old token is gone') { !tokens.containsKey('tok3') }
check('unknown or null token is refused') { app.tokenTake(tokens, 'nope', t0) == null && app.tokenTake(tokens, null, t0) == null && app.tokenTake(null, 'tok1', t0) == null }
check('issuing a token revokes the outstanding one') {
    Map tk = [:]
    app.tokenIssue(tk, 'a', 'leave', '00124B0000000001', [:], t0)
    app.tokenIssue(tk, 'b', 'ota', '00124B0000000001', [:], t0 + 10)
    app.tokenTake(tk, 'a', t0 + 20) == null && app.tokenTake(tk, 'b', t0 + 20)?.action == 'ota'
}
check('issuing drops expired tokens') {
    Map tk = [old: [action: 'leave', ieee: 'x', args: [:], issued: t0]]
    app.tokenIssue(tk, 'new', 'ota', '00124B0000000002', [:], t0 + 200_000)
    tk.keySet() == ['new'] as Set
}

// ---- raw command check ----
check('raw: a he command for the target is accepted') { app.rawCommandError('he raw 0x1A2B 1 0x01 0x0006 {11 43 02}', '1A2B') == null }
check('raw: the same command for another target is refused') {
    app.rawCommandError('he raw 0x1A2B 1 0x01 0x0006 {11 43 02}', '5E6F') == 'A raw command must be a he command for this device.'
}
check('raw: a zdo command is refused') {
    app.rawCommandError('zdo bind 0x1A2B 0x01 0x01 0x0006 {00124B0000000001} {}', '1A2B') == 'A raw command must be a he command for this device.'
}
check('raw: address and verb in any case') { app.rawCommandError('he wattr 0x1a2b 0x01 0x0006 0x4003 0x30 {01}', '1A2B') == null }
check('raw: an address that only starts with the target is refused') {
    app.rawCommandError('he raw 0x1A2B5 1 0x01 0x0006 {11 43 02}', '1A2B') == 'A raw command must be a he command for this device.'
}
check('raw: two lines are refused') {
    app.rawCommandError('he raw 0x1A2B 1 0x01 0x0006 {11 43 02}\nhe raw 0x5E6F 1 0x01 0x0006 {11 43 02}', '1A2B') == 'A raw command must be a he command for this device.'
}
check('raw: an unknown he verb is refused') { app.rawCommandError('he leave 0x1A2B', '1A2B') == 'A raw command must be a he command for this device.' }
String WLEAVE = 'Cluster 0x0034 is Mgmt_Leave: the device may leave the network.'
String WOTA = 'Cluster 0x0019 is OTA Upgrade: the device may start a firmware update.'
check('raw: Mgmt_Leave is accepted, with a warning on the confirmation page') {
    String c = 'he raw 0x1A2B 0 0 0x0034 {00 01 00 00 00 00 4B 12 00 00} {0x0000}'
    app.rawCommandError(c, '1A2B') == null && app.rawWarning(c) == WLEAVE
}
check('raw: OTA cluster commands are accepted, with a warning') {
    String c1 = 'he raw 0x1A2B 1 0x01 0x0019 {09 00 00 03 64 FF FF FF FF FF FF FF FF}'
    String c2 = 'he cmd 0x1A2B 0x01 0x19 0x00 {}'
    app.rawCommandError(c1, '1A2B') == null && app.rawWarning(c1) == WOTA && app.rawCommandError(c2, '1A2B') == null && app.rawWarning(c2) == WOTA
}
check('raw: cluster 0034 warns whatever the endpoint') { app.rawWarning('he raw 0x1A2B 1 0x01 0x0034 {00 00 00 00 00}') == WLEAVE }
check('raw: an ordinary command has no warning') {
    app.rawWarning('he raw 0x1A2B 1 0x01 0x0006 {11 43 02}') == null && app.rawWarning('he wattr 0x1a2b 0x01 0x0006 0x4003 0x30 {01}') == null
}
String REF = 'A raw command must be a he command for this device.'
check('raw: a cluster token of more than 4 hex digits is refused') {
    app.rawCommandError('he raw 0x1A2B 0 0 0x00000 {00}', '1A2B') == REF && app.rawCommandError('he raw 0x1A2B 1 0x01 0x00019 {00}', '1A2B') == REF
}
check('raw: a multi-digit token without 0x is refused (decimal or hex?)') {
    app.rawCommandError('he raw 0x1A2B 0 0 52 {00}', '1A2B') == REF && app.rawCommandError('he raw 0x1A2B 1 10 0x0006 {00}', '1A2B') == REF
}
check('raw: a single-digit endpoint is accepted') { app.rawCommandError('he raw 0x1A2B 0 0 0x0031 {00 00} {0x0000}', '1A2B') == null }
check('raw: a frame too short to name its cluster is refused') { app.rawCommandError('he raw 0x1A2B 1 0x01', '1A2B') == REF && app.rawCommandError('he cmd 0x1A2B 0x01', '1A2B') == REF }
check('raw: Mgmt_Permit_Joining and Mgmt_NWK_Update are refused') {
    String z = 'Raw commands may not send ZDO requests that change the network (0x0036, 0x0038).'
    app.rawCommandError('he raw 0x1A2B 0 0 0x0036 {00 3C 00} {0x0000}', '1A2B') == z && app.rawCommandError('he raw 0x1A2B 0 0 0x0038 {00 00 F8 FF 07 FE} {0x0000}', '1A2B') == z
}
check('raw: ZDO reads stay allowed') {
    ['0x0031', '0x0032', '0x0033', '0x0002', '0x0004', '0x0005'].every { app.rawCommandError("he raw 0x1A2B 0 0 ${it} {00 00} {0x0000}", '1A2B') == null }
}
check('raw: non-printable characters are refused') {
    app.rawCommandError('he raw 0x1A2B 1 0x01 0x0006	{11 43 02}', '1A2B') == REF && app.rawCommandError('he raw 0x1A2B 1 0x01 0x0006 {11 43 02} ', '1A2B') == REF &&
        app.rawCommandError('he raw 0x1A2B 1 0x01 0x0006 {11 43 02} ', '1A2B') == REF
}
check('raw: waits while a request to the device is pending') {
    app.rawSendBlock([k: [addr: '1a2b', sent: true]], '1A2B') == 'Wait: a request to this device is still waiting for its reply.' &&
        app.rawSendBlock([k: [addr: '3C4D', sent: true]], '1A2B') == null && app.rawSendBlock(null, '1A2B') == null
}

// ---- attribute value encoding (hex as written, most significant byte first) ----
check('enum8 value') { app.encodeZclValue(0x30, '1') == '01' }
check('uint16 value is sent little-endian') { app.encodeZclValue(0x21, '1234') == '34 12' }
check('uint32 is padded to 4 bytes') { app.encodeZclValue(0x23, '0') == '00 00 00 00' }
check('a 0x prefix and spaces are accepted') { app.encodeZclValue(0x21, '0x12 34') == '34 12' }
check('too long for the type is refused') { app.encodeZclValue(0x21, '12345') == null }
check('octet and character strings get their length byte') { app.encodeZclValue(0x41, '0A0B') == '02 0A 0B' && app.encodeZclValue(0x42, '414243') == '03 41 42 43' }
check('long strings get a 2-byte length') { app.encodeZclValue(0x44, '41') == '01 00 41' }
check('unsupported type, bad hex or empty is refused') {
    app.encodeZclValue(0x48, '00') == null && app.encodeZclValue(0x20, 'zz') == null && app.encodeZclValue(0x20, '') == null && app.encodeZclValue(0x20, null) == null
}

// ---- form validation ----
Map wf = [ep: '1', cluster: '6', attr: '4003', type: '30', value: '01']
check('write form gives the request fields and kind') {
    app.zclFormRequest('write', wf, '01') == [ep: '01', cluster: '0006', attr: '4003', type: 0x30, value: '01', cmd: 0x02, expectCmds: [0x04, 0x0B], kind: 'write:01:0006']
}
check('write form keeps a manufacturer code') { app.zclFormRequest('write', wf + [mfg: '1037'], '01').mfg == '1037' }
check('write form without an endpoint uses the device endpoint') { app.zclFormRequest('write', wf + [ep: null], '02').kind == 'write:02:0006' }
check('write form: bad cluster') { app.zclFormRequest('write', wf + [cluster: '12345'], '01') == [error: "Cluster must be 4 hex digits, not '12345'"] }
check('write form: bad attribute') { app.zclFormRequest('write', wf + [attr: null], '01') == [error: "Attribute must be 4 hex digits, not 'null'"] }
check('write form: bad type') { app.zclFormRequest('write', wf + [type: 'xyz'], '01') == [error: "Type must be 2 hex digits, not 'xyz'"] }
check('write form: value does not fit the type') { app.zclFormRequest('write', wf + [value: '0102'], '01') == [error: "The value '0102' does not fit type 0x30"] }
check('write form: bad manufacturer code') { app.zclFormRequest('write', wf + [mfg: 'G1'], '01') == [error: "Manufacturer code must be 4 hex digits, not 'G1'"] }
check('read reporting configuration form') {
    app.zclFormRequest('readReport', [ep: '01', cluster: '0006', attr: '0000'], '01') == [ep: '01', cluster: '0006', attr: '0000', cmd: 0x08, expectCmds: [0x09, 0x0B], kind: 'report:01:0006']
}
check('configure reporting form, discrete type: no change') {
    app.zclFormRequest('configReport', [ep: '01', cluster: '0006', attr: '0000', type: '10', min: '1', max: '600'], '01') ==
        [ep: '01', cluster: '0006', attr: '0000', type: 0x10, min: 1, max: 600, change: null, cmd: 0x06, expectCmds: [0x07, 0x0B], kind: 'report:01:0006']
}
check('configure reporting form, analog type: change required and encoded') {
    app.zclFormRequest('configReport', [ep: '01', cluster: '0402', attr: '0000', type: '29', min: '30', max: '3600', change: '32'], '01').change == '32 00' &&
        app.zclFormRequest('configReport', [ep: '01', cluster: '0402', attr: '0000', type: '29', min: '30', max: '3600'], '01') == [error: "Type 0x29 needs a reportable change (hex)"]
}
check('configure reporting form: intervals are seconds, 0-65535') {
    app.zclFormRequest('configReport', [ep: '01', cluster: '0006', attr: '0000', type: '10', min: '-1', max: '600'], '01') == [error: "Minimum interval must be 0-65535 seconds, not '-1'"] &&
        app.zclFormRequest('configReport', [ep: '01', cluster: '0006', attr: '0000', type: '10', min: '1', max: '70000'], '01') == [error: "Maximum interval must be 0-65535 seconds, not '70000'"]
}
check('cluster command form') {
    app.zclFormRequest('cmd', [ep: '01', cluster: '0006', cmd: '2', payload: '', frameType: 'cluster', toClient: false, ddr: false], '01') ==
        [ep: '01', cluster: '0006', cmdId: 0x02, payload: '', frameType: 1, toClient: false, ddr: false, cmd: 0x02, kind: 'cmd:01:0006']
}
check('cluster command form: payload bytes upper-cased and spaced') {
    app.zclFormRequest('cmd', [ep: '01', cluster: '0008', cmd: '04', payload: 'fe0a00', frameType: 'cluster'], '01').payload == 'FE 0A 00'
}
check('ZCL forms take the OTA cluster, with a warning (a cluster command there can be an Image Notify)') {
    Map c = app.zclFormRequest('cmd', [ep: '01', cluster: '0019', cmd: '00', payload: '00 64', frameType: 'cluster', toClient: true], '01')
    Map w = app.zclFormRequest('write', wf + [cluster: '0019'], '01')
    Map r = app.zclFormRequest('configReport', [ep: '01', cluster: '0019', attr: '0002', type: '23', min: '1', max: '60', change: '1'], '01')
    c.kind == 'cmd:01:0019' && w.kind == 'write:01:0019' && r.kind == 'report:01:0019' &&
        [app.requestWarning('cmd', c), app.requestWarning('write', w), app.requestWarning('configReport', r)] == [WOTA, WOTA, WOTA]
}
check('reading the OTA cluster reporting configuration has no warning') {
    app.requestWarning('readReport', app.zclFormRequest('readReport', [ep: '01', cluster: '0019', attr: '0002'], '01')) == null
}
check('ZCL forms refuse endpoint 00 and endpoints above F0') {
    app.zclFormRequest('write', wf + [ep: '00'], '01') == [error: "ZCL endpoints are 01-F0, not '00'"] &&
        app.zclFormRequest('cmd', [ep: '0', cluster: '0006', cmd: '01'], '01') == [error: "ZCL endpoints are 01-F0, not '00'"] &&
        app.zclFormRequest('readReport', [ep: 'F2', cluster: '0006', attr: '0000'], '01') == [error: "ZCL endpoints are 01-F0, not 'F2'"] &&
        app.zclFormRequest('configReport', [ep: '00', cluster: '0006', attr: '0000', type: '10', min: '1', max: '60'], '01') == [error: "ZCL endpoints are 01-F0, not '00'"] &&
        app.bindFormRequest('bind', [srcEp: '00', cluster: '0006'], '00124B0000000001', '00124B0000000002', '01') == [error: "ZCL endpoints are 01-F0, not '00'"] &&
        app.zclFormRequest('write', wf + [ep: 'F0'], '01').kind == 'write:F0:0006'
}
check('cluster command form: odd-length payload refused') {
    app.zclFormRequest('cmd', [ep: '01', cluster: '0008', cmd: '04', payload: 'fe0', frameType: 'cluster'], '01') == [error: "Payload must be whole hex bytes, not 'fe0'"]
}
check('bind form to the hub') {
    app.bindFormRequest('bind', [srcEp: '1', cluster: '6', group: '', dstEp: null], '00124B0000000001', '00124B0000000002', '01') ==
        [srcEp: '01', bindCluster: '0006', dstIeee: '00124B0000000002', dstEp: '01', group: null, reqCluster: '0021', kind: 'bind']
}
check('unbind form to a group') {
    app.bindFormRequest('unbind', [srcEp: '01', cluster: '0006', group: '1'], '00124B0000000001', '00124B0000000002', '01') ==
        [srcEp: '01', bindCluster: '0006', dstIeee: null, dstEp: null, group: '0001', reqCluster: '0022', kind: 'bind']
}
check('bind form without the hub address') {
    app.bindFormRequest('bind', [srcEp: '01', cluster: '0006'], '00124B0000000001', null, '01') == [error: "The hub's Zigbee address is not known; give a group instead."]
}

// ---- frames ----
Map wr = [action: 'write', addr: '1A2B', ep: '01', cluster: '0006', attr: '4003', type: 0x30, value: '01']
check('write attributes frame') { app.buildRequestFrame(wr) == 'he raw 0x1A2B 1 0x01 0x0006 {00 00 02 03 40 30 01}' }
check('write attributes frame with a manufacturer code') { app.buildRequestFrame(wr + [mfg: '1037']) == 'he raw 0x1A2B 1 0x01 0x0006 {04 37 10 00 02 03 40 30 01}' }
check('read reporting configuration frame') {
    app.buildRequestFrame([action: 'readReport', addr: '1A2B', ep: '01', cluster: '0006', attr: '0000']) == 'he raw 0x1A2B 1 0x01 0x0006 {00 00 08 00 00 00}'
}
check('configure reporting frame, discrete') {
    app.buildRequestFrame([action: 'configReport', addr: '1A2B', ep: '01', cluster: '0006', attr: '0000', type: 0x10, min: 1, max: 600]) ==
        'he raw 0x1A2B 1 0x01 0x0006 {00 00 06 00 00 00 10 01 00 58 02}'
}
check('configure reporting frame, analog') {
    app.buildRequestFrame([action: 'configReport', addr: '1A2B', ep: '01', cluster: '0402', attr: '0000', type: 0x29, min: 30, max: 3600, change: '32 00']) ==
        'he raw 0x1A2B 1 0x01 0x0402 {00 00 06 00 00 00 29 1E 00 10 0E 32 00}'
}
check('cluster command frame') {
    app.buildRequestFrame([action: 'cmd', addr: '1A2B', ep: '01', cluster: '0006', cmdId: 0x02, payload: '', frameType: 1, toClient: false, ddr: false]) == 'he raw 0x1A2B 1 0x01 0x0006 {01 00 02}'
}
check('cluster command frame, default response disabled, payload, manufacturer') {
    app.buildRequestFrame([action: 'cmd', addr: '1A2B', ep: '01', cluster: 'FC00', cmdId: 0x01, payload: 'AA BB', frameType: 1, toClient: false, ddr: true, mfg: '1037']) ==
        'he raw 0x1A2B 1 0x01 0xFC00 {15 37 10 00 01 AA BB}'
}
check('general command frame, server to client') {
    app.buildRequestFrame([action: 'cmd', addr: '1A2B', ep: '01', cluster: '0006', cmdId: 0x00, payload: '00 00', frameType: 0, toClient: true, ddr: false]) == 'he raw 0x1A2B 1 0x01 0x0006 {08 00 00 00 00}'
}
check('bind frame to the hub') {
    app.buildRequestFrame([action: 'bind', addr: '1A2B', ieee: '00124B0000000001', reqCluster: '0021', srcEp: '01', bindCluster: '0006', dstIeee: '00124B0000000002', dstEp: '01']) ==
        'he raw 0x1A2B 0 0 0x0021 {00 01 00 00 00 00 4B 12 00 01 06 00 03 02 00 00 00 00 4B 12 00 01} {0x0000}'
}
check('unbind frame to a group') {
    app.buildRequestFrame([action: 'unbind', addr: '1A2B', ieee: '00124B0000000001', reqCluster: '0022', srcEp: '01', bindCluster: '0006', group: '0001']) ==
        'he raw 0x1A2B 0 0 0x0022 {00 01 00 00 00 00 4B 12 00 01 06 00 01 01 00} {0x0000}'
}
check('leave frame, no rejoin') {
    app.buildRequestFrame([action: 'leave', addr: '1A2B', ieee: '00124B0000000001', rejoin: false]) == 'he raw 0x1A2B 0 0 0x0034 {00 01 00 00 00 00 4B 12 00 00} {0x0000}'
}
check('leave frame, rejoin') {
    app.buildRequestFrame([action: 'leave', addr: '1A2B', ieee: '00124B0000000001', rejoin: true]) == 'he raw 0x1A2B 0 0 0x0034 {00 01 00 00 00 00 4B 12 00 80} {0x0000}'
}
check('firmware notify frame') { app.buildRequestFrame([action: 'ota', addr: '1A2B', ep: '01']) == 'he raw 0x1A2B 1 0x01 0x0019 {09 00 00 03 64 FFFF FFFF FFFF FFFF}' }

// ---- reply matching for the new requests ----
def zcl = { String cluster, List<String> p -> [addr: '1A2B', clusterId: cluster, srcEp: '01', payload: p] }
def one = { Map req, Map frame -> app.matchPending([k: req + [addr: '1A2B', zdo: false, sent: true, sentAt: t0, deadline: t0 + 25000]], frame, t0) != null }
Map wreq = [cluster: '0006', action: 'write', cmd: 0x02, attr: '4003', expectCmds: [0x04, 0x0B]]
check('write: all-success response fits') { one(wreq, zcl('0006', ['18', '6F', '04', '00'])) }
check('write: a failure record for the attribute fits') { one(wreq, zcl('0006', ['18', '6F', '04', '88', '03', '40'])) }
check('write: a record for another attribute does not fit') { !one(wreq, zcl('0006', ['18', '6F', '04', '86', '00', '00'])) }
check('write: a read response does not fit') { !one(wreq, zcl('0006', ['18', '6F', '01', '03', '40', '00', '30', '01'])) }
check('write: default response to Write Attributes fits, to another command not') {
    one(wreq, zcl('0006', ['08', '6F', '0B', '02', '86'])) && !one(wreq, zcl('0006', ['08', '6F', '0B', '00', '86']))
}
Map creq = [cluster: '0006', action: 'configReport', cmd: 0x06, attr: '0000', expectCmds: [0x07, 0x0B]]
check('configure reporting: all-success response fits') { one(creq, zcl('0006', ['18', '6F', '07', '00'])) }
check('configure reporting: failure record for the attribute fits, another attribute not') {
    one(creq, zcl('0006', ['18', '6F', '07', '8C', '00', '00', '00'])) && !one(creq, zcl('0006', ['18', '6F', '07', '8C', '00', '01', '00']))
}
Map rreq = [cluster: '0006', action: 'readReport', cmd: 0x08, attr: '0000', expectCmds: [0x09, 0x0B]]
List<String> rrsp = ['18', '6F', '09', '00', '00', '00', '00', '10', '01', '00', '58', '02']
check('read reporting configuration: record for the attribute fits') { one(rreq, zcl('0006', rrsp)) }
check('read reporting configuration: record for another attribute does not fit') {
    !one(rreq, zcl('0006', rrsp.withIndex().collect { b, i -> i == 5 ? '01' : b }))
}
check('read reporting configuration: an attribute report does not fit') { !one(rreq, zcl('0006', ['18', '6F', '0A', '00', '00', '10', '01'])) }
Map sreq = [cluster: '0006', action: 'cmd', cmd: 0x02, cmdId: 0x02, frameType: 1, toClient: false]
check('cluster command: default response to it fits') { one(sreq, zcl('0006', ['18', '6F', '0B', '02', '00'])) }
check('cluster command: default response to another command does not') { !one(sreq, zcl('0006', ['18', '6F', '0B', '01', '00'])) }
check('cluster command: the attribute report it triggers does not fit') { !one(sreq, zcl('0006', ['18', '6F', '0A', '00', '00', '10', '01'])) }
check('cluster command: a cluster-specific reply from the server fits') { one(sreq, zcl('0006', ['09', '6F', '00', '05'])) }
check('cluster command: a cluster-specific command from the device as client does not') { !one(sreq, zcl('0006', ['01', '6F', '00'])) }
Map greq = [cluster: '0006', action: 'cmd', cmd: 0x00, cmdId: 0x00, frameType: 0, toClient: false]
check('general command: its response command fits') { one(greq, zcl('0006', ['18', '6F', '01', '00', '00', '00', '10', '01'])) }
check('general command: another general command does not') { !one(greq, zcl('0006', ['18', '6F', '0A', '00', '00', '10', '01'])) }
Map oreq = [cluster: '0019', action: 'ota', cmd: 0x00]
check('firmware notify: the Query Next Image Request fits') { one(oreq, zcl('0019', ['01', '6F', '01', '00', '33', '12', '01', '00', '10', '00', '00', '02'])) }
check('firmware notify: other OTA traffic does not') { !one(oreq, zcl('0019', ['01', '6F', '03', '00'])) && !one(oreq, zcl('0019', ['09', '6F', '02', '00'])) }
def zdoOne = { String cl, List<String> p -> app.matchPending([k: [addr: '1A2B', cluster: cl, zdo: true, sent: true, sentAt: t0, deadline: t0 + 25000]],
    [addr: '1A2B', clusterId: cl, srcEp: '00', payload: p], t0) != null }
check('Bind_rsp, Unbind_rsp and Mgmt_Leave_rsp match on status') {
    zdoOne('8021', ['6F', '00']) && zdoOne('8022', ['6F', '88']) && zdoOne('8034', ['6F', '00']) && zdoOne('8021', ['6F', '8C'])
}

// ---- results of the new requests ----
def hdr = { List<String> p -> app.parseZclHeader(p) }
def outcome = { Map req, List<String> p -> Map h = hdr(p); app.zclResult(req, h, app.decodeZcl(h, p)) }
Map wreq2 = wreq + [ep: '01', type: 0x30, value: '01']
check('write success result') {
    outcome(wreq2, ['18', '6F', '04', '00']) ==
        [status: 'ok', data: [ep: '01', cluster: '0006', attr: '4003', type: 0x30, value: '01', records: [[status: 0, statusName: 'SUCCESS']]]]
}
check('write refused result') {
    outcome(wreq2, ['18', '6F', '04', '88', '03', '40']) ==
        [status: 'refused', statusName: 'READ_ONLY', error: 'The device answered READ_ONLY.']
}
check('read reporting configuration result') {
    outcome(rreq + [ep: '01'], rrsp) ==
        [status: 'ok', data: [op: 'read', ep: '01', cluster: '0006', attr: '0000',
                              records: [[direction: 0, attr: 0, status: 0, statusName: 'SUCCESS', type: 0x10, min: 1, max: 600]]]]
}
check('read reporting configuration with a record status is still a result') {
    outcome(rreq + [ep: '01'], ['18', '6F', '09', '8B', '00', '00', '00']) ==
        [status: 'ok', data: [op: 'read', ep: '01', cluster: '0006', attr: '0000', records: [[direction: 0, attr: 0, status: 0x8B, statusName: 'NOT_FOUND']]]]
}
check('configure reporting success result') {
    outcome(creq + [ep: '01', type: 0x10, min: 1, max: 600, change: null], ['18', '6F', '07', '00']) ==
        [status: 'ok', data: [op: 'configure', ep: '01', cluster: '0006', attr: '0000', type: 0x10, min: 1, max: 600, change: null, records: [[status: 0, statusName: 'SUCCESS']]]]
}
check('cluster command answered by a default response') {
    outcome(sreq + [ep: '01'], ['18', '6F', '0B', '02', '00']) == [status: 'ok', data: [ep: '01', cluster: '0006', cmd: 0x02, defaultResponse: 'SUCCESS']]
}
check('cluster command refused by a default response') {
    outcome(sreq + [ep: '01'], ['18', '6F', '0B', '02', '81']) == [status: 'refused', statusName: 'UNSUP_CLUSTER_COMMAND', error: 'The device answered UNSUP_CLUSTER_COMMAND.']
}
check('cluster command answered by a frame matched by cluster and direction says so') {
    outcome(sreq + [ep: '01'], ['09', '6F', '00', '05']) ==
        [status: 'ok', data: [ep: '01', cluster: '0006', cmd: 0x02, reply: [clusterSpecific: true, cmd: 0x00, payload: ['05'], matchedBy: 'cluster and direction']]]
}
check('general command answered by its response command') {
    outcome(greq + [ep: '01'], ['18', '6F', '01', '00', '00', '00', '10', '01']).data.reply.matchedBy == 'response command'
}
check('a ZCL reply from another endpoint does not match') {
    Map req = sreq + [addr: '1A2B', ep: '01', zdo: false, sent: true, sentAt: t0, deadline: t0 + 25000]
    app.matchPending([k: req], [addr: '1A2B', clusterId: '0006', srcEp: '02', payload: ['18', '6F', '0B', '02', '00']], t0) == null &&
        app.matchPending([k: req], [addr: '1A2B', clusterId: '0006', srcEp: '01', payload: ['18', '6F', '0B', '02', '00']], t0) != null &&
        app.matchPending([k: req + [ep: '0B']], [addr: '1A2B', clusterId: '0006', srcEp: '0B', payload: ['18', '6F', '0B', '02', '00']], t0) != null
}
check('firmware notify answered by a query') {
    outcome(oreq + [ep: '01'], ['01', '6F', '01', '00', '33', '12', '01', '00', '10', '00', '00', '02']) ==
        [status: 'ok', data: [query: [mfgCode: '1233', imageType: '0001', fileVersion: '02000010']]]
}
check('the no-reply text says when a success may send nothing') {
    app.timeoutEntry([frame: 'f', action: 'cmd', ddr: true], 1L).error == 'No reply. With the default response disabled, a command that succeeds may send none.' &&
        app.timeoutEntry([frame: 'f', action: 'ota'], 1L).error == 'No query came. The device may not take firmware updates this way, or may ask later.' &&
        app.timeoutEntry([frame: 'f', action: 'write'], 1L).error == 'No reply within 10 seconds. A battery device answers only when awake.'
}

// ==== Round 2: ZCL endpoint on every path, OTA reply endpoint, factory reset ====
check('every ZCL endpoint is 01-F0 (read and discover use zclEpError)') {
    app.zclEpError('00') == "ZCL endpoints are 01-F0, not '00'" && app.zclEpError('0') == "ZCL endpoints are 01-F0, not '00'" &&
        app.zclEpError('F1') == "ZCL endpoints are 01-F0, not 'F1'" && app.zclEpError('zz') == "Endpoint must be 2 hex digits, not 'zz'" &&
        app.zclEpError('1') == null && app.zclEpError('01') == null && app.zclEpError('F0') == null
}
check('a firmware notify query from another endpoint still matches') {
    Map req = [addr: '1A2B', cluster: '0019', action: 'ota', cmd: 0x00, ep: '01', zdo: false, sent: true, sentAt: t0, deadline: t0 + 25000]
    app.matchPending([k: req], [addr: '1A2B', clusterId: '0019', srcEp: '02', payload: ['01', '6F', '01', '00', '33', '12', '01', '00', '10', '00', '00', '02']], t0) != null
}
String RESET = 'This is Reset to Factory Defaults (Basic cluster command 00): the device loses its settings and may leave the network.'
String RESET_MAYBE = 'This Basic cluster frame cannot be read: it may be Reset to Factory Defaults.'
check('cluster command form takes Reset to Factory Defaults, with a warning') {
    Map a = app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '00', frameType: 'cluster', toClient: false], '01')
    Map b = app.zclFormRequest('cmd', [ep: '01', cluster: '0', cmd: '0', frameType: 'cluster'], '01')
    a.kind == 'cmd:01:0000' && app.requestWarning('cmd', a) == RESET && app.requestWarning('cmd', b) == RESET
}
check('other Basic cluster commands have no warning') {
    app.requestWarning('cmd', app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '00', frameType: 'general'], '01')) == null &&
        app.requestWarning('cmd', app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '00', frameType: 'cluster', toClient: true], '01')) == null &&
        app.requestWarning('cmd', app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '01', frameType: 'cluster'], '01')) == null
}
check('cluster command form still sends other Basic cluster commands') {
    app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '00', frameType: 'general'], '01').kind == 'cmd:01:0000' &&
        app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '00', frameType: 'cluster', toClient: true], '01').kind == 'cmd:01:0000' &&
        app.zclFormRequest('cmd', [ep: '01', cluster: '0000', cmd: '01', frameType: 'cluster'], '01').kind == 'cmd:01:0000'
}
check('raw: he cmd Reset to Factory Defaults is accepted, with a warning') {
    List<String> cs = ['he cmd 0x1A2B 0x01 0x0000 0x00 {}', 'he cmd 0x1A2B 1 0x0 0 {}']
    cs.every { app.rawCommandError(it, '1A2B') == null && app.rawWarning(it) == RESET }
}
check('raw: he cmd without a readable command id is still refused') {
    app.rawCommandError('he cmd 0x1A2B 0x01 0x0000', '1A2B') == REF && app.rawCommandError('he cmd 0x1A2B 0x01 0x0000 00 {}', '1A2B') == REF
}
check('raw: he raw Reset to Factory Defaults warns, with or without a manufacturer code') {
    ['{01 00 00}', '{11 00 00}', '{05 37 10 00 00}', '{010000}'].every { String b ->
        String c = "he raw 0x1A2B 1 0x01 0x0000 ${b}"
        app.rawCommandError(c, '1A2B') == null && app.rawWarning(c) == RESET
    }
}
check('raw: an unreadable Basic cluster frame may be a reset') {
    app.rawWarning('he raw 0x1A2B 1 0x01 0x0000 {01 00}') == RESET_MAYBE && app.rawWarning('he raw 0x1A2B 1 0x01 0x0000 {zz}') == RESET_MAYBE &&
        app.rawWarning('he raw 0x1A2B 1 0x01 0x0000') == RESET_MAYBE
}
check('raw: other Basic cluster frames have no warning') {
    ['he raw 0x1A2B 1 0x01 0x0000 {00 00 00 04 00}', 'he raw 0x1A2B 1 0x01 0x0000 {09 00 00}', 'he raw 0x1A2B 1 0x01 0x0000 {01 00 01}',
     'he raw 0x1A2B 0 0 0x0000 {00 01 00 00 00 00 4B 12 00 00 00} {0x0000}', 'he cmd 0x1A2B 0x01 0x0000 0x01 {}', 'he rattr 0x1A2B 0x01 0x0000 0x0004'].every { app.rawWarning(it) == null }
}
check('raw: other Basic cluster frames and ZDO NWK_addr_req pass') {
    app.rawCommandError('he raw 0x1A2B 1 0x01 0x0000 {00 00 00 04 00}', '1A2B') == null && app.rawCommandError('he raw 0x1A2B 1 0x01 0x0000 {09 00 00}', '1A2B') == null &&
        app.rawCommandError('he raw 0x1A2B 1 0x01 0x0000 {01 00 01}', '1A2B') == null && app.rawCommandError('he raw 0x1A2B 0 0 0x0000 {00 01 00 00 00 00 4B 12 00 00 00} {0x0000}', '1A2B') == null &&
        app.rawCommandError('he cmd 0x1A2B 0x01 0x0000 0x01 {}', '1A2B') == null && app.rawCommandError('he rattr 0x1A2B 0x01 0x0000 0x0004', '1A2B') == null
}

check('raw: he cmd command tokens above 0xFF are refused (a hub keeping one byte would send 00)') {
    String REFUSED = 'A raw command must be a he command for this device.'
    app.rawCommandError('he cmd 0x1A2B 0x01 0x0000 0x0100 {}', '1A2B') == REFUSED && app.rawCommandError('he cmd 0x1A2B 0x01 0x0006 0x0101 {}', '1A2B') == REFUSED &&
        app.rawCommandError('he cmd 0x1A2B 0x01 0x0006 0x100 {}', '1A2B') == REFUSED && app.rawCommandError('he cmd 0x1A2B 0x01 0x0006 0xFF {}', '1A2B') == null &&
        app.rawCommandError('he cmd 0x1A2B 0x01 0x0006 0x01 {}', '1A2B') == null
}
check('raw: he cmd without a readable command token is refused on any cluster') {
    app.rawCommandError('he cmd 0x1A2B 0x01 0x0006', '1A2B') == 'A raw command must be a he command for this device.' &&
        app.rawCommandError('he cmd 0x1A2B 0x01 0x0006 01 {}', '1A2B') == 'A raw command must be a he command for this device.'
}

// ---- monitor devices ----
Map monNames = [byNwk: ['0000': 'Hub', '1A2B': 'Plug A', '3C4D': 'Sensor B'], byIeee: ['00124B0000000002': 'Plug A']]
Map monNeighbors = [total: 4, entries: [
    [ieee: '00124B0000000002', nwk: '1A2B', relationship: 'Sibling', lqi: 140],
    [ieee: '00124B0000000003', nwk: '3C4D', relationship: 'Child', lqi: 33],
    [ieee: '00124B0000000004', nwk: '5E6F', relationship: 'Child', lqi: 90],
    [ieee: '00124B0000000001', nwk: '0000', relationship: 'Parent', lqi: 200]]]
Map monRoutes = [total: 3, entries: [[dest: '1A2B', routeStatus: 'Active'], [dest: '3C4D', routeStatus: 'Inactive'], [dest: '5E6F', routeStatus: 'Active']]]
Map mv = app.monitorValues(monNeighbors, monRoutes, monNames)
check('monitorValues counts neighbours by the table total') { mv.neighborCount == 4 }
check('monitorValues counts children by relationship Child') { mv.childCount == 2 }
check('monitorValues names the Parent entry') { mv.parent == 'Hub' }
check('monitorValues gives the lowest LQI with its name') { mv.lowestLqi == '33 Sensor B' }
check('monitorValues counts Active routes') { mv.routeCount == 2 }
check('monitorValues without a parent says none, unnamed entries show their address') {
    Map v = app.monitorValues([total: 1, entries: [[ieee: '00124B0000000009', nwk: '7A8B', relationship: 'Sibling', lqi: 12]]], null, monNames)
    v == [neighborCount: 1, childCount: 0, parent: 'none', lowestLqi: '12 7A8B']
}
check('monitorValues with no tables is empty') { app.monitorValues(null, null, monNames) == [:] }
long hNow = 1_800_000_000_000L
String iso(long ms) { def f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"); f.setTimeZone(TimeZone.getTimeZone('UTC')); f.format(new Date(ms)) }
check('healthFrom: offline 61 minutes after the last message with a 60-minute limit') { app.healthFrom(iso(hNow - 61 * 60000L), 60, hNow) == 'offline' }
check('healthFrom: online at 59 minutes') { app.healthFrom(iso(hNow - 59 * 60000L), 60, hNow) == 'online' }
check('healthFrom: unknown without a time') { app.healthFrom(null, 60, hNow) == 'unknown' && app.healthFrom('', 60, hNow) == 'unknown' }
check('healthFrom: unknown for an unreadable time') { app.healthFrom('yesterday', 60, hNow) == 'unknown' && app.healthFrom('2026-10-18T22:53:49+0000junk', 60, hNow) == 'unknown' }
check('healthFrom reads the hub format and ISO offsets') {
    app.isoMillis('2026-10-18T22:53:49+0000') == app.isoMillis('2026-10-18T18:53:49-04:00') && app.isoMillis('2026-10-18T22:53:49+0000') == 1792364029000L &&
        app.isoMillis('2026-10-18T22:53:49Z') == 1792364029000L && app.isoMillis('2026-10-18T22:53:49.250+0000') == 1792364029250L && app.isoMillis('2026-10-18 22:53:49') == null
}
check('defaultInactiveMinutes: 60 mains, 720 battery, from the node descriptor') {
    app.defaultInactiveMinutes([logicalType: 'Router', macCapability: 0x8E]) == 60 && app.defaultInactiveMinutes([logicalType: 'End Device', macCapability: 0x80]) == 720 &&
        app.defaultInactiveMinutes([logicalType: 'End Device', macCapability: 0x84]) == 60 && app.defaultInactiveMinutes([logicalType: 'End Device']) == 720 &&
        app.defaultInactiveMinutes(null) == 60
}
check('pollCron: one run per interval at the given offsets') {
    app.pollCron(15, 7, 22) == '7 7/15 * ? * *' && app.pollCron(30, 59, 45) == '59 15/30 * ? * *' &&
        app.pollCron(60, 0, 41) == '0 41 * ? * *' && app.pollCron(180, 12, 40) == '12 40 1/3 ? * *'
}
check('monitor DNI round trip') { app.monitorDni('00124b0000000002') == 'zbworkbench-mon-00124B0000000002' && app.monitorIeee('zbworkbench-mon-00124B0000000002') == '00124B0000000002' }
check('monitorIeee refuses other DNIs') { app.monitorIeee('zbworkbench-radio') == null && app.monitorIeee('zbworkbench-mon-XYZ') == null && app.monitorIeee(null) == null }
check('orphanMonitors lists monitors whose device is no longer picked, never others') {
    app.orphanMonitors(['zbworkbench-radio', 'zbworkbench-mon-00124B0000000003', 'zbworkbench-mon-00124B0000000002'], ['00124B0000000002']) == ['00124B0000000003'] &&
        app.orphanMonitors([], ['00124B0000000002']) == [] && app.orphanMonitors(['zbworkbench-mon-00124B0000000002'], []) == ['00124B0000000002']
}

// ---- review fixes: health of an unlisted device, polls never cancel a user's request ----
Map hDetails = [devices: [[zigbeeId: '00124B0000000002', lastMessage: iso(hNow - 5 * 60000L)], [zigbeeId: '00124B0000000003', lastMessage: null]]]
check('healthFor: a listed device follows its last message') { app.healthFor(hDetails, '00124b0000000002', 60, hNow) == 'online' }
check('healthFor: a device the hub no longer lists is offline') { app.healthFor(hDetails, '00124B0000000009', 60, hNow) == 'offline' }
check('healthFor: no last message is unknown') { app.healthFor(hDetails, '00124B0000000003', 60, hNow) == 'unknown' }
check('healthFor: no device list (failed read) is unknown') { app.healthFor([:], '00124B0000000002', 60, hNow) == 'unknown' && app.healthFor(null, '00124B0000000002', 60, hNow) == 'unknown' }
check('pollKinds skips kinds already pending for the device') {
    Map pend = [a: [ieee: '00124B0000000002', kind: 'neighbors'], b: [ieee: '00124B0000000003', kind: 'routes']]
    app.pollKinds(['node', 'neighbors', 'routes'], pend, '00124B0000000002') == ['node', 'routes'] &&
        app.pollKinds(['neighbors', 'routes'], null, '00124B0000000002') == ['neighbors', 'routes']
}

// ---- R28: the radio's socket stays open; a request finding it down is lost at once ----
check('flushDecision: connected sends, anything else fails at once') {
    app.flushDecision('connected') == 'send' && app.flushDecision('connecting') == 'fail' && app.flushDecision('failed') == 'fail' &&
        app.flushDecision('disconnected') == 'fail' && app.flushDecision(null) == 'fail'
}
check('watchFor lists pending addresses once, sorted, empty when nothing is pending') {
    app.watchFor([a: [addr: '3c4d'], b: [addr: '1A2B'], c: [addr: '3C4D']]) == ['1A2B', '3C4D'] && app.watchFor([:]) == [] && app.watchFor(null) == []
}

// ---- interview ----
def ints = { int n, int type -> (0..<n).collect { [id: it, type: type] } }
check('readChunks: eight fixed attributes per read at most') {
    app.readChunks(ints(9, 0x20)) == [(0..7).toList(), [8]]
}
check('readChunks: a string fills most of a read, a type the codec cannot size reads alone') {
    app.readChunks([[id: 4, type: 0x42], [id: 0, type: 0x20], [id: 5, type: 0x42], [id: 9, type: 0x48], [id: 10, type: 0x20]]) ==
        [[4, 0], [5], [9], [10]]
}
check('interviewPlan: node and endpoints first') {
    app.interviewPlan([:]).collect { it.key } == ['node', 'endpoints']
}
Map ivPlan = [endpoints: ['01', 'F2'], simple: ['01': [inClusters: ['0000', '0006']], 'F2': [inClusters: ['0021']]],
    disc: ['01:0006': [[id: 0, type: 0x10], [id: 0x4003, type: 0x30]]]]
check('interviewPlan: descriptors per endpoint, ZCL steps only on 01-F0, reads after discovery') {
    app.interviewPlan(ivPlan).collect { it.key } ==
        ['node', 'endpoints', 'simple:01', 'simple:F2', 'discover:01:0000', 'discover:01:0006', 'read:01:0006:0000']
}
check('interviewPlan: a read names its endpoint, cluster and attributes') {
    Map s = app.interviewPlan(ivPlan).last()
    s.action == 'read' && s.args == ['01', '0006', '0000,4003'] && s.kind == 'read:01:0006' && s.attrs == ['0000', '4003']
}
check('interviewNext: first step not tried, null when all are') {
    app.interviewNext([tried: ['node']]).key == 'endpoints' && app.interviewNext([tried: ['node', 'endpoints'], endpoints: []]) == null
}
check('interviewRecord: a read keeps only the attributes it asked for') {
    Map iv = [:]
    Map step = [key: 'read:01:0006:0000', action: 'read', args: ['01', '0006', '0000'], attrs: ['0000']]
    app.interviewRecord(iv, step, [status: 'ok', data: [records: [[attr: 0, status: 0, type: 0x10, value: true], [attr: 0x4003, status: 0, type: 0x30, value: 1]]]])
    iv.vals == ['01:0006': ['0000': [status: 0, statusName: null, type: 0x10, value: true]]]
}
check('interviewRecord: endpoints, simple, discover data; a failure keeps its reason') {
    Map iv = [:]
    app.interviewRecord(iv, [key: 'endpoints', action: 'endpoints', args: []], [status: 'ok', data: [endpoints: ['01']]])
    app.interviewRecord(iv, [key: 'simple:01', action: 'simple', args: ['01']], [status: 'ok', data: [inClusters: ['0006']]])
    app.interviewRecord(iv, [key: 'discover:01:0006', action: 'discover', args: ['01', '0006']], [status: 'ok', data: [attrs: [[id: 0, type: 0x10]], complete: true]])
    app.interviewRecord(iv, [key: 'node', action: 'node', args: []], [status: 'timeout', error: 'No reply'])
    iv.endpoints == ['01'] && iv.simple == ['01': [inClusters: ['0006']]] && iv.disc == ['01:0006': [[id: 0, type: 0x10]]] &&
        iv.failed == [node: 'timeout: No reply']
}

// ---- timeout hint follows the node descriptor ----
check('sleepyFrom: receiver-on-when-idle bit clear means sleepy; unread is null') {
    app.sleepyFrom([macCapability: 0x8E]) == false && app.sleepyFrom([macCapability: 0x80]) == true &&
        app.sleepyFrom([:]) == null && app.sleepyFrom(null) == null
}
check('noReplyText: the battery hint only for a sleepy or unknown device') {
    app.noReplyText([action: 'read', sleepy: false]) == 'No reply within 10 seconds.' &&
        app.noReplyText([action: 'read', sleepy: true]).contains('answers only when awake') &&
        app.noReplyText([action: 'read']).contains('A battery device answers only when awake') &&
        app.noReplyText([action: 'cmd', ddr: true, sleepy: false]).contains('default response')
}
check('configReport: a minimum longer than the maximum is refused, except for maximum 0 or 65535') {
    Map f = [ep: '01', cluster: '0006', attr: '0000', type: '10']
    app.zclFormRequest('configReport', f + [min: '600', max: '60'], '01') == [error: "The minimum interval (600 s) is longer than the maximum (60 s)"] &&
        !app.zclFormRequest('configReport', f + [min: '600', max: '0'], '01').error &&
        !app.zclFormRequest('configReport', f + [min: '65535', max: '65535'], '01').error &&
        !app.zclFormRequest('configReport', f + [min: '60', max: '60'], '01').error
}
check('firmware notify: a successful Default Response is not the answer, a failed one is') {
    Map ota = [action: 'ota', cmd: 0x00, cluster: '0019']
    List ok = '18 05 0B 00 00'.split(' ') as List
    List bad = '18 05 0B 00 81'.split(' ') as List
    !app.replyFits(ota, ok, app.parseZclHeader(ok)) && app.replyFits(ota, bad, app.parseZclHeader(bad))
}
// ---- frameTimeMs ----
TimeZone tor = TimeZone.getTimeZone('America/Toronto')
long tFrame = Date.parse('yyyy-MM-dd HH:mm:ss.SSS', '2026-10-27 21:52:00.577', tor).time
check('frameTimeMs: reads the frame time in the hub time zone') {
    app.frameTimeMs([time: '2026-10-27 21:52:00.577'], tFrame + 30000L, tor) == tFrame
}
check('frameTimeMs: never later than now') {
    app.frameTimeMs([time: '2026-10-27 21:52:00.577'], tFrame - 1000L, tor) == tFrame - 1000L
}
check('frameTimeMs: now when the time is missing or unreadable') {
    app.frameTimeMs([:], 42L, tor) == 42L && app.frameTimeMs([time: 'soon'], 42L, tor) == 42L && app.frameTimeMs(null, 42L, tor) == 42L
}
check('a reply processed after its deadline matches by its frame time, not by now') {
    Map req = zdoReq + [sentAt: tFrame - 2000L, deadline: tFrame + 1000L]
    Map frame = zdoFrame + [time: '2026-10-27 21:52:00.577']
    long late = tFrame + 20000L
    app.matchPending(['k': new LinkedHashMap(req)], frame, late) == null &&
        app.matchPending(['k': new LinkedHashMap(req)], frame, app.frameTimeMs(frame, late, tor)) != null
}
println "${passed} passed, ${failed} failed, 0 warnings"
System.exit(failed == 0 ? 0 : 1)
