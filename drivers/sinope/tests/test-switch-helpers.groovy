// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant) for the SW2500ZB and DM2500ZB pure helpers:
// brace-extracts them from the shipped drivers and runs them under plain Groovy.
//
// Run: groovy drivers/sinope/tests/test-switch-helpers.groovy   (no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File dir = scriptFile.parentFile.parentFile

String extract(String text, String signature) {
    int start = text.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = text.indexOf('{', start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        if (text[i] == '{') depth++
        else if (text[i] == '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

["Sinope_SW2500ZB.groovy", "Sinope_DM2500ZB.groovy"].each { String name ->
    println name
    String text = new File(dir, name).text
    List<String> sigs = ['private static int hexToSignedInt16(String hex) {',
                         'private static String percentToHex(Integer percentValue) {']
    if (name.contains("DM")) sigs << 'private static int fadeToRate(BigDecimal seconds) {'
    def h = new GroovyShell().parse(sigs.collect { extract(text, it) }.join("\n") + "\nreturn this\n").run()

    check 'temperature: 0x0014 is 20', h.hexToSignedInt16("0014") == 20
    check 'temperature: FFF8 is -8', h.hexToSignedInt16("FFF8") == -8
    check 'temperature: 7FFF stays positive', h.hexToSignedInt16("7FFF") == 32767
    check 'temperature: 8000 is -32768', h.hexToSignedInt16("8000") == -32768
    check 'intensity 5 % is two digits', h.percentToHex(5) == "0c"
    check 'intensity 0 % is 00', h.percentToHex(0) == "00"
    check 'intensity 100 % is ff', h.percentToHex(100) == "ff"
    check 'intensity 48 % unchanged (7a)', h.percentToHex(48) == "7a"
    check 'every intensity is two digits', (0..100).every { h.percentToHex(it).length() == 2 }
    if (name.contains("DM")) {
        check 'fade 10 s is rate 100', h.fadeToRate(10G) == 100
        check 'fade 0 is rate 0', h.fadeToRate(0G) == 0
        check 'fade 1.5 s is rate 15', h.fadeToRate(1.5G) == 15
        check 'fade rate is an Integer', h.fadeToRate(3G) instanceof Integer
    }
}
println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
