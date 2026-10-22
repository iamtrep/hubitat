// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction unit test for the Zigbee Workbench shareable report: the allowlisted report data,
// masking, the forum size limit, file names and retention, and the report run's extra steps.
// Brace-extracts the codec and engine regions from the shipped ZigbeeWorkbench.groovy; no hub.
//
// Run: groovy apps/ZigbeeWorkbench/tests/test-report.groovy

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

def app = new GroovyShell().evaluate("""
import groovy.transform.CompileStatic
import groovy.transform.Field
${region(src, 'Codec')}
${region(src, 'Engine')}
this
""")

int passed = 0
int failed = 0
def check = { String name, Closure c ->
    boolean ok
    String why = ''
    try { ok = c() } catch (Throwable t) { ok = false; why = " (${t.class.simpleName}: ${t.message})" }
    if (ok) { passed++; println "[PASS] ${name}" } else { failed++; println "[FAIL] ${name}${why}" }
}

// Made-up identities: never a real device's.
String IEEE = '00124B0001ABCDEF'
String NB_IEEE = '00124B0002FEDCBA'
String EXT_PAN = 'A1B2C3D4E5F60718'
String LABEL = 'Kitchen Plug'
String NB_LABEL = 'Hall Light'
long NOW = 1_800_000_000_000L

Map iv = [node: [logicalType: 'Router', mfgCode: '1233'], endpoints: ['01'],
          simple: ['01': [profileId: '0104', deviceId: '0051', inClusters: ['0000', '0006'], outClusters: ['0019']]],
          disc: ['01:0000': [[id: 0x0004, type: 0x42], [id: 0x0005, type: 0x42], [id: 0x0007, type: 0x30]],
                 '01:0006': [[id: 0x0000, type: 0x10]]],
          vals: ['01:0000': ['0004': [status: 0, value: 'Acme'], '0005': [status: 0, value: 'PLUG-1'], '0007': [status: 0, value: 1]],
                 '01:0006': ['0000': [status: 0, value: true]]],
          rep: ['01:0006': ['0000': [status: 0, min: 0, max: 600, change: null]]]]
// A neighbour reply carries the extended PAN ID and IEEE addresses little-endian.
def le = { String h -> (0..7).collect { h.substring(2 * it, 2 * it + 2) }.reverse() }
String rawReply = (['00', '00', '01', '00', '01'] + le(EXT_PAN) + le(NB_IEEE)).join(' ')
Map results = [neighbors: [time: NOW - 1000, status: 'ok', lqi: 200, rssi: -60,
                           data: [total: 1, entries: [[nwk: '1A2B', ieee: NB_IEEE, extPan: EXT_PAN, relationship: 'Parent', deviceType: 'Router', lqi: 180]]]],
               bindings: [time: NOW - 1000, status: 'error', error: 'Could not decode the reply', frame: "he raw 0x58D3 0 0 0x0033 {00 00}", raw: le(IEEE) + ['00']],
               routes: [time: NOW - 1000, status: 'refused', error: 'The device answered NOT_SUPPORTED.', frame: 'he raw 0x58D3 0 0 0x0032 {00 00}', raw: rawReply.split(' ') as List]]
Map inp = [ieee: IEEE, nwk: '58D3', name: LABEL, iv: iv, results: results, record: [lastMessage: '2027-01-15T08:00:00Z', messageCount: 42],
           names: [byIeee: [(IEEE): LABEL, (NB_IEEE): NB_LABEL], byNwk: ['58D3': LABEL, '1A2B': NB_LABEL, '0000': 'Hub']],
           driver: [name: 'Generic Zigbee Outlet', namespace: 'hubitat', data: [firmwareMT: '1233-D3A3-00001234', version: '1.2', secret: 'x']],
           hub: [model: 'C-8', firmware: '2.5.2.139', channel: 20, powerLevel: 8, extra: 'x'],
           started: NOW - 5000, version: '0.1.0', now: NOW, time: '2027-01-15T08:00:00-05:00']
Map rd = app.reportData(inp)
Map aliases = app.makeAliases([LABEL, NB_LABEL, 'Some App'])
Map on = [aliases: true, aliasMap: aliases, extPans: [EXT_PAN]]
Map off = [aliases: false, aliasMap: aliases, extPans: [EXT_PAN]]

check('reportData: only the allowlisted keys, at each level') {
    rd.keySet() == ['device', 'driver', 'endpoints', 'attributes', 'bindings', 'mesh', 'hub', 'run', 'failures'] as Set &&
        rd.device.keySet() == ['name', 'ieee', 'nwk', 'manufacturer', 'model', 'applicationVersion', 'stackVersion', 'firmwareMT', 'powerSource',
                               'logicalType', 'manufacturerCode'] as Set &&
        rd.driver.keySet() == ['name', 'namespace', 'version'] as Set &&
        rd.hub.keySet() == ['model', 'firmware', 'channel', 'powerLevel'] as Set &&
        rd.run.keySet() == ['appVersion', 'time'] as Set &&
        rd.mesh.keySet() == ['parent', 'neighbors', 'routes', 'lastHopLqi', 'lastHopRssi', 'messageCount', 'minutesSinceMessage'] as Set &&
        rd.mesh.neighbors[0].keySet() == ['name', 'nwk', 'ieee', 'relationship', 'deviceType', 'lqi'] as Set &&
        rd.endpoints[0].keySet() == ['ep', 'profileId', 'deviceId', 'inClusters', 'outClusters'] as Set &&
        rd.attributes[0].keySet() == ['ep', 'cluster', 'name', 'attrs'] as Set &&
        rd.attributes[0].attrs[0].keySet() == ['id', 'name', 'type', 'value', 'status', 'min', 'max', 'change'] as Set &&
        rd.failures[0].keySet() == ['kind', 'status', 'error', 'frame', 'raw'] as Set
}
check('reportData: device fields from the interview, driver data values, mesh from the tables') {
    rd.device.manufacturer == 'Acme' && rd.device.model == 'PLUG-1' && rd.device.powerSource == 'Mains (single phase)' &&
        rd.device.firmwareMT == '1233-D3A3-00001234' && rd.driver.version == '1.2' && rd.mesh.parent == NB_LABEL &&
        rd.mesh.lastHopLqi == 200 && rd.mesh.messageCount == 42 && rd.mesh.routes == null && rd.bindings == null &&
        rd.attributes.find { it.cluster == '0006' }.attrs[0].max == 600
}
check('reportData: failures are the run\'s non-ok results only') {
    Map old = inp + [results: results + [power: [time: NOW - 99999, status: 'timeout', error: 'No reply']]]
    app.reportData(old).failures*.kind.sort() == ['bindings', 'routes']
}

String forumOn = app.renderForum(rd, on)
String forumOff = app.renderForum(rd, off)
check('masking: no IEEE, extended PAN ID or device name with aliases on; masked IEEE shown') {
    [IEEE, NB_IEEE, EXT_PAN, LABEL, NB_LABEL].every { !forumOn.contains(it) } &&
        forumOn.contains('00124BXXXXXXXXXX') && forumOn.contains(aliases[LABEL]) && forumOn.contains(aliases[NB_LABEL])
}
check('masking: IEEE and extended PAN ID bytes in a raw reply, either byte order') {
    [le(EXT_PAN).join(' '), le(NB_IEEE).join(' '), le(IEEE).join(' ')].every { !forumOn.contains(it) } &&
        forumOn.contains('XX XX XX XX XX 4B 12 00')
}
check('aliases off: the names appear, the IEEE stays masked') {
    forumOff.contains(LABEL) && forumOff.contains(NB_LABEL) && !forumOff.contains(IEEE)
}
check('a name missing from the alias list is never shown') {
    Map rd2 = app.reportData(inp + [names: [byIeee: [(IEEE): LABEL, (NB_IEEE): 'Not Registered'], byNwk: [:]]])
    String f = app.renderForum(rd2, on)
    !f.contains('Not Registered') && f.contains('unlisted device')
}
check('frame text keeps its case while masking') {
    app.maskHex('he raw 0x58D3 0 0 0x0034 {00 00 ' + le(IEEE).join(' ') + ' 00}', [IEEE], []) ==
        'he raw 0x58D3 0 0 0x0034 {00 00 XX XX XX XX XX 4B 12 00 00}'
}

// 400 attributes: the forum version fits a post, the full one keeps every attribute.
Map big = iv + [disc: ['01:0006': (0..<400).collect { [id: it, type: 0x42] }],
                vals: ['01:0006': (0..<400).collectEntries { [(String.format('%04X', it)): [status: 0, value: 'v' * 60]] }]]
Map rdBig = app.reportData(inp + [iv: big])
String forumBig = app.renderForum(rdBig, on)
String fullBig = app.renderFull(rdBig, on)
check('400 attributes: forum version under 30000 characters, says what it cut') {
    forumBig.length() <= 30000 && forumBig.contains('Full attribute list in the attached report.')
}
check('400 attributes: full version keeps all 400 values') {
    (0..<400).every { fullBig =~ /\| 0x${String.format('%04X', it)}( \w+)? \| string \| ${'v' * 60} \|/ }
}

check('file prefix: model characters outside A-Z a-z 0-9 _ - become _') {
    app.reportFilePrefix('3RSP019BZ/v2') == 'zigbee-workbench-3RSP019BZ_v2-' && app.reportFilePrefix(null) == 'zigbee-workbench-unknown-'
}
check('retention: of 5 reports for a model, the 2 oldest are deleted; other files untouched') {
    String p = 'zigbee-workbench-PLUG-1-'
    List files = ['20270101-0800', '20270103-0800', '20270102-0800', '20270105-0800', '20270104-0800'].collect { [name: "${p}${it}.md"] } +
        [[name: 'zigbee-workbench-OTHER-20260101-0000.md'], [name: "${p}notes.txt"]]
    app.reportsToDelete(files, p, 3) == ["${p}20270101-0800.md", "${p}20270102-0800.md"]
}
check('report steps: tables not fresh, routes only for non-end devices, reporting config per cluster') {
    List keys = app.reportSteps([node: [logicalType: 'Router'], fresh: ['bindings'],
                                 disc: ['01:0000': [[id: 4, type: 0x42]], '01:0006': (0..5).collect { [id: it, type: 0x10] } + [[id: 0xFFFD, type: 0x21]]]]).collect { it.key }
    List endDev = app.reportSteps([node: [logicalType: 'End Device'], disc: [:]]).collect { it.key }
    keys == ['neighbors', 'routes', 'reportcfg:01:0006:0000', 'reportcfg:01:0006:0004'] && endDev == ['neighbors', 'bindings']
}

check('failures: an interview step that failed earlier is reported, with its frame when the result has it') {
    Map ivF = iv + [failed: ['discover:01:0003': 'timeout: No reply within 10 seconds.', 'read:01:0006:4000': 'refused: The device answered UNSUP_COMMAND.']]
    Map resF = results + ['read:01:0006': [time: NOW - 99999, status: 'refused', error: 'The device answered UNSUP_COMMAND.', frame: 'he raw 0x58D3 1 0x01 0x0006 {00 00 00 00 40}']]
    List f = app.reportData(inp + [iv: ivF, results: resF]).failures
    Map d = f.find { it.kind == 'discover:01:0003' }
    Map r = f.find { it.kind == 'read:01:0006' }
    d != null && d.status == 'timeout' && d.error == 'No reply within 10 seconds.' && d.frame == null &&
        r != null && r.frame.contains('0x0006') && f.count { it.kind == 'read:01:0006' } == 1
}
check('retention: a model whose name goes on after a dash is another model') {
    String p = 'zigbee-workbench-SNZB-'
    List files = ['20261001-1200', '20261002-1200', '20261003-1200', '20261004-1200'].collect { [name: "${p}${it}.md"] } +
        [[name: 'zigbee-workbench-SNZB-02-20261001-1200.md']]
    app.reportsToDelete(files, p, 3) == ["${p}20261001-1200.md"]
}
println "${passed} passed, ${failed} failed"
System.exit(failed == 0 ? 0 : 1)
