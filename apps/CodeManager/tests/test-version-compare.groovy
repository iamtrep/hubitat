// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the version heuristics in CodeManager.groovy (DESIGN.md §4).
// Run: groovy apps/CodeManager/tests/test-version-compare.groovy      (no hub; ~2 s)
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
String code = ['parseVersionLiteral', 'normalizeVersion', 'compareVersions', 'freshnessStatus'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nclass H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('CODE_VERSION field', { assert H.parseVersionLiteral('@Field static final String CODE_VERSION = "5.83.4"') == [ver: '5.83.4', src: 'field'] })
t('DRIVER_VERSION single quotes', { assert H.parseVersionLiteral("@Field static final String DRIVER_VERSION = '5.6.1'").ver == '5.6.1' })
t('non-static @Field driverVersion with suffix', { assert H.parseVersionLiteral('@Field String driverVersion = "v1.10 (29th August 2023)"').ver == 'v1.10 (29th August 2023)' })
t('def version() method', { assert H.parseVersionLiteral('def version() { return "2.3.9l" }') == [ver: '2.3.9l', src: 'method'] })
t('static String version() method', { assert H.parseVersionLiteral('static String version() { "3.5.5" }').ver == '3.5.5' })
t('library() header', { assert H.parseVersionLiteral("library(\n  name: 'commonLib', namespace: 'kkossev',\n  version: '4.0.3'\n)").src == 'library' })
t('header comment', { assert H.parseVersionLiteral('/*\n * Version: 1.0.3\n */\ndefinition(name: "x")') == [ver: '1.0.3', src: 'comment'] })
t('nothing', { assert H.parseVersionLiteral('definition(name: "x", namespace: "y")') == [ver: null, src: null] })
t('only first 400 lines scanned', { String s = ('\n' * 405) + '@Field static final String CODE_VERSION = "9.9.9"'; assert H.parseVersionLiteral(s).ver == null })
t('@Field static String VERSION (no final)', { assert H.parseVersionLiteral('@Field static String VERSION = "1.2.3"').ver == '1.2.3' })
t('@Field static final VERSION (no type)', { assert H.parseVersionLiteral('@Field static final VERSION = "1.2.3"').ver == '1.2.3' })
t('@Field static def VERSION', { assert H.parseVersionLiteral('@Field static def VERSION = "1.2.3"').ver == '1.2.3' })
t('static final String VERSION without @Field', { assert H.parseVersionLiteral('static final String VERSION = "1.2.3"').ver == '1.2.3' })
t('header comment beats nothing but loses to a field', { assert H.parseVersionLiteral('/* Version: 0.9 */\n@Field static final String CODE_VERSION = "1.0"').ver == '1.0' })

t('normalize strips v and suffix', { assert H.normalizeVersion('v1.10 (29th August 2023)') == [1, 10] })
t('normalize letter suffix on last segment', { assert H.normalizeVersion('2.3.9l') == [2, 3, 9] })
t('normalize date YYYY-MM-DD', { assert H.normalizeVersion('2023-05-25') == [2023, 5, 25] })
t('normalize garbage -> null', { assert H.normalizeVersion('latest') == null })
t('normalize empty -> null', { assert H.normalizeVersion('') == null && H.normalizeVersion(null) == null })

t('compare newer', { assert H.compareVersions('1.2.3', '1.2.4') == -1 })
t('compare equal with missing segment', { assert H.compareVersions('1.2', '1.2.0') == 0 })
t('compare 2.0 vs 1.5 (HPM ordering defect must not recur)', { assert H.compareVersions('2.0', '1.5') == 1 })
t('compare unavailable', { assert H.compareVersions('1.2', 'latest') == null })

t('status current', { assert H.freshnessStatus('aa', 'aa', '1.0', '1.1', false) == 'current' })
t('status update-available', { assert H.freshnessStatus('aa', 'bb', '1.0', '1.1', false) == 'update-available' })
t('status changed-upstream (no compare)', { assert H.freshnessStatus('aa', 'bb', null, null, false) == 'changed-upstream' })
t('status changed-upstream (remote older)', { assert H.freshnessStatus('aa', 'bb', '2.0', '1.9', false) == 'changed-upstream' })
t('status modified-locally wins', { assert H.freshnessStatus('aa', 'bb', '1.0', '1.1', true) == 'modified-locally' })
t('status unknown when no remote hash', { assert H.freshnessStatus('aa', null, '1.0', null, false) == 'unknown' })

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
