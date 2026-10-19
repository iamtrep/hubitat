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
String constant = ['PROVISIONAL_MS', 'LARGE_SOURCE_BYTES', 'FACT_FAIL_LIMIT', 'FACT_BACKOFF_MS', 'FACT_GAP_MS', 'FACT_READ_TIMEOUT_MS', 'FACT_WRITE_WAIT_STEPS'].collect { String n ->
    java.util.regex.Matcher cm = src =~ /(?m)^@Field (static final [^\n=]*\b${n} = [^\n]*?)\s*(?:\/\/[^\n]*)?$/
    assert cm.find() : "${n} not found"
    cm.group(1)
}.join('\n')
String code = ['factBasis', 'factQueue', 'recordFailure', 'recordSuccess', 'factNextAction', 'factNeedsRetry', 'factJobStale', 'classifyOrigin', 'ckey', 'childOfIndex', 'walkAppTree', 'docPruneDecision', 'factVisible'].collect { extract(it) }.join('\n')
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

// --- factQueue ---
long NOW = 1_000_000L
def qc = { Map over -> [k: 'app', id: 1, lm: 1L] + over }
t('factQueue orders newest-changed first', { assert H.factQueue([qc(id: 1, lm: 3L), qc(id: 2, lm: 1L), qc(id: 3, lm: 2L)], NOW) == ['app:1', 'app:3', 'app:2'] })
t('factQueue skips an item backed off into the future', { assert H.factQueue([qc(fnext: NOW + 1)], NOW) == [] })
t('factQueue includes an item whose back-off has passed', { assert H.factQueue([qc(fnext: NOW)], NOW) == ['app:1'] })
t('factQueue skips a large source', { assert H.factQueue([qc(sz: 600_000)], NOW) == [] })
t('factQueue keeps a source at the size limit', { assert H.factQueue([qc(sz: 524_288)], NOW) == ['app:1'] })
t('factQueue skips a component with no hub id', { assert H.factQueue([qc(id: null)], NOW) == [] })
t('factQueue skips bundles and files', { assert H.factQueue([qc(k: 'file'), qc(k: 'bundle')], NOW) == [] })
t('factQueue includes provisional and stale, not valid', {
    assert H.factQueue([qc(id: 1, lm: 1000L, flm: 1000L, frd: 1500L), qc(id: 2, lm: 2000L, flm: 1000L, frd: 9000L), qc(id: 3, lm: 1000L, flm: 1000L, frd: 9000L)], NOW) == ['app:2', 'app:1'] })
t('factQueue keys libraries and drivers the same way', { assert H.factQueue([qc(k: 'library', id: 7), qc(k: 'driver', id: 8, lm: 0L)], NOW) == ['library:7', 'driver:8'] })

// --- recordFailure / recordSuccess ---
Map f1 = H.recordFailure([k: 'app', id: 1], 'timeout', NOW)
t('first failure counts, no back-off yet', { assert f1.ff == 1 && f1.fnext == null && f1.fe == 'timeout' })
Map f2 = H.recordFailure(f1, 'timeout', NOW)
t('second failure backs off one hour', { assert f2.ff == 2 && f2.fnext == NOW + 3_600_000L })
Map f3 = H.recordFailure(f2, 'timeout', NOW)
t('third failure backs off 24 hours', { assert f3.ff == 3 && f3.fnext == NOW + 86_400_000L })
t('fourth failure stays at 24 hours', { assert H.recordFailure(f3, 'x', NOW).fnext == NOW + 86_400_000L })
t('recordFailure leaves the input map alone', { assert f1.ff == 1 && f2.ff == 2 })
Map ok1 = H.recordSuccess(f3, [loc: 'https://x/a.groovy', ih: 'abc', iv: '1.0', ivs: 'field', sz: 120, ch: true, pv: false], 5000L, 9000L)
t('success clears the failure fields', { assert !ok1.containsKey('ff') && !ok1.containsKey('fnext') && !ok1.containsKey('fe') })
t('success stamps the read version and time', { assert ok1.flm == 5000L && ok1.frd == 9000L })
t('success stores the facts', { assert ok1.loc == 'https://x/a.groovy' && ok1.ih == 'abc' && ok1.iv == '1.0' && ok1.sz == 120 && ok1.ch == true })
t('success adopts ih as ah when none is set', { assert ok1.ah == 'abc' })
t('success keeps an existing ah', { assert H.recordSuccess([ah: 'old'], [ih: 'new'], 1L, 2L).ah == 'old' })
t('success drops facts that came back empty and leaves unlisted ones', { Map r = H.recordSuccess([loc: 'u', iv: '1', ivs: 'field'], [ih: 'h', iv: null], 1L, 2L); assert !r.containsKey('iv') && r.loc == 'u' && r.ivs == 'field' })
t('a successful read is valid for its version', { assert H.factBasis(ok1, 5000L) == 'valid' })
t('a read within 2 s of the version is provisional', { assert H.factBasis(H.recordSuccess([:], [ih: 'h'], 5000L, 5500L), 5000L) == 'provisional' })

// --- step decisions ---
t('a step reads when nothing blocks it', { assert H.factNextAction([writeOutstanding: false, waits: 0, queueEmpty: false]) == 'read' })
t('a step finishes on an empty queue', { assert H.factNextAction([writeOutstanding: false, waits: 0, queueEmpty: true]) == 'finish' })
t('a step defers while a write is outstanding, even with work queued', { assert H.factNextAction([writeOutstanding: true, waits: 0, queueEmpty: false]) == 'defer' && H.factNextAction([writeOutstanding: true, waits: 5, queueEmpty: true]) == 'defer' })
t('a step defers up to the wait limit, then gives up', { assert H.factNextAction([writeOutstanding: true, waits: 299]) == 'defer' && H.factNextAction([writeOutstanding: true, waits: 300]) == 'giveup' })
t('a step yields to a live reader without counting toward the wait limit', { assert H.factNextAction([writeOutstanding: false, readerFresh: true, waits: 5000, queueEmpty: false]) == 'yield' })
t('a write outstanding still counts when a live reader is also running', { assert H.factNextAction([writeOutstanding: true, readerFresh: true, waits: 300]) == 'giveup' })
t('with no live reader and no write the step reads', { assert H.factNextAction([writeOutstanding: false, readerFresh: false, waits: 0, queueEmpty: false]) == 'read' })
// provisional: read 500 ms after the save, re-read once, then valid
Map prov = H.recordSuccess([k: 'app', id: 1, lm: 10_000L], [ih: 'a'], 10_000L, 10_500L)
t('a read right after a save is provisional and wanted again', { assert H.factBasis(prov, 10_000L) == 'provisional' && H.factNeedsRetry(10_000L, 10_500L, false) && H.factQueue([prov], 10_600L) == ['app:1'] })
t('the second read is not retried again and leaves a valid fact', {
    Map again = H.recordSuccess(prov, [ih: 'b'], 10_000L, 13_000L)
    assert !H.factNeedsRetry(10_000L, 13_000L, true) && !H.factNeedsRetry(10_000L, 13_000L, false) && H.factBasis(again, 10_000L) == 'valid' && H.factQueue([again], 13_100L) == [] })
t('a provisional item already retried once is not retried', { assert !H.factNeedsRetry(10_000L, 10_500L, true) })
// two timeouts back an item off, and it stays out of the queue until fnext
Map fl = [k: 'app', id: 4, lm: 5L]
fl = H.recordFailure(fl, 'timeout', NOW); t('after one failure the item is still queued', { assert H.factQueue([fl], NOW + 10) == ['app:4'] })
fl = H.recordFailure(fl, 'timeout', NOW)
t('after two failures the item is out of the queue until fnext', { assert H.factQueue([fl], NOW + 10) == [] && H.factQueue([fl], NOW + 3_599_999L) == [] && H.factQueue([fl], NOW + 3_600_000L) == ['app:4'] })
t('the backed-off item reads as unread with its error and a failing count', { assert H.factBasis(fl, 5L) == 'unread' && fl.fe == 'timeout' && fl.ff == 2 })
// a reader that stopped taking steps
t('a reader is stale after 5 gaps plus the read timeout', { assert !H.factJobStale(1000L, 1000L + 40_000L) && H.factJobStale(1000L, 1000L + 40_001L) })

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
