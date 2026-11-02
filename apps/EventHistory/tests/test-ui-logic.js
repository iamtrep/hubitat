// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the logic in eventhistory-ui.html: query parsing, severity, CSV cells.
// Run: node apps/EventHistory/tests/test-ui-logic.js      (no hub; instant)
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const html = fs.readFileSync(path.join(__dirname, '../eventhistory-ui.html'), 'utf8');
const fn = name => {
  const start = html.indexOf('function ' + name + '(');
  assert(start > 0, name);
  return html.slice(start, html.indexOf('\n}\n', start) + 3);
};
const sets = ['CRITICAL', 'WARNING', 'NOTICE'].map(n => html.match(new RegExp('const ' + n + ' = .*'))[0]).join('\n');
const m = new Function(sets + fn('parseQuery') + fn('severity') + fn('csvCell') + 'return {parseQuery, severity, csvCell};')();
let n = 0;
const t = (name, f) => { f(); n++; console.log('  ok   ' + name); };

t('words, exclusions and phrases', () => {
  assert.deepStrictEqual(m.parseQuery('zigbee -offline "System Start" -"two words"'),
    {inc: ['zigbee', 'system start'], exc: ['offline', 'two words']});
});
t('a lone minus is ignored', () => assert.deepStrictEqual(m.parseQuery('a - b'), {inc: ['a', 'b'], exc: []}));
t('severity by name and by value', () => {
  assert.strictEqual(m.severity({n: 'severeLoad'}), 'critical');
  assert.strictEqual(m.severity({n: 'zigbeeStatus', v: 'offline'}), 'critical');
  assert.strictEqual(m.severity({n: 'zigbeeStatus', v: 'online'}), '');
  assert.strictEqual(m.severity({n: 'manualReboot'}), 'warning');
  assert.strictEqual(m.severity({n: 'systemStart'}), 'notice');
  assert.strictEqual(m.severity({n: 'sunset'}), '');
});
t('a CSV cell can\'t run as a formula', () => {
  for (const v of ['=1+1', '+1', '-1', '@x', '\tx', '\rx']) assert(m.csvCell(v).replace(/^"/, '').startsWith("'"), JSON.stringify(v));
});
t('CSV quoting', () => {
  assert.strictEqual(m.csvCell('a,b'), '"a,b"');
  assert.strictEqual(m.csvCell('say "hi"'), '"say ""hi"""');
  assert.strictEqual(m.csvCell('two\nlines'), '"two\nlines"');
  assert.strictEqual(m.csvCell(null), '');
  assert.strictEqual(m.csvCell(0), '0');
});
const pickSrc = html.slice(html.indexOf('function newPick('), html.indexOf('\nfunction loadRules'));
const pk = new Function(pickSrc + fn('savePick') + fn('readPick') +
  'return {newPick, picks, pickAll, pickOne, savePick, readPick};')();
const keys = ['a', 'b', 'c'];
const shown = p => keys.filter(k => pk.picks(p, k));
t('a filter starts with everything shown; unchecking one leaves it out', () => {
  const p = pk.newPick();
  assert.deepStrictEqual(shown(p), keys);
  pk.pickOne(p, 'b', false, keys);
  assert.deepStrictEqual(shown(p), ['a', 'c']);
  assert(pk.picks(p, 'new'), 'a type that appears later shows while leaving one out');
});
t('unchecking All, then checking one, shows only that one', () => {
  const p = pk.newPick();
  pk.pickAll(p, false);
  assert.deepStrictEqual(shown(p), []);
  pk.pickOne(p, 'c', true, keys);
  assert.deepStrictEqual(shown(p), ['c']);
  assert(!pk.picks(p, 'new'), 'a type that appears later stays hidden while showing one');
});
t('checking or unchecking every item by hand is the same as All', () => {
  const p = pk.newPick();
  keys.forEach(k => pk.pickOne(p, k, false, keys));
  assert.deepStrictEqual(p, {only: true, set: new Set()});
  keys.forEach(k => pk.pickOne(p, k, true, keys));
  assert.deepStrictEqual(p, pk.newPick());
});
t('a filter survives the address, and older type= links still show only those', () => {
  for (const make of [p => pk.pickOne(p, 'b', false, keys), p => pk.pickAll(p, false), p => { pk.pickAll(p, false); pk.pickOne(p, 'a', true, keys); }, () => {}]) {
    const p = pk.newPick(); make(p);
    const u = new URLSearchParams(); pk.savePick(u, 'type', p);
    assert.deepStrictEqual(pk.readPick(new URLSearchParams(u.toString()), 'type'), p, u.toString());
  }
  assert.deepStrictEqual(pk.readPick(new URLSearchParams('type=x'), 'type'), {only: true, set: new Set(['x'])});
});
t('event text never reaches innerHTML', () => {
  assert(!/innerHTML|outerHTML|insertAdjacentHTML|document\.write/.test(html));
});
t('the theme comes from the hub, never the system preference', () => {
  assert(!/prefers-color-scheme/.test(html));
});
t('app and page versions match', () => {
  const app = fs.readFileSync(path.join(__dirname, '../EventHistory.groovy'), 'utf8');
  assert.strictEqual(html.match(/const CODE_VERSION = '([^']+)'/)[1], app.match(/CODE_VERSION = "([^"]+)"/)[1]);
});
console.log(n + ' passed');
