#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for the SPA's network-mesh derivations (Network tab + forum export).
//
// The hub ships the raw mesh (zwave.mesh.nodes; the zigbee neighbor list); the SPA applies the
// thresholds and assembles the Z-Wave problem-node issue strings + the Zigbee weak/stale subsets
// (logic that used to live in getNetworkData / fetchZigbeeMeshInfo). This EXTRACTS
// zwProblemNodes/zbWeakNeighbors/zbStaleNeighbors (plus the threshold consts) from
// hub_diagnostics_ui.html by name, so the test stays bound to the shipped code.
//
// Run: node apps/HubDiagnostics/tests/test-network-derivations.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_diagnostics_ui.html'), 'utf8');

// Brace-matched extraction of a (possibly multi-line) `function NAME(...){ ... }`.
function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found: ' + name);
  const open = src.indexOf('{', start);
  let depth = 0, i = open;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}
const consts = (src.match(/const ZWAVE_PER_CRIT=[^;]+;/) || [])[0];
assert(consts, 'threshold consts not found in HTML');
const harness = consts + '\n' +
  ['zwProblemNodes', 'zbWeakNeighbors', 'zbStaleNeighbors'].map(extractFn).join('\n') +
  '\nmodule.exports = { zwProblemNodes, zbWeakNeighbors, zbStaleNeighbors };';
const tmp = path.join(os.tmpdir(), 'hd_net_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const N = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); }
  catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

console.log('network-mesh derivations');

// ---- zwProblemNodes (Z-Wave) ----
const nodes = [
  { name: 'OK Node',   deviceId: 1, nodeId: 2, state: 'OK',      per: 0 },
  { name: 'Bad State', deviceId: 3, nodeId: 4, state: 'FAILED',  per: 0 },
  { name: 'High PER',  deviceId: 5, nodeId: 6, state: 'OK',      per: 5 },
  { name: 'Both',      deviceId: 7, nodeId: 8, state: 'PENDING', per: 3 }
];
t('flags non-OK state and PER>crit; healthy node excluded', () => {
  const p = N.zwProblemNodes(nodes);
  assert.deepStrictEqual(p.map(x => x.name).sort(), ['Bad State', 'Both', 'High PER']);
  assert(!p.some(x => x.name === 'OK Node'), 'healthy node should be excluded');
});
t('assembles issue strings (State / PER, both)', () => {
  const by = Object.fromEntries(N.zwProblemNodes(nodes).map(x => [x.name, x.issues]));
  assert.strictEqual(by['Bad State'], 'State: FAILED');
  assert.strictEqual(by['High PER'], 'PER: 5%');
  assert.strictEqual(by['Both'], 'State: PENDING, PER: 3%');
});
t('carries deviceId/nodeId; null/empty -> []', () => {
  assert.strictEqual(N.zwProblemNodes(nodes).find(x => x.name === 'Both').nodeId, 8);
  assert.deepStrictEqual(N.zwProblemNodes(null), []);
  assert.deepStrictEqual(N.zwProblemNodes([]), []);
});

// ---- zbWeakNeighbors / zbStaleNeighbors (Zigbee) ----
const neighbors = [
  { shortId: 'A1', lqi: 200,  age: 1 },
  { shortId: 'B2', lqi: 100,  age: 2 },    // weak: lqi < 150
  { shortId: 'C3', lqi: 255,  age: 10 },   // stale: age > 6
  { shortId: 'D4', lqi: null, age: null }
];
t('zbWeakNeighbors: lqi<150, null lqi excluded', () => {
  assert.deepStrictEqual(N.zbWeakNeighbors(neighbors).map(x => x.shortId), ['B2']);
  assert.deepStrictEqual(N.zbWeakNeighbors(null), []);
});
t('zbStaleNeighbors: age>6, null age excluded', () => {
  assert.deepStrictEqual(N.zbStaleNeighbors(neighbors).map(x => x.shortId), ['C3']);
  assert.deepStrictEqual(N.zbStaleNeighbors(null), []);
});

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
