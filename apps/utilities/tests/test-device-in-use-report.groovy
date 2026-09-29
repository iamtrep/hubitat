// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for the Device "in use by" Enumerator report. buildReport() takes plain maps (the
// output of the reference-source functions) and returns HTML, so it runs off-hub. This
// BRACE-EXTRACTS buildReport and esc from the shipped DeviceInUseEnumerator.groovy so the test
// stays bound to the shipped code.
//
// Run: groovy apps/utilities/tests/test-device-in-use-report.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'DeviceInUseEnumerator.groovy')
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

def harness = new GroovyShell().evaluate(
    extract(src, 'String buildReport(List<Map> devs, Map refs, Map parents, Map opts) {') + "\n" +
    extract(src, 'String groupedLinks(List<Map> apps, String base) {') + "\n" +
    extract(src, 'String esc(String s) {') + "\n[report: this.&buildReport]")

List<Map> devs = [
    [id: 1L, label: 'Hall <switch>', room: 'Hall', disabled: false],
    [id: 2L, label: 'Unused lamp', room: null, disabled: false],
    [id: 3L, label: 'Camera motion', parentDeviceId: 9L, room: 'Garage', disabled: false],
    [id: 4L, label: 'Old sensor', room: null, disabled: true],
]
Map refs = [
    1L: [[id: 10L, label: 'Night rule', type: 'Rule-5.1', disabled: false],
         [id: 11L, label: 'Morning rule', type: 'Rule-5.1', disabled: false],
         [id: 12L, label: 'All Devices', type: 'Easy Mobile Dashboard', disabled: false, dashboard: true]],
    2L: [[id: 12L, label: 'All Devices', type: 'Easy Mobile Dashboard', disabled: false, dashboard: true]],
    3L: [[id: 13L, label: 'Garage lights', type: 'Room Lighting', disabled: false]],
    4L: [[id: 14L, label: 'Retired rule', type: 'Rule-5.1', disabled: true]],
]
Map parents = ['d9': 'Camera hub']

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'Device "in use by" report'

String html = harness.report(devs, refs, parents, [loopback: false, onlyChildren: false, base: 'http://hub'])
check 'summary counts: 4 devices, 1 unused, 1 only-disabled, 1 child used',
    html.contains('4 devices &middot; 1 used by no app &middot; 1 used only by disabled apps &middot; 1 child devices used directly by apps')
check 'groups apps by type, sorted by label within a type',
    html.contains("<b>Rule-5.1</b>: <a href='http://hub/installedapp/configure/11' target='_blank'>Morning rule</a>, <a href='http://hub/installedapp/configure/10' target='_blank'>Night rule</a>")
check 'mobile dashboards listed apart, not counted',
    html.contains("<span style='opacity:.6'>Mobile dashboards: <a href='http://hub/installedapp/configure/12' target='_blank'>All Devices</a></span>") &&
    html.contains("Hall &lt;switch&gt;</a></td><td>Hall</td><td>&ndash;</td><td>2</td>")
check 'a device used only by mobile dashboards counts as unused', html.contains('1 used by no app')
check 'most-used device first', html.indexOf('Hall &lt;switch&gt;') < html.indexOf('Camera motion')
check 'escapes labels', html.contains('Hall &lt;switch&gt;') && !html.contains('Hall <switch>')
check 'parent device linked by label', html.contains("<a href='http://hub/device/edit/9' target='_blank'>Camera hub</a>")
check 'disabled app marked', html.contains('Retired rule</a> (disabled)</span>')
check 'disabled device marked', html.contains('Old sensor</a> (disabled)')
check 'no loopback banner on the platform path', !html.contains('loopback method')

String child = harness.report(devs, refs, parents, [loopback: true, reason: 'forced in settings', onlyChildren: true, base: ''])
check 'onlyChildren keeps child devices only', child.contains('Camera motion') && !child.contains('Unused lamp')
check 'loopback banner names the reason', child.contains('Using the slower loopback method (forced in settings)')

String empty = harness.report([], [:], [:], [loopback: false, onlyChildren: false])
check 'empty input renders an empty table', empty.contains('0 devices') && empty.endsWith('</table>')

println "\n${pass}/${pass + fail} passed${fail ? ", ${fail} failed" : ''}"
System.exit(fail ? 1 : 0)
