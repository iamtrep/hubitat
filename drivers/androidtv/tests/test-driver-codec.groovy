// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant) for the Android TV Remote driver's protobuf and DER codec,
// frame reassembly (parse) and pairing hash. Brace-extracts the shipped functions from AndroidTVRemote.groovy
// and runs them under plain Groovy; nothing is copied.
//
// Run: groovy drivers/androidtv/tests/test-driver-codec.groovy   (a few seconds, no hub)

import java.security.MessageDigest

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile.parentFile, 'AndroidTVRemote.groovy')
// ANDROIDTV_SOURCE lets a mutation check point the test at a modified copy.
String override = System.getenv('ANDROIDTV_SOURCE')
if (override) src = new File(override)
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

List<String> SIGNATURES = [
    'private String pvarint(long v) {',
    'private String pKey(int field, int wireType) {',
    'private String pInt(int field, long v) {',
    'private String pBytes(int field, String hex) {',
    'private String pStr(int field, String s) {',
    'private String pMsg(int field, String bodyHex) {',
    'private List readVarint(String hex, int i) {',
    'private Map<Integer, List> pDecode(String hex) {',
    'private def pFirst(Map<Integer, List> m, int field) {',
    'private String pDump(String hex, int depth = 0) {',
    'private boolean looksNested(String hex, int depth) {',
    'private Map<Integer, List> pDecodeStrict(String hex) {',
    'private String printable(String hex) {',
    'private String hexOf(byte[] b) {',
    'private String utf8(String hex) {',
    'private String evenHex(String h) {',
    'private String certificateFromHandshake(String hex) {',
    'private List derLen(int n) {',
    'private List derTlv(int tag, List body) {',
    'private List derOid(String dotted) {',
    'private byte[] pairingSecret(List<BigInteger> numbers, String code) {',
    'void parse(String hex) {',
]
// hubitat.helper.HexUtils is a platform class; this stand-in has the same one method the driver calls.
String PRELUDE = '''import java.security.MessageDigest
class HexUtils {
    static byte[] hexStringToByteArray(String s) {
        byte[] out = new byte[s.length() / 2 as int]
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16)
        return out
    }
}
'''
Binding bind = new Binding()
Map env = [mode: 'remote', msgs: [], pairMsgs: []]
Map runtime = [:]
bind.setVariable('rt', { -> runtime })
bind.setVariable('now', { -> 1000L })
bind.setVariable('traceEnable', false)
bind.setVariable('logTrace', { String s -> })
bind.setVariable('handleRemoteMessage', { String m -> env.msgs << m })
bind.setVariable('handlePairingMessage', { String m -> env.pairMsgs << m })
def h = new GroovyShell(bind).parse(PRELUDE + SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'varint'
Map<Long, String> known = [0L: '00', 1L: '01', 127L: '7F', 128L: '8001', 300L: 'AC02', 16384L: '808001', 2147483647L: 'FFFFFFFF07']
known.each { Long v, String enc -> check "pvarint ${v}", h.pvarint(v) == enc }
check 'pvarint of a negative is the 10-byte two\'s complement form', h.pvarint(-1L) == 'FFFFFFFFFFFFFFFFFF01'
known.each { Long v, String enc -> check "readVarint ${v}", h.readVarint(enc + 'FF', 0) == [v, enc.length()] }
check 'readVarint resumes at an index', h.readVarint('AAAC02', 2) == [300L, 6]
check 'incomplete varint: null', h.readVarint('AC', 0) == null && h.readVarint('', 0) == null
[0L, 1L, 127L, 128L, 255L, 16383L, 16384L, 1234567890123L, Long.MAX_VALUE].each { Long v ->
    check "round trip ${v}", h.readVarint(h.pvarint(v), 0)[0] == v
}

println 'pDecode'
String msg = h.pInt(1, 7) + h.pStr(2, 'hello') + h.pMsg(3, h.pInt(1, 300) + h.pStr(2, 'x')) + h.pInt(1, 9)
Map d = h.pDecode(msg)
check 'varints and repeats collected per field', d[1] == [7L, 9L]
check 'string field is hex of its bytes', h.utf8(h.pFirst(d, 2) as String) == 'hello'
Map inner = h.pDecode(h.pFirst(d, 3) as String)
check 'nested message decodes', inner[1] == [300L] && h.utf8(h.pFirst(inner, 2) as String) == 'x'
check 'empty and null input', h.pDecode('') == [:] && h.pDecode(null) == [:]
check 'fixed32 and fixed64 values keep their bytes', h.pDecode(h.pKey(4, 5) + '01020304' + h.pKey(5, 1) + '0102030405060708') == [4: ['01020304'], 5: ['0102030405060708']]
check 'truncated field stops without throwing', h.pDecode(h.pInt(1, 5) + h.pKey(2, 0) + '80') == [1: [5L]]
check 'length past the end is clipped', h.pDecode(h.pKey(2, 2) + '05' + 'AABB') == [2: ['AABB']]
byte[] bin = [0, 1, (byte) 0xFE] as byte[]
check 'hexOf', h.hexOf(bin) == '0001FE'

println 'pDecodeStrict / pDump'
check 'strict: well-formed', h.pDecodeStrict(msg) == d
check 'strict: truncated is null', h.pDecodeStrict(h.pKey(2, 2) + '05' + 'AABB') == null && h.pDecodeStrict(h.pInt(1, 5) + '80') == null
check 'strict: odd length and field 0 are null', h.pDecodeStrict('123') == null && h.pDecodeStrict('0001') == null
check 'dump: scalars', h.pDump(h.pInt(1, 7) + h.pInt(2, 300)) == '1:7 2:300'
check 'dump: printable string quoted', h.pDump(h.pStr(2, 'hello')) == '2:"hello"'
check 'dump: nested message in braces', h.pDump(h.pMsg(3, h.pInt(1, 5) + h.pStr(2, 'ab'))) == '3:{1:5 2:"ab"}'
check 'dump: not a protobuf prints hex', h.pDump('FF') == '0xFF'
check 'dump: binary field as hex', h.pDump(h.pBytes(1, 'FFFEFD')) == '1:0xFFFEFD'
check 'dump: long binary is cut at 64 chars with its size', h.pDump(h.pBytes(1, 'FF' * 40)).contains('(40 bytes)')
// The case the driver comment names: tag and length bytes of a nested message are both printable ("b$").
String pkg = 'com.example.app.package.name.of.36chars!'.take(36)
check 'dump: nested message whose tag and length are printable', h.pDump(h.pMsg(2, h.pStr(12, pkg))) == "2:{12:\"${pkg}\"}"
check 'looksNested: text only is not nested', !h.looksNested(h.hexOf('hello'.bytes), 0)
check 'looksNested: a message of strings is', h.looksNested(h.pStr(1, 'ab') + h.pStr(2, 'cd'), 0)

println 'DER'
check 'derLen short and long forms', h.derLen(5) == [5] && h.derLen(127) == [127] && h.derLen(128) == [0x81, 128] && h.derLen(256) == [0x82, 1, 0] && h.derLen(65535) == [0x82, 255, 255]
check 'derOid sha256WithRSA', h.derOid('1.2.840.113549.1.1.11') == [0x06, 9, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B]
check 'derOid commonName', h.derOid('2.5.4.3') == [0x06, 3, 0x55, 0x04, 0x03]
check 'derTlv wraps with tag and length', h.derTlv(0x04, [1, 2, 3]) == [4, 3, 1, 2, 3]

println 'certificateFromHandshake'
String certDer = 'AB' * 300
String certMsg = '0B' + String.format('%06X', 3 + 3 + 300) + String.format('%06X', 3 + 300) + String.format('%06X', 300) + certDer
String serverHello = '02' + String.format('%06X', 4) + '03030000'
String hsAll = serverHello + certMsg
String rec1 = '160303' + String.format('%04X', 100) + hsAll.take(200)
String rec2 = '160303' + String.format('%04X', (hsAll.length() - 200) / 2 as int) + hsAll.substring(200)
check 'certificate across two records', h.certificateFromHandshake(rec1 + rec2) == certDer
check 'second record missing: null', h.certificateFromHandshake(rec1) == null
check 'one record', h.certificateFromHandshake('160303' + String.format('%04X', hsAll.length() / 2 as int) + hsAll) == certDer
check 'split at every offset gives null or the certificate, never an error', (0..(rec1 + rec2).length() - 1).every { int n ->
    String got = h.certificateFromHandshake((rec1 + rec2).take(n))
    got == null || got == certDer
}
check 'no certificate message: null', h.certificateFromHandshake('160303' + String.format('%04X', serverHello.length() / 2 as int) + serverHello) == null

println 'pairing secret'
// Independent value from a separate implementation (Python hashlib): odd-length hex gets a leading zero.
byte[] sec = h.pairingSecret([new BigInteger('C0FFEE123', 16), 65537G, new BigInteger('ABCDEF0102030405060708', 16), 3G], '5F3C5D')
check 'known answer', h.hexOf(sec) == '5F4D3E37801622747D3948864DBEA73AF5A71608B4DB9A8A485E84103A1CA620'
check 'digest byte 0 is the first two characters of the code', (sec[0] & 0xFF) == 0x5F
check 'only the last four code characters are hashed', h.hexOf(h.pairingSecret([1G, 2G, 3G, 4G], '003C5D')) == h.hexOf(h.pairingSecret([1G, 2G, 3G, 4G], 'FF3C5D'))
check 'the 65537 exponent is hashed as 010001', h.hexOf(h.pairingSecret([65537G], '000000')) == MessageDigest.getInstance('SHA-256').digest([1, 0, 1, 0, 0] as byte[]).encodeHex().toString().toUpperCase()
check 'evenHex', h.evenHex('1') == '01' && h.evenHex('10') == '10' && h.evenHex('10001') == '010001'

println 'parse: frame reassembly'
String m1 = h.pInt(1, 7) + h.pStr(2, 'a' * 200)       // 200+ bytes: the length takes two varint bytes
String m2 = h.pInt(1, 9)
String m3 = h.pMsg(3, h.pInt(1, 5))
String f1 = h.pvarint(m1.length() / 2 as long) + m1
String f2 = h.pvarint(m2.length() / 2 as long) + m2
String f3 = h.pvarint(m3.length() / 2 as long) + m3
check 'the long message has a two-byte length', f1.startsWith(h.pvarint(m1.length() / 2 as long)) && h.pvarint(m1.length() / 2 as long).length() == 4
def feed = { String mode, List<String> chunks ->
    runtime.clear(); runtime.putAll([mode: mode, buf: ''])
    env.msgs = []; env.pairMsgs = []
    chunks.each { h.parse(it) }
}
feed('remote', [f1 + f2 + f3])
check 'three frames in one chunk', env.msgs == [m1, m2, m3] && runtime.buf == ''
feed('remote', [f1, f2, f3])
check 'one frame per chunk', env.msgs == [m1, m2, m3]
String all = f1 + f2 + f3
boolean everySplit = true
for (int n = 0; n <= all.length(); n += 2) {
    feed('remote', [all.take(n), all.substring(n)].findAll { it })
    if (env.msgs != [m1, m2, m3] || runtime.buf != '') { everySplit = false; println "    split at byte ${n / 2 as int} gave ${env.msgs.size()} messages" }
}
check 'three frames split at every byte offset', everySplit
feed('remote', all.toList().collate(2)*.join())
check 'three frames one byte at a time', env.msgs == [m1, m2, m3]
feed('remote', [f1.take(f1.length() - 2)])
check 'incomplete frame is held, not delivered', env.msgs == [] && runtime.buf == f1.take(f1.length() - 2)
h.parse(f1.substring(f1.length() - 2))
check 'the held frame completes on the next chunk', env.msgs == [m1] && runtime.buf == ''
feed('remote', [f1.take(2)])
check 'half a two-byte length is held', env.msgs == [] && runtime.buf == f1.take(2)
feed('pair', [f2 + f3])
check 'pair mode routes to the pairing handler', env.pairMsgs == [m2, m3] && env.msgs == []
feed('remote', [h.pvarint(0) + f2])
check 'a zero-length frame is delivered empty, then the next frame', env.msgs == ['', m2]

println 'trace lines the tester waits on (driver dump text against the tester regexes)'
File testerFile = new File(scriptFile.parentFile, 'AndroidTVRemoteTester.groovy')
String testerText = testerFile.text
List<String> waits = (testerText =~ /wait: ('[^']*')/).collect { List m -> new GroovyShell().evaluate(m[1] as String) as String }.toSet().toList()
// What the driver logs for the messages those patterns describe: "tx " + dump for a key, "rx " + dump for a reply.
Map<String, String> samples = [
    'tx 10:{1:3 ': 'tx ' + h.pDump(h.pMsg(10, h.pInt(1, 3) + h.pInt(2, 3))),
    'tx 10:{1:127 ': 'tx ' + h.pDump(h.pMsg(10, h.pInt(1, 127) + h.pInt(2, 3))),
    'tx 10:{1:126 ': 'tx ' + h.pDump(h.pMsg(10, h.pInt(1, 126) + h.pInt(2, 3))),
    'tx 10:{1:86 ': 'tx ' + h.pDump(h.pMsg(10, h.pInt(1, 86) + h.pInt(2, 3))),
    'rx 40:{1:1}': 'rx ' + h.pDump(h.pMsg(40, h.pInt(1, 1))),
    'rx 40:{1:0}': 'rx ' + h.pDump(h.pMsg(40, h.pInt(1, 0))),
]
check 'driver dump of a key press and a power message has the shape the tester waits on', samples.every { String want, String got ->
    String lit = want.replace('{', '{')
    boolean wanted = waits.any { String re -> got =~ re }
    if (!wanted) println "    no tester wait pattern matches: ${got}"
    wanted
}
check 'every tx and rx 40 wait pattern matches some driver dump above', waits.findAll { it.startsWith('^tx 10:') || it.startsWith('^rx 40:') }.every { String re -> samples.values().any { it =~ re } }

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
