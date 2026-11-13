// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Source check: the app keeps one history file under a constant name, so its definition() must be singleInstance.
// Run: groovy apps/tests/test-battery-change-logger-instance.groovy      (no hub)
String text = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../BatteryChangeLogger.groovy').text
int from = text.indexOf('definition(')
assert from > 0 : 'definition( not found'
String defBlock = text.substring(from, text.indexOf('\n)\n', from))
boolean constantFile = text =~ /HISTORY_FILE\s*=\s*"[^$"]+"/
boolean single = defBlock =~ /(?m)^\s*singleInstance:\s*true/
println((constantFile && single ? '  ok   ' : '  FAIL ') + 'constant history file requires singleInstance: true')
System.exit(constantFile && single ? 0 : 1)
