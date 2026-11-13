// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads configInt() and configDecimal() from the shipped VisiblAirSensorC.groovy.
// A configuration value that is missing or not numeric gives null, never an exception, so the
// rest of the sensor's update still runs.
// Run: groovy integrations/visiblair/tests/test-config-values.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../VisiblAirSensorC.groovy')
String text = src.text
StringBuilder body = new StringBuilder()
['static Integer configInt', 'static BigDecimal configDecimal'].each { String sig ->
    int from = text.indexOf(sig)
    assert from > 0 : sig + ' not found in ' + src
    int to = text.indexOf('\n}\n', from)
    body.append(text.substring(from, to + 3)).append('\n')
}
def C = new GroovyShell().parse("class C {\n${body}\n}\nreturn C").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('null gives null') { assert C.configInt(null) == null && C.configDecimal(null) == null }
t('integer') { assert C.configInt(300) == 300 }
t('numeric string') { assert C.configInt('300') == 300 && C.configInt(' 12 ') == 12 }
t('decimal string to int drops the fraction') { assert C.configInt('12.5') == 12 }
t('decimal kept') { assert C.configDecimal('-1.5') == -1.5 && C.configDecimal(0.5d) == 0.5 }
t('not numeric gives null') { assert C.configInt('n/a') == null && C.configDecimal('') == null }
t('the shipped update uses no bare cast on a config value') { assert !(text =~ /value as int|value as BigDecimal/) }

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
