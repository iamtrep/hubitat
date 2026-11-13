// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant): Ecobee schedule move range check.
// Run: groovy drivers/tests/test-ecobee-schedule-move.groovy   (no hub)

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

File src = System.getenv("SRC_OVERRIDE") ? new File(System.getenv("SRC_OVERRIDE")) : new File(scriptFile.parentFile.parentFile, "EcobeeCompanion.groovy")
String text = src.text
def h = new GroovyShell().parse(extract(text, "private static String scheduleMoveError(") + "\nreturn this\n").run()

// 30-min blocks: sleep 00:00-06:00 (0-11), home 06:00-08:00 (12-15), away 08:00-17:00 (16-33), home 17:00-24:00 (34-47)
List day = (["sleep"] * 12) + (["home"] * 4) + (["away"] * 18) + (["home"] * 14)

println "L-8: moving a comfort run start"
check "later, inside the run (06:00 to 07:30)", h.scheduleMoveError(day, 12, 15, "home") == null
check "later, to the old end (08:00) would remove the run", h.scheduleMoveError(day, 12, 16, "home") != null
check "later, past the end (09:00) would overwrite Away", h.scheduleMoveError(day, 12, 18, "home") != null
check "earlier, inside the previous run (05:00)", h.scheduleMoveError(day, 12, 10, "home") == null
check "earlier, to the previous run start (00:00) would remove it", h.scheduleMoveError(day, 12, 0, "home") != null
check "earlier, past the start of Away (16:00 back to 06:00)", h.scheduleMoveError(day, 34, 12, "home") != null
check "earlier by one block", h.scheduleMoveError(day, 34, 33, "home") == null
check "last run moves later inside itself", h.scheduleMoveError(day, 34, 40, "home") == null
check "last run moves later to the end of the day is rejected", h.scheduleMoveError(day, 34, 48, "home") != null

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
