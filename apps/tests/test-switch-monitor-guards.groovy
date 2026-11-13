// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads startRecovery(), checkLowLoad(), scheduleLoadCheck() and the static guards from the
// shipped SwitchMonitor source and runs them against stubbed state and devices. Covers one recovery chain per
// group, per-switch and per-load grace, and one pending low-load check per group.
// Run: groovy apps/tests/test-switch-monitor-guards.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String text = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../SwitchMonitor.groovy').text
String grab(String head) {
    int from = text.indexOf(head)
    if (from < 0) return ''    // a helper the shipped source lacks: the tests that call it fail
    int to = text.indexOf('\n}\n', from)
    return text.substring(from, to + 3)
}
String body = ['static boolean graceElapsed', 'static Long nextLoadCheckAt', 'static boolean isOrphanLoadCheck',
               'void startRecovery(Map data)', 'void checkLowLoad(Map data)', 'private void scheduleLoadCheck',
               'private List getActionableSwitches'].collect { grab(it) }.join('\n')
body = body.replace('DeviceWrapper', 'Object')    // the stub devices are maps; only the type name changes
String stubs = '''
    static final Integer DEFAULT_LOAD_GRACE_MINUTES = 2
    static final Integer DEFAULT_LOAD_REMINDER_MINUTES = 30
    Map state = [:]
    Map settings = [:]
    long clock = 1_000_000_000L
    List calls = []
    List notes = []
    long now() { clock }
    void checkVersion() {}
    void logInfo(String m) {}
    void logDebug(String m) {}
    void logTrace(String m) {}
    void logWarn(String m) {}
    void logError(String m) {}
    String getGroupTargetState(int g) { "on" }
    String getGroupLabel(int g) { "G" }
    List devices = []
    List getGroupDevices(int g) { devices }
    int graceSecs = 300
    int getGroupGracePeriodSeconds(int g) { graceSecs }
    String getGroupGracePeriodLabel(int g) { "5 minute(s)" }
    boolean getBoolGroupSetting(int g, String n, boolean d = false) { n == "enableLoadMonitoring" ? true : d }
    BigDecimal getDecimalGroupSetting(int g, String n, def d) { 50.0 }
    int getIntGroupSetting(int g, String n, int d) { n == "loadGraceMinutes" ? 2 : (n == "loadReminderMinutes" ? 30 : d) }
    void sendGroupNotification(int g, String m) { notes << m }
    void attemptRecovery(Map d) { calls << "attempt" }
    void runIn(int secs, String h, Map o) { calls << "runIn:${h}:${secs}" }
    Map getGroupState(int g) { state.groupState = state.groupState ?: [:]; state.groupState["" + g] = state.groupState["" + g] ?: [recoveryActive: false, retryCount: 0, lowLoadDevices: [:]]; state.groupState["" + g] }
    void saveGroupState(int g, Map gs) { state.groupState["" + g] = gs }
    Map sw(String name, String value, Long sinceMs) { [displayName: name, id: name, currentSwitch: value, currentValue: { String a -> 10.0 }, currentState: { String a -> sinceMs == null ? null : [date: new Date(sinceMs)] }] }
'''
Class C = new GroovyShell().parse("import groovy.transform.Field\nclass C {\n${stubs}\n${body}\n}\nreturn C").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('graceElapsed: due only after the full grace') {
    assert !C.graceElapsed(1000L, 1000L + 60_000L, 300_000L)
    assert C.graceElapsed(1000L, 1000L + 300_000L, 300_000L)
    assert C.graceElapsed(null, 5L, 300_000L)
}
t('startRecovery: a second job does nothing while a chain runs') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "off", a.clock - 400_000L)]
    a.getGroupState(1).recoveryActive = true
    a.startRecovery([groupNum: 1])
    assert a.calls == [] && a.notes == []
}
t('startRecovery: starts a chain when none runs') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "off", a.clock - 400_000L)]
    a.startRecovery([groupNum: 1])
    assert a.calls == ["attempt"] && a.getGroupState(1).recoveryActive
}
t('startRecovery: a switch off for 1 min of a 5 min grace does not start recovery (restored and deviated again)') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "off", a.clock - 60_000L)]
    a.startRecovery([groupNum: 1])
    assert a.calls == [] && a.notes == []
}
t('startRecovery: a not-yet-due switch is not named when another is due') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "off", a.clock - 400_000L), a.sw("B", "off", a.clock - 20_000L)]
    a.startRecovery([groupNum: 1])
    assert a.calls == ["attempt"]
    assert a.notes.every { it.startsWith("A turned") } : a.notes.toString()
}
t('checkLowLoad: a load low for 20 s of a 2 min grace is not reported') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "on", null), a.sw("B", "on", null)]
    a.getGroupState(1).lowLoadDevices = [A: a.clock - 130_000L, B: a.clock - 20_000L]
    a.checkLowLoad([groupNum: 1])
    assert a.notes.size() == 1 && a.notes[0].contains("A (") && !a.notes[0].contains("B (") : a.notes.toString()
}
t('checkLowLoad: the not-yet-due load gets a check at its own due time, not a 30 min reminder only') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "on", null), a.sw("B", "on", null)]
    a.getGroupState(1).lowLoadDevices = [A: a.clock - 130_000L, B: a.clock - 20_000L]
    a.checkLowLoad([groupNum: 1])
    assert a.calls == ["runIn:checkLowLoad:100"] : a.calls.toString()
}
t('checkLowLoad: only not-yet-due loads schedule one check and send nothing') {
    def a = C.newInstance()
    a.devices = [a.sw("B", "on", null)]
    a.getGroupState(1).lowLoadDevices = [B: a.clock - 20_000L]
    a.checkLowLoad([groupNum: 1])
    assert a.notes == [] && a.calls == ["runIn:checkLowLoad:100"] : a.calls.toString()
}
t('scheduleLoadCheck: two drops and a manual check leave one pending job') {
    def a = C.newInstance()
    a.scheduleLoadCheck(1, 120_000L)
    a.clock += 10_000L
    a.scheduleLoadCheck(1, 120_000L)
    a.scheduleLoadCheck(1, 120_000L)
    assert a.calls.size() == 1 : a.calls.toString()
}
t('scheduleLoadCheck: a sooner need adds a job; a later one does not') {
    def a = C.newInstance()
    a.scheduleLoadCheck(1, 1_800_000L)
    a.scheduleLoadCheck(1, 120_000L)
    a.scheduleLoadCheck(1, 1_800_000L)
    assert a.calls == ["runIn:checkLowLoad:1800", "runIn:checkLowLoad:120"] : a.calls.toString()
}
t('checkLowLoad: the reminder is not duplicated when two jobs fired') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "on", null)]
    a.getGroupState(1).lowLoadDevices = [A: a.clock - 130_000L]
    a.checkLowLoad([groupNum: 1])
    a.checkLowLoad([groupNum: 1])   // second job of the same drop: the reminder is already live
    assert a.calls.count { it.startsWith("runIn") } == 1 : a.calls.toString()
    assert a.notes.size() == 1 : a.notes.toString()
}
t('checkLowLoad: a leftover job that fires before the live one does nothing') {
    def a = C.newInstance()
    a.devices = [a.sw("A", "on", null)]
    a.getGroupState(1).lowLoadDevices = [A: a.clock - 130_000L]
    a.getGroupState(1).loadCheckDueAt = a.clock + 600_000L
    a.checkLowLoad([groupNum: 1])
    assert a.notes == [] && a.calls == []
}

println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)
