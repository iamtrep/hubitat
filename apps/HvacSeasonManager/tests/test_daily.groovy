// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Daily-path unit tests for HVAC Season Manager: the 05:00 evaluation and the Open-Meteo callbacks
// (startEvaluation, archiveHandler, forecastHandler, evaluateSeason with why "daily"), driven with a
// stub `state`, `settings`, `location`, clock and canned Open-Meteo responses. The shipped app file is
// parsed whole and run as a script; nothing is copied. Two source edits are made in memory before
// parsing: the hub-only imports and their two type names are dropped, and realToday() returns the stub day (the app derives the
// day from the wall clock). Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

import groovy.json.JsonSlurper

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
src = new File(here.parentFile, 'HvacSeasonManager.groovy').text
src = src.replace('import com.hubitat.app.ChildDeviceWrapper\n', '').replace('import com.hubitat.hub.domain.State\n', '')
        .replace('ChildDeviceWrapper ', 'Object ').replace('State st = ', 'Object st = ')
String clockLine = "String realToday() { return new Date().format('yyyy-MM-dd', location.timeZone) }"
if (!src.contains(clockLine)) { println '[FAIL] realToday() line not found'; System.exit(2) }
src = src.replace(clockLine, "String realToday() { return fakeToday }")
codeVersion = (src =~ /CODE_VERSION = "([^"]+)"/)[0][1]
fixture = new JsonSlurper().parse(new File(here, 'fixtures/openmeteo-archive.json')) as Map

passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

// state that records which of the watched keys are written, in order
class RecState extends LinkedHashMap {
    List<String> log
    Object put(Object k, Object v) { if (k in ['season', 'since', 'lastDaily']) log << ("${k}=${v}".toString()); return super.put(k, v) }
    Object remove(Object k) { if (k in ['holdUntil']) log << ("-${k}".toString()); return super.remove(k) }
}
class StubResp {
    int status = 200; boolean error = false; Object body
    boolean hasError() { error }
    Object getJson() { if (body instanceof Exception) throw body; return body }
}
class StubApp { String getLabel() { 'test' } ; String getId() { '1' } }
class StubDevice { List updates; void updateStatus(Map m) { updates << m } }
class H {
    Script s; RecState st; Map logs; List http; StubDevice dev
    Object methodMissing(String n, Object args) { s.invokeMethod(n, args) }
}

T0 = Date.parse("yyyy-MM-dd HH:mm", "2026-03-20 12:00", TimeZone.getTimeZone('America/Toronto')).time
DAY = 86400000L

// A fresh app instance. Overrides: settings, state entries, today, now, scale.
H mk(Map o = [:]) {
    Script s = new GroovyShell().parse(src)
    RecState st = new RecState(log: [])
    st.putAll([version: codeVersion, season: 'winter'] + ((o.state ?: [:]) as Map))
    st.log.clear()
    Map logs = [info: [], debug: [], warn: []]
    Object logger = [info: { logs.info << it.toString() }, debug: { logs.debug << it.toString() }, warn: { logs.warn << it.toString() }, error: { logs.warn << it.toString() }, trace: {}]
    List http = []
    StubDevice dev = new StubDevice(updates: [])
    Binding b = new Binding([
        state: st, settings: [w2sFrom: '03-11', w2sUntil: '04-08', f2wFrom: '10-26', f2wUntil: '11-11', summerFrom: '05-10', summerUntil: '09-14', fallFrom: '08-15'] + ((o.settings ?: [:]) as Map),
        location: [latitude: 45.38, longitude: -75.63, temperatureScale: 'C', timeZone: TimeZone.getTimeZone('America/Toronto')],
        app: new StubApp(), log: logger, debugEnable: true, txtEnable: true, traceEnable: false, notifyDevices: null,
        fakeToday: o.today ?: '2026-03-20',
        now: { -> (o.now ?: T0) as long },
        asynchttpGet: { String cb, Map p, Map d -> http << [cb: cb, uri: p.uri, data: d] },
        getChildDevice: { String n -> dev }, addChildDevice: { Object... a -> dev },
        runIn: { Object... a -> }, schedule: { Object... a -> },
    ])
    s.setBinding(b)
    return new H(s: s, st: st, logs: logs, http: http, dev: dev)
}
String plus(String iso, int n) { Date.parse('yyyy-MM-dd', iso).plus(n).format('yyyy-MM-dd') }
Map days(String from, String to, Object v) { Map m = [:]; String d = from; while (d <= to) { m[d] = new BigDecimal(v.toString()); d = plus(d, 1) }; return m }

// ── startEvaluation: Open-Meteo off ──
H a = mk(settings: [omEnable: false], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 5), lastDaily: '2026-03-15'])
a.startEvaluation('daily')
check('no request with Open-Meteo off', a.http.size(), 0)
check('daily without Open-Meteo changes the season', a.st.season, 'spring')
check('since is the first day of the catch-up that qualified', a.st.since, '2026-03-16')
check('lastDaily moves to today', a.st.lastDaily, '2026-03-20')
check('write order: season, since, lastDaily', a.st.log, ['season=spring', 'since=2026-03-16', 'lastDaily=2026-03-20'])
check('publish carries the new season', a.dev.updates.last(), [season: 'spring'])
check('change logged with its day', a.logs.info.any { it.contains('season winter → spring on 2026-03-16: ') }, true)

// ── first / lastDaily arithmetic and clamp ──
// Summer with a cold mean relapses to spring on the first day evaluated; January would end summer to fall.
cold = days('2025-01-01', '2026-12-01', 10)
H c = mk(settings: [omEnable: false], today: '2026-12-01', state: [season: 'summer', om: cold, lastDaily: '2024-01-01'])
c.startEvaluation('daily')
check('clamp: first change is on today-120', c.logs.info.find { it.contains('season summer → ') }?.contains('on 2026-08-03'), true)
H c2 = mk(settings: [omEnable: false], today: '2026-12-01', state: [season: 'spring', om: days('2026-11-20', '2026-11-30', 20), lastDaily: '2026-12-05'])
c2.startEvaluation('daily')
check('clamp: a future lastDaily evaluates today only, no error', c2.st.lastDaily, '2026-12-01')
H c3 = mk(settings: [omEnable: false], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 5), lastDaily: '2026-03-20'])
c3.startEvaluation('daily')
check('lastDaily = today re-evaluates today', c3.st.since, '2026-03-20')

// no change: debug line, lastDaily set, season/since untouched
H d = mk(settings: [omEnable: false], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 1), since: '2026-01-01'])
d.startEvaluation('daily')
check('stays: season unchanged', d.st.season, 'winter')
check('stays: since unchanged', d.st.since, '2026-01-01')
check('stays: only lastDaily written', d.st.log, ['lastDaily=2026-03-20'])
check('stays: debug line', d.logs.debug.any { it.startsWith('test: daily: season stays winter') }, true)
check('daily ignores testDate and testMean', mk(settings: [omEnable: false, testDate: '2026-12-01', testMean: '-9'], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 5)]).with { startEvaluation('daily'); st.season }, 'spring')

// ── hold ──
H h = mk(settings: [omEnable: false], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 5), holdUntil: T0 + 2 * DAY])
h.startEvaluation('daily')
check('hold covers today: season stays', h.st.season, 'winter')
check('hold covers today: still held', h.st.holdUntil, T0 + 2 * DAY)
check('hold covers today: lastDaily still moves', h.st.lastDaily, '2026-03-20')
H he = mk(settings: [omEnable: false], today: '2026-03-23', now: T0 + 3 * DAY, state: [om: days('2026-03-10', '2026-03-22', 5), holdUntil: T0 + 2 * DAY, lastDaily: '2026-03-20'])
he.startEvaluation('daily')
check('hold expired: removed', he.st.containsKey('holdUntil'), false)
check('hold expired: change resumes after the last held day', he.st.since, '2026-03-23')
check('hold expired: resumed line', he.logs.info.contains('test: automatic season changes resumed'), true)
check('hold expired: removal precedes the season write', he.st.log, ['-holdUntil', 'season=spring', 'since=2026-03-23', 'lastDaily=2026-03-23'])
H hl = mk(settings: [omEnable: false], today: '2026-03-23', now: T0 + 2 * DAY + 1000, state: [om: days('2026-03-10', '2026-03-22', 5), holdUntil: T0 + 2 * DAY, lastDaily: '2026-03-20'])
hl.startEvaluation('daily')
check('hold last day is still skipped on the day it expires', hl.st.since, '2026-03-23')

// ── Open-Meteo on: request, callbacks ──
H o = mk(settings: [omEnable: true], today: '2026-04-16', state: [lastDaily: '2026-04-10'])
o.startEvaluation('daily')
check('one archive request', o.http.collect { it.cb }, ['archiveHandler'])
check('archive window: 10 days back to yesterday', o.http[0].uri.contains('start_date=2026-04-06&end_date=2026-04-15'), true)
check('archive request carries why', o.http[0].data, [why: 'daily'])
check('nothing evaluated before the response', o.st.log, [])
o.archiveHandler(new StubResp(body: fixture), [why: 'daily'])
check('archive stored: last ten days', (o.st.om as Map).keySet().sort() as List, (6..15).collect { '2026-04-' + String.format('%02d', it) })
check('forecast requested next', o.http*.cb, ['archiveHandler', 'forecastHandler'])
check('still nothing evaluated', o.st.log, [])
fcBody = [daily: [time: (16..22).collect { '2026-04-' + it }, temperature_2m_mean: [9, 9, 9, 9, 9, 9, 9]]]
o.forecastHandler(new StubResp(body: fcBody), [why: 'daily'])
check('forecast stored', (o.st.fc as Map).made, '2026-04-16')
check('evaluated in the forecast callback: season', o.st.season, 'spring')
check('catch-up from lastDaily+1 finds the missed last day', o.st.since, '2026-04-11')
check('callback write order', o.st.log, ['season=spring', 'since=2026-04-11', 'lastDaily=2026-04-16'])
check('outlook written', o.st.outlook != null, true)

// startEvaluation start date with a stale lastDaily, and the 120-day cap
H s1 = mk(settings: [omEnable: true], today: '2026-04-16', state: [lastDaily: '2026-03-01'])
s1.startEvaluation('daily')
check('stale lastDaily pulls the archive start back to lastDaily-2', s1.http[0].uri.contains('start_date=2026-02-27&'), true)
H s2 = mk(settings: [omEnable: true], today: '2026-04-16', state: [lastDaily: '2025-01-01'])
s2.startEvaluation('daily')
check('archive start capped at 120 days', s2.http[0].uri.contains('start_date=2025-12-17&'), true)
H s3 = mk(settings: [omEnable: true], today: '2026-04-16')
s3.startEvaluation('refresh')
s3.archiveHandler(new StubResp(body: fixture), [why: 'refresh'])
s3.forecastHandler(new StubResp(body: fcBody), [why: 'refresh'])
check('refresh fetches but does not evaluate', s3.st.log, [])
check('refresh still stores the forecast', (s3.st.fc as Map).made, '2026-04-16')

// failures: the evaluation still runs and falls back
H f1 = mk(settings: [omEnable: true], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 5)])
f1.archiveHandler(new StubResp(status: 500, body: [:]), [why: 'daily'])
check('archive HTTP 500: warned once', f1.logs.warn.count { it.contains('Open-Meteo unavailable (HTTP 500)') }, 1)
check('archive HTTP 500: stored days untouched', (f1.st.om as Map).size(), 10)
check('archive HTTP 500: forecast still requested', f1.http*.cb, ['forecastHandler'])
f1.forecastHandler(new StubResp(status: 500, body: [:]), [why: 'daily'])
check('forecast HTTP 500: evaluation still runs on the stored days', f1.st.season, 'spring')
check('forecast HTTP 500: no forecast stored', f1.st.fc, null)
H f2 = mk(settings: [omEnable: true], today: '2026-03-20', state: [om: days('2026-03-10', '2026-03-19', 5)])
f2.archiveHandler(new StubResp(body: new RuntimeException('bad json')), [why: 'daily'])
f2.forecastHandler(new StubResp(error: true, status: -1), [why: 'daily'])
check('unreadable archive, failed forecast: evaluated all the same', f2.st.lastDaily, '2026-03-20')
H f3 = mk(settings: [omEnable: true], today: '2026-03-20', state: [omWarned: true])
f3.archiveHandler(new StubResp(body: fixture), [why: 'daily'])
check('recovery clears the warning', f3.st.containsKey('omWarned'), false)
check('recovery logs', f3.logs.info.contains('test: Open-Meteo available again'), true)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
