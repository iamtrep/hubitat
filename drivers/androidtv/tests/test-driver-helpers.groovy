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
    'boolean differentTv(Map entry, String tvId, String current) {',
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

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
