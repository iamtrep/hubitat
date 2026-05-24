#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for the SPA's radio top-talker derivation (Performance tab).
//
// The hub ships raw per-device message counts (radioStats); the SPA ranks the top talkers
// (sort + top-N) — a pure derivation that used to live in Groovy (buildRadioDeviceList +
// sort.take(3)). This EXTRACTS topRadioTalkers from hub_diagnostics_ui.html by name
// (brace-matched), so the test stays bound to the shipped function rather than a copy.
//
// Run: node apps/HubDiagnostics/tests/test-radio-derivations.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_diagnostics_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

// Extract a (possibly multi-line) `function NAME(...){ ... }` from the HTML by brace matching.
// topRadioTalkers contains no braces inside string literals, so naive depth counting is safe.
function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found in HTML: ' + name);
  const braceOpen = src.indexOf('{', start);
  let depth = 0, i = braceOpen;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}

// topRadioTalkers builds on flattenRadioDevices, so extract both.
const harness = extractFn('flattenRadioDevices') + '\n' + extractFn('topRadioTalkers') +
  '\nmodule.exports = { flattenRadioDevices, topRadioTalkers };';
const tmp = path.join(os.tmpdir(), 'hd_radio_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const { flattenRadioDevices, topRadioTalkers } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(name, fn){ try { fn(); pass++; console.log('  ok   ' + name); }
  catch (e) { fail++; console.log('  FAIL ' + name + '\n         ' + e.message); } }

console.log('topRadioTalkers (Performance top-talker derivation)');

const rs = {
  zwave: [
    { name: 'ZW Quiet', deviceId: 1, msgCount: 0 },
    { name: 'ZW Loud',  deviceId: 2, msgCount: 500 },
    { name: 'ZW Mid',   deviceId: 3, msgCount: 50 }
  ],
  zigbee: [
    { name: 'ZB Top', id: 10, msgCount: 900 },
    { name: 'ZB Low', id: 11, msgCount: 20 }
  ]
};

t('flattenRadioDevices: full flatten, keeps silent devices, no ranking', () => {
  const all = flattenRadioDevices(rs);
  assert.strictEqual(all.length, 5);  // 3 zwave + 2 zigbee, including the 0-count device
  assert(all.some(x => x.name === 'ZW Quiet' && x.msgCount === 0), 'silent device retained in full list');
  const zb = all.find(x => x.name === 'ZB Top');
  assert.strictEqual(zb.deviceId, 10);          // zigbee id -> deviceId
  assert.strictEqual(zb.integration, 'Zigbee');
  assert.deepStrictEqual(flattenRadioDevices(null), []);
});
t('ranks across both radios, top 3 by msgCount desc', () => {
  const top = topRadioTalkers(rs);
  assert.strictEqual(top.length, 3);
  assert.deepStrictEqual(top.map(x => x.name), ['ZB Top', 'ZW Loud', 'ZW Mid']);
  assert.deepStrictEqual(top.map(x => x.msgCount), [900, 500, 50]);
});
t('tags integration and maps zigbee id -> deviceId', () => {
  const top = topRadioTalkers(rs);
  const zbTop = top.find(x => x.name === 'ZB Top');
  assert.strictEqual(zbTop.integration, 'Zigbee');
  assert.strictEqual(zbTop.deviceId, 10);   // zigbee entries key off .id
  const zwLoud = top.find(x => x.name === 'ZW Loud');
  assert.strictEqual(zwLoud.integration, 'Z-Wave');
  assert.strictEqual(zwLoud.deviceId, 2);   // z-wave entries key off .deviceId
});
t('drops silent devices (msgCount 0)', () => {
  const top = topRadioTalkers(rs);
  assert(!top.some(x => x.name === 'ZW Quiet'), 'silent device should be excluded');
});
t('null/empty radioStats -> empty list (no crash)', () => {
  assert.deepStrictEqual(topRadioTalkers(null), []);
  assert.deepStrictEqual(topRadioTalkers({}), []);
  assert.deepStrictEqual(topRadioTalkers({ zwave: [], zigbee: [] }), []);
});

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
