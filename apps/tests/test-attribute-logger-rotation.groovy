// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for Attribute Logger Child log rotation helpers.
// Brace-extracts the pure helpers from the shipped AttributeLoggerChild.groovy and runs
// them under local groovy. No hub needed.
//
// Run: groovy apps/tests/test-attribute-logger-rotation.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'AttributeLoggerChild.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String extract(String src, String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = src.indexOf('{', start)
    int depth = 0
    for (int i = open; i < src.length(); i++) {
        if (src[i] == '{') depth++
        else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

List<String> sigs = [
    'Long rowSeconds(String content, int start) {',
    'int firstRowIndex(String content) {',
    'int findCut(String content, int firstRow, long cutoff) {',
    'String archiveName(String logFileName, String firstDay, String lastDay, Collection<String> existing) {',
    'String dayStamp(long seconds, TimeZone tz) {',
]
def h = new GroovyShell().evaluate(sigs.collect { extract(src, it) }.join("\n") +
    "\n[row: this.&rowSeconds, first: this.&firstRowIndex, cut: this.&findCut, name: this.&archiveName, day: this.&dayStamp]")

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

HEADER = "timestamp,illuminance\n"   // binding variable: visible inside the methods below
String csv(List<Long> ts, String header = HEADER, String eol = "\n") {
    header + ts.collect { "${it},${it % 97}${eol}" }.join('')
}
// Line start of the first row with ts >= cutoff, by linear scan (reference for sorted input).
int linearCut(String c, int firstRow, long cutoff) {
    int pos = firstRow
    while (pos < c.length()) {
        int comma = c.indexOf(',', pos)
        int nl = c.indexOf('\n', pos)
        int end = [comma, nl].findAll { it >= 0 }.min() ?: c.length()
        if (new BigDecimal(c.substring(pos, end).trim()).longValue() >= cutoff) return pos
        pos = nl < 0 ? c.length() : nl + 1
    }
    return c.length()
}
List<String> rowsOf(String c, int from, int to) { c.substring(from, to).readLines().findAll { it } }
boolean isLineStart(String c, int i, int firstRow) { i == firstRow || i == c.length() || c[i - 1] == '\n' }

println 'rowSeconds'
check('integer timestamp',          h.row("1791164812,7\n", 0) == 1791164812L)
check('decimal timestamp',          h.row("1739964898.503,inactive,off,1\n", 0) == 1739964898L)
check('row with no comma',          h.row("1791164812\n", 0) == 1791164812L)
check('header is not a row',        h.row(HEADER, 0) == null)
check('start past the end',         h.row("1791164812,7\n", 50) == null)
check('CRLF row',                   h.row("1791164812\r\n", 0) == 1791164812L)
check('row in the middle',          h.row("1,a\n2,b\n", 4) == 2L)

println 'firstRowIndex'
check('with header',                h.first(HEADER + "1,2\n") == HEADER.length())
check('headerless',                 h.first("1791164812,7\n") == 0)
check('header-only, no newline',    h.first("timestamp,illuminance") == "timestamp,illuminance".length())
check('empty',                      h.first("") == 0)

println 'findCut'
List<Long> hourly = (0..<100).collect { 1790000000L + it * 3600L }
String c = csv(hourly)
int fr = h.first(c)
check('cut at row 50',              rowsOf(c, fr, h.cut(c, fr, hourly[50])).size() == 50)
check('cutoff before all rows',     h.cut(c, fr, 0L) == fr)
check('cutoff after all rows',      h.cut(c, fr, 9999999999L) == c.length())
check('cutoff between rows',        h.cut(c, fr, hourly[10] + 1) == linearCut(c, fr, hourly[10] + 1))
List<Long> dup = [10L, 20L, 30L, 30L, 30L, 40L]
String cd = csv(dup)
check('equal timestamps: first of them', rowsOf(cd, h.first(cd), h.cut(cd, h.first(cd), 30L)) == ['10,10', '20,20'])
String hl = csv(hourly, '')
check('headerless',                 h.cut(hl, 0, hourly[50]) == linearCut(hl, 0, hourly[50]))
String one = csv([5L])
check('single row kept',            h.cut(one, h.first(one), 5L) == h.first(one))
check('single row dropped',         h.cut(one, h.first(one), 6L) == one.length())
String dec = HEADER + hourly.collect { "${it}.503,1\n" }.join('')
check('decimal timestamps',         h.cut(dec, h.first(dec), hourly[50]) == linearCut(dec, h.first(dec), hourly[50]))
check('header-only',                h.cut(HEADER, h.first(HEADER), 5L) == HEADER.length())
check('empty',                      h.cut("", 0, 5L) == 0)
String noNl = c.substring(0, c.length() - 1)
check('no trailing newline',        h.cut(noNl, fr, hourly[99]) == linearCut(noNl, fr, hourly[99]))
String crlf = csv(hourly, "timestamp,illuminance\r\n", "\r\n")
int frc = h.first(crlf)
check('CRLF content',               rowsOf(crlf, frc, h.cut(crlf, frc, hourly[50])).size() == 50)

Random rnd = new Random(42)
boolean allMatch = (1..200).every {
    long cutoff = hourly[0] - 7200 + (long) (rnd.nextDouble() * 370000)
    h.cut(c, fr, cutoff) == linearCut(c, fr, cutoff)
}
check('200 random cutoffs match the linear scan', allMatch)

List<Long> unsorted = hourly.collect { it }
Collections.shuffle(unsorted, new Random(7))
String cu = csv(unsorted)
int fru = h.first(cu)
boolean keepsAll = (1..50).every {
    long cutoff = hourly[0] + (long) (rnd.nextDouble() * 360000)
    int k = h.cut(cu, fru, cutoff)
    isLineStart(cu, k, fru) && k >= fru && k <= cu.length() &&
        rowsOf(cu, fru, k) + rowsOf(cu, k, cu.length()) == rowsOf(cu, fru, cu.length())
}
check('unsorted rows keep every row', keepsAll)

String bad = csv(hourly[0..<40]) + "garbage,1\n" + hourly[40..<100].collect { "${it},${it % 97}\n" }.join('')
int frb = h.first(bad)
int kb = h.cut(bad, frb, hourly[60])
check('corrupt row keeps every row', isLineStart(bad, kb, frb) &&
    rowsOf(bad, frb, kb) + rowsOf(bad, kb, bad.length()) == rowsOf(bad, frb, bad.length()))

println 'archiveName'
check('with extension',             h.name('log.csv', '20260708', '20260902', []) == 'log_20260708_20260902.csv')
check('without extension',          h.name('log', '20260708', '20260902', []) == 'log_20260708_20260902')
check('several dots',               h.name('a.b.csv', '20260708', '20260902', []) == 'a.b_20260708_20260902.csv')
check('collision adds -2',          h.name('log.csv', '1', '2', ['log_1_2.csv']) == 'log_1_2-2.csv')
check('second collision adds -3',   h.name('log.csv', '1', '2', ['log_1_2.csv', 'log_1_2-2.csv']) == 'log_1_2-3.csv')

println 'dayStamp'
check('Toronto evening',            h.day(1791164812L, TimeZone.getTimeZone('America/Toronto')) == '20261004')
check('same instant in UTC',        h.day(1791164812L, TimeZone.getTimeZone('UTC')) == '20261005')

println "\n=== ${pass}/${pass + fail} passed ==="
System.exit(fail == 0 ? 0 : 1)
