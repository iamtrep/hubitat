// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Fixture tests for scripts/shared_sync.groovy. Each case builds a throwaway fork root
// (shared/ blocks, manifest, one target file), runs the tool as a library and compares
// the result with the expected text.
//   groovy scripts/tests/test_shared_sync.groovy

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
File toolFile = new File(here.parentFile, 'shared_sync.groovy')

passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}\n--- expected:\n${want}\n--- got:\n${got}\n---" }
}

// Runs the tool in root with args; returns [code, out].
Map tool(File toolFile, File root, List<String> args) {
    StringWriter sw = new StringWriter()
    Binding b = new Binding([SHARED_SYNC_LIBRARY: true, out: new PrintWriter(sw, true)])
    Script s = new GroovyShell(b).parse(toolFile)
    s.run()
    int code = s.runTool((['--root', root.path] + args) as String[])
    return [code: code, out: sw.toString()]
}

File fork(Map<String, String> files) {
    File root = File.createTempDir('shared-sync-', '')
    files.each { String p, String t -> File f = new File(root, p); f.parentFile.mkdirs(); f.text = t }
    return root
}

String BLOCK = '''// block-version: 2
// requires-import: groovy.transform.Field

@Field static final Integer FLOOR = 30

// Doubles it.
Long twice(Long x) {
    return 2L * x
}

boolean quiet(Long a, Long b) { a > b }
'''

String MANIFEST = '{"apps/A.groovy": {"q": {"version": 1, "items": ["FLOOR", "twice", "quiet"]}}}'

String APP_HEAD = '''import groovy.transform.Field

@Field static final String V = "1"
@Field static final Integer FLOOR = 30
'''

// ── replace ──
String drifted = APP_HEAD + '''
void own() { }

// Doubles it (old note).
Long twice(Long x) {
    return x + x
}

boolean quiet(Long a, Long b) { a > b }
'''
String synced = APP_HEAD + '''
void own() { }

// Doubles it.
Long twice(Long x) {
    return 2L * x
}

boolean quiet(Long a, Long b) { a > b }
'''
File r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': drifted])
Map res = tool(toolFile, r, ['--check'])
check('check reports drift', res.code, 1)
check('check names the drifted function', res.out.contains('twice'), true)
check('check changes nothing', new File(r, 'apps/A.groovy').text, drifted)
res = tool(toolFile, r, ['sync'])
check('sync exit', res.code, 0)
check('sync replaces body and comment above', new File(r, 'apps/A.groovy').text, synced)
check('manifest version updated', new groovy.json.JsonSlurper().parse(new File(r, 'shared/manifest.json'))['apps/A.groovy']['q']['version'], 2)
res = tool(toolFile, r, ['sync'])
check('second sync changes nothing', new File(r, 'apps/A.groovy').text, synced)
check('check clean after sync', tool(toolFile, r, ['--check']).code, 0)

// ── section header above a function stays ──
String withHeader = APP_HEAD + '''
// ── Helpers ──
Long twice(Long x) { x }

boolean quiet(Long a, Long b) { a > b }
'''
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': withHeader])
tool(toolFile, r, ['sync'])
check('section header kept', new File(r, 'apps/A.groovy').text, APP_HEAD + '''
// ── Helpers ──
// Doubles it.
Long twice(Long x) {
    return 2L * x
}

boolean quiet(Long a, Long b) { a > b }
''')

// ── insert after the block's other functions ──
String partial = APP_HEAD + '''
boolean quiet(Long a, Long b) { a > b }

void tail() { }
'''
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': partial])
res = tool(toolFile, r, ['sync'])
check('insert note', res.out.contains('inserted twice()'), true)
check('insert after block function', new File(r, 'apps/A.groovy').text, APP_HEAD + '''
boolean quiet(Long a, Long b) { a > b }

// Doubles it.
Long twice(Long x) {
    return 2L * x
}

void tail() { }
''')

// ── insert into a file with none of the block: methods at the end, field after the last @Field ──
String bare = '''import groovy.transform.Field

@Field static final String V = "1"

void own() { }
'''
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': bare])
res = tool(toolFile, r, ['sync'])
check('insert into bare file exit', res.code, 0)
check('insert into bare file', new File(r, 'apps/A.groovy').text, '''import groovy.transform.Field

@Field static final String V = "1"
@Field static final Integer FLOOR = 30

void own() { }

// Doubles it.
Long twice(Long x) {
    return 2L * x
}

boolean quiet(Long a, Long b) { a > b }
''')
check('check clean after insert', tool(toolFile, r, ['--check']).code, 0)

// ── a missing import is an error, nothing written ──
String noImport = '''@Field static final String V = "1"

void own() { }
'''
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': noImport])
res = tool(toolFile, r, ['sync'])
check('missing import exit', res.code, 1)
check('missing import message', res.out.contains("needs 'import groovy.transform.Field'"), true)
check('missing import writes nothing', new File(r, 'apps/A.groovy').text, noImport)

// ── a name the block requires must be defined in the file ──
String NEEDS_BLOCK = '''// block-version: 1
// requires: logIt, LIMIT, twice

Long twice(Long x) { logIt "x"; 2L * x * LIMIT }
'''
String NEEDS_MANIFEST = '{"apps/A.groovy": {"n": {"version": 1, "items": ["twice"]}}}'
String noLog = '''import groovy.transform.Field

@Field static final Long LIMIT = 3

void own() { }
'''
r = fork(['shared/n.groovy': NEEDS_BLOCK, 'shared/manifest.json': NEEDS_MANIFEST, 'apps/A.groovy': noLog])
res = tool(toolFile, r, ['sync'])
check('missing required name exit', res.code, 1)
check('missing required name message', res.out.contains('block n needs logIt defined in the file'), true)
check('present required field not reported', res.out.contains('needs LIMIT'), false)
check('name the block provides not reported', res.out.contains('needs twice'), false)
check('missing required name writes nothing', new File(r, 'apps/A.groovy').text, noLog)
String withLog = noLog + '''
void logIt(String m) { }
'''
r = fork(['shared/n.groovy': NEEDS_BLOCK, 'shared/manifest.json': NEEDS_MANIFEST, 'apps/A.groovy': withLog])
check('required names present syncs', tool(toolFile, r, ['sync']).code, 0)
check('required names present check clean', tool(toolFile, r, ['--check']).code, 0)

// ── duplicate definition is an error ──
String dup = synced + '''
Long twice(Long x, Long y) { x }
'''
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': dup])
res = tool(toolFile, r, ['sync'])
check('duplicate exit', res.code, 1)
check('duplicate message', res.out.contains('twice defined more than once'), true)
check('duplicate writes nothing', new File(r, 'apps/A.groovy').text, dup)

// ── a function dropped from the block is reported, not deleted ──
String oldManifest = '{"apps/A.groovy": {"q": {"version": 1, "items": ["FLOOR", "twice", "quiet", "gone"]}}}'
String withGone = synced + '''
void gone() { }
'''
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': oldManifest, 'apps/A.groovy': withGone])
res = tool(toolFile, r, ['sync'])
check('dropped function reported', res.out.contains('gone left block q'), true)
check('dropped function kept', new File(r, 'apps/A.groovy').text, withGone)

// ── file that does not parse ──
String broken = 'void own( {\n'
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': broken])
res = tool(toolFile, r, ['sync'])
check('unparsable exit', res.code, 1)
check('unparsable message', res.out.contains('does not parse'), true)

// ── --names: unlisted file with a shared name warns; listed file is checked for drift ──
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': drifted,
          'apps/B.groovy': 'Long twice(Long x) { x }\nvoid other() { }\n'])
res = tool(toolFile, r, ['--names', new File(r, 'apps/B.groovy').path])
check('names warns on unlisted copy', res.code, 1)
check('names message', res.out.contains('B.groovy: twice is in shared block q'), true)
res = tool(toolFile, r, ['--names', new File(r, 'apps/A.groovy').path])
check('names on listed file reports drift', res.code, 1)
check('names on listed file says DRIFT', res.out.contains('DRIFT apps/A.groovy'), true)
res = tool(toolFile, r, ['--names', new File(r, 'shared/q.groovy').path])
check('names ignores the shared source', res.code, 0)

// ── unlisted files are left alone by sync ──
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST, 'apps/A.groovy': synced,
          'apps/B.groovy': 'Long twice(Long x) { x }\n'])
tool(toolFile, r, ['sync', new File(r, 'apps/B.groovy').path])
check('sync skips unlisted file', new File(r, 'apps/B.groovy').text, 'Long twice(Long x) { x }\n')

// ── report counts names across files and leaves out block names and lifecycle ──
r = fork(['shared/q.groovy': BLOCK, 'shared/manifest.json': MANIFEST,
          'apps/A.groovy': 'void fmt() { 1 }\nvoid installed() { }\nLong twice(Long x) { x }\n',
          'apps/B.groovy': 'void fmt() { 2 }\nvoid installed() { }\nLong twice(Long x) { x }\n',
          'apps/tests/C.groovy': 'void fmt() { 3 }\n'])
res = tool(toolFile, r, ['--report', '--min', '2'])
check('report lists fmt with 2 versions', (res.out =~ /(?m)^\s+2\s+2\s+fmt$/).find(), true)
check('report leaves out lifecycle', res.out.contains('installed'), false)
check('report leaves out block names', res.out.contains('twice'), false)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
