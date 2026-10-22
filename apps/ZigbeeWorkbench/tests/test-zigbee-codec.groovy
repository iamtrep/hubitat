// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the Zigbee Workbench ZDO/ZCL codec.
// Brace-extracts the codec methods and constants from the shipped ZigbeeWorkbench.groovy and
// evaluates them under plain groovy; no live hub required.
//
// Run: groovy apps/ZigbeeWorkbench/tests/test-zigbee-codec.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'ZigbeeWorkbench.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

// The codec is the self-contained region between these markers (static methods and constants only).
String BEGIN = '// ==== Codec begin ===='
String END = '// ==== Codec end ===='
int cb = src.indexOf(BEGIN)
int ce = src.indexOf(END)
assert cb >= 0 && ce > cb : "signature not found: codec region markers"
String codec = src.substring(cb, ce)

String harness = """
import groovy.transform.CompileStatic
import groovy.transform.Field
${codec}
this
"""
def app = new GroovyShell().evaluate(harness)

def decodeZdo = { String c, List p -> app.decodeZdo(c, p) }
def decodeZcl = { Map h, List p -> app.decodeZcl(h, p) }
def parseZclHeader = { List p -> app.parseZclHeader(p) }
def decodeZclValue = { int t, List p, int o -> app.decodeZclValue(t, p, o) }
def zdoFrame = { String a, String c, int s, String p -> app.zdoFrame(a, c, s, p) }
def zclFrame = { String a, String ep, String c, int s, int cmd, String p, Map o -> app.zclFrame(a, ep, c, s, cmd, p, o) }
def imageNotifyFrame = { String a, String ep, int s -> app.imageNotifyFrame(a, ep, s) }
def nwkPayload = { String a -> app.nwkPayload(a) }
def simpleDescPayload = { String a, String ep -> app.simpleDescPayload(a, ep) }
def indexPayload = { int i -> app.indexPayload(i) }
def leavePayload = { String i, boolean r -> app.leavePayload(i, r) }
def bindPayload = { String a, String b2, String c, String d, String e2 -> app.bindPayload(a, b2, c, d, e2) }
def bindGroupPayload = { String a, String b2, String c, String g -> app.bindGroupPayload(a, b2, c, g) }
def readAttrPayload = { List<Integer> ids -> app.readAttrPayload(ids) }
def discoverAttrPayload = { int s, int m -> app.discoverAttrPayload(s, m) }
def writeAttrPayload = { int i, int t, String v -> app.writeAttrPayload(i, t, v) }
def configReportingPayload = { int i, int t, int mn, int mx, String c -> app.configReportingPayload(i, t, mn, mx, c) }
def readReportingPayload = { int i -> app.readReportingPayload(i) }
def L = { String s -> s.split(' ') as List }
def zcl = { String s -> List p = L(s); decodeZcl(parseZclHeader(p), p) }

int passed = 0
int failed = 0
def check = { String name, Closure c ->
    boolean ok
    String why = ''
    try { ok = c() } catch (Throwable t) { ok = false; why = " (${t.class.simpleName}: ${t.message})" }
    if (ok) { passed++; println "[PASS] ${name}" } else { failed++; println "[FAIL] ${name}${why}" }
}

// ---- ZDO decoders -------------------------------------------------------------------------
def lqi = L('C0 00 16 00 02 89 67 45 23 01 EF CD AB 01 00 00 00 00 4B 12 00 6F 5E 25 02 02 45 89 67 45 23 01 EF CD AB 02 00 00 00 00 4B 12 00 8B 7A 25 02 01 8B')
check('8031 header') {
    def r = decodeZdo('8031', lqi)
    r.seq == 0xC0 && r.status == 0 && r.statusName == 'SUCCESS' && r.total == 22 && r.start == 0 && r.entries.size() == 2
}
check('8031 entry 0') {
    decodeZdo('8031', lqi).entries[0] == [extPan: 'ABCDEF0123456789', ieee: '00124B0000000001', nwk: '5E6F', deviceType: 'Router',
        rxOnWhenIdle: 'On', relationship: 'Sibling', permitJoin: 'Unknown', depth: 2, lqi: 69]
}
check('8031 entry 1') {
    def e = decodeZdo('8031', lqi).entries[1]
    e.nwk == '7A8B' && e.lqi == 139 && e.depth == 1 && e.ieee == '00124B0000000002'
}
check('8031 coordinator/end device/parent/child/permit') {
    // octet1 0x10|0x00: Coordinator, rx off, relationship 1 Child; octet2 0x01: permit joining
    def p = L('01 00 01 00 01 00 00 00 00 00 00 00 00 01 00 00 00 00 4B 12 00 34 12 10 01 00 20')
    def e = decodeZdo('8031', p).entries[0]
    // 0x10: bits0-1=0 coordinator, bits2-3=0 rx off, bits4-6=1 child
    e.deviceType == 'Coordinator' && e.rxOnWhenIdle == 'Off' && e.relationship == 'Child' && e.permitJoin == 'Yes' && e.lqi == 32
}
check('8031 empty table') {
    def r = decodeZdo('8031', L('05 00 00 00 00'))
    r.total == 0 && r.entries == []
}
check('8032 not supported') {
    def r = decodeZdo('8032', ['01', '84'])
    r.seq == 1 && r.status == 0x84 && r.statusName == 'NOT_SUPPORTED' && !r.containsKey('entries')
}
def rtg = L('11 00 03 00 02 2B 1A 00 6F 5E 8B 7A 3B 00 00')
check('8032 two entries') {
    def r = decodeZdo('8032', rtg)
    r.seq == 0x11 && r.total == 3 && r.start == 0 && r.entries.size() == 2 &&
    r.entries[0] == [dest: '1A2B', routeStatus: 'Active', memoryConstrained: false, manyToOne: false, routeRecordRequired: false, nextHop: '5E6F'] &&
    r.entries[1] == [dest: '7A8B', routeStatus: 'Inactive', memoryConstrained: true, manyToOne: true, routeRecordRequired: true, nextHop: '0000']
}
def bnd = L('12 00 02 00 02 01 00 00 00 00 4B 12 00 01 06 00 01 34 12 01 00 00 00 00 4B 12 00 01 08 00 03 02 00 00 00 00 4B 12 00 0A')
check('8033 group and ieee entries') {
    def r = decodeZdo('8033', bnd)
    r.total == 2 && r.start == 0 && r.entries.size() == 2 &&
    r.entries[0] == [srcIeee: '00124B0000000001', srcEp: '01', cluster: '0006', dstGroup: '1234'] &&
    r.entries[1] == [srcIeee: '00124B0000000001', srcEp: '01', cluster: '0008', dstIeee: '00124B0000000002', dstEp: '0A']
}
check('8033 no entry status') { decodeZdo('8033', ['02', '88']).statusName == 'NO_ENTRY' }
check('8002 node descriptor') {
    def r = decodeZdo('8002', L('13 00 2B 1A 01 40 8E 37 10 52 00 01 40 2C 00 01 00'))
    r.seq == 0x13 && r.nwk == '1A2B' && r.logicalType == 'Router' && r.mfgCode == '1037' && r.maxBufferSize == 82 &&
    r.macCapability == 0x8E && r.serverMask == 0x2C40
}
check('8002 end device') { decodeZdo('8002', L('13 00 2B 1A 02 40 80 37 10 52 00 01 40 2C 00 01 00')).logicalType == 'End Device' }
check('8003 power descriptor') {
    def r = decodeZdo('8003', L('14 00 2B 1A 10 C1'))
    r.nwk == '1A2B' && r.currentMode == 0 && r.availableSources == 1 && r.currentSource == 1 && r.level == 12
}
check('8005 active endpoints') {
    def r = decodeZdo('8005', L('15 00 2B 1A 02 01 F2'))
    r.nwk == '1A2B' && r.endpoints == ['01', 'F2']
}
check('8004 simple descriptor') {
    def r = decodeZdo('8004', L('16 00 2B 1A 0E 01 04 01 00 01 01 02 00 00 06 00 01 19 00'))
    r.nwk == '1A2B' && r.ep == '01' && r.profileId == '0104' && r.deviceId == '0100' && r.version == 1 &&
    r.inClusters == ['0000', '0006'] && r.outClusters == ['0019']
}
check('8004 invalid ep') {
    def r = decodeZdo('8004', L('17 82 2B 1A 00'))
    r.status == 0x82 && r.statusName == 'INVALID_EP' && r.nwk == null
}
check('8034 status only') {
    def r = decodeZdo('8034', L('18 00'))
    r.seq == 0x18 && r.status == 0 && r.statusName == 'SUCCESS'
}
check('unknown ZDO status name') { decodeZdo('8034', L('19 9C')).statusName == '0x9C' }
check('ZDO status names') {
    app.ZDO_STATUS == [0x00: 'SUCCESS', 0x80: 'INV_REQUESTTYPE', 0x81: 'DEVICE_NOT_FOUND', 0x82: 'INVALID_EP', 0x83: 'NOT_ACTIVE',
        0x84: 'NOT_SUPPORTED', 0x85: 'TIMEOUT', 0x86: 'NO_MATCH', 0x88: 'NO_ENTRY', 0x89: 'NO_DESCRIPTOR',
        0x8A: 'INSUFFICIENT_SPACE', 0x8B: 'NOT_PERMITTED', 0x8C: 'TABLE_FULL', 0x8D: 'NOT_AUTHORIZED', 0x8E: 'DEVICE_BINDING_TABLE_FULL']
}

// ---- ZDO and ZCL builders -----------------------------------------------------------------
check('zdoFrame index') { zdoFrame('1A2B', '0031', 0x12, indexPayload(4)) == 'he raw 0x1A2B 0 0 0x0031 {12 04} {0x0000}' }
check('indexPayload 255') { indexPayload(255) == 'FF' }
check('nwkPayload') { nwkPayload('1A2B') == '2B 1A' }
check('zdoFrame node desc') { zdoFrame('1A2B', '0002', 0x0A, nwkPayload('1A2B')) == 'he raw 0x1A2B 0 0 0x0002 {0A 2B 1A} {0x0000}' }
check('simpleDescPayload') { simpleDescPayload('1A2B', '01') == '2B 1A 01' }
check('leavePayload no rejoin') { leavePayload('00124B0000000001', false) == '01 00 00 00 00 4B 12 00 00' }
check('leavePayload rejoin') { leavePayload('00124B0000000001', true) == '01 00 00 00 00 4B 12 00 80' }
check('bindPayload') {
    bindPayload('00124B0000000001', '01', '0006', '00124B0000000002', '0A') == '01 00 00 00 00 4B 12 00 01 06 00 03 02 00 00 00 00 4B 12 00 0A'
}
check('bindGroupPayload') { bindGroupPayload('00124B0000000001', '01', '0006', '1234') == '01 00 00 00 00 4B 12 00 01 06 00 01 34 12' }
check('zdoFrame bind') {
    zdoFrame('1A2B', '0021', 0x20, bindGroupPayload('00124B0000000001', '01', '0006', '1234')) ==
        'he raw 0x1A2B 0 0 0x0021 {20 01 00 00 00 00 4B 12 00 01 06 00 01 34 12} {0x0000}'
}
check('zclFrame read') { zclFrame('1A2B', '01', '0006', 5, 0x00, readAttrPayload([0x0000, 0x4003]), [:]) == 'he raw 0x1A2B 1 0x01 0x0006 {00 05 00 00 00 03 40}' }
check('zclFrame mfg') { zclFrame('1A2B', '01', 'FC11', 6, 0x00, readAttrPayload([0x0000]), [mfgCode: '1037']) == 'he raw 0x1A2B 1 0x01 0xFC11 {04 37 10 06 00 00 00}' }
check('zclFrame cluster specific, no payload') { zclFrame('1A2B', '01', '0006', 9, 0x01, '', [clusterSpecific: true]) == 'he raw 0x1A2B 1 0x01 0x0006 {01 09 01}' }
check('zclFrame toClient + disableDefaultRsp') { zclFrame('1A2B', '01', '0006', 10, 0x0B, '01 00', [toClient: true, disableDefaultRsp: true]) == 'he raw 0x1A2B 1 0x01 0x0006 {18 0A 0B 01 00}' }
check('zclFrame all flags') { zclFrame('1A2B', '01', 'FCC0', 3, 0x02, '', [clusterSpecific: true, disableDefaultRsp: true, mfgCode: '115F']) == 'he raw 0x1A2B 1 0x01 0xFCC0 {15 5F 11 03 02}' }
check('discoverAttrPayload') { discoverAttrPayload(0, 255) == '00 00 FF' && discoverAttrPayload(0x4000, 16) == '00 40 10' }
check('writeAttrPayload') { writeAttrPayload(0x0011, 0x20, '05') == '11 00 20 05' }
check('configReportingPayload no change') { configReportingPayload(0x0000, 0x10, 1, 300, '') == '00 00 00 10 01 00 2C 01' }
check('configReportingPayload change') { configReportingPayload(0x0000, 0x29, 10, 3600, '0A 00') == '00 00 00 29 0A 00 10 0E 0A 00' }
check('readReportingPayload') { readReportingPayload(0x0000) == '00 00 00' && readReportingPayload(0x4003) == '00 03 40' }
check('imageNotifyFrame') { imageNotifyFrame('1A2B', '01', 7) == 'he raw 0x1A2B 1 0x01 0x0019 {09 07 00 03 64 FFFF FFFF FFFF FFFF}' }

// ---- ZCL decoders -------------------------------------------------------------------------
check('parseZclHeader plain') {
    parseZclHeader(L('18 05 01 00 00 00 10 01 03 40 86')) == [frameType: 0, mfgSpecific: false, toClient: true, disableDefaultRsp: true,
        mfgCode: null, seq: 5, cmd: 1, offset: 3]
}
check('parseZclHeader mfg') {
    parseZclHeader(L('1C 37 10 05 0A 00')) == [frameType: 0, mfgSpecific: true, toClient: true, disableDefaultRsp: true,
        mfgCode: '1037', seq: 5, cmd: 0x0A, offset: 5]
}
def rdp = L('18 05 01 00 00 00 10 01 03 40 86')
def rd = decodeZcl(parseZclHeader(rdp), rdp)
check('0x01 success record') { rd.records[0] == [attr: 0x0000, status: 0, statusName: 'SUCCESS', type: 0x10, value: true] }
check('0x01 unsupported attribute') { rd.records[1].status == 0x86 && rd.records[1].statusName == 'UNSUPPORTED_ATTRIBUTE' && rd.records[1].attr == 0x4003 && rd.records.size() == 2 }
check('0x01 mfg header') {
    def r = zcl('1C 37 10 05 01 00 00 00 20 07')
    r.records[0] == [attr: 0, status: 0, statusName: 'SUCCESS', type: 0x20, value: 7]
}
check('0x01 unknown type stops the list') {
    def r = zcl('18 05 01 00 00 00 48 20 02 00 01 02 01 00 00 20 05')
    r.records.size() == 1 && r.records[0].type == 0x48 && r.records[0].value.startsWith('raw:2002000102')
}
check('0x01 string and int') {
    def r = zcl('18 05 01 04 00 00 42 05 48 65 6C 6C 6F 05 00 00 29 FE FF')
    r.records[0].value == 'Hello' && r.records[1].attr == 5 && r.records[1].value == -2
}
check('0x0D discover') {
    def r = zcl('18 06 0D 01 00 00 21 01 00 20')
    r.complete == true && r.attrs == [[id: 0, type: 0x21], [id: 1, type: 0x20]]
}
check('0x0D incomplete') { zcl('18 06 0D 00 00 00 21').complete == false }
check('0x04 all success') { zcl('18 07 04 00').records == [[status: 0, statusName: 'SUCCESS']] }
check('0x04 one failure') { zcl('18 08 04 86 03 40').records == [[status: 0x86, statusName: 'UNSUPPORTED_ATTRIBUTE', attr: 0x4003]] }
check('0x04 mixed') { zcl('18 08 04 00 00 00 88 01 00').records.collect { it.status } == [0, 0x88] }
check('0x07 all success') { zcl('18 09 07 00').records == [[status: 0, statusName: 'SUCCESS']] }
check('0x07 failure') { zcl('18 09 07 8C 00 00 00').records == [[status: 0x8C, statusName: 'UNREPORTABLE_ATTRIBUTE', direction: 0, attr: 0]] }
check('0x09 reporting config') {
    def r = zcl('18 0A 09 00 00 00 00 29 01 00 2C 01 0A 00 86 00 03 40')
    r.records[0] == [direction: 0, attr: 0, status: 0, statusName: 'SUCCESS', type: 0x29, min: 1, max: 300, change: 10] &&
    r.records[1] == [direction: 0, attr: 0x4003, status: 0x86, statusName: 'UNSUPPORTED_ATTRIBUTE']
}
check('0x09 discrete and received side') {
    def r = zcl('18 0A 09 00 00 06 00 10 00 00 3C 00 00 01 01 00 3C 00')
    r.records[0] == [direction: 0, attr: 6, status: 0, statusName: 'SUCCESS', type: 0x10, min: 0, max: 60] &&
    r.records[1] == [direction: 1, attr: 1, status: 0, statusName: 'SUCCESS', timeout: 60]
}
check('0x0B default response') {
    def r = zcl('18 0A 0B 01 81')
    r.cmd == 1 && r.status == 0x81 && r.statusName == 'UNSUP_CLUSTER_COMMAND' && r.seq == 0x0A
}
check('ZCL unknown status name') { zcl('18 0A 0B 01 9C').statusName == '0x9C' }
check('ZCL 0x86 name') { app.ZCL_STATUS[0x86] == 'UNSUPPORTED_ATTRIBUTE' && app.ZCL_STATUS[0] == 'SUCCESS' && app.ZCL_STATUS[0xC3] == 'UNSUPPORTED_CLUSTER' }

// ---- decodeZclValue -----------------------------------------------------------------------
check('value char string') { decodeZclValue(0x42, L('05 48 65 6C 6C 6F'), 0) == ['Hello', 6] }
check('value int16 signed') { decodeZclValue(0x29, L('FE FF'), 0) == [-2, 2] }
check('value 0x48 raw') { decodeZclValue(0x48, L('20 02 00 01 02'), 0)[0].startsWith('raw:') && decodeZclValue(0x48, L('20 02 00 01 02'), 0)[1] == 5 }
check('value 0x4C/0x50/0x51 raw') { [0x4C, 0x50, 0x51].every { decodeZclValue(it, L('01 02'), 0)[0] == 'raw:0102' } }
check('value offset') { decodeZclValue(0x20, L('AA BB'), 1) == [0xBB, 1] }
check('value bool') { decodeZclValue(0x10, L('01'), 0) == [true, 1] && decodeZclValue(0x10, L('00'), 0) == [false, 1] }
check('value unsigned ints') {
    decodeZclValue(0x20, L('FF'), 0) == [255, 1] && decodeZclValue(0x21, L('FF FF'), 0) == [65535, 2] &&
    decodeZclValue(0x22, L('01 02 03'), 0) == [0x030201, 3] && decodeZclValue(0x23, L('FF FF FF FF'), 0) == [4294967295L, 4] &&
    decodeZclValue(0x24, L('01 00 00 00 00'), 0) == [1, 5] && decodeZclValue(0x25, L('01 00 00 00 00 00'), 0) == [1, 6] &&
    decodeZclValue(0x26, L('01 00 00 00 00 00 00'), 0) == [1, 7] &&
    decodeZclValue(0x27, L('FF FF FF FF FF FF FF FF'), 0) == [new BigInteger('18446744073709551615'), 8]
}
check('value signed ints') {
    decodeZclValue(0x28, L('80'), 0) == [-128, 1] && decodeZclValue(0x2A, L('FF FF FF'), 0) == [-1, 3] &&
    decodeZclValue(0x2B, L('FF FF FF FF'), 0) == [-1, 4] && decodeZclValue(0x2C, L('FF FF FF FF FF'), 0) == [-1, 5] &&
    decodeZclValue(0x2F, L('FF FF FF FF FF FF FF 7F'), 0) == [Long.MAX_VALUE, 8]
}
check('value bitmaps') {
    decodeZclValue(0x18, L('0F'), 0) == [15, 1] && decodeZclValue(0x19, L('01 80'), 0) == [0x8001, 2] && decodeZclValue(0x1B, L('01 00 00 80'), 0) == [0x80000001L, 4]
}
check('value enums') { decodeZclValue(0x30, L('02'), 0) == [2, 1] && decodeZclValue(0x31, L('01 01'), 0) == [0x0101, 2] }
check('value floats') {
    decodeZclValue(0x38, L('00 3C'), 0) == [1.0d, 2] && decodeZclValue(0x39, L('00 00 80 3F'), 0) == [1.0d, 4] &&
    decodeZclValue(0x3A, L('00 00 00 00 00 00 F0 3F'), 0) == [1.0d, 8] && decodeZclValue(0x38, L('00 C0'), 0) == [-2.0d, 2]
}
check('value octet and long strings') {
    decodeZclValue(0x41, L('03 01 02 03'), 0) == ['010203', 4] && decodeZclValue(0x44, L('03 00 41 42 43'), 0) == ['ABC', 5] &&
    decodeZclValue(0x43, L('02 00 AB CD'), 0) == ['ABCD', 4] && decodeZclValue(0x42, L('00'), 0) == ['', 1]
}
check('value time types') {
    decodeZclValue(0xE0, L('0A 1E 00 00'), 0) == ['10:30:00.00', 4] && decodeZclValue(0xE1, L('7D 0A 12 07'), 0) == ['2025-10-18', 4] &&
    decodeZclValue(0xE2, L('10 0E 00 00'), 0) == [3600, 4]
}
check('value ids and ieee') {
    decodeZclValue(0xE8, L('06 00'), 0) == ['0006', 2] && decodeZclValue(0xE9, L('03 40'), 0) == ['4003', 2] &&
    decodeZclValue(0xEA, L('01 02 03 04'), 0) == ['04030201', 4] &&
    decodeZclValue(0xF0, L('01 00 00 00 00 4B 12 00'), 0) == ['00124B0000000001', 8]
}
check('value truncated gives raw') { decodeZclValue(0x21, L('01'), 0) == ['raw:01', 1] }

// ---- interview report --------------------------------------------------------------------
Map ivFix = [node: [logicalType: 'Router', mfgCode: '1189'], endpoints: ['01'],
    simple: ['01': [profileId: '0104', deviceId: '0051', inClusters: ['0000', '0006'], outClusters: ['0019']]],
    disc: ['01:0000': [[id: 0x0004, type: 0x42], [id: 0x0005, type: 0x42]],
           '01:0006': [[id: 0x0000, type: 0x10], [id: 0xF000, type: 0x21], [id: 0xFFFD, type: 0x21]]],
    vals: ['01:0000': ['0004': [status: 0, value: 'LEDVANCE'], '0005': [status: 0, value: 'PLUG']],
           '01:0006': ['0000': [status: 0, value: true], 'F000': [status: 0, value: 5]]]]
String wantReport = """Manufacturer: LEDVANCE
Model: PLUG
Node type: Router, manufacturer code 0x1189
Endpoints: 01
Endpoint 01: profile 0x0104, device 0x0051
  Client clusters: 0x0019 OTA Upgrade
  0x0000 Basic
    0x0004 ManufacturerName (string) = LEDVANCE
    0x0005 ModelIdentifier (string) = PLUG
  0x0006 On/Off
    0x0000 OnOff (bool) = true
    0xF000 (uint16) = 5
    0xFFFD ClusterRevision (uint16): not read"""
check('renderReport: one endpoint, two clusters, unknown attribute shown as hex') {
    String got = app.renderReport(ivFix, app.ZCL_ATTR_NAMES)
    if (got != wantReport) println got
    got == wantReport
}
check('renderReport: attributes in id order, whatever order the device listed them') {
    Map iv = ivFix + [disc: ivFix.disc + ['01:0006': [[id: 0xFFFD, type: 0x21], [id: 0xF000, type: 0x21], [id: 0x0000, type: 0x10]]]]
    app.renderReport(iv, app.ZCL_ATTR_NAMES) == wantReport
}
check('renderReport: failures carry their reason, refused reads their status') {
    Map iv = [failed: [node: 'timeout: No reply', 'discover:01:0006': 'refused: UNSUP_COMMAND', 'read:01:0000:0004': 'timeout: No reply'],
        endpoints: ['01'], simple: ['01': [profileId: '0104', deviceId: '0051', inClusters: ['0000', '0006', 'FC01']]],
        disc: ['01:0000': [[id: 0x0004, type: 0x42], [id: 0x0007, type: 0x30]]],
        vals: ['01:0000': ['0007': [status: 0x86, statusName: 'UNSUPPORTED_ATTRIBUTE']]]]
    String got = app.renderReport(iv, app.ZCL_ATTR_NAMES)
    List lines = got.split('\n') as List
    lines[0] == 'Manufacturer: not read' && lines[2] == 'Node type: not read (timeout: No reply)' &&
        lines.contains('    0x0004 ManufacturerName (string): not read') &&
        lines.contains('    0x0007 PowerSource (enum8): UNSUPPORTED_ATTRIBUTE') &&
        lines.contains('    read from 0x0004 failed (timeout: No reply)') &&
        lines.contains('    attributes not discovered (refused: UNSUP_COMMAND)') && lines.contains('  0xFC01')
}

check('renderReport: Electrical Measurement AC formatting attributes are named') {
    Map iv = [endpoints: ['01'], simple: ['01': [profileId: '0104', deviceId: '0051', inClusters: ['0B04']]],
              disc: ['01:0B04': [[id: 0x0400, type: 0x21], [id: 0x0401, type: 0x21]]],
              vals: ['01:0B04': ['0400': [status: 0, value: 1], '0401': [status: 0, value: 1]]]]
    String got = app.renderReport(iv, app.ZCL_ATTR_NAMES)
    got.contains('0x0400 ACFrequencyMultiplier (uint16) = 1') && got.contains('0x0401 ACFrequencyDivisor (uint16) = 1')
}
check('8032 route status 4 is Validation Underway, 5 Reserved') {
    def r = decodeZdo('8032', L('01 00 02 00 02 2B 1A 04 6F 5E 8B 7A 05 00 00'))
    r.entries[0].routeStatus == 'Validation Underway' && r.entries[1].routeStatus == 'Reserved'
}
check('value time types: 0xFF fields are unused, all unused is no value') {
    decodeZclValue(0xE0, L('0A 1E FF FF'), 0) == ['10:30:--.--', 4] && decodeZclValue(0xE0, L('FF FF FF FF'), 0) == [null, 4] &&
        decodeZclValue(0xE1, L('7D 0A FF 07'), 0) == ['2025-10---', 4] && decodeZclValue(0xE1, L('FF 0A 12 07'), 0) == ['-----10-18', 4] &&
        decodeZclValue(0xE1, L('FF FF FF FF'), 0) == [null, 4]
}
check('value data8-data64 and key128 decode; a data8 record does not end the read') {
    decodeZclValue(0x08, L('05'), 0) == ['05', 1] && decodeZclValue(0x0B, L('01 02 03 04'), 0) == ['04030201', 4] &&
        decodeZclValue(0xF1, L('00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F'), 0)[1] == 16 &&
        zcl('18 01 01 00 00 00 08 05 01 00 00 20 07').records*.value == ['05', 7]
}
println "${passed} passed, ${failed} failed"
System.exit(failed == 0 ? 0 : 1)
