// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant) for the Android TV Remote driver's pure helpers:
// brace-extracts them from AndroidTVRemote.groovy and runs them under plain Groovy.
//
// Run: groovy drivers/androidtv/tests/test-driver-helpers.groovy   (a few seconds, no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(scriptFile.parentFile.parentFile, 'AndroidTVRemote.groovy')
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
    'Map tvEntry(Map r) {',
    'List<Map> tvEntries(List results) {',
    'Map entryWithId(List<Map> entries, String id) {',
    'Map entryWithAddress(List<Map> entries, String ip) {',
    'String addressFor(Map entry, String current) {',
    'String repairAddress(List<Map> entries, String id, String current) {',
    'boolean searchDue(Long last, long nowMs, long minMs) {',
    'boolean offByDrop(Long powerOffAt, long nowMs, long windowMs) {',
    'boolean sessionUp(String mode, long lastRx, long nowMs, long limitMs) {',
    'String commandRoute(String mode, boolean ready, int held) {',
    'boolean differentTv(Map entry, String tvId, String current) {',
    'Integer keyCode(String keyArg, Map<String, Integer> codes) {',
]
def h = new GroovyShell().parse(SIGNATURES.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

// Shaped like NetworkUtils.getMDNSServiceResults entries; documentation addresses, made-up MACs.
Map a = [name: "Living room", port: 6466, ip4Addresses: ["192.0.2.10"], ip6Addresses: [], textRecords: [bt: "aa:bb:cc:00:00:01"]]
Map a2 = [name: "Living room", port: 6466, ip4Addresses: ["192.0.2.10", "192.0.2.99"], textRecords: [bt: "AA:BB:CC:00:00:01"]]
Map b = [name: "Living room", port: 6466, ip4Addresses: ["192.0.2.11"], textRecords: [bt: "AA:BB:CC:00:00:02"]]
Map noBt = [name: "X", ip4Addresses: ["192.0.2.12"], textRecords: [:]]
Map noV4 = [name: "Y", ip4Addresses: [], textRecords: [bt: "AA:BB:CC:00:00:03"]]
// getMDNSEntries shape: addresses as one comma-separated String, TXT keys at the top level
Map csv = [name: "Den", ip4Addresses: "192.0.2.20, 192.0.2.21", textRecords: [bt: "AA:BB:CC:00:00:04"]]

println 'tvEntry'
check 'entry from a result', h.tvEntry(a) == [id: "AA:BB:CC:00:00:01", name: "Living room", ips: ["192.0.2.10"], label: "Living room (192.0.2.10)"]
check 'no bt: ignored', h.tvEntry(noBt) == null
check 'no IPv4: ignored', h.tvEntry(noV4) == null
check 'comma-separated addresses', h.tvEntry(csv).ips == ["192.0.2.20", "192.0.2.21"]

println 'tvEntries'
List e = h.tvEntries([a, a2, b, noBt, noV4])
check 'one entry per TV, bt case ignored', e*.id == ["AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02"]
check 'addresses of duplicates merged', e[0].ips == ["192.0.2.10", "192.0.2.99"]
check 'same names told apart by IP', e*.label == ["Living room (192.0.2.10)", "Living room (192.0.2.11)"]
check 'empty and null', h.tvEntries([]) == [] && h.tvEntries(null) == []

println 'lookups'
check 'by id, any case', h.entryWithId(e, "aa:bb:cc:00:00:02")?.ips == ["192.0.2.11"]
check 'by id, unknown', h.entryWithId(e, "AA:BB:CC:00:00:09") == null
check 'by address, not the first', h.entryWithAddress(e, "192.0.2.99")?.id == "AA:BB:CC:00:00:01"
check 'by address, unknown', h.entryWithAddress(e, "192.0.2.50") == null
Map shared = [id: "AA:BB:CC:00:00:05", name: "Other", ips: ["192.0.2.99"], label: "Other (192.0.2.99)"]
check 'by address, advertised by two TVs: no answer', h.entryWithAddress(e + [shared], "192.0.2.99") == null

println 'addresses'
check 'current kept when advertised', h.addressFor(e[0], "192.0.2.99") == "192.0.2.99"
check 'first otherwise', h.addressFor(e[0], "192.0.2.50") == "192.0.2.10"
check 'no repair while current is advertised', h.repairAddress(e, "AA:BB:CC:00:00:01", "192.0.2.99") == null
check 'repair to the advertised address', h.repairAddress(e, "AA:BB:CC:00:00:01", "192.0.2.50") == "192.0.2.10"
check 'no repair for a TV not found', h.repairAddress(e, "AA:BB:CC:00:00:09", "192.0.2.50") == null
check 'repair never takes another TV', h.repairAddress([e[1]], "AA:BB:CC:00:00:01", "192.0.2.50") == null

println 'differentTv'
check 'known id, same TV', !h.differentTv(e[0], "aa:bb:cc:00:00:01", "192.0.2.50")
check 'known id, other TV', h.differentTv(e[1], "AA:BB:CC:00:00:01", "192.0.2.10")
check 'no id, address advertised by it', !h.differentTv(e[0], null, "192.0.2.99")
check 'no id, address not advertised by it', h.differentTv(e[1], null, "192.0.2.10")
check 'no id, no address: unknown, not different', !h.differentTv(e[0], null, null)

println 'searchDue'
check 'never searched', h.searchDue(null, 1000L, 300000L)
check 'too soon', !h.searchDue(1000L, 2000L, 300000L)
check 'due', h.searchDue(1000L, 301000L, 300000L)

println 'offByDrop'
check 'no power key', !h.offByDrop(null, 5000L, 10000L)
check 'drop 1 s after power off', h.offByDrop(4000L, 5000L, 10000L)
check 'drop long after power off', !h.offByDrop(4000L, 20000L, 10000L)

println 'sessionUp'
check 'open and recent: up', h.sessionUp('remote', 1000L, 20000L, 20000L)
check 'quiet past the limit: not up', !h.sessionUp('remote', 1000L, 21001L, 20000L)
check 'closed: not up', !h.sessionUp(null, 1000L, 1500L, 20000L)
check 'pairing: not up', !h.sessionUp('pair', 1000L, 1500L, 20000L)

println 'commandRoute'
check 'ready: send', h.commandRoute('remote', true, 0) == 'send'
check 'starting: hold', h.commandRoute('remote', false, 19) == 'hold'
check 'starting, 20 held: full', h.commandRoute('remote', false, 20) == 'full'
check 'closed: drop', h.commandRoute(null, true, 0) == 'drop'
check 'pairing: drop', h.commandRoute('pair', false, 0) == 'drop'

println 'keyCode'
Map<String, Integer> codes = [HOME: 3, "0": 7, "7": 14, LAST_CHANNEL: 229]
check 'name', h.keyCode('HOME', codes) == 3
check 'name, any case and spaces', h.keyCode(' home ', codes) == 3
check 'KEYCODE_ name', h.keyCode('KEYCODE_HOME', codes) == 3
check 'bare number is a code', h.keyCode('7', codes) == 7
check 'KEYCODE_ digit is the digit key', h.keyCode('KEYCODE_7', codes) == 14
check 'KEYCODE_0 is the digit key', h.keyCode('KEYCODE_0', codes) == 7
check 'KEYCODE_ number with no name is a code', h.keyCode('KEYCODE_229', codes) == 229
check 'name outside the old table', h.keyCode('last_channel', codes) == 229
check 'unknown name', h.keyCode('NUM0', codes) == null
check 'empty', h.keyCode('', codes) == null && h.keyCode(null, codes) == null

println 'KEYCODES'
String mapText = (text =~ /(?s)Map<String, Integer> KEYCODES = (\[.*?\n\])/)[0][1]
Map keyMap = new GroovyShell().evaluate(mapText) as Map
check 'every AOSP key but UNKNOWN', keyMap.size() == 337 && !keyMap.containsKey('UNKNOWN')
check 'digit names are strings', keyMap['0'] == 7 && keyMap['9'] == 16
check 'old table unchanged', [HOME: 3, BACK: 4, POWER: 26, MEDIA_PLAY: 126, VOLUME_MUTE: 164, TV_INPUT: 178, WAKEUP: 224].every { k, v -> keyMap[k] == v }
check 'requested keys', keyMap.LAST_CHANNEL == 229 && keyMap.TV_INPUT_HDMI_1 == 243

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
