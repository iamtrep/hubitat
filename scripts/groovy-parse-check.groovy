#!/usr/bin/env groovy
/*
 * groovy-parse-check.groovy — fast local syntax check for Hubitat Groovy files.
 *
 * Hubitat drivers/apps can't be compiled locally: they reference sandbox APIs
 * (httpPost, sendEvent, now(), the metadata/definition DSL) that aren't on the
 * classpath, so `groovyc` fails on unresolved types. Parsing only through the
 * CONVERSION phase (parse + AST build, no type/import resolution) is the one
 * cheap check that works — it catches brace/quote/expression syntax errors
 * before the code is pushed to a hub.
 *
 * Scope: SYNTAX ONLY. It does NOT catch type errors, undefined methods, or any
 * runtime/semantic bug. Use behaviour tests for those.
 *
 * Usage:
 *   groovy scripts/groovy-parse-check.groovy [path ...]
 *     - no args        -> recursively scan the current directory for *.groovy
 *     - file path(s)   -> check exactly those files
 *     - directory(s)   -> recursively scan them for *.groovy
 *   Exit code 0 if all parse, 1 if any file has a syntax error.
 */

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

def excludedDir = { String p -> p.contains('/.git/') || p.contains('/.claude/') }

List<File> targets = []
def collectFrom = { File dir ->
    dir.eachFileRecurse { f ->
        if (f.isFile() && f.name.endsWith('.groovy') && !excludedDir(f.path)) targets << f
    }
}

if (args.length == 0) {
    collectFrom(new File('.'))
} else {
    args.each { String a ->
        File f = new File(a)
        if (!f.exists())            { System.err.println "skip (not found): $a" }
        else if (f.isDirectory())   { collectFrom(f) }
        else if (a.endsWith('.groovy')) { targets << f }   // explicit file: check even if under an excluded dir
        else                        { System.err.println "skip (not .groovy): $a" }
    }
}

targets = targets.unique { it.canonicalPath }.sort { it.path }

if (targets.isEmpty()) {
    println "groovy-parse-check: no .groovy files to check"
    System.exit(0)
}

int failed = 0
targets.each { File f ->
    try {
        def cu = new CompilationUnit()
        cu.addSource(f)
        cu.compile(Phases.CONVERSION)
    } catch (Throwable t) {
        failed++
        System.err.println "FAIL  ${f.path}"
        (t.message ?: t.toString()).readLines().take(5).each { System.err.println "      ${it}" }
    }
}

println "groovy-parse-check: ${targets.size()} file(s) checked, ${failed} failed"
System.exit(failed == 0 ? 0 : 1)
