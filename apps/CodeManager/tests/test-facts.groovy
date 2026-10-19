// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for per-item fact bases, origin classification and parent links in CodeManager.groovy (DESIGN.md 3.1, 3.2).
// Run: groovy apps/CodeManager/tests/test-facts.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../CodeManager.groovy').text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^(?:@\w+\s+)?static\s+[^\n(]*\b${name}\(/
    assert m.find() : "method not found: ${name}"
    int start = m.start()
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
java.util.regex.Matcher cm = src =~ /(?m)^@Field (static final long PROVISIONAL_MS = [\d_]+L)/
assert cm.find() : 'PROVISIONAL_MS not found'
String constant = cm.group(1)
String code = ['factBasis', 'classifyOrigin', 'ckey', 'childOfIndex', 'walkAppTree', 'docPruneDecision', 'factVisible'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nclass H {\n${constant}\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// --- factBasis ---
t('read at the current lastModified is valid', { assert H.factBasis([flm: 1000L, frd: 9000L], 1000L) == 'valid' })
t('read within 2 s of lastModified is provisional', { assert H.factBasis([flm: 1000L, frd: 2500L], 1000L) == 'provisional' })
t('read exactly 2 s after lastModified is valid', { assert H.factBasis([flm: 1000L, frd: 3000L], 1000L) == 'valid' })
t('item changed since the read is stale', { assert H.factBasis([flm: 1000L, frd: 9000L], 5000L) == 'stale' })
t('never read is unread', { assert H.factBasis([:], 5000L) == 'unread' })
t('Integer-typed stored values compare like Long', { assert H.factBasis([flm: 1000, frd: 9000], 1000L) == 'valid' })

// --- classifyOrigin ---
t('importUrl with valid facts is url', { assert H.classifyOrigin([loc: 'https://x/a.groovy'], false, false, 'valid') == 'url' })
t('importUrl with stale facts is unread, not url', { assert H.classifyOrigin([loc: 'https://x/a.groovy'], false, false, 'stale') == 'unread' })
t('never read is unread', { assert H.classifyOrigin([:], false, false, 'unread') == 'unread' })
t('hpm wins regardless of basis', { assert H.classifyOrigin([:], true, false, 'unread') == 'hpm' && H.classifyOrigin([loc: 'u'], true, true, 'stale') == 'hpm' })
t('bundle wins over facts', { assert H.classifyOrigin([:], false, true, 'stale') == 'bundle' })
t('read and no importUrl is local', { assert H.classifyOrigin([:], false, false, 'valid') == 'local' })
t('provisional facts are usable', { assert H.classifyOrigin([loc: 'u'], false, false, 'provisional') == 'url' })

// --- childOfIndex ---
List tree = [
    [data: [id: 10, appTypeId: 500, name: 'Parent A'], children: [
        [data: [id: 11, appTypeId: 501, name: 'Child 1'], children: []],
        [data: [id: 12, appTypeId: 501, name: 'Child 2'], children: []]]],
    [data: [id: 20, appTypeId: 500, name: 'Parent B'], children: [
        [data: [id: 21, appTypeId: 501, name: 'Child 3'], children: []]]],
    [data: [id: 30, appTypeId: 502, name: 'Lone'], children: []]]
List devs = [
    [id: 1, name: 'Hub device', deviceTypeId: 70, parentDeviceId: null],
    [id: 2, name: 'Child dev A', deviceTypeId: 71, parentDeviceId: 1],
    [id: 3, name: 'Child dev B', deviceTypeId: 71, parentDeviceId: 1],
    [id: 4, name: 'App made', deviceTypeId: 72, parentDeviceId: null]]
Map idx = H.childOfIndex(tree, devs)
t('a child app type lists each parent instance once', { assert idx['app:501'].collect { it.id }.sort() == [10, 20] && idx['app:501'].every { it.kind == 'app' } })
t('parent instance carries its label', { assert idx['app:501'].find { it.id == 10 }.name == 'Parent A' })
t('top-level app types have no parent', { assert idx['app:500'] == null && idx['app:502'] == null })
t('a child driver type lists its parent device once', { assert idx['driver:71'] == [[kind: 'device', id: 1, name: 'Hub device']] })
t('a device with no parent in the list has no entry', { assert idx['driver:72'] == null && idx['driver:70'] == null })
t('empty inputs are fine', { assert H.childOfIndex(null, null) == [:] })

// --- docPruneDecision (a document goes only when its package left the registry and no former member is unread) ---
Map oldM = ['url:a': ['app:1'], 'url:b': ['app:2'], 'local:c': ['app:3', 'app:4'], 'url:keep': ['app:5']]
Map dec = H.docPruneDecision(oldM, ['url:keep'] as Set, ['app:1', 'app:4'] as Set)
t('a package that left with an unread member is held, not pruned', { assert dec.hold.keySet() == ['url:a', 'local:c'] as Set && !dec.prune.contains('url:a') })
t('a package that left with no unread member is pruned', { assert dec.prune == ['url:b'] })
t('a package still in the registry is neither pruned nor held', { assert !dec.prune.contains('url:keep') && !dec.hold.containsKey('url:keep') })
t('an unchanged package set prunes nothing', { assert H.docPruneDecision(oldM, oldM.keySet() as Set, [] as Set) == [prune: [], hold: [:]] })
t('a held package whose item was deleted is pruned', { assert H.docPruneDecision(['url:a': ['app:1']], [] as Set, [] as Set).prune == ['url:a'] })

// --- factVisible ---
t('item facts need a usable basis', { assert !H.factVisible('url', 'stale', 'loc') && !H.factVisible('url', 'unread', 'ih') && H.factVisible('url', 'valid', 'ih') && H.factVisible('url', 'provisional', 'loc') })
t('hpm manifest metadata is shown whatever the basis', { assert H.factVisible('hpm', 'unread', 'loc') && H.factVisible('hpm', 'stale', 'iv') && H.factVisible('hpm', 'stale', 'ivs') })
t('an hpm item hash still needs a usable basis', { assert !H.factVisible('hpm', 'unread', 'ih') })
t('components without a basis (bundles, files) are always shown', { assert H.factVisible('bundle', null, 'loc') })

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
