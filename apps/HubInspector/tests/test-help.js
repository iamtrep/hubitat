#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 (pure-JS) unit test for the help popups: the HELP dictionary, helpBtn() and helpHtml().
// Loads the shipped block from hub_inspector_ui.html (between "===== HELP POPUPS =====" and the
// popover state) so the test stays bound to the real entries.
//
// Checks: every help key the page uses has an entry; every entry has a title, a definition and a
// README anchor that exists; the network-tab tables give every jargon column a key; thresholds in
// the text match the constants the colouring uses; no help markup reaches the AI/forum export.
//
// Run: node apps/HubInspector/tests/test-help.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_inspector_ui.html');
const README = path.join(__dirname, '..', 'README.md');
const src = fs.readFileSync(HTML, 'utf8');
const readme = fs.readFileSync(README, 'utf8');

const a = src.indexOf('// ===== HELP POPUPS =====');
const b = src.indexOf('const HLP=');
assert(a >= 0 && b > a, 'help block not found');
const block = src.slice(a, b);
const hFn = src.match(/function h\(s\)\{[^\n]*\}/)[0];
const thLine = src.match(/let TH=\{[^\n]*\};/)[0];
const harness = hFn + '\n' + thLine + '\nfunction ftemp(c){return c+" °C"}\n' + block +
  '\nmodule.exports={HELP,helpBtn,helpHtml,setStack:v=>{HELP_ZWJS=v},TH};';
const tmp = path.join(os.tmpdir(), 'hd_help_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const { HELP, helpBtn, helpHtml, setStack, TH } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0;
function t(name, fn) { fn(); pass++; console.log('  ok  ' + name); }

// GitHub heading anchors, with the -1, -2 suffixes it adds to repeated headings.
function anchors(md) {
  const seen = {}, out = new Set();
  let fence = false;
  md.split('\n').forEach(line => {
    if (/^```/.test(line)) { fence = !fence; return; }
    if (fence) return;
    const m = line.match(/^#{1,6}\s+(.*)$/);
    if (!m) return;
    let slug = m[1].trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s/g, '-');
    if (seen[slug] != null) { seen[slug]++; slug = slug + '-' + seen[slug]; } else seen[slug] = 0;
    out.add(slug);
  });
  return out;
}

t('every key used in the page has an entry', () => {
  const used = new Set();
  for (const m of src.matchAll(/helpBtn\('([^']+)'\)/g)) used.add(m[1]);
  for (const m of src.matchAll(/\{l:'[^']*',k:'([^']+)'/g)) used.add(m[1]);
  for (const m of src.matchAll(/help:'([^']+)'/g)) used.add(m[1]);
  // Any key-shaped literal outside the dictionary (ni / mc / resTile arguments).
  const outside = src.slice(0, a) + src.slice(b);
  for (const m of outside.matchAll(/'((?:net|zb|zw|zt|zj|mt|hm|al|pf|dv)\.[A-Za-z]+)'/g)) used.add(m[1]);
  assert(used.size > 40, 'expected many help keys, found ' + used.size);
  const missing = [...used].filter(k => !HELP[k]);
  assert.deepStrictEqual(missing, []);
});

t('every entry has a title, a definition and an existing README anchor', () => {
  const an = anchors(readme);
  Object.entries(HELP).forEach(([k, e]) => {
    assert(typeof e.t === 'string' && e.t.length, k + ': title');
    assert(typeof e.d === 'string' && e.d.length > 10, k + ': definition');
    assert(e.doc && an.has(e.doc), k + ': README anchor #' + e.doc + ' not found');
    if (e.th != null) assert(typeof (typeof e.th === 'function' ? e.th() : e.th) === 'string', k + ': threshold');
  });
});

t('hub alert keys cover every flag the app relays', () => {
  const groovy = fs.readFileSync(path.join(__dirname, '..', 'HubInspector.groovy'), 'utf8');
  const map = groovy.match(/ALERT_DISPLAY_NAMES = \[([\s\S]*?)\n\]/)[1];
  const keys = [...map.matchAll(/^\s*(\w+):/gm)].map(m => m[1]);
  assert(keys.length >= 10);
  assert.deepStrictEqual(keys.filter(k => !HELP['pa.' + k]), []);
});

t('network-tab tables give every jargon column a key', () => {
  const PLAIN = new Set(['Device', 'Node', 'Destination', 'Via', 'Direct', 'Route']);
  ['zwMeshTbl', 'zbNeighborTbl', 'zbRouteTbl'].forEach(id => {
    const s = src.indexOf("tbl('" + id + "'");
    assert(s >= 0, id);
    const body = src.slice(s, src.indexOf('],', s));
    for (const m of body.matchAll(/\{l:'([^']+)'(,k:'[^']+')?/g)) {
      if (PLAIN.has(m[1]) || m[1] === 'Age' && id === 'zbRouteTbl' || m[1] === 'Concentrator') continue;
      assert(m[2], id + ': column "' + m[1] + '" has no help key');
    }
  });
});

t('helpBtn: unknown or empty key gives nothing', () => {
  assert.strictEqual(helpBtn('no.such'), '');
  assert.strictEqual(helpBtn(''), '');
  assert.strictEqual(helpBtn(undefined), '');
});

t('helpBtn escapes the title in its label', () => {
  const out = helpBtn('zw.failMsgs');
  assert(/^<button type="button" class="hlp" data-help="zw\.failMsgs" aria-label="[^"<>]*">/.test(out), out);
  HELP['x.test'] = { t: 'a "quoted" <b>', d: 'definition text here' };
  assert(!/<b>/.test(helpBtn('x.test')));
  assert(!/<b>/.test(helpHtml('x.test')));
  delete HELP['x.test'];
});

t('helpHtml shows the line for the hub\'s Z-Wave stack', () => {
  setStack(true);
  let o = helpHtml('zw.rssi');
  assert(/dBm: negative/.test(o) && !/Z\/IP reports/.test(o));
  setStack(false);
  o = helpHtml('zw.rssi');
  assert(/Z\/IP reports/.test(o) && !/dBm: negative/.test(o));
  setStack(null);
  o = helpHtml('zw.rssi');
  assert(/<b>Z-Wave JS:<\/b>/.test(o) && /<b>Z\/IP:<\/b>/.test(o));
});

t('configurable thresholds show the current setting', () => {
  TH.chattyDeviceThreshold = 25;
  assert(/Red at 25 or more/.test(helpHtml('zw.msgsMin')));
  TH.warnCpuLoad = 3; TH.critCpuLoad = 7;
  assert(/above 3, red above 7/.test(helpHtml('al.cpu')));
});

t('threshold text matches the constants the colouring uses', () => {
  assert(/const ZWAVE_PER_CRIT=1\.0, ZIGBEE_LQI_CRIT=150;/.test(src));
  const groovySrc = fs.readFileSync(path.join(__dirname, '..', 'HubInspector.groovy'), 'utf8');
  assert(/ZWAVE_PER_RATE_CRIT = 1\.0G/.test(groovySrc), 'Groovy problem-node threshold differs from ZWAVE_PER_CRIT');
  assert(/above 1%/.test(HELP['zw.failMsgs'].th) && /above 1%/.test(HELP['zw.problem'].th));
  assert(/Below 150/.test(HELP['zb.lqi'].th) && /LQI below 150/.test(HELP['zb.weak'].th));
  assert(/n\.age!=null&&n\.age>6/.test(src) && /Above 6/.test(HELP['zb.age'].d));
  assert(readme.includes('| Problem Nodes | State ≠ OK, or (Z/IP) failure rate = failed ÷ (Msgs + failed) > 1% | 1% of messages | Warning |'));
  assert(readme.includes('| Weak Neighbors | Any neighbor LQI < 150 | 150 | Critical |'));
});

t('no help markup in the AI/forum export builder', () => {
  const s = src.indexOf('function forumMarkdownBody(');
  const e = src.indexOf('\n}\n', s);
  const body = src.slice(s, e);
  assert(s >= 0 && body.length > 1000);
  assert(!/helpBtn|class="hlp/.test(body));
});

console.log(pass + ' passed');
