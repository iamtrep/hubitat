#!/usr/bin/env node
// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Unit tests of the tester page's pure logic. Run: node test-tester-ui.js
'use strict';
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'androidtv-tester-ui.html'), 'utf8');
const m = /<script id="logic">([\s\S]*?)<\/script>/.exec(html);
if (!m) { console.log('FAIL extract logic block'); process.exit(1); }
const win = {};
vm.runInNewContext(m[1], { window: win });
const U = win.TesterUI;

let failed = 0, passed = 0;
function check(name, fn) {
  try { fn(); passed++; console.log('ok   ' + name); }
  catch (e) { failed++; console.log('FAIL ' + name + ': ' + e.message); }
}
const eq = assert.strictEqual;
// Objects from the vm context have another prototype; compare as JSON.
const deq = (a, b) => assert.deepStrictEqual(JSON.parse(JSON.stringify(a)), b);

check('CODE_VERSION literal in file', () => assert.ok(html.includes("const CODE_VERSION = '0.7.0'")));
check('CODE_VERSION exported', () => eq(U.CODE_VERSION, '0.7.0'));
check('required element ids present', () => {
  for (const id of ['run', 'abort', 'setup', 'setup-kind', 'attended', 'opt-standby', 'opt-standby-min', 'opt-cut', 'opt-wrongcode', 'rows',
    'current-countdown', 'prompt', 'prompt-text', 'prompt-buttons', 'prompt-countdown', 'timeout-note', 'report', 'copy', 'download-profile',
    'pad', 'pad-key', 'pad-text', 'pad-link', 'version-banner', 'pad-log', 'pad-log-entries', 'pad-log-copy', 'pad-log-clear']) {
    assert.ok(html.includes('id="' + id + '"'), id);
  }
});
check('kind radios', () => {
  const vals = [...html.matchAll(/<input type="radio" name="kind" value="([^"]+)"/g)].map(x => x[1]);
  deq(vals, ['tv', 'box', 'unknown']);
});
check('pad buttons: command ids and key names', () => {
  const found = [...html.matchAll(/<button[^>]*data-key="([^"]+)"(?: data-arg="([^"]+)")?/g)].map(x => x[2] ? x[1] + ':' + x[2] : x[1]);
  const want = ['on', 'off', 'volumeUp', 'volumeDown', 'play', 'pause', 'stop', 'connect', 'disconnect', 'sendKey', 'sendText', 'launchApp',
    ...['DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT', 'DPAD_CENTER', 'BACK', 'HOME', 'MENU', 'SEARCH', 'ASSIST', 'SETTINGS', 'TV_INPUT', 'GUIDE',
      'VOLUME_MUTE', 'CHANNEL_UP', 'CHANNEL_DOWN', 'MEDIA_PREVIOUS', 'MEDIA_REWIND', 'MEDIA_FAST_FORWARD', 'MEDIA_NEXT'].map(k => 'sendKey:' + k)];
  deq(found.slice().sort(), want.slice().sort());
});
check('no external resources', () => assert.ok(!/(src|href)="https?:/.test(html) && !/url\(/.test(html)));
check('legend wording', () => {
  assert.ok(html.includes("box differs: your TV does something the driver doesn't assume, often harmless."));
  assert.ok(html.includes("driver differs: the driver didn't act on what the TV sent."));
});

// countdown
check('formatCountdown basics', () => {
  eq(U.formatCountdown(120000), '2:00'); eq(U.formatCountdown(61000), '1:01'); eq(U.formatCountdown(9000), '0:09');
});
check('formatCountdown rounds up', () => { eq(U.formatCountdown(1), '0:01'); eq(U.formatCountdown(59001), '1:00'); });
check('formatCountdown out of time / bad input', () => {
  eq(U.formatCountdown(0), '0:00'); eq(U.formatCountdown(-5), '0:00'); eq(U.formatCountdown(null), '0:00'); eq(U.formatCountdown(NaN), '0:00');
});
check('clock offset correction', () => {
  const off = U.clockOffset(1000000, 400000);
  eq(off, 600000);
  eq(U.remainingMs(1120000, off, 400000), 120000);
  eq(U.remainingMs(1120000, off, 410000), 110000);
});
check('clock offset keeps the least delayed reply', () => {
  eq(U.nextOffset(null, -300), -300);
  eq(U.nextOffset(-300, -900), -300);
  eq(U.nextOffset(-300, -100), -100);
  eq(U.nextOffset(-300, 9000), 9000);
});
check('countdownView text and fraction', () => {
  const v = U.countdownView(1000, 11000, 0, 6000);
  eq(v.text, '0:05'); eq(v.fraction, 0.5);
  const e = U.countdownView(1000, 11000, 0, 20000);
  eq(e.text, '0:00'); eq(e.fraction, 1);
  eq(U.countdownView(1, null, 0, 5).text, '');
});

// rows
const cur = { test: 3, name: 'Power off/on', step: 'off', label: 'Waiting for the box to report off' };
check('rowView ok/box/driver/skipped icons', () => {
  eq(U.rowView({ test: 1, name: 'A', state: 'ok', detail: 'as expected' }).icon, '\u2713');
  eq(U.rowView({ test: 1, name: 'A', state: 'box', detail: 'x' }).icon, '\u26A0');
  eq(U.rowView({ test: 1, name: 'A', state: 'driver', detail: 'x' }).icon, '\u2717');
  eq(U.rowView({ test: 1, name: 'A', state: 'skipped', detail: 'x' }).icon, '\u2013');
  eq(U.rowView({ test: 1, name: 'A', state: 'ok', detail: 'as expected' }).detail, 'as expected');
  eq(U.rowView({ test: 1, name: 'A', state: 'box' }).cls, 's-box');
});
check('old "tv" state is not a state any more', () => eq(U.rowView({ test: 1, state: 'tv' }).state, 'not run'));
check('rowView running shows step label only on the current test', () => {
  const r = U.rowView({ test: 3, name: 'Power off/on', state: 'running', detail: '' }, cur);
  eq(r.detail, cur.label); eq(r.current, true); eq(r.cls, 's-running');
  const o = U.rowView({ test: 8, name: 'HOME', state: 'running', detail: 'd' }, cur);
  eq(o.detail, 'd'); eq(o.current, false);
});
check('rowView waiting / not run / unknown state', () => {
  eq(U.rowView({ test: 2, name: 'B', state: 'waiting' }).cls, 's-waiting');
  eq(U.rowView({ test: 2, name: 'B', state: 'not run' }).cls, 's-notrun');
  eq(U.rowView({ test: 2, name: 'B', state: 'weird' }).state, 'not run');
  eq(U.rowView({ test: 2, state: 'ok' }).name, 'Test 2');
});
check('rowHtml escapes hub strings', () => {
  const h = U.rowHtml(U.rowView({ test: 3, name: '<b>x</b>', state: 'ok', detail: '"q" & <i>' }));
  assert.ok(!h.includes('<b>') && !h.includes('<i>'));
  assert.ok(h.includes('&lt;b&gt;x&lt;/b&gt;') && h.includes('&quot;q&quot; &amp; &lt;i&gt;'));
  assert.ok(h.includes('data-test="3"'));
});
check('test list follows the run order', () => {
  const names = {}; U.TEST_LIST.forEach(t => { names[t[0]] = t[1]; });
  eq(U.TEST_LIST.length, 15);
  eq(names[9], 'Discovery'); eq(names[10], 'Text input'); eq(names[11], 'Media keys'); eq(names[12], 'Long standby');
  eq(names[13], 'Power cut'); eq(names[14], 'Wrong pairing code'); eq(names[15], 'Pairing');
});
check('idleRows: paired shows 1-11, optional tests when ticked', () => {
  const ids = c => U.idleRows(c).map(r => r.test);
  deq(ids({ paired: true }), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
  deq(ids({ paired: true, standby: true, cut: true, wrongcode: true }), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]);
  deq(ids({ paired: true, cut: true }), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13]);
  deq(ids(undefined), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
});
check('idleRows: unpaired shows the pairing test alone', () => {
  deq(U.idleRows({ paired: false, standby: true }).map(r => r.test), [15]);
  assert.ok(U.idleRows({ paired: true }).every(x => x.state === 'not run'));
});
check('runMode', () => {
  eq(U.runMode(null), 'before'); eq(U.runMode({ status: null }), 'before'); eq(U.runMode({ status: 'running' }), 'running');
  for (const s of ['done', 'aborted', 'stalled', 'interrupted']) eq(U.runMode({ status: s }), 'after');
});
check('summaryLine', () => {
  eq(U.summaryLine(null), '');
  eq(U.summaryLine({ status: 'done', rows: [{ state: 'ok' }, { state: 'ok' }, { state: 'box' }, { state: 'driver' }, { state: 'skipped' }] }),
    'Done: 2 ok, 1 box, 1 driver, 1 skipped');
  eq(U.summaryLine({ status: 'stalled', reason: 'no progress', rows: [] }), 'Stalled (no progress): 0 ok, 0 box, 0 driver, 0 skipped');
  eq(U.summaryLine({ status: 'running', rows: [{ state: 'ok' }] }), 'Running');
});

// prompts
const prompt = { id: 'p4', test: 3, kind: 'picture', text: 'Click when the picture is back.', askedAtMs: 1000, deadlineMs: 61000,
  choices: [{ id: 'on', label: 'Picture is back' }, { id: 'never', label: "It didn't come back" }] };
check('promptView choices, test and countdown', () => {
  const v = U.promptView(prompt, 0, 31000, false);
  eq(v.id, 'p4'); eq(v.test, 3); eq(v.kind, 'picture'); eq(v.countdown, '0:30'); eq(v.expired, false); eq(v.choices.length, 2);
  eq(v.choices[0].id, 'on'); eq(v.choices[0].disabled, false);
});
check('promptView renders every kind generically', () => {
  for (const kind of ['dark', 'picture', 'app', 'home']) {
    const v = U.promptView({ id: 'x', test: 5, kind, text: 'Q', choices: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }, { id: 'c', label: 'C' }] }, 0, 0, false);
    eq(v.kind, kind); eq(v.choices.length, 3);
  }
});
check('promptView with offset', () => { eq(U.promptView(prompt, 50000, 11000, false).countdown, '0:00'); eq(U.promptView(prompt, 20000, 11000, false).countdown, '0:30'); });
check('promptView clicked disables buttons', () => {
  assert.ok(U.promptView(prompt, 0, 5000, true).choices.every(c => c.disabled));
});
check('promptView expired', () => {
  const v = U.promptView(prompt, 0, 130000, false);
  eq(v.expired, true); eq(v.countdown, '0:00'); assert.ok(v.choices.every(c => c.disabled));
});
check('promptView null and no deadline', () => {
  eq(U.promptView(null, 0, 0, false), null);
  eq(U.promptView({ id: 'a', text: 't', choices: [{ id: 'done', label: 'Done' }] }, 0, 5, false).expired, false);
});
check('promptButtonsHtml escapes and marks disabled', () => {
  const h = U.promptButtonsHtml(U.promptView({ id: 'a', text: 't', choices: [{ id: 'x"y', label: '<i>' }] }, 0, 0, true));
  assert.ok(h.includes('data-choice="x&quot;y"') && h.includes('&lt;i&gt;') && h.includes(' disabled'));
});

// timeout note
check('timeoutNote window of 4 s', () => {
  const lt = { test: 3, step: 'picture', label: 'l', atMs: 100000 };
  eq(U.timeoutNote(lt, 0, 100000), 'Test 3: no answer, moving on');
  eq(U.timeoutNote(lt, 0, 103999) !== null, true);
  eq(U.timeoutNote(lt, 0, 104000), null);
  eq(U.timeoutNote(lt, 0, 200000), null);
});
check('timeoutNote uses hub time', () => {
  const lt = { test: 7, atMs: 100000 };
  eq(U.timeoutNote(lt, 500000, 100000 - 500000 + 2000) !== null, true);
  eq(U.timeoutNote(lt, 500000, 100000 - 500000 + 4000), null);
});
check('timeoutNote none', () => { eq(U.timeoutNote(null, 0, 1), null); eq(U.timeoutNote({}, 0, 1), null); });

// pad / controls
check('padEnabled by run status', () => {
  eq(U.padEnabled(null), true); eq(U.padEnabled({ status: null }), true); eq(U.padEnabled({ status: 'running' }), false);
  for (const s of ['done', 'aborted', 'stalled', 'interrupted']) eq(U.padEnabled({ status: s }), true);
});
check('controls', () => {
  const dev = { selected: true };
  deq(U.controls({ device: dev, run: null }), { run: true, abort: false, setup: true, pad: true });
  deq(U.controls({ device: dev, run: { status: 'running' } }), { run: false, abort: true, setup: false, pad: false });
  eq(U.controls({ device: { selected: false }, run: null }).run, false);
});
check('padRequest: plain commands', () => {
  for (const c of ['on', 'off', 'volumeUp', 'volumeDown', 'play', 'pause', 'stop', 'connect', 'disconnect']) deq(U.padRequest(c, null, {}), { params: { name: c } });
  eq(U.padRequest('explode', null, {}).error, 'Unknown button');
});
check('padRequest: sendKey with a button key name and from the field', () => {
  deq(U.padRequest('sendKey', 'DPAD_UP', { key: 'ignored' }), { params: { name: 'sendKey', key: 'DPAD_UP' } });
  deq(U.padRequest('sendKey', null, { key: ' MEDIA_PLAY_PAUSE ' }), { params: { name: 'sendKey', key: 'MEDIA_PLAY_PAUSE' } });
  deq(U.padRequest('sendKey', null, { key: '85' }), { params: { name: 'sendKey', key: '85' } });
  assert.ok(U.padRequest('sendKey', null, { key: '' }).error);
  assert.ok(U.padRequest('sendKey', null, { key: 'a b' }).error);
  assert.ok(U.padRequest('sendKey', null, { key: 'A'.repeat(41) }).error);
});
check('padRequest: sendText limits', () => {
  deq(U.padRequest('sendText', null, { text: 'hello there' }), { params: { name: 'sendText', text: 'hello there' } });
  assert.ok(U.padRequest('sendText', null, { text: '' }).error);
  assert.ok(U.padRequest('sendText', null, { text: 'x'.repeat(201) }).error);
  eq(U.padRequest('sendText', null, { text: 'x'.repeat(200) }).error, undefined);
});
check('padRequest: launchApp link', () => {
  deq(U.padRequest('launchApp', null, { link: 'https://www.youtube.com' }), { params: { name: 'launchApp', link: 'https://www.youtube.com' } });
  deq(U.padRequest('launchApp', null, { link: 'market://launch?id=com.x' }).params.name, 'launchApp');
  assert.ok(U.padRequest('launchApp', null, { link: '' }).error);
  assert.ok(U.padRequest('launchApp', null, { link: 'youtube' }).error);
  assert.ok(U.padRequest('launchApp', null, { link: 'https://x/' + 'a'.repeat(300) }).error);
});

// pad log
const pe = (at, button, key, n) => ({ atMs: at, button, key, lines: Array.from({ length: n }, (_, i) => 'line ' + (i + 1)) });
check('padLogView: up to 6 lines are all shown, no toggle', () => {
  const v = U.padLogView([pe(1000, 'Home', null, 6)], 0, {})[0];
  eq(v.lines.length, 6); eq(v.hidden, 0); eq(v.canCollapse, false);
  assert.ok(!U.padLogHtml([v]).includes('pad-more'));
});
check('padLogView: beyond 6 lines the rest hides behind more', () => {
  const v = U.padLogView([pe(1000, 'Home', null, 15)], 0, {})[0];
  eq(v.lines.length, 6); eq(v.lines[5], 'line 6'); eq(v.hidden, 9); eq(v.expanded, false);
  const h = U.padLogHtml([v]);
  assert.ok(h.includes('+9 more') && h.includes('data-at="1000"') && !h.includes('line 7'));
});
check('padLogView: an opened entry shows every line and offers less', () => {
  const v = U.padLogView([pe(1000, 'Home', null, 15), pe(2000, 'Mute', null, 3)], 0, { 1000: true })[0];
  eq(v.lines.length, 15); eq(v.hidden, 0); eq(v.canCollapse, true);
  const h = U.padLogHtml([v]);
  assert.ok(h.includes('line 15') && h.includes('>less<') && !/\+\d+ more/.test(h));
});
check('padLogView: order kept (newest last), time, key and missing fields', () => {
  const vs = U.padLogView([pe(1000, 'Home', null, 0), { atMs: 2000, button: 'sendKey', key: 'HOME' }], 0, null);
  eq(vs.length, 2); eq(vs[0].atMs, 1000); eq(vs[1].key, 'HOME'); eq(vs[1].lines.length, 0);
  assert.ok(/^\d\d:\d\d:\d\d$/.test(vs[0].time));
  deq(U.padLogView(null, 0, {}), []); deq(U.padLogView(undefined, 0, {}), []);
});
check('clockText follows the offset', () => {
  const d = new Date(2026, 10, 8, 12, 30, 45).getTime();
  eq(U.clockText(d, 0), '12:30:45'); eq(U.clockText(d + 60000, 60000), '12:30:45');
});
check('padLogHtml: empty state, escapes hub text', () => {
  assert.ok(U.padLogHtml([]).includes('No keys pressed yet'));
  const h = U.padLogHtml(U.padLogView([{ atMs: 1, button: '<b>', key: 'K"Y', lines: ['<i>&'] }], 0, {}));
  assert.ok(h.includes('&lt;b&gt;') && !h.includes('<i>') && h.includes('&lt;i&gt;&amp;') && h.includes('K&quot;Y'));
});
check('padLogText: all lines, collapsed or not', () => {
  const t = U.padLogText([pe(1000, 'Home', null, 8), pe(2000, 'Send key', 'HOME', 0)], 0);
  const rows = t.split('\n');
  eq(rows.length, 10); assert.ok(rows[0].endsWith(' Home') && rows[9].endsWith(' Send key HOME') && rows[8] === '   line 8');
  eq(U.padLogText(null, 0), '');
});
check('pad log panel sits under the pad and has Copy and Clear', () => {
  assert.ok(html.indexOf('id="pad-log"') > html.indexOf('id="pad"') && /id="pad-log-copy"/.test(html) && /id="pad-log-clear"/.test(html) && html.includes('id="pad-log-entries"'));
});

// version banner, device line
check('versionBanner', () => {
  eq(U.versionBanner('0.7.0', '0.7.0'), null);
  eq(U.versionBanner('0.7.0', undefined), null);
  assert.ok(/0\.7\.0.*0\.7\.1/.test(U.versionBanner('0.7.0', '0.7.1')));
});
check('deviceLine', () => {
  eq(U.deviceLine({ name: 'TV', switch: 'on', networkStatus: 'online', pairing: 'paired', selected: true }), 'TV \u00B7 switch on \u00B7 online \u00B7 paired');
  eq(U.deviceLine({ selected: false }), 'No device selected');
});

// setup
check('setupView: paired shows the optional tests, plug enables the cut', () => {
  const v = U.setupView({ device: { pairing: 'paired', plug: true }, options: { standbyMinutes: 5 } });
  eq(v.paired, true); eq(v.showOptional, true); eq(v.unpairedNote, false); eq(v.cutAvailable, true); eq(v.standbyDefault, 5);
  eq(U.setupView({ device: { pairing: 'paired', plug: false }, options: {} }).cutAvailable, false);
  eq(U.setupView({ device: { pairing: 'paired' }, options: {} }).standbyDefault, 2);
});
check('setupView: unpaired says pairing alone and hides the optional tests', () => {
  for (const p of ['unpaired', 'pairing', undefined]) {
    const v = U.setupView({ device: { pairing: p, plug: true }, options: {} });
    eq(v.paired, false); eq(v.showOptional, false); eq(v.unpairedNote, true);
  }
  eq(U.setupView(null).unpairedNote, false);
});
check('standbyMinutes clamps to 1..240', () => {
  eq(U.standbyMinutes('5', 2), 5); eq(U.standbyMinutes('0', 2), 1); eq(U.standbyMinutes('999', 2), 240);
  eq(U.standbyMinutes('', 2), 2); eq(U.standbyMinutes('abc', 3), 3); eq(U.standbyMinutes('7.9', 2), 7); eq(U.standbyMinutes('', 999), 2);
});

// urls and run serialization
check('accessToken parse', () => {
  eq(U.accessToken('?access_token=abc-123'), 'abc-123'); eq(U.accessToken('?x=1&access_token=a%20b'), 'a b'); eq(U.accessToken(''), '');
});
check('endpointUrl replaces last path segment', () => {
  const loc = { pathname: '/apps/api/12/ui.html', search: '?access_token=tok' };
  eq(U.endpointUrl(loc, 'status'), '/apps/api/12/status?access_token=tok');
  eq(U.endpointUrl(loc, 'answer', { id: 'p4', choice: 'on' }), '/apps/api/12/answer?id=p4&choice=on&access_token=tok');
  eq(U.endpointUrl(loc, 'key', { name: 'sendKey', key: 'HOME' }), '/apps/api/12/key?name=sendKey&key=HOME&access_token=tok');
  eq(U.endpointUrl(loc, 'padlog/clear'), '/apps/api/12/padlog/clear?access_token=tok');
});
check('runParams: defaults', () => {
  deq(U.runParams(null, true, true), { attended: 'true', deviceKind: 'unknown' });
  eq(U.encodeQuery(U.runParams({ attended: true, deviceKind: 'box' }, true, true)), 'attended=true&deviceKind=box');
});
check('runParams: attended off sends false; bad kind is unknown', () => {
  eq(U.runParams({ attended: false }, true, true).attended, 'false');
  eq(U.runParams({ attended: 'true' }, true, true).attended, 'true');
  eq(U.runParams({ deviceKind: 'toaster' }, true, true).deviceKind, 'unknown');
  eq(U.runParams({ deviceKind: 'tv' }, true, true).deviceKind, 'tv');
});
check('runParams: optional tests', () => {
  deq(U.runParams({ attended: true, deviceKind: 'tv', standby: true, standbyMin: '10', cut: true, wrongcode: true }, true, true),
    { attended: 'true', deviceKind: 'tv', standby: 10, cut: 'true', wrongcode: 'true' });
  eq(U.runParams({ standby: true, standbyMin: '' }, true, true).standby, 2);
  eq(U.runParams({ standby: true, standbyMin: '500' }, true, true).standby, 240);
  eq(U.runParams({ standby: false, standbyMin: '10' }, true, true).standby, undefined);
  eq(U.runParams({ cut: true }, true, false).cut, undefined);
});
check('runParams: unpaired sends no optional tests', () => {
  deq(U.runParams({ attended: true, deviceKind: 'box', standby: true, cut: true, wrongcode: true }, false, true), { attended: 'true', deviceKind: 'box' });
});

// report / profile
check('reportView with profile', () => {
  const v = U.reportView({ status: 'done', report: 'line1\nline2', profile: { model: 'X' } });
  eq(v.text, 'line1\nline2'); eq(v.hasProfile, true); deq(JSON.parse(v.profileJson), { model: 'X' }); assert.ok(v.profileJson.endsWith('\n'));
});
check('reportView without profile', () => {
  const v = U.reportView({ report: 'r', profile: null });
  eq(v.hasProfile, false); eq(v.profileJson, null);
  eq(U.reportView(null).text, '');
});
check('runKey one report per run', () => {
  eq(U.runKey(null), null); eq(U.runKey({ status: 'running', startedAtMs: 5 }), null);
  eq(U.runKey({ status: 'done', startedAtMs: 5 }), '5'); eq(U.runKey({ status: 'aborted', startedAtMs: 6 }), '6');
});

// polling
check('pollState note after 3 failures', () => {
  let s = null;
  s = U.pollState(s, false); eq(s.noteVisible, false);
  s = U.pollState(s, false); eq(s.noteVisible, false);
  s = U.pollState(s, false); eq(s.noteVisible, true);
  s = U.pollState(s, true); eq(s.noteVisible, false); eq(s.fails, 0);
});

check('escapeHtml', () => {
  eq(U.escapeHtml(`<a href="x">'&'</a>`), '&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;');
  eq(U.escapeHtml(null), '');
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
