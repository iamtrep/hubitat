// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the Z-Wave topology parser.
//
// parseZwaveTopology turns the hub's /hub/zwaveTopology <table> into [ids, rows]. The fixtures
// copy the page's markup: single-quoted bgcolor, no </tr> on body rows, and on a hub with no
// nodes a self-only table with no closing </table>. BRACE-EXTRACTS the method from the shipped
// HubInspector.groovy, so no hub is needed.
//
// Run: groovy apps/HubInspector/tests/test-zwave-topology.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'HubInspector.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String extract(String src, String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = src.indexOf('{', start)
    int depth = 0
    for (int i = open; i < src.length(); i++) {
        if (src[i] == '{') depth++
        else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

def parse = new GroovyShell().evaluate(
    extract(src, 'Map parseZwaveTopology(String txt) {') + "\nthis.&parseZwaveTopology")

String cell(String c) { "<td bgcolor='${c}'>&nbsp;</td>" }
String table(List<String> ids, List<List<String>> colours) {
    StringBuilder sb = new StringBuilder("<table>\n<tr><td>&nbsp;</td>")
    ids.each { sb << "<td>${it}</td>" }
    sb << "</tr>\n\n"
    ids.eachWithIndex { String id, int i ->
        sb << "<tr><td>${id}</td>" << colours[i].collect { cell(it) }.join('') << "\n"
    }
    sb << "</tr>\n"
    return sb.toString()
}

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'parseZwaveTopology (Z-Wave neighbour matrix)'

// Hub 01 and three nodes; 0C and 0D only hear each other one way (0D lists 0C).
String three = table(['01', '0C', '0D', '0E'], [
    ['white', 'blue', 'blue', 'blue'],
    ['blue', 'white', 'red', 'blue'],
    ['blue', 'blue', 'white', 'red'],
    ['blue', 'blue', 'red', 'white'],
])
Map t = parse(three)
check('ids are decimal node ids', t?.ids == [1, 12, 13, 14])
check('one row per node', t?.rows?.size() == 4)
check('row is the node\'s own list', t?.rows?.get(1) == '1-01' && t?.rows?.get(2) == '11-0')
check('diagonal is -', (0..3).every { t.rows[it][it] == '-' })

// No Z-Wave nodes: the hub returns a self-only matrix and leaves the table unclosed.
Map lone = parse("<table>\n<tr><td>&nbsp;</td><td>01</td></tr>\n\n<tr><td>01</td><td bgcolor='white'>&nbsp;</td></tr>\n\n")
check('self-only matrix parses', lone?.ids == [1] && lone?.rows == ['-'])

check('unknown colour is ?', parse(table(['01', '0C'], [['white', 'green'], ['blue', 'white']]))?.rows?.get(0) == '-?')
check('ragged row is rejected', parse(table(['01', '0C'], [['white'], ['blue', 'white']])) == null)
check('null text is null', parse(null) == null)
check('non-table text is null', parse('<html>Login</html>') == null)

println "\n${pass} passed, ${fail} failed"
if (fail) System.exit(1)
