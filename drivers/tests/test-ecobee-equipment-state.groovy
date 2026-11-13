// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, extraction variant): Ecobee operating state from equipmentStatus (L-3) and PIN expiry (L-1).
// Run: groovy drivers/tests/test-ecobee-equipment-state.groovy   (no hub)

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
def h = new GroovyShell().parse(extract(text, "private static String operatingStateFromEquipment(") + "\n" +
        extract(text, "private static long pinExpiryMs(") + "\nreturn this\n").run()

println "L-3: equipmentStatus tokens"
["heatPump", "heatPump2", "heatPump3", "auxHeat1", "auxHeat2", "auxHeat3"].each { String t ->
    check "${t} is heating", h.operatingStateFromEquipment(t) == "heating"
}
["compCool1", "compCool2"].each { String t ->
    check "${t} is cooling", h.operatingStateFromEquipment(t) == "cooling"
}
check "fan alone is fan only", h.operatingStateFromEquipment("fan") == "fan only"
["humidifier", "dehumidifier", "ventilator", "economizer", "compHotWater", "auxHotWater"].each { String t ->
    check "${t} alone is idle", h.operatingStateFromEquipment(t) == "idle"
}
check "empty is idle", h.operatingStateFromEquipment("") == "idle"
check "null is idle", h.operatingStateFromEquipment(null) == "idle"
check "heatPump,auxHeat1,fan is heating", h.operatingStateFromEquipment("heatPump,auxHeat1,fan") == "heating"
check "auxHeat1,fan is heating (furnace)", h.operatingStateFromEquipment("auxHeat1,fan") == "heating"
check "compCool1,fan is cooling", h.operatingStateFromEquipment("compCool1,fan") == "cooling"
check "auxHotWater,fan is fan only", h.operatingStateFromEquipment("auxHotWater,fan") == "fan only"
check "compHotWater,auxHotWater is idle", h.operatingStateFromEquipment("compHotWater,auxHotWater") == "idle"
check "auxHotWater,auxHeat2 is heating", h.operatingStateFromEquipment("auxHotWater,auxHeat2") == "heating"
check "spaces after commas", h.operatingStateFromEquipment("heatPump, fan") == "heating"
check "token match is exact (not case folded substring)", h.operatingStateFromEquipment("heatPumpX") == "idle"

println "\nL-1: PIN expiry is minutes"
check "9 minutes is 540000 ms", h.pinExpiryMs(1000L, 9) == 1000L + 540000L
check "integer-valued BigDecimal accepted", h.pinExpiryMs(0L, 9G) == 540000L

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
