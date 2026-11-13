// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// The hub-start handler must re-arm the keep-alive timer: a one-shot runIn that fell due while
// the hub was down is gone. Extracts startHandler from the shipped app and runs it with stubs.

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
File app = new File(here.parentFile, System.getProperty('app') ?: 'SwitchedHeaterThermostat.groovy')
String src = app.text
int a = src.indexOf('void startHandler(evt) {')
int b = src.indexOf('\n}\n', a)
if (a < 0 || b < a) { println '[FAIL] startHandler not found'; System.exit(2) }
String body = src.substring(a, b + 3)
List calls = []
String script = body + '''
void checkVersion() { calls << 'checkVersion' }
void logInfo(String m) { calls << 'logInfo' }
void checkAll() { calls << 'checkAll' }
void scheduleKeepAlive() { calls << 'scheduleKeepAlive' }
startHandler(null)
'''
Binding bd = new Binding(calls: calls)
new GroovyShell(bd).evaluate(script)
boolean ok = calls.contains('scheduleKeepAlive')
println ok ? '[PASS] hub start re-arms the keep-alive' : "[FAIL] hub start does not re-arm the keep-alive (calls: ${calls})"
System.exit(ok ? 0 : 1)
