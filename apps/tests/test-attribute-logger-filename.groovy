// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads olderSiblingWithName() and initialize() from the shipped AttributeLoggerChild source.
// Two loggers must not share one file; the older instance keeps the name and the newer one does not subscribe.
// Run: groovy apps/tests/test-attribute-logger-filename.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String text = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../AttributeLoggerChild.groovy').text
String grab(String head) {
    int from = text.indexOf(head)
    if (from < 0) return ''    // a helper the shipped source lacks: the tests that call it fail
    return text.substring(from, text.indexOf('\n}\n', from) + 3)
}
String body = ['static Long olderSiblingWithName', 'private boolean logFileConflict', 'void initialize()'].collect { grab(it) }.join('\n')
String stubs = '''
    String logFileName = "log.csv"
    Map settings = [logFileName: "log.csv", selectedAttributes: ["temperature"]]
    List selectedAttributes = ["temperature"]
    def selectedDevice = "dev"
    Map siblings = [:]
    List subscribed = []
    List errors = []
    def app = [id: 20L, removeSetting: { String s -> }]
    def parent = [logFileNames: { -> siblings }]
    void checkVersion(boolean r) {}
    void unsubscribe() {}
    void subscribe(def d, String a, def h) { subscribed << a }
    def handleEvent = "h"
    void logError(String m) { errors << m }
    void logWarn(String m) {}
'''
Class C = new GroovyShell().parse("class C {\n${stubs}\n${body}\n}\nreturn C").run()
void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('an older sibling with the same name is found') { assert C.olderSiblingWithName('log.csv', 20L, ['10': 'log.csv', '20': 'log.csv']) == 10L }
t('a newer sibling does not count') { assert C.olderSiblingWithName('log.csv', 10L, ['10': 'log.csv', '20': 'log.csv']) == null }
t('a different name does not count') { assert C.olderSiblingWithName('b.csv', 20L, ['10': 'a.csv']) == null }
t('a blank name does not count') { assert C.olderSiblingWithName('', 20L, ['10': '']) == null }
t('the same name with stray spaces counts') { assert C.olderSiblingWithName('log.csv', 20L, ['10': ' log.csv ']) == 10L }
t('initialize: a clash with an older logger subscribes to nothing and says why') {
    def a = C.newInstance()
    a.siblings = ['10': 'log.csv', '20': 'log.csv']
    a.initialize()
    assert a.subscribed == [] && a.errors.size() == 1
}
t('initialize: a unique name subscribes') {
    def a = C.newInstance()
    a.siblings = ['10': 'other.csv', '20': 'log.csv']
    a.initialize()
    assert a.subscribed == ['temperature'] && a.errors == []
}
println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
