// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for shared/oauth-self-enable.groovy and shared/github-version.groovy, run against
// the shared sources with the hub calls they make stubbed. scripts/shared_sync.groovy --check
// keeps the apps' copies identical to these sources.
//   groovy shared/tests/test_platform_blocks.groovy

here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

// Hub calls the blocks make, recorded in `calls`; `hub` holds what each one returns or throws.
STUBS = '''
IMPORT_URL_APP = "https://example.invalid/App.groovy"
void logDebug(m) { calls << "debug: ${m}".toString() }
void logError(m) { calls << "error: ${m}".toString() }
void logNet(m) { calls << "net: ${m}".toString() }
void refreshUpdateLabel() { calls << "label" }
long now() { hub.now }
void createAccessToken() {
    calls << "createAccessToken"
    if (!hub.oauthOn) throw new IllegalStateException("OAuth is not enabled for this App")
    state.accessToken = "t"
}
void httpGet(Map p, Closure c) {
    calls << "GET ${p.path}".toString()
    if (hub.getFails) throw new IOException("Connection refused")
    c([data: [version: hub.codeVersion]])
}
void httpPost(Map p, Closure c) {
    calls << "POST ${p.path} ${p.body.oauthEnabled} v${p.body.version}".toString()
    if (hub.postFails) throw new IOException("Read timed out")
    hub.oauthOn = true
    c([status: 200])
}
void asynchttpGet(String cb, Map p) { calls << "async ${cb} ${p.uri}".toString() }
'''

Script load(String block, Map hub) {
    Binding b = new Binding([calls: [], hub: hub, state: [:], app: [getAppTypeId: { -> hub.typeId }]])
    Script s = new GroovyShell(b).parse(STUBS + new File(here.parentFile, block).text)
    s.run()
    return s
}

// ── oauth-self-enable ──
Map hub = [oauthOn: true, typeId: 42, codeVersion: 7]
Script s = load('oauth-self-enable.groovy', hub)
check('token created when OAuth is on', s.checkOAuth(), true)
check('no loopback call when OAuth is on', s.binding.calls, ['createAccessToken'])
s.binding.calls.clear()
check('existing token returns at once', s.checkOAuth(), true)
check('existing token makes no call', s.binding.calls, [])

hub = [oauthOn: false, typeId: 42, codeVersion: 7]
s = load('oauth-self-enable.groovy', hub)
check('OAuth off: turned on, token created', s.checkOAuth(), true)
check('OAuth off: reads the version, saves with it, retries',
      s.binding.calls.findAll { !it.startsWith('debug') },
      ['createAccessToken', 'GET /app/ajax/code', 'POST /app/edit/update true v7', 'createAccessToken'])
check('OAuth off: token stored', s.binding.state.accessToken, 't')

hub = [oauthOn: false, typeId: null]
s = load('oauth-self-enable.groovy', hub)
check('no type id: fails', s.checkOAuth(), false)
check('no type id: says so', s.binding.calls.contains("error: Could not find this app's type id"), true)

hub = [oauthOn: false, typeId: 42, getFails: true]
s = load('oauth-self-enable.groovy', hub)
check('version read fails: fails', s.checkOAuth(), false)
check('version read fails: no save', s.binding.calls.any { it.startsWith('POST') }, false)
check('version read fails: logs the reason',
      s.binding.calls.contains('error: Could not read the app code version: Connection refused'), true)

hub = [oauthOn: false, typeId: 42, codeVersion: null]
s = load('oauth-self-enable.groovy', hub)
check('no version: fails without saving', [s.checkOAuth(), s.binding.calls.any { it.startsWith('POST') }], [false, false])

hub = [oauthOn: false, typeId: 42, codeVersion: 7, postFails: true]
s = load('oauth-self-enable.groovy', hub)
check('save fails: fails', s.checkOAuth(), false)
check('save fails: no second token attempt', s.binding.calls.count('createAccessToken'), 1)

// ── github-version ──
hub = [now: 10_000_000L]
s = load('github-version.groovy', hub)
check('first call returns null', s.checkGithubVersion(), null)
check('first call requests', s.binding.calls, ['async githubVersionCallback https://example.invalid/App.groovy'])
check('request time stored', s.binding.state.lastGithubVersionCheck, 10_000_000L)
s.binding.calls.clear()
hub.now += 3_599_999L
s.checkGithubVersion()
check('no request within the hour, even with no reply yet', s.binding.calls, [])
hub.now += 1L
s.checkGithubVersion()
check('request again after an hour', s.binding.calls.size(), 1)

s.binding.calls.clear()
s.githubVersionCallback(new Expando(hasError: { -> false }, status: 200, data: 'x\n@Field static final String CODE_VERSION = "1.2.3"\n'), null)
check('reply stores the version', s.binding.state.lastGithubVersion, '1.2.3')
check('reply refreshes the label', s.binding.calls, ['label'])
check('stored version returned', s.checkGithubVersion(), '1.2.3')

s.binding.calls.clear()
s.githubVersionCallback(new Expando(hasError: { -> false }, status: 200, data: 'no version here'), null)
check('reply without a version changes nothing', [s.binding.state.lastGithubVersion, s.binding.calls], ['1.2.3', []])

s.binding.calls.clear()
s.githubVersionCallback(new Expando(hasError: { -> true }, status: 408), null)
check('failed reply keeps the last version', s.binding.state.lastGithubVersion, '1.2.3')
check('failed reply is logged', s.binding.calls, ['net: GitHub version check failed: HTTP 408'])

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
