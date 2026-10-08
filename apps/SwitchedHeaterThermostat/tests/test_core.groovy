// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Switched Heater Thermostat. Parses the block between the
// "Core (pure)" and "End core" markers of the shipped app file and runs it, so the
// tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
File app = new File(here.parentFile, 'SwitchedHeaterThermostat.groovy')
if (!app.exists()) { println '[FAIL] SwitchedHeaterThermostat.groovy not found'; System.exit(2) }
String src = app.text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}
MIN = 60000L

// ── readings ──
check('average', core.aggregateTemp([19, 21], 'average'), 20.0)
check('average skips null, rounds half up', core.aggregateTemp([19.0, 20.5, null], 'average'), 19.8)
check('minimum', core.aggregateTemp([19.5, 18.2], 'minimum'), 18.2)
check('string values', core.aggregateTemp(['20.25'], 'average'), 20.3)
check('none gives null', core.aggregateTemp([], 'average'), null)
check('all null gives null', core.aggregateTemp([null], 'minimum'), null)

// ── activity and quiet limit ──
check('first activity', core.recordActivity(null, 1000L, 48), [last: 1000L, gaps: []])
check('gap recorded', core.recordActivity([last: 1000L, gaps: []], 4000L, 48), [last: 4000L, gaps: [3000L]])
check('older activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], 2000L, 48), [last: 4000L, gaps: [3000L]])
check('same activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], 4000L, 48), [last: 4000L, gaps: [3000L]])
check('null activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], null, 48), [last: 4000L, gaps: [3000L]])
check('gaps trimmed to keep', core.recordActivity([last: 10L, gaps: [1L, 2L]], 15L, 2), [last: 15L, gaps: [2L, 5L]])
check('fallback until learned', core.quietLimitMs([10 * MIN, 10 * MIN, 10 * MIN, 10 * MIN], 120 * MIN, 30 * MIN, 5), 120 * MIN)
check('floor applies', core.quietLimitMs([10 * MIN, 10 * MIN, 10 * MIN, 10 * MIN, 10 * MIN], 120 * MIN, 30 * MIN, 5), 30 * MIN)
check('learned limit', core.quietLimitMs([60 * MIN, 90 * MIN, 120 * MIN, 30 * MIN, 45 * MIN], 120 * MIN, 30 * MIN, 5), 180 * MIN)
check('one outage does not stretch the limit', core.quietLimitMs([60 * MIN, 60 * MIN, 60 * MIN, 60 * MIN, 60 * MIN, 600 * MIN], 120 * MIN, 30 * MIN, 5), 120 * MIN)
check('never heard from is quiet', core.isQuiet(null, 1000L, 500L), true)
check('within limit', core.isQuiet([last: 1000L, gaps: []], 1400L, 500L), false)
check('at limit is not quiet', core.isQuiet([last: 1000L, gaps: []], 1500L, 500L), false)
check('quiet after limit', core.isQuiet([last: 1000L, gaps: []], 1501L, 500L), true)
check('numOrNull number', core.numOrNull('5'), 5L)
check('numOrNull junk', core.numOrNull('none'), null)
check('numOrNull null', core.numOrNull(null), null)

// ── wanted state ──
Map w(Map over) { core.decideLoad([frost: false, mode: 'heat', opState: 'idle', sensorFault: false, policy: 'off', cycleOn: null, resting: false] + over) }
check('heating wants on', w([opState: 'heating']), [want: 'on', why: 'thermostat'])
check('idle wants off', w([:]), [want: 'off', why: 'thermostat'])
check('mode off wins', w([mode: 'off', opState: 'heating']), [want: 'off', why: 'mode'])
check('unknown mode wants off', w([mode: null, opState: 'heating']), [want: 'off', why: 'mode'])
check('resting wins', w([opState: 'heating', resting: true]), [want: 'off', why: 'rest'])
check('sensor fault, policy off', w([opState: 'heating', sensorFault: true]), [want: 'off', why: 'sensor'])
check('sensor fault, policy on', w([sensorFault: true, policy: 'on']), [want: 'on', why: 'sensor'])
check('sensor fault, cycle on phase', w([sensorFault: true, policy: 'cycle', cycleOn: true]), [want: 'on', why: 'sensor'])
check('sensor fault, cycle off phase', w([opState: 'heating', sensorFault: true, policy: 'cycle', cycleOn: false]), [want: 'off', why: 'sensor'])
check('mode off wins over policy on', w([mode: 'off', sensorFault: true, policy: 'on']), [want: 'off', why: 'mode'])
check('resting wins over policy on', w([sensorFault: true, policy: 'on', resting: true]), [want: 'off', why: 'rest'])
check('frost wins over mode off', w([frost: true, mode: 'off']), [want: 'on', why: 'frost'])
check('frost wins over resting', w([frost: true, resting: true]), [want: 'on', why: 'frost'])

// ── minimum on and off times ──
check('no minimum', core.holdForMinTimes('off', 'on', 'thermostat', 0L, 1000L, null, null), [want: 'off', until: null])
check('min on holds', core.holdForMinTimes('off', 'on', 'thermostat', 0L, 1000L, 5000L, null), [want: 'on', until: 5000L])
check('min on elapsed', core.holdForMinTimes('off', 'on', 'thermostat', 0L, 5000L, 5000L, null), [want: 'off', until: null])
check('min off holds', core.holdForMinTimes('on', 'off', 'thermostat', 0L, 1000L, null, 3000L), [want: 'off', until: 3000L])
check('min off uses its own setting', core.holdForMinTimes('on', 'off', 'thermostat', 0L, 1000L, 9000L, null), [want: 'on', until: null])
check('safety change not held', core.holdForMinTimes('off', 'on', 'mode', 0L, 1000L, 5000L, null), [want: 'off', until: null])
check('frost not held', core.holdForMinTimes('on', 'off', 'frost', 0L, 1000L, null, 3000L), [want: 'on', until: null])
check('no change, no hold', core.holdForMinTimes('on', 'on', 'thermostat', 0L, 1000L, 5000L, 5000L), [want: 'on', until: null])
check('first decision not held', core.holdForMinTimes('on', null, 'thermostat', null, 1000L, 5000L, 5000L), [want: 'on', until: null])

// ── frost ──
check('frost off when warm', core.frostStep(false, 6.0, 5.0), false)
check('frost starts below', core.frostStep(false, 4.9, 5.0), true)
check('frost not at the line', core.frostStep(false, 5.0, 5.0), false)
check('frost keeps running within a degree', core.frostStep(true, 5.9, 5.0), true)
check('frost stops a degree above', core.frostStep(true, 6.0, 5.0), false)
check('frost off with no reading', core.frostStep(true, null, 5.0), false)
check('frost off with no setting', core.frostStep(false, 0.0, null), false)
check('decOrNull decimal', core.decOrNull('4.5'), 4.5)
check('decOrNull junk', core.decOrNull('none'), null)

// ── warming ──
check('no rise before the window', core.noRise(0L, 29 * MIN, 18.0, 18.0, 30 * MIN, 0.3), false)
check('no rise after the window', core.noRise(0L, 30 * MIN, 18.0, 18.2, 30 * MIN, 0.3), true)
check('rose enough', core.noRise(0L, 30 * MIN, 18.0, 18.3, 30 * MIN, 0.3), false)
check('no check without a start reading', core.noRise(0L, 30 * MIN, null, 18.0, 30 * MIN, 0.3), false)
check('no check when off', core.noRise(null, 30 * MIN, 18.0, 18.0, 30 * MIN, 0.3), false)
check('no check without a setting', core.noRise(0L, 30 * MIN, 18.0, 18.0, null, 0.3), false)
check('riseOk', core.riseOk(18.0, 18.4, 0.3), true)
check('riseDetail', core.riseDetail(18.0, 18.1, 0.3, 'C'), '0.1 °C (expected 0.3 °C)')
check('tempText', core.tempText(4.85, 'C'), '4.9 °C')
check('tempText no reading', core.tempText(null, 'C'), 'no temperature')

// ── verification ──
check('verify ok', core.verifyOutcome('on', 'on', 1, 2), 'ok')
check('verify retry', core.verifyOutcome('on', 'off', 1, 2), 'retry')
check('verify fault', core.verifyOutcome('on', 'off', 2, 2), 'fault')
check('verify ok on last attempt', core.verifyOutcome('off', 'off', 2, 2), 'ok')

// ── cycle and limit ──
check('cycle starts on', core.cyclePhase(0L, 0L, 1000L, 30), [on: true, next: 300L])
check('cycle end of on phase', core.cyclePhase(299L, 0L, 1000L, 30), [on: true, next: 300L])
check('cycle off phase', core.cyclePhase(300L, 0L, 1000L, 30), [on: false, next: 1000L])
check('cycle second period', core.cyclePhase(1250L, 0L, 1000L, 30), [on: true, next: 1300L])
check('cycle with no start begins now', core.cyclePhase(500L, null, 1000L, 30), [on: true, next: 800L])
check('no limit', core.overLimit(0L, 10000000L, null), false)
check('not heating', core.overLimit(null, 10000000L, 60 * MIN), false)
check('under limit', core.overLimit(0L, 59 * MIN, 60 * MIN), false)
check('at limit', core.overLimit(0L, 60 * MIN, 60 * MIN), true)
check('on since before a restart', core.overLimit(0L, 300 * MIN, 60 * MIN), true)

// ── power ──
check('on, no draw', core.powerVerdict('on', 0, 20), 'noDraw')
check('on, drawing', core.powerVerdict('on', 1000, 20), 'ok')
check('off, drawing', core.powerVerdict('off', 1500, 20), 'drawWhileOff')
check('off, standby draw', core.powerVerdict('off', 0.5, 20), 'ok')
check('no reading', core.powerVerdict('on', null, 20), 'unknown')
check('string reading', core.powerVerdict('on', '1000.0', 20), 'ok')
check('mismatch starts the grace', core.powerStep(null, 'noDraw', 1000L, 120000L), [badSince: 1000L, fault: false])
check('power report lags within grace', core.powerStep(1000L, 'noDraw', 60000L, 120000L), [badSince: 1000L, fault: false])
check('grace passed', core.powerStep(1000L, 'noDraw', 121000L, 120000L), [badSince: 1000L, fault: true])
check('back to ok resets', core.powerStep(1000L, 'ok', 121000L, 120000L), [badSince: null, fault: false])
check('no reading resets', core.powerStep(1000L, 'unknown', 121000L, 120000L), [badSince: null, fault: false])
check('detail on', core.powerDetail('Plug', 'on', 0), 'Plug (on, draws 0 W)')
check('detail rounds', core.powerDetail('Plug', 'off', '1499.6'), 'Plug (off, draws 1500 W)')

// ── alerts ──
check('separate switches', core.alertSwitchTargets([sensor: '7', load: '8', limit: '9', rise: '10'], [sensor: true, load: false, limit: true, rise: true]), ['7': 'on', '8': 'off', '9': 'on', '10': 'on'])
check('shared switch, one raised', core.alertSwitchTargets([sensor: '7', load: '7', limit: '7', rise: '7'], [sensor: false, load: true, limit: false, rise: false]), ['7': 'on'])
check('shared switch, none raised', core.alertSwitchTargets([sensor: '7', load: '7', limit: null, rise: '7'], [sensor: false, load: false, limit: false, rise: false]), ['7': 'off'])
check('no switches', core.alertSwitchTargets([sensor: null, load: null, limit: null, rise: null], [sensor: true, load: true, limit: true, rise: true]), [:])
check('retry advice', core.retryAdvice([[label: 'B', available: true, enabled: false], [label: 'A', available: true, enabled: false], [label: 'C', available: true, enabled: true], [label: 'V', available: false, enabled: false]]), ['A', 'B'])
check('retry advice, all set', core.retryAdvice([[label: 'C', available: true, enabled: true]]), [])
check('retry advice, none readable', core.retryAdvice([]), [])
check('listDiff', core.listDiff(['b', 'a'], ['c', 'b']), [added: ['c'], removed: ['a']])
check('namesText sorted', core.namesText(['Kitchen', 'Den']), 'Den, Kitchen')
check('policy off', core.policyText('off', 30, 30), 'Heat is off')
check('policy on', core.policyText('on', 30, 30), 'Heaters kept on')
check('policy cycle', core.policyText('cycle', 40, 20), 'Heaters on 40% of every 20 minutes')
check('sensor raised', core.faultMessage('Office', 'sensor', true, 'A, B', 'Heat is off'), 'Office: no temperature, all sensors quiet (A, B). Heat is off.')
check('sensor cleared', core.faultMessage('Office', 'sensor', false, 'A', ''), 'Office: temperature back (A).')
check('load raised', core.faultMessage('Office', 'load', true, 'Plug (commanded on, reads off)', ''), 'Office: lost control of Plug (commanded on, reads off).')
check('load cleared', core.faultMessage('Office', 'load', false, '', ''), 'Office: heaters under control again.')
check('limit raised', core.faultMessage('Office', 'limit', true, '120', '15'), 'Office: heaters on for 120 minutes, the limit. Off for 15 minutes.')
check('limit cleared', core.faultMessage('Office', 'limit', false, '', ''), 'Office: heating time back to normal.')
check('rise raised', core.faultMessage('Office', 'rise', true, '30', '0.1 °C (expected 0.3 °C)'), 'Office: heaters on for 30 minutes and the temperature rose 0.1 °C (expected 0.3 °C).')
check('rise cleared', core.faultMessage('Office', 'rise', false, '', ''), 'Office: warming check back to normal.')
check('frost on', core.faultMessage('Office', 'frost', true, '4.9 °C', ''), 'Office: frost protection on (4.9 °C).')
check('frost off', core.faultMessage('Office', 'frost', false, '6.0 °C', ''), 'Office: frost protection off (6.0 °C).')
check('quiet raised', core.faultMessage('Office', 'quiet', true, 'B', ''), 'Office: B quiet, left out of the temperature.')
check('quiet cleared', core.faultMessage('Office', 'quiet', false, 'B', ''), 'Office: B reporting again.')

println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
