// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Keeps shared helper functions identical across the apps and drivers that use them.
// Hubitat code goes to the hub as one file per app or driver, so a shared helper lives
// as a copy inside every file that needs it. The canonical copy is in shared/<block>.groovy;
// shared/manifest.json lists which files take which blocks. The copies carry no markers:
// functions and @Field constants are found by name in the Groovy syntax tree.
//
//   groovy scripts/shared_sync.groovy sync [files…]      rewrite copies from shared/ (all listed files if none given)
//   groovy scripts/shared_sync.groovy --check [files…]   report drift, change nothing; exit 1 on drift
//   groovy scripts/shared_sync.groovy --names <files…>   warn about functions named like a shared one in files
//                                                         the manifest doesn't give that block; exit 1 on a warning
//   groovy scripts/shared_sync.groovy --report [--min N]  functions with the same name in N+ files (default 3)
//                                                         that no block covers: candidates for the next block
//   --root <dir>   fork root (default: the parent of this script's folder)
//
// Exit codes: 0 clean, 1 drift or warnings, 2 usage or parse errors.
// Runs under Groovy 2.4 and later. Library use (tests): bind SHARED_SYNC_LIBRARY = true,
// run the script, then call runTool(String[]) for an exit code; output goes to the binding's `out`.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

// Names the report leaves out: platform lifecycle and capability commands share a name, not a body.
REPORT_SKIP = ['installed', 'updated', 'uninstalled', 'initialize', 'mainPage', 'parse', 'configure',
               'refresh', 'on', 'off', 'poll', 'ping', 'push', 'hold', 'release', 'doubleTap',
               'appButtonHandler', 'deviceTypeUpdated', 'systemStartHandler', 'run', 'main'] as Set

// All output goes through say(): the binding's `out` when a test supplies one, else stdout.
void say(Object m) {
    def o = binding.variables.get('out')
    if (o != null) o.println(m) else System.out.println(m)
}

// ── Parsing ───────────────────────────────────────────────────────────

// Top-level items of a Groovy script: methods and @Field declarations, each with its line range
// (1-based, inclusive) including the comment lines directly above it. A comment line starting
// "// ──" is a section header and stays with the file.
List<Map> itemsOf(String text, String label) {
    CompilationUnit cu = new CompilationUnit()
    cu.addSource(label, text)
    cu.compile(Phases.CONVERSION)
    List<String> lines = text.split('\n', -1) as List
    List<Map> items = []
    cu.AST.modules.each { mod ->
        mod.classes.each { ClassNode c ->
            if (!c.isScript()) return
            c.methods.each { MethodNode m ->
                if (m.name in ['run', 'main'] || m.lineNumber < 1) return
                int start = m.lineNumber
                m.annotations.each { AnnotationNode a -> if (a.lineNumber > 0 && a.lineNumber < start) start = a.lineNumber }
                items << [kind: 'method', name: m.name, start: withComments(lines, start), end: m.lastLineNumber]
            }
            MethodNode run = c.getMethod('run', new org.codehaus.groovy.ast.Parameter[0])
            if (run?.code instanceof BlockStatement) {
                ((BlockStatement) run.code).statements.each { Statement st ->
                    if (!(st instanceof ExpressionStatement)) return
                    def e = ((ExpressionStatement) st).expression
                    if (!(e instanceof DeclarationExpression)) return
                    DeclarationExpression d = (DeclarationExpression) e
                    if (!d.annotations.any { it.classNode.nameWithoutPackage == 'Field' }) return
                    if (!(d.leftExpression instanceof VariableExpression)) return
                    int start = st.lineNumber
                    d.annotations.each { AnnotationNode a -> if (a.lineNumber > 0 && a.lineNumber < start) start = a.lineNumber }
                    items << [kind: 'field', name: ((VariableExpression) d.leftExpression).name,
                              start: withComments(lines, start), end: st.lastLineNumber]
                }
            }
        }
    }
    return items.sort { it.start }
}

int withComments(List<String> lines, int start) {
    int s = start
    while (s > 1) {
        String t = lines[s - 2].trim()
        if (!t.startsWith('//') || t.startsWith('// ──')) break
        s--
    }
    return s
}

String slice(List<String> lines, int start, int end) { lines.subList(start - 1, end).join('\n') }

String norm(String s) { s.split('\n', -1).collect { it.replaceAll(/\s+$/, '') }.join('\n').trim() }

// ── Blocks and manifest ───────────────────────────────────────────────

// A block file: header lines "// block-version: N", "// requires-import: a.b.C" and
// "// requires: name, name" (functions or @Field constants the host file must define), then its items.
Map loadBlock(File root, String name) {
    File f = new File(root, "shared/${name}.groovy")
    if (!f.exists()) throw new IllegalStateException("shared/${name}.groovy not found")
    String text = f.text
    def ver = (text =~ /(?m)^\/\/ block-version: (\d+)\s*$/)
    if (!ver.find()) throw new IllegalStateException("shared/${name}.groovy has no '// block-version: N' line")
    List<String> imports = []
    (text =~ /(?m)^\/\/ requires-import: (\S+)\s*$/).each { imports << it[1] }
    List<String> needs = []
    (text =~ /(?m)^\/\/ requires: (.+?)\s*$/).each { needs.addAll(it[1].split(/\s*,\s*/).findAll { n -> n } as List) }
    List<String> lines = text.split('\n', -1) as List
    List<Map> items = itemsOf(text, "shared/${name}.groovy").collect { it + [text: slice(lines, it.start, it.end)] }
    if (!items) throw new IllegalStateException("shared/${name}.groovy defines no function or @Field")
    return [name: name, version: ver.group(1) as int, imports: imports, needs: needs, items: items]
}

File manifestFile(File root) { new File(root, 'shared/manifest.json') }

Map loadManifest(File root) {
    File f = manifestFile(root)
    if (!f.exists()) return [:]
    return new JsonSlurper().parseText(f.text) as Map
}

void saveManifest(File root, Map m) {
    Map sorted = new TreeMap(m)
    sorted.each { k, v -> sorted[k] = new TreeMap(v as Map) }
    manifestFile(root).text = JsonOutput.prettyPrint(JsonOutput.toJson(sorted)) + '\n'
}

String rel(File root, String path) {
    File f = new File(path)
    if (!f.absolute) f = new File(System.getProperty('user.dir'), path)
    String r = root.canonicalPath, p = f.canonicalPath
    return p.startsWith(r + File.separator) ? p.substring(r.length() + 1) : null
}

// ── Sync ──────────────────────────────────────────────────────────────

// Applies every block listed for one file. Returns [text: newText, notes: [...], errors: [...]].
Map syncText(String text, String label, List<Map> blocks, Map entry) {
    List<String> notes = [], errors = []
    List<Map> have
    try { have = itemsOf(text, label) } catch (Exception e) { return [text: text, notes: notes, errors: ["${label}: does not parse: ${firstLine(e)}"]] }
    List<String> lines = text.split('\n', -1) as List
    List<Map> edits = []   // [start, end, text]; an insertion has end = start - 1
    blocks.each { Map b ->
        List<String> blockNames = b.items.collect { it.name }
        List<Map> found = have.findAll { it.name in blockNames }
        List<String> dup = found.countBy { it.name }.findAll { k, v -> v > 1 }.keySet() as List
        if (dup) { errors << "${label}: ${dup.join(', ')} defined more than once; block ${b.name} not applied".toString(); return }
        b.imports.each { imp ->
            if (!(text =~ /(?m)^import\s+${java.util.regex.Pattern.quote(imp)}\s*$/).find()) errors << "${label}: block ${b.name} needs 'import ${imp}'".toString()
        }
        (b.needs ?: []).each { String n ->
            if (!(n in blockNames) && !have.any { it.name == n }) errors << "${label}: block ${b.name} needs ${n} defined in the file".toString()
        }
        List<Map> foundMethods = found.findAll { it.kind == 'method' }
        int eof = lines.size() - (lines && lines[-1] == '' ? 1 : 0)
        int methodAnchor = foundMethods ? (foundMethods*.end).max() : eof
        List<Map> fieldsInFile = have.findAll { it.kind == 'field' }
        Integer fieldAnchor = fieldsInFile ? (fieldsInFile*.end).max() : lastImportLine(lines)
        List<String> newMethods = [], newFields = []
        b.items.each { Map it ->
            Map cur = found.find { f -> f.name == it.name }
            if (cur) {
                if (norm(slice(lines, cur.start, cur.end)) != norm(it.text)) edits << [start: cur.start, end: cur.end, text: it.text]
            } else if (it.kind == 'field') {
                newFields << it.text; notes << "${label}: inserted @Field ${it.name}".toString()
            } else {
                newMethods << it.text; notes << "${label}: inserted ${it.name}()".toString()
            }
        }
        if (newFields) edits << [start: fieldAnchor + 1, end: fieldAnchor, text: newFields.join('\n')]
        if (newMethods) edits << [start: methodAnchor + 1, end: methodAnchor, text: '\n' + newMethods.join('\n\n')]
        List<String> previous = ((entry?.get(b.name) as Map)?.items ?: []) as List
        (previous - blockNames).each { gone ->
            if (have.any { it.name == gone }) notes << "${label}: ${gone} left block ${b.name}; remove it by hand if nothing calls it".toString()
        }
    }
    if (errors) return [text: text, notes: notes, errors: errors]
    // Apply bottom-up so earlier line numbers stay valid; at one position, replacements before insertions.
    edits.sort { Map e -> [-(e.start as int), e.end < e.start ? 1 : 0] }.each { Map e ->
        List<String> repl = (e.text as String).split('\n', -1) as List
        List<String> head = lines.subList(0, e.start - 1), tail = lines.subList(e.end, lines.size())
        lines = head + repl + tail
    }
    return [text: lines.join('\n'), notes: notes, errors: errors]
}

int lastImportLine(List<String> lines) {
    int last = 0
    lines.eachWithIndex { String l, int i -> if (l.startsWith('import ')) last = i + 1 }
    return last
}

String firstLine(Exception e) { (e.message ?: e.toString()).split('\n').find { it.trim() } ?: e.toString() }

// ── Commands ──────────────────────────────────────────────────────────

int cmdSync(File root, List<String> files, boolean checkOnly) {
    Map manifest = loadManifest(root)
    List<String> targets = files ? files.collect { rel(root, it) } : (manifest.keySet() as List)
    Map<String, Map> blockCache = [:]
    int bad = 0
    boolean manifestChanged = false
    targets.each { String t ->
        if (t == null || !manifest.containsKey(t)) return   // not a listed file: nothing to sync or check
        File f = new File(root, t)
        if (!f.exists()) { say "ERROR ${t}: listed in shared/manifest.json but missing"; bad++; return }
        Map entry = manifest[t] as Map
        List<Map> blocks = []
        try {
            entry.keySet().each { String bn ->
                if (!blockCache.containsKey(bn)) blockCache[bn] = loadBlock(root, bn)
                blocks << blockCache[bn]
            }
        } catch (Exception e) { say "ERROR ${t}: ${firstLine(e)}"; bad++; return }
        String before = f.text
        Map r = syncText(before, t, blocks, entry)
        r.errors.each { say "ERROR ${it}" }
        if (r.errors) { bad++; return }
        boolean behind = blocks.any { b -> ((entry[b.name] as Map)?.version as Integer) != b.version }
        if (checkOnly) {
            if (r.text != before) {
                List<String> drifted = driftedNames(before, r.text, t, blocks)
                say "DRIFT ${t}: not the same as shared/: ${drifted.join(', ')} (edit the shared file and run sync, or run sync to restore the shared copy)"
                bad++
            } else if (behind) {
                say "DRIFT ${t}: manifest version behind shared/ (run sync)"
                bad++
            }
        } else {
            r.notes.each { say "NOTE ${it}" }
            if (r.text != before) { f.text = r.text; say "SYNC ${t}" }
            blocks.each { b ->
                Map want = [version: b.version, items: b.items.collect { it.name }]
                if (entry[b.name] != want) { entry[b.name] = want; manifestChanged = true }
            }
        }
    }
    if (manifestChanged) saveManifest(root, manifest)
    return bad ? 1 : 0
}

// Which block items a sync would change or add in this file.
List<String> driftedNames(String before, String after, String label, List<Map> blocks) {
    List<String> out = []
    List<Map> have = itemsOf(before, label)
    List<String> lines = before.split('\n', -1) as List
    blocks.each { b ->
        b.items.each { it ->
            Map cur = have.find { h -> h.name == it.name }
            if (!cur) out << "${it.name} (missing)".toString()
            else if (norm(slice(lines, cur.start, cur.end)) != norm(it.text)) out << it.name.toString()
        }
    }
    return out
}

int cmdNames(File root, List<String> files) {
    Map manifest = loadManifest(root)
    if (!files) { say 'usage: --names <files…>'; return 2 }
    Map<String, String> owner = [:]   // item name -> block
    File dir = new File(root, 'shared')
    (dir.listFiles() ?: []).findAll { it.name.endsWith('.groovy') }.each { File bf ->
        String bn = bf.name - '.groovy'
        try { loadBlock(root, bn).items.each { owner[it.name] = bn } } catch (Exception e) { say "ERROR shared/${bf.name}: ${firstLine(e)}" }
    }
    int warned = 0
    files.each { String p ->
        String t = rel(root, p)
        if (t == null || t.startsWith('shared/') || !t.endsWith('.groovy')) return
        File f = new File(root, t)
        if (!f.exists()) return
        List<Map> have
        try { have = itemsOf(f.text, t) } catch (Exception e) { return }   // the parse-check reports syntax errors
        Set<String> listed = ((manifest[t] as Map)?.keySet() ?: []) as Set
        have.findAll { owner.containsKey(it.name) && !(owner[it.name] in listed) }.each {
            say "WARN ${t}: ${it.name} is in shared block ${owner[it.name]}; add \"${owner[it.name]}\" for this file to shared/manifest.json and run sync, or rename it if it is not the same helper"
            warned++
        }
    }
    if (files.any { manifest.containsKey(rel(root, it)) }) {
        if (cmdSync(root, files.findAll { manifest.containsKey(rel(root, it)) }, true) != 0) warned++
    }
    return warned ? 1 : 0
}

int cmdReport(File root, int min) {
    Map manifest = loadManifest(root)
    Set<String> covered = [] as Set
    File dir = new File(root, 'shared')
    (dir.listFiles() ?: []).findAll { it.name.endsWith('.groovy') }.each { File bf ->
        try { loadBlock(root, bf.name - '.groovy').items.each { covered << it.name } } catch (Exception ignored) { }
    }
    Map<String, List<Map>> byName = [:].withDefault { [] }
    int scanned = 0
    groovyFiles(root).each { File f ->
        String t = f.path.substring(root.path.length() + 1)
        String text = f.text
        List<Map> items
        try { items = itemsOf(text, t) } catch (Exception e) { say "SKIP ${t}: does not parse"; return }
        scanned++
        List<String> lines = text.split('\n', -1) as List
        items.findAll { it.kind == 'method' }.each { byName[it.name] << [file: t, body: norm(slice(lines, it.start, it.end).replaceAll(/(?m)^\s*\/\/.*$/, ''))] }
    }
    List<Map> rows = []
    byName.each { String n, List<Map> occ ->
        int files = (occ*.file).unique().size()
        if (files >= min && !(n in REPORT_SKIP) && !(n in covered)) rows << [name: n, files: files, versions: (occ*.body).unique().size()]
    }
    say "${scanned} files scanned; ${rows.size()} names in ${min}+ files, not in a block (lifecycle names left out)"
    say String.format('%5s %8s  %s', 'files', 'versions', 'name')
    rows.sort { a, b -> b.files <=> a.files ?: a.name <=> b.name }.each { say String.format('%5d %8d  %s', it.files, it.versions, it.name) }
    return 0
}

// Every .groovy file under the root that ships to a hub: no tests, tools, shared sources or scratch.
List<File> groovyFiles(File root) {
    Set<String> skipDirs = ['tests', '.git', '.claude', 'backups', 'tmp', 'node_modules', 'shared', 'scripts'] as Set
    List<File> out = []
    Closure walk
    walk = { File d ->
        (d.listFiles() ?: []).sort { it.name }.each { File f ->
            if (f.isDirectory()) { if (!(f.name in skipDirs)) walk(f) }
            else if (f.name.endsWith('.groovy')) out << f
        }
    }
    walk(root)
    return out
}

int runTool(String[] argv) {
    List<String> a = argv as List
    File root = null
    int ri = a.indexOf('--root')
    if (ri >= 0) {
        if (ri + 1 >= a.size()) { say 'usage: --root <dir>'; return 2 }
        root = new File(a[ri + 1]).canonicalFile; a = a.take(ri) + a.drop(ri + 2)
    }
    if (root == null) {
        File here = new File(getClass().protectionDomain.codeSource.location.toURI()).canonicalFile
        root = here.parentFile.parentFile
    }
    if (!a) { say 'usage: sync [files…] | --check [files…|--all] | --names <files…> | --report [--min N]'; return 2 }
    String cmd = a[0]
    List<String> rest = a.drop(1)
    try {
        switch (cmd) {
            case 'sync': return cmdSync(root, rest, false)
            case '--check': return cmdSync(root, rest == ['--all'] ? [] : rest, true)
            case '--names': return cmdNames(root, rest)
            case '--report':
                int mi = rest.indexOf('--min')
                return cmdReport(root, mi >= 0 && mi + 1 < rest.size() ? rest[mi + 1] as int : 3)
            default: say "unknown command ${cmd}"; return 2
        }
    } catch (Exception e) {
        say "ERROR ${firstLine(e)}"
        return 2
    }
}

if (!binding.variables.containsKey('SHARED_SYNC_LIBRARY')) System.exit(runTool(args))
