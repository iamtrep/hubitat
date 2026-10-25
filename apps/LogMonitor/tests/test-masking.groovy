// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the masking in LogMonitor.groovy (secrets always, private details per filter).
// Run: groovy apps/LogMonitor/tests/test-masking.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../LogMonitor.groovy').text

int from = src.indexOf('// Masking\n')
int to = src.indexOf('// End of masking')
assert from > 0 && to > from : 'masking section not found'
String code = src.substring(from, to).replaceAll(/(?m)^@Field /, '')
def M = new GroovyShell().parse("import java.util.regex.Matcher\nimport java.util.regex.Pattern\nclass M {\n${code}\n}\nreturn M").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

String B = '•' * 8
String D = '•'
// Test-only values, none of them real.
String TOKEN = '3f2a9c1e-7b4d-4e8f-a1b2-c3d4e5f6a7b8'
String KEY = 'k9Xq2LmP7vR4tY8wZ3nB'
String JWTV = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkw.c2lnbmF0dXJlMTIzNDU2'

// Every secret line: the raw value must be gone, and the shown form must be there.
[
    ['Maker API URL',          "Testing URL is http://192.168.7.86/apps/api/12/devices/all?access_token=${TOKEN}", TOKEN, "access_token=${B}a7b8"],
    ['cloud URL path + query', "GET (https://cloud.hubitat.com/api/${TOKEN}/apps/1/trigger?access_token=${KEY})", KEY, "access_token=${B}Z3nB)"],
    ['URL user:pass',          'fetch http://admin:hunter2pass@192.168.7.9/status', 'hunter2pass', "admin:${B}@192.168.7.9"],
    ['URL fragment token',     "redirect https://example.com/cb#access_token=${KEY}&state=x", KEY, "#access_token=${B}Z3nB&state=x"],
    ['webhook path key',       "POST https://hooks.example.com/services/T0001/B0002/${KEY}", KEY, "/services/T0001/B0002/${B}Z3nB"],
    ['Bearer header',          "headers:[Authorization:Bearer ${KEY}]", KEY, "Bearer ${B}Z3nB]"],
    ['Basic header',           '{"headers":{"Authorization":"Basic YWRtaW46aHVudGVyMg=="}}', 'YWRtaW46aHVudGVyMg', "Basic ${B}\"}}"],
    ['JSON key',               "{\"apiKey\":\"${KEY}\",\"user\":\"bob\"}", KEY, "\"apiKey\":\"${B}Z3nB\",\"user\":\"bob\""],
    ['Groovy map',             "settings: [password:hunter2pass, username:bob]", 'hunter2pass', "password:${B}, username:bob"],
    ['quoted kwarg',           "login(user: 'bob', password: 'hunter2pass')", 'hunter2pass', "password: '${B}'"],
    ['short PIN',              'unlock with pin: 4321', '4321', "pin: ${B}"],
    ['refresh token',          "got refresh_token=${KEY} expires_in=3600", KEY, "refresh_token=${B}Z3nB expires_in=3600"],
    ['JWT',                    "jwt ${JWTV}", JWTV, "jwt ${B}NDU2"],
    ['bare UUID',              "token is ${TOKEN}", TOKEN, "token is ${B}a7b8"],
    ['private key body',       '-----BEGIN PRIVATE KEY----- MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw -----END PRIVATE KEY-----', 'MIIEvQIBADANBgkqhkiG9w0', "KEY----- ${B} -----END"],
    ['URISyntaxException',     "Illegal character in query at index 40: https://h.example/x?token=${KEY} y", KEY, "token=${B}Z3nB"],
].each { List c ->
    t("secret: ${c[0]}", {
        String out = M.maskSecrets(c[1] as String)
        assert !out.contains(c[2] as String) : out
        assert out.contains(c[3] as String) : out
    })
}

// Lines that must come through unchanged.
[
    'Kitchen Light was turned on [physical]',
    'tokenExpiry: 2026-10-25T10:00:00Z, authUrl: https://example.com/oauth',
    'token: settings.apiKey',
    'password: null, passwordSet: true',
    'Authorization: Bearer',
    'http://192.168.7.86/apps/api/12/devices/all',
    'GET /local/hub_inspector_ui.html done',
    'apiKey: ${settings.key}',
].each { String line ->
    t("unchanged: ${line}", { assert M.maskSecrets(line) == line })
}

t('mask is idempotent', {
    String once = M.maskSecrets("x?access_token=${TOKEN} and password=hunter2pass")
    assert M.maskSecrets(once) == once
})
t('null and empty pass through', { assert M.maskSecrets(null) == null && M.maskSecrets('') == '' })
t('maskUrl keeps host and plain params', {
    assert M.maskUrl("https://maker.example.com/api/x?id=5&apiKey=${KEY}&on=true") == "https://maker.example.com/api/x?id=5&apiKey=${B}Z3nB&on=true"
})
t('maskUrl: file name in path not masked', { assert M.maskUrl('http://h/local/backup_2026_10_25.csv') == 'http://h/local/backup_2026_10_25.csv' })

// Private details.
[
    ['private IPv4',  'LAN host 192.168.7.40 replied',          '192.168.7.40',  "192.${D}.${D}.40"],
    ['public IPv4',   'cloud 161.216.10.20 replied',            '161.216.10.20', "161.${D}.${D}.${D}"],
    ['hex IPv4',      'desc=[mac:null, ip:c0a80756, port:97b4]', 'c0a80756',     "192.${D}.${D}.86"],
    ['MAC',           'id:34:E1:D1:81:E6:18 joined',            '81:E6',         "34:E1:D1:${D * 2}:${D * 2}:18"],
    ['EUI-64 packed', 'zigbee 00124B0012345678 joined',         '0012345678',    "00:12:4B:${D * 2}:${D * 2}:${D * 2}:${D * 2}:78"],
    ['email',         'mail alice@example.com',                 'alice',         "a${D * 3}@example.com"],
    ['phone',         'call 514-555-0199',                      '555-0199',      "${D * 8}99"],
    ['coordinates',   'station near (45.5123, -73.6456)',       '45.5123',       '45.5, -73.6'],
    ['serial',        'serialNumber: ABCD12345678',             'ABCD12345678',  "${D * 4}5678"],
    ['SSID',          'ssid: MyHomeNet',                        'MyHomeNet',     "ssid: ${B}"],
    ['Z-Wave home id','homeId: 0xC1D2E3F4',                     'C1D2E3F4',      "homeId: ${B}"],
    ['mDNS host',     'found tv-salon.local on port 8009',      'tv-salon',      "${D * 4}.local"],
    ['ULA IPv6',      'v6 fd12:3456:789a::1 up',                'fd12:3456',     "fd12:${D * 3}:1"],
].each { List c ->
    t("private: ${c[0]}", {
        String out = M.maskIdentifiers(c[1] as String)
        assert !out.contains(c[2] as String) : out
        assert out.contains(c[3] as String) : out
    })
}
[
    'build 2.5.2.129 installed',
    'loopback 127.0.0.1 and docs 192.0.2.5',
    'at 14:03:22.123 the switch turned on',
    "already masked ${B}a7b8",
].each { String line ->
    t("private unchanged: ${line}", { assert M.maskIdentifiers(line) == line })
}

// Name aliases.
t('alias is stable and salted', {
    String a = M.newAlias('Kitchen Light', 'salt-1', [] as Set)
    assert a ==~ /[a-z]+-[a-z]+/
    assert M.newAlias('Kitchen Light', 'salt-1', [] as Set) == a
    assert ['salt-2', 'salt-3', 'salt-4'].any { M.newAlias('Kitchen Light', it, [] as Set) != a }
})
t('alias collision walks to a free one', {
    String a = M.newAlias('X', 's', [] as Set)
    assert M.newAlias('X', 's', [a] as Set) != a
})
t('alias matches Hub Inspector for the same salt', {
    // Obf.setSalt('salt-1'); Obf.alias('Kitchen Light') in hub_inspector_ui.html gives tame-ferret.
    assert M.newAlias('Kitchen Light', 'salt-1', [] as Set) == 'tame-ferret'
})
t('names replaced as whole words, longest first', {
    Map al = ['Kitchen': 'amber-otter', 'Kitchen Light': 'brave-heron', 'Rule Machine': 'Rule Machine', 'TV': 'calm-iris']
    List order = M.orderNames(al)
    assert order == ['Kitchen Light', 'Kitchen']
    assert M.aliasNames('Kitchen Light on; Kitchen motion; Kitchenette; Rule Machine ran; TV', order, al) ==
        'brave-heron on; amber-otter motion; Kitchenette; Rule Machine ran; TV'
})

println "\n${pass} passed, ${fail} failed"
if (fail) System.exit(1)
