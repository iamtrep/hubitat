// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the status vocabulary in CodeManager.groovy (DESIGN.md §3, §4).
// Run: groovy apps/CodeManager/tests/test-status.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../CodeManager.groovy').text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^static\s+[^\n(]*\b${name}\(/
    assert m.find() : "method not found: ${name}"
    int start = m.start()
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String code = ['componentStatus', 'packageStatus', 'driftEntryValid', 'pkgDriftValid', 'freshnessStatus', 'isLocallyModified', 'compareVersions', 'normalizeVersion'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nclass H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// A drift entry counts only for the installed code it was computed against (ilm == the component's lastModified).
// An HPM package-row entry counts only for the installed package version it was computed against (iv == p.v).

// --- componentStatus: a component the package declares but the hub does not have ---
t('optional and never installed is not a defect', { assert H.componentStatus([o: 'hpm'], [k: 'driver', id: null, req: false], null, [:]) == 'not-installed' })
t('required and absent is missing', { assert H.componentStatus([o: 'hpm'], [k: 'driver', id: null, req: true], null, [:]) == 'missing' })
t('no required flag defaults to missing', { assert H.componentStatus([o: 'hpm'], [k: 'driver', id: null], null, [:]) == 'missing' })
t('a bound bundle is judged by its package manifest', { assert H.componentStatus([o: 'hpm', v: '1.0'], [k: 'bundle', id: '1'], null, [checkedAt: 1L, rver: '1.0', iv: '1.0']) == 'current' })
t('a File Manager file that is gone is missing', { assert H.componentStatus([o: 'hpm', v: '1.0'], [k: 'file', id: null, req: true], null, [checkedAt: 1L, rver: '1.0', iv: '1.0']) == 'missing' })

// --- componentStatus: present on the hub ---
t('private code is never read', { assert H.componentStatus([o: 'bundle'], [k: 'driver', id: '1', pv: true], null, [:]) == 'private' })
t('local origin has nothing upstream to compare', { assert H.componentStatus([o: 'local'], [k: 'driver', id: '1', ih: 'aa'], null, [:]) == 'unmanaged' })
t('local origin with a leftover broken entry is unmanaged (B3)', { assert H.componentStatus([o: 'local'], [k: 'driver', id: '1', lm: 5L], [ilm: 5L, broken: true, httpStatus: 404, failCount: 25], [:]) == 'unmanaged' })
t('url origin never checked reads as not-checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [:], [:]) == 'not-checked' })
t('url origin with no entry at all reads as not-checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], null, [:]) == 'not-checked' })
t('matching hashes against the installed code are current', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [rhash: 'aa', ilm: 5L], [:]) == 'current' })
t('a result computed against older code is not-checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 6L, ih: 'aa', ah: 'aa'], [rhash: 'aa', ilm: 5L], [:]) == 'not-checked' })
t('a result with no ilm is not-checked (data from 0.1)', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [rhash: 'aa'], [:]) == 'not-checked' })
t('an ilm that survived a JSON round trip still matches', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 1787300000000L, ih: 'aa', ah: 'aa'], [rhash: 'aa', ilm: 1787300000000], [:]) == 'current' })
t('newer upstream version is an update', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa', iv: '1.0'], [rhash: 'bb', rver: '1.1', ilm: 5L], [:]) == 'update-available' })
t('differing hash with no version order is changed-upstream', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [rhash: 'bb', ilm: 5L], [:]) == 'changed-upstream' })
t('edited on the hub is modified-locally', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'cc', ah: 'aa'], [rhash: 'bb', ilm: 5L], [:]) == 'modified-locally' })
t('a strike-out on the component is broken-source', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [rhash: 'aa', broken: true, ilm: 5L], [:]) == 'broken-source' })
t('a strike-out against older code is not-checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 6L, ih: 'aa', ah: 'aa'], [broken: true, ilm: 5L], [:]) == 'not-checked' })
t('a result for another location is not-checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, loc: 'https://b/x', ih: 'aa', ah: 'aa'], [rhash: 'aa', ilm: 5L, loc: 'https://a/x'], [:]) == 'not-checked' })
t('an entry with no upstream hash is not-checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [ilm: 5L, httpStatus: 503, unreachable: true], [:]) == 'not-checked' })

t('a component whose facts are unread is never judged against upstream', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, basis: 'unread'], [rhash: 'aa', ilm: 5L], [:]) == 'not-checked' })
t('a component whose facts are stale is never current', { assert H.componentStatus([o: 'bundle'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa', basis: 'stale'], [rhash: 'aa', ilm: 5L], [:]) == 'not-checked' })
t('an unread-origin package never reads current', { assert H.componentStatus([o: 'unread'], [k: 'app', id: '1', lm: 5L], [rhash: 'aa', ilm: 5L], [:]) == 'not-checked' })
t('provisional facts are judged normally', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa', basis: 'provisional'], [rhash: 'aa', ilm: 5L], [:]) == 'current' })
t('hpm components do not need a per-item fact', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1', basis: 'unread'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.2']) == 'current' })

// --- componentStatus: HPM components are judged by their package's manifest version ---
t('hpm component follows the package version', { assert H.componentStatus([o: 'hpm', v: '1.0'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.0']) == 'update-available' })
t('hpm component at the manifest version is current', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.2']) == 'current' })
t('a manifest checked against another installed version is not-checked', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.1']) == 'not-checked' })
t('a manifest result with no recorded installed version is not-checked', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2']) == 'not-checked' })
t('a package with no manifest result leaves its components not-checked', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [:]) == 'not-checked' })
t('a manifest that 404s never reads as current', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.2', httpStatus: 404]) == 'not-checked' })
t('a struck-out manifest breaks every component under it', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [broken: true, checkedAt: 1L, iv: '1.2']) == 'broken-source' })
t('a struck-out manifest checked against another version breaks nothing', { assert H.componentStatus([o: 'hpm', v: '1.3'], [k: 'driver', id: '1'], [:], [broken: true, checkedAt: 1L, iv: '1.2']) == 'not-checked' })
t('an hpm component with its own valid entry is judged by hash', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1', lm: 5L, ih: 'aa', ah: 'aa'], [rhash: 'bb', ilm: 5L], [checkedAt: 1L, rver: '1.2', iv: '1.2']) == 'changed-upstream' })
t('a file component with no entry is not-checked and never inherits the package (B2)', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'file', id: 'a.json'], null, [checkedAt: 1L, rver: '1.5', iv: '1.2']) == 'not-checked' })
t('a file component of a current package is not-checked (B2)', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'file', id: 'a.json'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.2']) == 'not-checked' })
t('a bundle member without its own entry does not inherit the package', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1', bm: '9'], [:], [checkedAt: 1L, rver: '1.2', iv: '1.2']) == 'not-checked' })

// --- validity helpers ---
t('driftEntryValid needs a matching ilm', { assert H.driftEntryValid([lm: 5L], [ilm: 5L]); assert !H.driftEntryValid([lm: 5L], [ilm: 4L]); assert !H.driftEntryValid([lm: 5L], [:]); assert !H.driftEntryValid([lm: 5L], null) })
t('pkgDriftValid needs a checked entry for this installed version', { assert H.pkgDriftValid([v: '1.0'], [checkedAt: 1L, iv: '1.0']); assert !H.pkgDriftValid([v: '1.0'], [checkedAt: 1L, iv: '0.9']); assert !H.pkgDriftValid([v: '1.0'], [:]) })
t('a package without a version matches an entry recorded without one', { assert H.pkgDriftValid([v: null], [checkedAt: 1L]) })

// --- packageStatus ---
t('a required component off the hub makes the package incomplete', { assert H.packageStatus('hpm', ['current', 'missing'], [:]) == 'incomplete' })
t('optional components never make a package incomplete', { assert H.packageStatus('hpm', ['current', 'not-installed'], [:]) == 'current' })
t('an available update outranks a changed file', { assert H.packageStatus('hpm', ['changed-upstream', 'update-available'], [:]) == 'update-available' })
t('a broken manifest outranks everything', { assert H.packageStatus('hpm', ['current'], [broken: true]) == 'broken-source' })
t('a broken component surfaces on the package', { assert H.packageStatus('url', ['current', 'broken-source'], [:]) == 'broken-source' })
t('nothing comparable is not the same as current', { assert H.packageStatus('hpm', ['not-checked', 'not-installed'], [:]) == 'not-checked' })
t('all not-checked is not-checked', { assert H.packageStatus('url', ['not-checked', 'not-checked'], [:]) == 'not-checked' })
t('a package with no components is not-checked', { assert H.packageStatus('hpm', [], [:]) == 'not-checked' })
t('a package whose code is all uninstalled says so', { assert H.packageStatus('hpm', ['not-installed', 'not-installed'], [:]) == 'not-installed' })
t('a local package reports unmanaged', { assert H.packageStatus('local', ['unmanaged'], [:]) == 'unmanaged' })
t('one verified component makes the package current', { assert H.packageStatus('bundle', ['current', 'private', 'not-checked'], [:]) == 'current' })

// --- the closed vocabulary: no branch returns anything else, and never 'unknown' ---
Set VOCAB = ['current', 'update-available', 'changed-upstream', 'modified-locally', 'broken-source', 'private', 'unmanaged', 'not-checked', 'missing', 'not-installed', 'incomplete'] as Set
t('no input to componentStatus or packageStatus yields a status outside the vocabulary', {
    List origins = ['hpm', 'bundle', 'url', 'local', 'unread']
    List kinds = ['app', 'driver', 'library', 'bundle', 'file']
    List ids = [null, '1']
    List bases = [null, 'valid', 'provisional', 'stale', 'unread']
    List entries = [null, [:], [rhash: 'aa', ilm: 5L], [rhash: 'bb', ilm: 5L, rver: '2.0'], [ilm: 5L, broken: true], [ilm: 4L, rhash: 'aa'], [rhash: 'aa']]
    List pes = [[:], [checkedAt: 1L, rver: '2.0', iv: '1.0'], [checkedAt: 1L, rver: '1.0', iv: '1.0'], [checkedAt: 1L, iv: '1.0', httpStatus: 404], [broken: true, checkedAt: 1L, iv: '1.0'], [checkedAt: 1L, rver: 'x', iv: '0.5']]
    int n = 0
    Set seen = [] as Set
    for (o in origins) for (k in kinds) for (id in ids) for (b in bases) for (e in entries) for (pe in pes) for (pvv in [false, true]) for (bm in [null, '9']) for (req in [true, false]) {
        Map c = [k: k, id: id, lm: 5L, ih: 'aa', ah: 'aa', pv: pvv, req: req, basis: b, bm: bm]
        String st = H.componentStatus([o: o, v: '1.0'], c, e, pe)
        assert VOCAB.contains(st), "componentStatus returned ${st} for ${o} ${k} ${id} ${b} ${e} ${pe}"
        seen << st; n++
    }
    assert n > 1000
    println "         (${n} inputs, statuses seen: ${seen.sort()})"
    List all = VOCAB.toList()
    for (o in origins) for (a in all + []) for (b2 in all) for (pe in pes + [[broken: true]]) {
        String ps = H.packageStatus(o, [a, b2], pe)
        assert VOCAB.contains(ps), "packageStatus returned ${ps}"
    }
    assert VOCAB.contains(H.packageStatus('hpm', [], [:]))
    assert H.freshnessStatus('aa', null, '1', null, false) == 'not-checked'
})

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
