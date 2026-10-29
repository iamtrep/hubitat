// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Hydro-Québec Peak Period Manager. Parses the block between the "Core (pure)"
// and "End core" markers of the shipped app file and runs it, so the tests bind to shipped code.
// Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

import java.text.SimpleDateFormat

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'HydroPeakEvents.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse('import java.text.SimpleDateFormat; ' + src.substring(a, b))   // same line keeps line numbers

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

tz = TimeZone.getTimeZone('America/Montreal')
long at(String s) { new SimpleDateFormat("yyyy-MM-dd HH:mm").with { setTimeZone(tz); parse(s).time } }
Map ev(String day, String w) { w == 'am' ? [start: at("${day} 06:00"), end: at("${day} 10:00"), window: 'am']
                                         : [start: at("${day} 16:00"), end: at("${day} 20:00"), window: 'pm'] }
String ph(String now, List events, List skipped = [], List aborted = [], String offer = 'CPC-D') {
    core.phaseAt(at(now), events, skipped, aborted, offer, tz).phase
}

// ── parseEvents ──
List recs = [
    [datedebut: '2026-12-03T06:00:00-05:00', datefin: '2026-12-03T10:00:00-05:00', plagehoraire: 'AM', offre: 'CPC-D'],
    [datedebut: '2026-12-03T06:00:00-05:00', datefin: '2026-12-03T10:00:00-05:00', plagehoraire: 'AM', offre: 'CPC-D'],   // duplicate
    [datedebut: '2026-12-02T16:00:00-05:00', datefin: '2026-12-02T20:00:00-05:00', offre: 'CPC-D'],                       // no plage: from the hour
    [datedebut: '2026-11-01T16:00:00-04:00', datefin: '2026-11-01T20:00:00-04:00', plagehoraire: 'PM'],                    // past
    [datedebut: 'not a date', datefin: '2026-12-09T10:00:00-05:00'],
    [datedebut: null, datefin: null]
]
Map p = core.parseEvents(recs, at('2026-12-01 12:00'), tz)
check('parse: dated counts records with both dates', p.dated, 5)
check('parse: bad counts unparseable', p.bad, 1)
check('parse: past and duplicate dropped', p.events.size(), 2)
check('parse: sorted by start', p.events*.start, [at('2026-12-02 16:00'), at('2026-12-03 06:00')])
check('parse: window from the hour', p.events[0].window, 'pm')
check('parse: window from plagehoraire', p.events[1].window, 'am')
check('parse: in-progress event kept', core.parseEvents(recs[0..0], at('2026-12-03 07:00'), tz).events.size(), 1)
check('parse: ended event dropped', core.parseEvents(recs[0..0], at('2026-12-03 10:00'), tz).events.size(), 0)

// ── anchor windows ──
Map an = core.anchorOf(ev('2026-12-03', 'am'), 'CPC-D', tz)
check('anchor AM 01:00', an.start, at('2026-12-03 01:00'))
check('anchor AM 04:00', an.end, at('2026-12-03 04:00'))
Map ap = core.anchorOf(ev('2026-12-03', 'pm'), 'CPC-D', tz)
check('anchor PM 12:00', ap.start, at('2026-12-03 12:00'))
check('anchor PM 14:00', ap.end, at('2026-12-03 14:00'))
check('anchor Flex D none', core.anchorOf(ev('2026-12-03', 'am'), 'TPC-DPC', tz), null)
// After the March DST change the window keeps its clock hours.
Map dst = core.anchorOf([start: at('2027-03-15 06:00'), end: at('2027-03-15 10:00'), window: 'am'], 'CPC-D', tz)
check('anchor after DST: 01:00 local', dst.start, at('2027-03-15 01:00'))

// ── phaseAt, morning event ──
List am = [ev('2026-12-03', 'am')]
check('none without events', ph('2026-12-02 12:00', []), 'none')
check('announced the day before', ph('2026-12-02 15:00', am), 'announced')
check('announced until 01:00', ph('2026-12-03 00:59', am), 'announced')
check('anchor at 01:00', ph('2026-12-03 01:00', am), 'anchor')
check('anchor until 04:00', ph('2026-12-03 03:59', am), 'anchor')
check('gap at 04:00', ph('2026-12-03 04:00', am), 'gap')
check('active at 06:00', ph('2026-12-03 06:00', am), 'active')
check('active until 10:00', ph('2026-12-03 09:59', am), 'active')
check('none at 10:00', ph('2026-12-03 10:00', am), 'none')
check('Flex D: no anchor phase', ph('2026-12-03 02:00', am, [], [], 'TPC-DPC'), 'announced')

// ── two events the same day ──
List both = [ev('2026-12-03', 'am'), ev('2026-12-03', 'pm')]
check('after AM event: PM announced', ph('2026-12-03 10:30', both), 'announced')
check('PM anchor at 12:00', ph('2026-12-03 12:00', both), 'anchor')
check('PM gap at 14:00', ph('2026-12-03 14:00', both), 'gap')
check('PM active at 16:00', ph('2026-12-03 16:00', both), 'active')

// ── next boundary ──
Map n1 = core.phaseAt(at('2026-12-02 15:00'), am, [], [], 'CPC-D', tz)
check('next from announced: anchor start', n1.next, at('2026-12-03 01:00'))
check('next from anchor: anchor end', core.phaseAt(at('2026-12-03 02:00'), am, [], [], 'CPC-D', tz).next, at('2026-12-03 04:00'))
check('next from gap: event start', core.phaseAt(at('2026-12-03 05:00'), am, [], [], 'CPC-D', tz).next, at('2026-12-03 06:00'))
check('next from active: event end', core.phaseAt(at('2026-12-03 07:00'), am, [], [], 'CPC-D', tz).next, at('2026-12-03 10:00'))
check('next with no event', core.phaseAt(at('2026-12-03 07:00'), [], [], [], 'CPC-D', tz).next, null)
check('next: pre-event start comes first', core.nextBoundary(n1, at('2026-12-02 15:00'), 180), at('2026-12-03 01:00'))
Map g = core.phaseAt(at('2026-12-03 04:30'), am, [], [], 'CPC-D', tz)
check('next: pre-event start inside the gap', core.nextBoundary(g, at('2026-12-03 04:30'), 60), at('2026-12-03 05:00'))
check('next: pre-event start passed', core.nextBoundary(g, at('2026-12-03 05:30'), 60), at('2026-12-03 06:00'))

// ── skip and abort ──
long k = am[0].start
check('skipped before start', ph('2026-12-03 02:00', am, [k]), 'skipped')
check('skipped during the event', ph('2026-12-03 07:00', am, [k]), 'skipped')
check('aborted during the event', ph('2026-12-03 07:00', am, [], [k]), 'aborted')
check('skip of another event ignored', ph('2026-12-03 02:00', am, [k + 1]), 'anchor')
check('skipped next is the event end', core.phaseAt(at('2026-12-03 02:00'), am, [k], [], 'CPC-D', tz).next, at('2026-12-03 10:00'))

Closure cc = { String cmd, String now, List sk = [], List ab = [] ->
    core.commandCheck(cmd, core.phaseAt(at(now), am, sk, ab, 'CPC-D', tz), at(now)).ok }
check('skip when announced', cc('skip', '2026-12-02 20:00'), true)
check('skip in the anchor window', cc('skip', '2026-12-03 02:00'), true)
check('skip in the gap', cc('skip', '2026-12-03 05:00'), true)
check('no skip during the event', cc('skip', '2026-12-03 07:00'), false)
check('no skip without an event', core.commandCheck('skip', core.phaseAt(0L, [], [], [], 'CPC-D', tz), 0L).ok, false)
check('unskip before start', cc('unskip', '2026-12-03 05:00', [k]), true)
check('no unskip after start', cc('unskip', '2026-12-03 06:30', [k]), false)
check('no unskip when not skipped', cc('unskip', '2026-12-03 05:00'), false)
check('abort during the event', cc('abort', '2026-12-03 07:00'), true)
check('no abort before the event', cc('abort', '2026-12-03 05:00'), false)
check('no abort of a skipped event', cc('abort', '2026-12-03 07:00', [k]), false)
check('unknown command', cc('pause', '2026-12-03 07:00'), false)

check('prune keeps a coming event', core.pruneMarks([k], am, at('2026-12-03 07:00')), [k])
check('prune drops an ended event', core.pruneMarks([k], am, at('2026-12-03 10:00')), [])
check('prune drops an event no longer listed', core.pruneMarks([k], [], at('2026-12-03 07:00')), [])

// ── switches ──
check('switches announced', core.switchesFor('announced'), [announced: true, anchor: false, active: false])
check('switches anchor', core.switchesFor('anchor'), [announced: true, anchor: true, active: false])
check('switches gap', core.switchesFor('gap'), [announced: true, anchor: false, active: false])
check('switches active', core.switchesFor('active'), [announced: false, anchor: false, active: true])
check('switches skipped', core.switchesFor('skipped'), [announced: false, anchor: false, active: false])
check('switches aborted', core.switchesFor('aborted'), [announced: false, anchor: false, active: false])
check('switches none', core.switchesFor('none'), [announced: false, anchor: false, active: false])

Closure lg = { String now, int pre, List sk = [] ->
    core.legacySwitches(core.phaseAt(at(now), am, sk, [], 'CPC-D', tz), at(now), pre) }
check('legacy: upcoming the day before', lg('2026-12-02 20:00', 180), [upcoming: true, pre: false, event: false])
check('legacy: pre-event 180 min before', lg('2026-12-03 03:00', 180), [upcoming: false, pre: true, event: false])
check('legacy: no pre-event switches', lg('2026-12-03 05:59', 0), [upcoming: true, pre: false, event: false])
check('legacy: event', lg('2026-12-03 06:00', 180), [upcoming: false, pre: false, event: true])
check('legacy: skipped turns all off', lg('2026-12-03 03:00', 180, [k]), [upcoming: false, pre: false, event: false])
check('legacy: after the event', lg('2026-12-03 10:00', 180), [upcoming: false, pre: false, event: false])

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
