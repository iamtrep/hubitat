// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant): IPReachabilitySensor retry threshold from the preference.
// Run: groovy drivers/tests/test-ipreachability-retry-threshold.groovy   (no hub)

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
String extract(String text, String signature) {
    int start = text.indexOf(signature)
    assert start >= 0 : "signature not found in shipped source: ${signature}"
    int open = text.indexOf("{", start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        if (text[i] == "{") depth++
        else if (text[i] == "}") { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}
int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

File src = System.getenv("SRC_OVERRIDE") ? new File(System.getenv("SRC_OVERRIDE")) : new File(scriptFile.parentFile.parentFile, "IPReachabilitySensor.groovy")
String text = src.text
String prelude = "@groovy.transform.Field static final int DEFAULT_RETRY_THRESHOLD = 3\n"
def h = new GroovyShell().parse(prelude + extract(text, "private static int resolveRetryThreshold(") + "\nreturn this\n").run()

println "L-6: Retry Threshold preference"
check "0 is honoured, not turned into 3", h.resolveRetryThreshold(0) == 0
check "6 is honoured", h.resolveRetryThreshold(6) == 6
check "BigDecimal 5 (UI number) is honoured", h.resolveRetryThreshold(5G) == 5
check "unset falls back to 3", h.resolveRetryThreshold(null) == 3

println "wiring"
String upd = extract(text, "void updated() {")
check "updated() refreshes state.retryThreshold", upd.contains("state.retryThreshold = resolveRetryThreshold(settings.retryThreshold)")

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
