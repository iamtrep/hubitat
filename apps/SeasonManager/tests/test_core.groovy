// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Season Manager. Parses the block between the "Core (pure)"
// and "End core" markers of the shipped app file and runs it, so the tests bind
// to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'SeasonManager.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

// ── date helpers ──
check('validMd ok', core.validMd('03-11'), true)
check('validMd bad month', core.validMd('13-01'), false)
check('validMd Feb 30', core.validMd('02-30'), false)
check('validMd Feb 29', core.validMd('02-29'), true)
check('validMd wrong shape', core.validMd('3-11'), false)
check('validMd non-string', core.validMd(311), false)
check('validIso', core.validIso('2026-10-03'), true)
check('validIso bad', core.validIso('2026-1-03'), false)
check('doyOf Jan 1', core.doyOf('01-01'), 1)
check('doyOf Dec 31', core.doyOf('12-31'), 365)
check('doyOf Feb 29 as Feb 28', core.doyOf('02-29'), 59)
check('inMd inside', core.inMd('2026-03-20', '03-11', '04-08'), true)
check('inMd first day', core.inMd('2026-03-11', '03-11', '04-08'), true)
check('inMd last day', core.inMd('2026-04-08', '03-11', '04-08'), true)
check('inMd after', core.inMd('2026-04-09', '03-11', '04-08'), false)
check('inMd wraps new year', core.inMd('2027-01-15', '12-01', '03-31'), true)
check('inMd wraps leap day', core.inMd('2028-02-29', '12-01', '03-31'), true)
check('inMd wraps outside', core.inMd('2026-04-01', '12-01', '03-31'), false)
check('inSpan excludes last day', core.inSpan('2026-09-14', '05-10', '09-14'), false)
check('inSpan includes day before', core.inSpan('2026-09-13', '05-10', '09-14'), true)
check('cyclicOrdered defaults', core.cyclicOrdered(['03-11', '04-08', '05-10', '08-15', '09-14', '10-26', '11-11']), true)
check('cyclicOrdered swapped', core.cyclicOrdered(['03-11', '04-08', '05-10', '09-14', '08-15', '10-26', '11-11']), false)
check('cyclicOrdered rotated start', core.cyclicOrdered(['10-26', '11-11', '03-11', '04-08', '05-10', '08-15', '09-14']), true)
check('cyclicOrdered all same', core.cyclicOrdered(['03-11', '03-11']), false)
check('addDays across year', core.addDays('2026-12-31', 1), '2027-01-01')
check('addDays back across leap day', core.addDays('2028-03-01', -1), '2028-02-29')
check('numOrNull string', core.numOrNull('21.5'), 21.5)
check('numOrNull blank', core.numOrNull(''), null)
check('numOrNull junk', core.numOrNull('abc'), null)
check('fmtNum', core.fmtNum(17), '17.0')

// ── configuration ──
Map badCfg(Closure edit) { Map x = core.defaultCfg('C'); edit(x); return x }
check('defaults valid', core.validateCfg(core.defaultCfg('C')), [])
check('F defaults valid', core.validateCfg(core.defaultCfg('F')), [])
check('F enter converted', core.defaultCfg('F').summer.enter, 62.6)
check('F w2s converted', core.defaultCfg('F').w2s.above, 37.4)
check('C kept', core.defaultCfg('C').summer.leave, 12.0)
check('bad date reported', core.validateCfg(badCfg { it.summer.fallFrom = '8-15' }), ['Fall from: enter a month and day as MM-DD'])
check('missing threshold', core.validateCfg(badCfg { it.summer.enter = '' }), ['Enter summer threshold: enter a number'])
check('leave not below enter', core.validateCfg(badCfg { it.summer.leave = 17.0 }), ['Leave summer threshold must be below the enter summer threshold'])
check('dates out of order', core.validateCfg(badCfg { it.summer.until = '08-01' }).size(), 1)
check('credit dates free', core.validateCfg(badCfg { it.credit.from = '11-15' }), [])
check('bad credit date', core.validateCfg(badCfg { it.credit.until = '04-31' }), ['Winter credit until: enter a month and day as MM-DD'])

// ── transitions ──
cfg = core.defaultCfg('C')
Map nxt(String season, String iso, Object mean) { core.nextSeason(season, iso, mean == null ? null : new BigDecimal(mean.toString()), cfg) }
check('winter before window stays', nxt('winter', '2026-03-01', 10).season, 'winter')
check('winter in window, cold, no change', nxt('winter', '2026-03-20', 2).reason, null)
check('winter to spring when warm', nxt('winter', '2026-03-20', 4).season, 'spring')
check('threshold is strict', nxt('winter', '2026-03-20', 3).season, 'winter')
check('winter to spring forced on last day', nxt('winter', '2026-04-08', -5).reason, 'last day of the winter to spring window')
check('missed last day still moves', nxt('winter', '2026-04-10', -5).season, 'spring')
check('winter in December stays', nxt('winter', '2026-12-15', 8).season, 'winter')
check('winter with no mean in window stays', nxt('winter', '2026-03-20', null).season, 'winter')
check('spring to summer', nxt('spring', '2026-05-20', 18).season, 'summer')
check('reason names the mean', nxt('spring', '2026-05-20', 18).reason, '3-day mean 18.0 above 17.0')
check('spring before summer possible stays', nxt('spring', '2026-05-09', 25).season, 'spring')
check('spring relabelled fall', nxt('spring', '2026-08-15', 15).season, 'fall')
check('spring warm after fall-from goes to summer', nxt('spring', '2026-08-15', 20).season, 'summer')
check('summer relapse to spring', nxt('summer', '2026-06-01', 10).season, 'spring')
check('summer inside the band stays', nxt('summer', '2026-06-01', 14).season, 'summer')
check('summer leaves to fall after fall-from', nxt('summer', '2026-08-20', 10).season, 'fall')
check('summer ends on until', nxt('summer', '2026-09-14', 25).season, 'fall')
check('summer in January ends, one step only', nxt('summer', '2027-01-15', -10).season, 'fall')
check('fall re-enters summer while open', nxt('fall', '2026-08-25', 20).season, 'summer')
check('fall after summer closed stays', nxt('fall', '2026-09-20', 25).season, 'fall')
check('fall to winter when cold', nxt('fall', '2026-10-30', 2).season, 'winter')
check('fall to winter forced on last day', nxt('fall', '2026-11-11', 10).season, 'winter')
check('fall past window moves', nxt('fall', '2026-11-20', 10).season, 'winter')
check('credit on Dec 1', core.creditOn('2026-12-01', cfg.credit), true)
check('credit on Mar 31', core.creditOn('2027-03-31', cfg.credit), true)
check('credit off Apr 1', core.creditOn('2027-04-01', cfg.credit), false)
check('credit leap day', core.creditOn('2028-02-29', cfg.credit), true)
check('parse case-insensitive', core.parseSeasonArgs('Summer', '2'), [ok: true, season: 'summer', days: 2])
check('parse blank days = 3', core.parseSeasonArgs('fall', ''), [ok: true, season: 'fall', days: 3])
check('parse null days = 3', core.parseSeasonArgs('fall', null), [ok: true, season: 'fall', days: 3])
check('parse zero days', core.parseSeasonArgs('winter', 0), [ok: true, season: 'winter', days: 0])
check('parse unknown season', core.parseSeasonArgs('monsoon', 3).ok, false)
check('parse fractional days', core.parseSeasonArgs('fall', '1.5').ok, false)
check('parse negative days', core.parseSeasonArgs('fall', -1).ok, false)
check('nextPossible winter', core.nextPossible('winter', cfg, '°C'), 'spring between 03-11 and 04-08, as soon as the 3-day mean is above 3.0 °C')

// ── daily means ──
Map fillDay(Map s, String day, Object v, int n) { Map x = s; n.times { x = core.addSample(x, day, new BigDecimal(v.toString())) }; return x }
full = fillDay(fillDay(fillDay([:], '2026-10-01', 10, 24), '2026-10-02', 12, 24), '2026-10-03', 14, 24)

// ── review fixes ──
check('summer before possible-from goes to spring', nxt('summer', '2026-05-03', 25).season, 'spring')
check('summer outside its dates gives the reason', nxt('summer', '2026-05-03', 25).reason, 'outside the summer dates (05-10 to 09-14)')
check('early winter in October stays', nxt('winter', '2026-10-20', 5).season, 'winter')
check('early winter after fall-from stays', nxt('winter', '2026-08-20', 15).season, 'winter')
check('winter catch-up before fall-from still moves', nxt('winter', '2026-07-01', 20).season, 'spring')
check('scheduled run ignores test inputs', core.evalInputs(false, '2026-03-20', '4', '2026-10-04'), [day: '2026-10-04', mean: null])
check('button run uses test inputs', core.evalInputs(true, '2026-03-20', '4', '2026-10-04'), [day: '2026-03-20', mean: 4])
check('button run with bad test day uses today', core.evalInputs(true, '2026-3-20', 'none', '2026-10-04'), [day: '2026-10-04', mean: null])
check('seasonsOn winter', core.seasonsOn('2026-01-15', cfg), ['winter'])
check('seasonsOn winter to spring window', core.seasonsOn('2026-03-11', cfg), ['winter', 'spring'])
check('seasonsOn spring on the window last day', core.seasonsOn('2026-04-08', cfg), ['spring'])
check('seasonsOn spring or summer', core.seasonsOn('2026-06-20', cfg), ['spring', 'summer'])
check('seasonsOn summer or fall from fall-from', core.seasonsOn('2026-08-15', cfg), ['summer', 'fall'])
check('seasonsOn fall on summer last day', core.seasonsOn('2026-09-14', cfg), ['fall'])
check('seasonsOn fall', core.seasonsOn('2026-10-03', cfg), ['fall'])
check('seasonsOn fall or winter', core.seasonsOn('2026-11-10', cfg), ['fall', 'winter'])
check('seasonsOn winter on the window last day', core.seasonsOn('2026-11-11', cfg), ['winter'])
check('seasonsOn agrees with forced moves', (1..365).every { int i ->
    String d = core.addDays('2026-01-01', i - 1); List<String> p = core.seasonsOn(d, cfg)
    p.size() > 1 || p.every { nxt(it, d, null).season == it } }, true)
// ── Open-Meteo ──
check('omCoord rounds', core.omCoord(45.4567), '45.5')
check('omCoord negative', core.omCoord(-73.5871), '-73.6')
check('omCoord null', core.omCoord(null), null)
check('archive url', core.omUrl('archive', 45.5123, -73.5871, 'C', '2026-03-01', '2026-04-15'),
      'https://archive-api.open-meteo.com/v1/archive?latitude=45.5&longitude=-73.6&daily=temperature_2m_mean&timezone=auto&start_date=2026-03-01&end_date=2026-04-15')
check('F url', core.omUrl('archive', 45.5, -73.6, 'F', '2026-03-01', '2026-03-02'),
      'https://archive-api.open-meteo.com/v1/archive?latitude=45.5&longitude=-73.6&daily=temperature_2m_mean&timezone=auto&temperature_unit=fahrenheit&start_date=2026-03-01&end_date=2026-03-02')
check('forecast url', core.omUrl('forecast', 45.5, -73.6, 'C', null, null),
      'https://api.open-meteo.com/v1/forecast?latitude=45.5&longitude=-73.6&daily=temperature_2m_mean&timezone=auto&forecast_days=7')
check('no coordinates gives no url', core.omUrl('archive', null, -73.6, 'C', '2026-03-01', '2026-03-02'), null)
fixture = new groovy.json.JsonSlurper().parse(new File(here, 'fixtures/openmeteo-archive.json'))
check('fixture days', core.parseDaily(fixture).size(), 46)
check('fixture first day', core.parseDaily(fixture)['2026-03-01'], fixture.daily.temperature_2m_mean[0])
check('null mean skipped', core.parseDaily(new groovy.json.JsonSlurper().parseText('{"daily":{"time":["2026-10-01","2026-10-02"],"temperature_2m_mean":[5.2,null]}}')).keySet() as List, ['2026-10-01'])
check('error response gives nothing', core.parseDaily([error: true, reason: 'bad']), [:])
check('null response gives nothing', core.parseDaily(null), [:])
// ── merged daily means ──
Map bd(Map m) { Map x = [:]; m.each { k, v -> x[k] = new BigDecimal(v.toString()) }; return x }
Map daysOf(String from, String to, Object v) { Map x = [:]; String d = from; while (d <= to) { x[d] = new BigDecimal(v.toString()); d = core.addDays(d, 1) }; return x }
om3 = bd(['2026-10-01': 10, '2026-10-02': 12, '2026-10-03': 14])
check('old days pruned', core.addSample(full, '2026-10-14', 5.0).keySet().sort(), ['2026-10-14'])
check('ten days kept', core.addSample(full, '2026-10-11', 5.0).keySet().sort(), ['2026-10-01', '2026-10-02', '2026-10-03', '2026-10-11'])
check('sensorMean', core.sensorMean(full, '2026-10-02', 12), 12.0)
check('sensorMean sparse day', core.sensorMean(fillDay([:], '2026-10-02', 12, 11), '2026-10-02', 12), null)
sens = fillDay(fillDay(fillDay([:], '2026-10-01', 12, 24), '2026-10-02', 14, 24), '2026-10-03', 16, 24)
check('offset from days with both', core.sensorOffset(sens, om3, '2026-10-04'), 2.0)
check('no offset without overlap', core.sensorOffset(sens, [:], '2026-10-04'), null)
Map nine = [:]; Map nineOm = [:]
(1..9).each { int i -> String d = core.addDays('2026-09-30', i); nine = fillDay(nine, d, i <= 2 ? 20 : 11, 24); nineOm[d] = 10.0 }
check('offset uses the last 7 days', core.sensorOffset(nine, nineOm, '2026-10-10'), 1.0)
check('offset ignores today', core.sensorOffset(fillDay(sens, '2026-10-04', 40, 24), bd(om3 + ['2026-10-04': 10]), '2026-10-04'), 2.0)
check('Open-Meteo first', core.mergeDays(sens, om3, '2026-10-04')['2026-10-02'].src, 'openmeteo')
check('Open-Meteo value', core.mergeDays(sens, om3, '2026-10-04')['2026-10-02'].mean, 12.0)
sens4 = fillDay(sens, '2026-10-04', 20, 24)
check('sensor fills missing Open-Meteo day', core.mergeDays(sens4, om3, '2026-10-05')['2026-10-04'].src, 'sensor')
check('sensor day corrected by the offset', core.mergeDays(sens4, om3, '2026-10-05')['2026-10-04'].mean, 18.0)
check('sensor uncorrected without overlap', core.mergeDays(sens4, [:], '2026-10-05')['2026-10-04'].mean, 20.0)
check('today left out', core.mergeDays(sens4, om3, '2026-10-04').containsKey('2026-10-04'), false)
check('day with too few readings gives none', core.mergeDays(fillDay([:], '2026-10-02', 12, 11), [:], '2026-10-04').containsKey('2026-10-02'), false)
check('meanValues', core.meanValues(core.mergeDays(sens, om3, '2026-10-04')), bd(['2026-10-01': 10, '2026-10-02': 12, '2026-10-03': 14]))
check('mean3At', core.mean3At(om3, '2026-10-04'), 12.0)
check('mean3At ignores the day itself', core.mean3At(bd(om3 + ['2026-10-04': 30]), '2026-10-04'), 12.0)
check('missing day gives none', core.mean3At(bd(['2026-10-01': 10, '2026-10-03': 14]), '2026-10-04'), null)
check('mean of a zero day', core.mean3At(bd(['2026-10-01': 0, '2026-10-02': 0, '2026-10-03': 0]), '2026-10-04'), 0)
check('pruneDays', core.pruneDays(daysOf('2026-09-25', '2026-10-12', 1), '2026-10-12', 10).keySet().sort(), daysOf('2026-10-02', '2026-10-11', 1).keySet().sort())
// ── running days ──
warm = daysOf('2026-03-01', '2026-03-31', 5)
check('one change a day at most', core.runDays('winter', '2026-03-20', '2026-03-22', warm, cfg).changes.size(), 1)
check('runDays season', core.runDays('winter', '2026-03-20', '2026-03-22', warm, cfg).season, 'spring')
check('runDays change day', core.runDays('winter', '2026-03-20', '2026-03-22', warm, cfg).changes[0].day, '2026-03-20')
check('runDays records the mean', core.runDays('winter', '2026-03-20', '2026-03-22', warm, cfg).changes[0].mean, 5.0)
flip = daysOf('2026-05-29', '2026-06-01', 20) + daysOf('2026-06-02', '2026-06-06', 5)
check('catch-up runs each day in order', core.runDays('spring', '2026-06-01', '2026-06-06', flip, cfg).changes*.season, ['summer', 'spring'])
check('catch-up change days', core.runDays('spring', '2026-06-01', '2026-06-06', flip, cfg).changes*.day, ['2026-06-01', '2026-06-04'])
check('hold days skipped', core.runDays('winter', '2026-03-20', '2026-03-22', warm, cfg, '2026-03-21').changes[0].day, '2026-03-22')
check('forced move without means', core.runDays('winter', '2026-04-07', '2026-04-08', [:], cfg).season, 'spring')
check('missing days listed', core.runDays('winter', '2026-04-07', '2026-04-08', [:], cfg).missing, ['2026-04-07', '2026-04-08'])
check('runDays empty range', core.runDays('fall', '2026-10-05', '2026-10-04', [:], cfg), [season: 'fall', changes: [], missing: []])

// ── install replay ──
check('anchor before the spring window', core.replayAnchor('2026-03-20', cfg), [day: '2026-03-10', season: 'winter'])
check('anchor is today when settled', core.replayAnchor('2026-10-03', cfg), [day: '2026-10-03', season: 'fall'])
check('anchor before summer', core.replayAnchor('2026-07-01', cfg), [day: '2026-05-09', season: 'spring'])
check('replay finds spring', core.seasonFromHistory('2026-03-20', daysOf('2026-03-08', '2026-03-19', 5), cfg), [season: 'spring', since: '2026-03-11'])
check('replay stays winter', core.seasonFromHistory('2026-03-20', daysOf('2026-03-08', '2026-03-19', -5), cfg), [season: 'winter', since: null])
check('replay finds summer', core.seasonFromHistory('2026-07-01', daysOf('2026-05-07', '2026-06-30', 20), cfg), [season: 'summer', since: '2026-05-10'])
check('replay with a missing day gives none', core.seasonFromHistory('2026-03-20', daysOf('2026-03-08', '2026-03-15', 5), cfg), null)
check('replay on a settled day needs no means', core.seasonFromHistory('2026-10-03', [:], cfg), [season: 'fall', since: null])
// ── outlook ──
check('dayLabel', core.dayLabel('2026-11-01'), 'Nov 1')
obs = daysOf('2026-10-22', '2026-10-24', 5)
cold = daysOf('2026-10-25', '2026-10-31', 0)
o1 = core.outlook('fall', '2026-10-25', obs, cold, cfg, null)
check('outlook change day', o1.changes[0].day, '2026-10-27')
check('outlook change season', o1.changes[0].season, 'winter')
check('outlook forecast change', o1.changes[0].kind, 'forecast')
check('outlook through', o1.through, '2026-11-01')
check('outlook days', o1.days*.day, cold.keySet().sort())
check('outlook text forecast change', core.outlookText(o1, '°C')[0], 'Forecast: winter on Oct 27 (3-day mean 1.7 °C)')
o3 = core.outlook('fall', '2026-11-06', daysOf('2026-11-03', '2026-11-05', 10), [:], cfg, null)
check('no forecast flagged', o3.forecast, false)
check('date-forced change without a forecast', o3.changes*.kind, ['date'])
check('outlook text without forecast', core.outlookText(o3, '°C'), ['No forecast available; only the date limits are shown.', 'Winter on Nov 11 (last day of the fall to winter window)'])
check('outlook hold', core.outlook('fall', '2026-10-25', obs, cold, cfg, '2026-10-29').changes[0].day, '2026-10-30')
o4 = core.outlook('fall', '2026-10-03', daysOf('2026-09-30', '2026-10-02', 15), daysOf('2026-10-03', '2026-10-09', 15), cfg, null)
check('outlook text no change', core.outlookText(o4, '°C'), ['The forecast predicts no season change through Oct 10'])
check('observed from today ignored', core.outlook('fall', '2026-10-25', obs + bd(['2026-10-25': 100]), cold, cfg, null).changes[0].day, '2026-10-27')
// ── hold end across DST ──
TimeZone tor = TimeZone.getTimeZone('America/Toronto')
long at(String local) { java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm'); f.setTimeZone(TimeZone.getTimeZone('America/Toronto')); return f.parse(local).getTime() }
check('hold ending after 05:00 covers that day', core.holdLastDayOf(at('2026-10-10 14:00'), tor), '2026-10-10')
check('hold ending at 05:00 stops the day before', core.holdLastDayOf(at('2026-10-10 05:00'), tor), '2026-10-09')
check('hold ending 05:30 on spring-forward day covers it', core.holdLastDayOf(at('2027-03-14 05:30'), tor), '2027-03-14')
check('hold ending 04:30 on fall-back day stops the day before', core.holdLastDayOf(at('2026-11-01 04:30'), tor), '2026-10-31')
check('no hold', core.holdLastDayOf(null, tor), null)
// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)
