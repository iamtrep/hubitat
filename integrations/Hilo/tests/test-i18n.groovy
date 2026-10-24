// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the interface text of the Hilo app and drivers: in
// each file the English and French catalogs hold the same keys with the same {placeholders},
// and every key the code names is in the catalog. No hub needed.
//
// Run: groovy integrations/Hilo/tests/test-i18n.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File dir = scriptFile.parentFile.parentFile

int pass = 0, fail = 0
def check = { String name, boolean cond, String detail = '' ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}${detail ? ': ' + detail : ''}" }
}
def placeholders = { String s -> (s =~ /\{(\w+)\}/).collect { it[1] }.toSet() }

// minKeys: how many keys the code names at least, so a broken pattern can't pass.
[['HiloManager.groovy', 20], ['HiloEnergyMeter.groovy', 8], ['HiloWinterCredit.groovy', 2]].each { String file, int minKeys ->
    println file
    String src = new File(dir, file).text
    String head = '@Field static final Map<String, Map<String, String>> I18N = '
    int start = src.indexOf(head)
    int end = src.indexOf('] // end I18N', start)
    check('has a catalog', start >= 0 && end > start)
    if (start < 0 || end < start) return
    Map<String, Map<String, String>> cat = new GroovyShell().evaluate(src.substring(start + head.length(), end + 1)) as Map

    check('English and French are the two languages', cat.keySet() == ['en', 'fr'] as Set, cat.keySet().toString())
    Set en = cat.en.keySet(), fr = cat.fr.keySet()
    check('French has every English key', (en - fr).isEmpty(), (en - fr).toString())
    check('English has every French key', (fr - en).isEmpty(), (fr - en).toString())
    Set mismatched = en.intersect(fr).findAll { placeholders(cat.en[it]) != placeholders(cat.fr[it]) }
    check('each key has the same placeholders in both', mismatched.isEmpty(), mismatched.toString())
    check('no text is empty', cat.values().every { m -> m.values().every { it?.trim() } })

    // Quoted literals with a catalog prefix ("sec.", "err.", ...) outside the catalog are keys.
    String code = src.substring(0, start) + src.substring(end)
    Set prefixes = en.collect { it.substring(0, it.indexOf('.')) }.toSet()
    Set used = (code =~ /["']([a-z]+\.[A-Za-z]+)["']/).collect { it[1] }.findAll { it.substring(0, it.indexOf('.')) in prefixes }.toSet()
    check("the code names at least ${minKeys} keys", used.size() >= minKeys, "${used.size()}")
    check('every key the code names is in the catalog', (used - en).isEmpty(), (used - en).toString())
}

println "\n=== ${pass}/${pass + fail} passed ==="
System.exit(fail == 0 ? 0 : 1)
