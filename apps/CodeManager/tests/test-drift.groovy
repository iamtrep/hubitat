// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the drift result rules in CodeManager.groovy (DESIGN.md 5.3, 7): what a stored entry records,
// when strikes count, and when they start over.
// Run: groovy apps/CodeManager/tests/test-drift.groovy      (no hub; ~2 s)
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
String field(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^@Field (static final [^\n]*\b${name}\b[^\n]*)$/
    assert m.find() : "field not found: ${name}"
    return m.group(1)
}
String code = (['BROKEN_AFTER', 'DRIFT_STRIKE_ERROR_RE'].collect { field(it) } +
    ['driftApply', 'sameStampValue', 'normalizeSource', 'sha256Hex', 'sourceHash', 'hashPrefix', 'parseVersionLiteral'].collect { extract(it) }).join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nimport groovy.json.JsonSlurper\nimport java.security.MessageDigest\nclass H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

String LOC = 'https://example.org/a.groovy'
String BODY = '@Field static final String VERSION = "1.2"\n'
Map ok200 = [status: 200, body: BODY, etag: 'W/"x"']
long N = 1000L

def apply = { Map prev, Map r, String loc = LOC, Object lm = 5L, boolean manifest = false -> H.driftApply(prev, r, loc, 'ilm', lm, manifest, N) }

t('a 200 records the hash, version, etag and what it was computed against', {
    Map res = apply(null, ok200); Map e = res.entry
    assert res.outcome == 'changed'
    assert e.rhash == H.hashPrefix(H.sourceHash(BODY)) && e.rver == '1.2' && e.etag == 'W/"x"' && e.ilm == 5L && e.loc == LOC && e.checkedAt == N
    assert e.failCount == 0 && e.broken == false
})
t('the same body again is checked, not changed', {
    Map e1 = apply(null, ok200).entry
    Map res = apply(e1, ok200); assert res.outcome == 'checked'
})
t('a 304 keeps the stored comparison and moves the stamp to the installed code', {
    Map e1 = apply(null, ok200, LOC, 5L).entry
    Map res = apply(e1, [status: 304], LOC, 9L); Map e = res.entry
    assert res.outcome == 'notModified' && e.ilm == 9L && e.rhash == e1.rhash && e.rver == '1.2' && e.etag == 'W/"x"' && e.httpStatus == 304
})
t('a 304 with nothing stored is not a comparison', {
    Map res = apply(null, [status: 304]); assert res.outcome == 'unreachable' && res.entry.rhash == null
})
t('a failed fetch keeps no hash from an earlier one', {
    Map e1 = apply(null, ok200).entry
    Map e = apply(e1, [status: 503]).entry
    assert e.rhash == null && e.rver == null && e.etag == null && e.unreachable == true && e.httpStatus == 503
})
t('three 404s on the same code and location mark it broken', {
    Map e = apply(null, [status: 404]).entry; assert e.failCount == 1 && e.broken == false
    e = apply(e, [status: 404]).entry; assert e.failCount == 2 && e.broken == false
    Map res = apply(e, [status: 404]); assert res.entry.failCount == 3 && res.entry.broken == true && res.outcome == 'broken'
})
t('strikes against older code do not count (audit B3)', {
    Map e = apply(null, [status: 404], LOC, 5L).entry
    e = apply(e, [status: 404], LOC, 5L).entry
    Map res = apply(e, [status: 404], LOC, 6L)                     // the item was edited since: start over
    assert res.entry.failCount == 1 && res.entry.broken == false
})
t('strikes for another location do not carry over (audit B3)', {
    Map e = apply(null, [status: 404], LOC).entry
    e = apply(e, [status: 404], LOC).entry
    Map res = apply(e, [status: 404], 'https://example.org/b.groovy')
    assert res.entry.failCount == 1 && res.entry.loc == 'https://example.org/b.groovy'
})
t('an etag from another location is not reused', {
    Map e1 = apply(null, ok200).entry
    Map res = apply(e1, [status: 304], 'https://example.org/b.groovy')
    assert res.outcome == 'unreachable'
})
t('a good answer clears the strikes', {
    Map e = apply(null, [status: 404]).entry
    assert apply(e, ok200).entry.failCount == 0
})
t('a timeout is unreachable and adds no strike', {
    Map e = apply(null, [status: 404]).entry
    Map res = apply(e, [status: 0, transport: true, error: 'java.net.SocketTimeoutException: timed out'])
    assert res.outcome == 'unreachable' && res.entry.failCount == 1 && res.entry.unreachable == true
})
t('a DNS failure is a strike', {
    Map res = apply(null, [status: 0, transport: true, error: 'java.net.UnknownHostException: nope'])
    assert res.outcome == 'struck' && res.entry.failCount == 1
})
t('an empty 200 is never hashed', {
    Map res = apply(null, [status: 200, body: '']); assert res.outcome == 'unreachable' && res.entry.rhash == null
})
t('the package row is stamped with the installed version, not a lastModified', {
    String man = '{"packageName":"P","version":"2.0","betaVersion":"2.1"}'
    Map res = H.driftApply(null, [status: 200, body: man, etag: 'e'], LOC, 'iv', '1.0', true, N); Map e = res.entry
    assert e.iv == '1.0' && e.ilm == null && e.rver == '2.0' && e.rbeta == '2.1' && e.rverSrc == 'manifest' && res.outcome == 'changed'
    Map again = H.driftApply(e, [status: 200, body: man], LOC, 'iv', '1.0', true, N + 1)
    assert again.outcome == 'checked' && again.entry.changedAt == N
})
t('a manifest that does not parse is not a comparison', {
    Map res = H.driftApply(null, [status: 200, body: 'not json'], LOC, 'iv', '1.0', true, N)
    assert res.outcome == 'unreachable' && res.entry.rhash == null
})
t('stamp values compare across number types', {
    assert H.sameStampValue(5, 5L) && !H.sameStampValue(5, 6L) && H.sameStampValue('1.0', '1.0') && H.sameStampValue(null, null)
})

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
