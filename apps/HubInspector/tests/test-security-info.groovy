// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for fetchSecurityInfo's parsing of the hub's text replies.
//
// getLimitedAccessAddresses separates its entries with <br/>; stripping the tags without a
// separator glued them into one entry. /hub/allowSubnets answers the literal "Not set" when empty.
// The fixtures are replies captured from a hub.
//
// This BRACE-EXTRACTS fetchSecurityInfo from the shipped HubInspector.groovy, so it stays
// bound to the shipped code; no live hub required.
//
// Run: groovy apps/HubInspector/tests/test-security-info.groovy

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

Map replies = [:]
String harness = """
@groovy.transform.Field static final String LIMITED_ACCESS_PATH = "/hub/advanced/getLimitedAccessAddresses"
@groovy.transform.Field static final String ALLOW_SUBNETS_PATH = "/hub/allowSubnets"
@groovy.transform.Field static final String DNS_FALLBACK_PATH = "/hub/advanced/getDNSFallback"
${extract(src, 'Map fetchSecurityInfo(Map prefetchedHubData = null) {')}
this
"""
def app = new GroovyShell().evaluate(harness)
app.metaClass.hubRequest = { String path, String label, String type, int timeout -> replies[path] }

Map hubData = [disableCloudController: false]

// No limit set, allowSubnets empty.
replies = ['/hub/advanced/getLimitedAccessAddresses': '<html><body>no limit set</body></html>\n',
           '/hub/allowSubnets': 'Not set', '/hub/advanced/getDNSFallback': 'true']
Map r = app.fetchSecurityInfo(hubData)
assert r.limitedAccess == [enabled: false, addresses: []]
assert r.allowedSubnets == []
assert r.dnsFallback == true
assert r.cloudController == 'enabled'

// A limit of three subnets: <br/>-separated, one entry each.
replies['/hub/advanced/getLimitedAccessAddresses'] =
    '<html><body>192.0.2.0/28<br/>198.51.100.0/28<br/>203.0.113.0/28</body></html>\n'
r = app.fetchSecurityInfo(hubData)
assert r.limitedAccess.enabled
assert r.limitedAccess.addresses == ['192.0.2.0/28', '198.51.100.0/28', '203.0.113.0/28']

// Addresses, with other <br> spellings.
replies['/hub/advanced/getLimitedAccessAddresses'] = '<html><body>192.0.2.1<br>192.0.2.2<BR />192.0.2.3</body></html>'
r = app.fetchSecurityInfo(hubData)
assert r.limitedAccess.addresses == ['192.0.2.1', '192.0.2.2', '192.0.2.3']

// allowSubnets with entries.
replies['/hub/allowSubnets'] = '192.0.2.0/24, 198.51.100.0/24'
r = app.fetchSecurityInfo(hubData)
assert r.allowedSubnets == ['192.0.2.0/24', '198.51.100.0/24']

// Failed fetches stay null, so the UI shows "Unknown".
replies = [:]
r = app.fetchSecurityInfo(hubData)
assert r.limitedAccess == null
assert r.allowedSubnets == null
assert r.dnsFallback == null

println "test-security-info: all assertions passed"
