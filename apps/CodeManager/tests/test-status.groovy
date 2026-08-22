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
String code = ['componentStatus', 'packageStatus', 'freshnessStatus', 'isLocallyModified', 'compareVersions', 'normalizeVersion'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nclass H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// --- componentStatus: a component the package declares but the hub does not have ---
t('optional and never installed is not a defect', { assert H.componentStatus([o: 'hpm'], [k: 'driver', id: null, req: false], null, [:]) == 'not-installed' })
t('required and absent is missing', { assert H.componentStatus([o: 'hpm'], [k: 'driver', id: null, req: true], null, [:]) == 'missing' })
t('no required flag defaults to missing', { assert H.componentStatus([o: 'hpm'], [k: 'driver', id: null], null, [:]) == 'missing' })
t('a bound bundle is no longer missing', { assert H.componentStatus([o: 'hpm', v: '1.0'], [k: 'bundle', id: '1'], null, [checkedAt: 1L, rver: '1.0']) == 'current' })
t('a File Manager file that is gone is missing', { assert H.componentStatus([o: 'hpm', v: '1.0'], [k: 'file', id: null, req: true], null, [checkedAt: 1L, rver: '1.0']) == 'missing' })

// --- componentStatus: present on the hub ---
t('private code is never read', { assert H.componentStatus([o: 'bundle'], [k: 'driver', id: '1', pv: true], null, [:]) == 'private' })
t('local origin has nothing upstream to compare', { assert H.componentStatus([o: 'local'], [k: 'driver', id: '1', ih: 'aa'], null, [:]) == 'unmanaged' })
t('url origin never swept reads as not checked', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', ih: 'aa', ah: 'aa'], [:], [:]) == 'unknown' })
t('matching hashes are current', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', ih: 'aa', ah: 'aa'], [rhash: 'aa'], [:]) == 'current' })
t('newer upstream version is an update', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', ih: 'aa', ah: 'aa', iv: '1.0'], [rhash: 'bb', rver: '1.1'], [:]) == 'update-available' })
t('differing hash with no version order is changed-upstream', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', ih: 'aa', ah: 'aa'], [rhash: 'bb'], [:]) == 'changed-upstream' })
t('edited on the hub is modified-locally', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', ih: 'cc', ah: 'aa'], [rhash: 'bb'], [:]) == 'modified-locally' })
t('a strike-out on the component is broken-source', { assert H.componentStatus([o: 'url'], [k: 'driver', id: '1', ih: 'aa', ah: 'aa'], [rhash: 'aa', broken: true], [:]) == 'broken-source' })

// --- componentStatus: HPM components are judged by their package's manifest version ---
t('hpm component follows the package version', { assert H.componentStatus([o: 'hpm', v: '1.0'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2']) == 'update-available' })
t('hpm component at the manifest version is current', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2']) == 'current' })
t('a manifest that 404s never reads as current', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [checkedAt: 1L, rver: '1.2', httpStatus: 404]) == 'unknown' })
t('a struck-out manifest breaks every component under it', { assert H.componentStatus([o: 'hpm', v: '1.2'], [k: 'driver', id: '1'], [:], [broken: true]) == 'broken-source' })

// --- packageStatus ---
t('a required component off the hub makes the package incomplete', { assert H.packageStatus('hpm', ['current', 'missing'], [:]) == 'incomplete' })
t('optional components never make a package incomplete', { assert H.packageStatus('hpm', ['current', 'not-installed'], [:]) == 'current' })
t('an available update outranks a changed file', { assert H.packageStatus('hpm', ['changed-upstream', 'update-available'], [:]) == 'update-available' })
t('a broken manifest outranks everything', { assert H.packageStatus('hpm', ['current'], [broken: true]) == 'broken-source' })
t('a broken component surfaces on the package', { assert H.packageStatus('url', ['current', 'broken-source'], [:]) == 'broken-source' })
t('nothing comparable is not the same as current', { assert H.packageStatus('hpm', ['unknown', 'not-installed'], [:]) == 'unknown' })
t('a package with no components is unknown', { assert H.packageStatus('hpm', [], [:]) == 'unknown' })
t('a package whose code is all uninstalled says so', { assert H.packageStatus('hpm', ['not-installed', 'not-installed'], [:]) == 'not-installed' })
t('a local package reports unmanaged, not unknown', { assert H.packageStatus('local', ['unmanaged'], [:]) == 'unmanaged' })
t('one verified component makes the package current', { assert H.packageStatus('bundle', ['current', 'private', 'unknown'], [:]) == 'current' })

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
