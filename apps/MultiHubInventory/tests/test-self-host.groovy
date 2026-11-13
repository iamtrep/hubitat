// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test: Multi-Hub Inventory decides a peer is "this hub" by exact host match,
// not by substring (192.168.1.8 must not match a peer at 192.168.1.80).
// Run: groovy apps/MultiHubInventory/tests/test-self-host.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../MultiHubInventory.groovy').text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^static\s+[^\n(]*\b${name}\(/
    assert m.find() : "method not found: ${name}"
    int start = m.start()
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
def H = new GroovyShell().parse("class H {\n${extract('isSelfHost')}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('same host is self') { assert H.isSelfHost('http://192.168.1.8', '192.168.1.8') }
t('same host with a port is self') { assert H.isSelfHost('http://192.168.1.8:8080', '192.168.1.8') }
t('https is self') { assert H.isSelfHost('https://192.168.1.8', '192.168.1.8') }
t('longer last octet is a different hub') { assert !H.isSelfHost('http://192.168.1.80', '192.168.1.8') }
t('longer last octet with a port is a different hub') { assert !H.isSelfHost('http://192.168.1.88:8080', '192.168.1.8') }
t('prefix of the address is a different hub') { assert !H.isSelfHost('http://10.192.168.1.8', '192.168.1.8') }
t('another subnet is a different hub') { assert !H.isSelfHost('http://192.168.1.9', '192.168.1.8') }
t('no hub ip is never self') { assert !H.isSelfHost('http://192.168.1.8', null) }
t('empty webBase is never self') { assert !H.isSelfHost('', '192.168.1.8') }

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)
