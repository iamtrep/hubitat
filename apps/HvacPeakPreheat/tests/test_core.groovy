// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for HVAC Peak Pre-heat. Parses the block between the "Core (pure)" and
// "End core" markers of the shipped app file and runs it, so the tests bind to shipped code.
// Every row of the decision, morning and evening, both plans, keep and cut, away, missing and
// stale inputs, and the silence fallback. Runs in about a second.
// Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

import java.text.SimpleDateFormat

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'HvacPeakPreheat.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse('import groovy.transform.CompileStatic; ' + src.substring(a, b))   // same line keeps line numbers

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

tz = TimeZone.getTimeZone('America/Montreal')
long at(String s) { new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").with { setTimeZone(tz); parse(s.length() == 16 ? s + ':00' : s).time } }

MIN = 60000L
cfg = [nightMins: 22 * 60 + 30, silenceMs: 10 * MIN,
       nightHold: 20.5G, preheatHold: 22.5G, amStopHold: 18.0G, pmStopHold: 19.5G, eventHold: 18.0G, keepHold: 20.5G]

// A morning event on 2026-12-03 unless a case says otherwise; the plan device names the same event.
Map inp(Map o) {
    long t = at((o.at ?: '2026-12-02 23:00') as String)
    Map m = [now: t, tz: tz, away: false, phase: 'none', window: 'AM', eventDate: '2026-12-03',
             plan: null, anchorStop: null, planEventDate: '2026-12-03', planWindow: 'AM',
             advice: 'pending', eventUsed: null, keepUse: null, quietRef: t]
    o.each { k, v -> if (k != 'at') m[k] = v }
    return m
}
Map d(Map o, Map c = cfg) { core.decide(inp(o), c) }
// [rule, action, setpoint]
List r(Map o, Map c = cfg) { Map x = d(o, c); [x.rule, x.action, x.setpoint] }

Map pmPlan = [window: 'PM', planWindow: 'PM']

// ── 1. away mode ──
check('away, no event: disown', r(away: true), [1, 'disown', null])
check('away during the anchor: disown, no pre-heat', r(away: true, phase: 'anchor', plan: 'at least', at: '2026-12-03 02:00'), [1, 'disown', null])
check('away during the event: disown, no event hold', r(away: true, phase: 'active', at: '2026-12-03 07:00'), [1, 'disown', null])
check('away the night before: disown', r(away: true, phase: 'announced'), [1, 'disown', null])

// ── 2. none, skipped, aborted ──
check('none: release', r(phase: 'none'), [2, 'release', null])
check('skipped: release', r(phase: 'skipped', at: '2026-12-03 02:00'), [2, 'release', null])
check('aborted: release', r(phase: 'aborted', at: '2026-12-03 07:00'), [2, 'release', null])
check('phase case-insensitive', r(phase: 'None'), [2, 'release', null])

// ── 3. the night before a morning event ──
check('announced AM 22:29: nothing to hold yet', r(phase: 'announced', at: '2026-12-02 22:29'), [7, 'release', null])
check('announced AM 22:30: hold 20.5', r(phase: 'announced', at: '2026-12-02 22:30'), [3, 'hold', 20.5G])
check('announced AM 23:59: hold 20.5', r(phase: 'announced', at: '2026-12-02 23:59'), [3, 'hold', 20.5G])
check('announced AM 00:45 on the day: hold 20.5', r(phase: 'announced', at: '2026-12-03 00:45'), [3, 'hold', 20.5G])
check('announced AM two nights before: nothing', r(phase: 'announced', eventDate: '2026-12-04', planEventDate: '2026-12-04', at: '2026-12-02 23:00'), [7, 'release', null])
check('announced AM, afternoon the day before: nothing', r(phase: 'announced', at: '2026-12-02 15:00'), [7, 'release', null])
check('announced PM: no night hold', r([phase: 'announced', at: '2026-12-02 23:00'] + pmPlan), [7, 'release', null])
check('announced lowercase window', r(phase: 'announced', window: 'am', at: '2026-12-02 23:00'), [3, 'hold', 20.5G])
// Without a readable event date: the nightly time to 06:00, any night.
check('no eventDate 23:00: hold', r(phase: 'announced', eventDate: null, at: '2026-12-02 23:00'), [3, 'hold', 20.5G])
check('no eventDate 15:00: nothing', r(phase: 'announced', eventDate: 'none', at: '2026-12-02 15:00'), [7, 'release', null])
check('no eventDate 00:30: hold', r(phase: 'announced', eventDate: 'garbage', at: '2026-12-03 00:30'), [3, 'hold', 20.5G])
// A nightly time after midnight counts on the event's own day.
Map late = cfg + [nightMins: 30]
check('nightly 00:30: not at 23:00 the day before', r([phase: 'announced', at: '2026-12-02 23:00'], late), [7, 'release', null])
check('nightly 00:30: hold at 00:30', r([phase: 'announced', at: '2026-12-03 00:30'], late), [3, 'hold', 20.5G])
check('nightly 00:30 no eventDate: 00:29 nothing', r([phase: 'announced', eventDate: null, at: '2026-12-03 00:29'], late), [7, 'release', null])
check('the night hold setting is used', r([phase: 'announced'], cfg + [nightHold: 21.0G]), [3, 'hold', 21.0G])

// ── 4. anchor window ──
Map anc = [phase: 'anchor', at: '2026-12-03 02:00']
check('anchor AM at least: pre-heat 22.5', r(anc + [plan: 'at least']), [4, 'hold', 22.5G])
check('anchor PM at least: pre-heat 22.5', r(anc + pmPlan + [plan: 'at least', at: '2026-12-03 12:30']), [4, 'hold', 22.5G])
check('anchor at least: no fallback', d(anc + [plan: 'at least']).wakeAt, null)
check('anchor at least ignores a silent plan device', r(anc + [plan: 'at least', quietRef: at('2026-12-03 01:00')]), [4, 'hold', 22.5G])
check('anchor AM at most, no stop: pre-heat 22.5', r(anc + [plan: 'at most', anchorStop: 'false']), [4, 'hold', 22.5G])
check('anchor at most: fallback armed 10 min after the last news', d(anc + [plan: 'at most', anchorStop: 'false', quietRef: at('2026-12-03 01:58')]).wakeAt, at('2026-12-03 02:08'))
check('anchor AM at most, stop: hold 18', r(anc + [plan: 'at most', anchorStop: 'true']), [4, 'hold', 18.0G])
check('anchor PM at most, stop: hold 19.5', r(anc + pmPlan + [plan: 'at most', anchorStop: 'true', at: '2026-12-03 13:00']), [4, 'hold', 19.5G])
check('anchor at most, stop: no fallback', d(anc + [plan: 'at most', anchorStop: 'true']).wakeAt, null)
check('anchor at most, quiet 9:59: still pre-heat', r(anc + [plan: 'at most', anchorStop: 'false', quietRef: at('2026-12-03 02:00') - 10 * MIN + 1000]), [4, 'hold', 22.5G])
check('anchor AM at most, quiet 10:00: stop 18', r(anc + [plan: 'at most', anchorStop: 'false', quietRef: at('2026-12-03 01:50')]), [4, 'hold', 18.0G])
check('anchor PM at most, quiet 10:00: stop 19.5', r(anc + pmPlan + [plan: 'at most', anchorStop: 'false', at: '2026-12-03 13:00', quietRef: at('2026-12-03 12:50')]), [4, 'hold', 19.5G])
check('anchor at most, silent: no fallback left to arm', d(anc + [plan: 'at most', quietRef: at('2026-12-03 01:00')]).wakeAt, null)
// The silence clock is the stop check's heartbeat (anchorChecked), not any plan attribute: the app
// passes the later of the last heartbeat and the window's start, whatever else arrived since.
Map pmAnc = [phase: 'anchor', window: 'PM', planWindow: 'PM', plan: 'at most', anchorStop: 'false']
check('PM at most, anchorUsed every minute but no heartbeat since 12:00: pre-heat at 12:09', r(pmAnc + [at: '2026-12-03 12:09', quietRef: at('2026-12-03 12:00')]), [4, 'hold', 22.5G])
check('PM at most, anchorUsed every minute but no heartbeat since 12:00: stop 19.5 at 12:10', r(pmAnc + [at: '2026-12-03 12:10', quietRef: at('2026-12-03 12:00')]), [4, 'hold', 19.5G])
check('PM at most, still no heartbeat at 13:30: stop 19.5', r(pmAnc + [at: '2026-12-03 13:30', quietRef: at('2026-12-03 12:00')]), [4, 'hold', 19.5G])
check('PM at most, heartbeat a minute ago at 13:30: pre-heat until anchorStop', r(pmAnc + [at: '2026-12-03 13:30', quietRef: at('2026-12-03 13:29')]), [4, 'hold', 22.5G])
check('PM at most, heartbeat live and anchorStop true: stop 19.5', r(pmAnc + [at: '2026-12-03 13:30', quietRef: at('2026-12-03 13:29'), anchorStop: 'true']), [4, 'hold', 19.5G])
check('PM at most, heartbeat back after the fallback, anchorStop false: pre-heat again', r(pmAnc + [at: '2026-12-03 12:40', quietRef: at('2026-12-03 12:40')]), [4, 'hold', 22.5G])
check('anchor at most, anchorStop missing: pre-heat with fallback', r(anc + [plan: 'at most', anchorStop: null]), [4, 'hold', 22.5G])
check('anchor at most, anchorStop missing: fallback armed', d(anc + [plan: 'at most', anchorStop: null]).wakeAt, at('2026-12-03 02:10'))
check('anchor at most, no silence reference: stop', r(anc + [plan: 'at most', quietRef: null]), [4, 'hold', 18.0G])
check('anchor at most, short silence setting', r(anc + [plan: 'at most', quietRef: at('2026-12-03 01:57')], cfg + [silenceMs: 3 * MIN]), [4, 'hold', 18.0G])
check('anchor AM, plan missing: night hold 20.5, no pre-heat', r(anc + [plan: null]), [4, 'hold', 20.5G])
check('anchor AM, plan missing: no fallback', d(anc + [plan: null]).wakeAt, null)
check('anchor AM, plan missing: the night hold setting is used', r(anc + [plan: null], cfg + [nightHold: 21.0G]), [4, 'hold', 21.0G])
check('anchor AM, unknown plan value: night hold', r(anc + [plan: 'maybe']), [4, 'hold', 20.5G])
check('anchor PM, plan missing: program, release', r(anc + pmPlan + [plan: null, at: '2026-12-03 12:30']), [4, 'release', null])
check('anchor PM, plan for another day: release', r(anc + pmPlan + [plan: 'at least', planEventDate: '2026-12-02', at: '2026-12-03 12:30']), [4, 'release', null])
check('anchor AM, plan for another day: night hold, no pre-heat', r(anc + [plan: 'at least', planEventDate: '2026-12-01']), [4, 'hold', 20.5G])
check('anchor AM, plan for the other window: night hold', r(anc + [plan: 'at least', planWindow: 'PM']), [4, 'hold', 20.5G])
check('anchor AM, stale at most plan with stop true: night hold', r(anc + [plan: 'at most', anchorStop: 'true', planEventDate: '2026-12-01']), [4, 'hold', 20.5G])
check('anchor, plan device has no event date: plan used', r(anc + [plan: 'at least', planEventDate: null, planWindow: null]), [4, 'hold', 22.5G])
check('anchor, window missing at 02:00: morning stop', r(anc + [window: null, plan: 'at most', anchorStop: 'true']), [4, 'hold', 18.0G])
check('anchor, window none at 13:00: evening stop', r(anc + [window: 'none', planWindow: null, plan: 'at most', anchorStop: 'true', at: '2026-12-03 13:00']), [4, 'hold', 19.5G])
check('anchor stop value case-insensitive', r(anc + [plan: 'at most', anchorStop: 'TRUE']), [4, 'hold', 18.0G])

// ── 5. gap ──
check('gap AM: pre-heat 22.5', r(phase: 'gap', at: '2026-12-03 05:00'), [5, 'hold', 22.5G])
check('gap PM: pre-heat 22.5', r([phase: 'gap', at: '2026-12-03 15:00'] + pmPlan), [5, 'hold', 22.5G])
check('gap after an at most stop: pre-heat again', r(phase: 'gap', plan: 'at most', anchorStop: 'true', at: '2026-12-03 04:00'), [5, 'hold', 22.5G])

// ── 6. event ──
Map act = [phase: 'active', at: '2026-12-03 07:00']
check('event, advice pending: 18', r(act), [6, 'hold', 18.0G])
check('event, advice cut: 18', r(act + [advice: 'cut', eventUsed: 1.0G, keepUse: 5.0G]), [6, 'hold', 18.0G])
check('event, keep, under keepUse: 20.5', r(act + [advice: 'keep', eventUsed: 3.2G, keepUse: 5.0G]), [6, 'hold', 20.5G])
check('event, keep, at keepUse: 18', r(act + [advice: 'keep', eventUsed: 5.0G, keepUse: 5.0G]), [6, 'hold', 18.0G])
check('event, keep, over keepUse: 18', r(act + [advice: 'keep', eventUsed: 6.1G, keepUse: 5.0G]), [6, 'hold', 18.0G])
check('event, keep, eventUsed missing: 18', r(act + [advice: 'keep', eventUsed: null, keepUse: 5.0G]), [6, 'hold', 18.0G])
check('event, keep, keepUse missing: 18', r(act + [advice: 'keep', eventUsed: 1.0G, keepUse: null]), [6, 'hold', 18.0G])
check('event, keep, numbers as text', r(act + [advice: 'keep', eventUsed: '3.2', keepUse: '5']), [6, 'hold', 20.5G])
check('event, keep, plan for another event: 18', r(act + [advice: 'keep', eventUsed: 1.0G, keepUse: 5.0G, planEventDate: '2026-12-01']), [6, 'hold', 18.0G])
check('event PM, keep under keepUse: 20.5', r(act + pmPlan + [advice: 'keep', eventUsed: 1.0G, keepUse: 5.0G, at: '2026-12-03 17:00']), [6, 'hold', 20.5G])
check('event PM, pending: 18', r(act + pmPlan + [at: '2026-12-03 17:00']), [6, 'hold', 18.0G])
check('event hold setting is used', r(act, cfg + [eventHold: 17.0G]), [6, 'hold', 17.0G])

// ── 7. anything else ──
check('phase missing: release', r(phase: null), [7, 'release', null])
check('unknown phase: release', r(phase: 'paused'), [7, 'release', null])

// ── hold switch: on while the event wants a hold, Away aside ──
boolean w(Map o) { core.wantsHold(inp(o), cfg) }
check('switch: no event, off', w([:]), false)
check('switch: announced AM before 22:30, off', w(phase: 'announced', at: '2026-12-02 22:29'), false)
check('switch: night hold, on', w(phase: 'announced', at: '2026-12-02 23:00'), true)
check('switch: night hold in Away, on', w(away: true, phase: 'announced', at: '2026-12-02 23:00'), true)
check('switch: anchor at least, on', w(phase: 'anchor', plan: 'at least', at: '2026-12-03 02:00'), true)
check('switch: anchor at most stopped, on', w(phase: 'anchor', plan: 'at most', anchorStop: 'true', at: '2026-12-03 02:00'), true)
check('switch: anchor PM no plan (program), off', w([phase: 'anchor', plan: null, at: '2026-12-03 12:30'] + pmPlan), false)
check('switch: gap in Away, on', w(away: true, phase: 'gap', at: '2026-12-03 05:00'), true)
check('switch: event, on', w(phase: 'active', at: '2026-12-03 07:00'), true)
check('switch: event in Away, on', w(away: true, phase: 'active', at: '2026-12-03 07:00'), true)
check('switch: skipped, off', w(phase: 'skipped', at: '2026-12-03 02:00'), false)
check('switch: aborted in Away, off', w(away: true, phase: 'aborted', at: '2026-12-03 07:00'), false)
check('switch: event over, off', w(phase: 'none', at: '2026-12-03 09:00'), false)

// ── helpers ──
check('away: none chosen, mode Away', core.awayMatch('Away', '3', []), true)
check('away: none chosen, mode Home', core.awayMatch('Home', '1', null), false)
check('away: chosen by name', core.awayMatch('Vacances', '7', ['Away', 'Vacances']), true)
check('away: chosen by id', core.awayMatch('Away', '3', ['3']), true)
check('away: chosen, other mode', core.awayMatch('Away', '3', ['Vacances']), false)
check('differs: same', core.differs(20.5G, 20.5G, 0.3G), false)
check('differs: rounding', core.differs(20.50G, 20.5G, 0.3G), false)
check('differs: half a degree', core.differs(20.0G, 20.5G, 0.3G), true)
check('differs: unknown current', core.differs(null, 20.5G, 0.3G), true)
// An Ecobee stores whole °F: each default hold reads back within 0.3 °C, so it is not re-sent.
[18.0G: 17.8G, 19.5G: 19.4G, 20.5G: 20.6G, 22.5G: 22.8G].each { want, read ->
    check("differs: Ecobee reads ${want} as ${read}", core.differs(read, want, 0.3G), false)
}
check('differs: 1 °F changed by hand', core.differs(21.1G, 20.5G, 0.3G), true)
check('differs: °F scale, half a degree', core.differs(68.5G, 69.0G, 0.5G), false)
check('differs: °F scale, a degree', core.differs(68.0G, 69.0G, 0.5G), true)
check('toNum: integer', core.toNum(21), 21G)
check('toNum: text', core.toNum(' 20.5 '), 20.5G)
check('toNum: junk', core.toNum('n/a'), null)
check('plan matches: same event', core.planMatches('2026-12-03', 'AM', '2026-12-03', 'AM'), true)
check('plan matches: case of window', core.planMatches('2026-12-03', 'am', '2026-12-03', 'AM'), true)
check('plan matches: other day', core.planMatches('2026-12-03', 'AM', '2026-12-02', 'AM'), false)
check('plan matches: none on the phase device', core.planMatches('none', 'none', '2026-12-02', 'PM'), true)
check('night start: evening before', core.nightStart('2026-12-03', 22 * 60 + 30, tz), at('2026-12-02 22:30'))
check('night start: across the March DST change', core.nightStart('2027-03-15', 22 * 60 + 30, tz), at('2027-03-14 22:30'))
check('night start: after midnight is the same day', core.nightStart('2026-12-03', 30, tz), at('2026-12-03 00:30'))
check('night start: bad date', core.nightStart('none', 1350, tz), null)
check('minutesOf', core.minutesOf('22:30'), 1350)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
