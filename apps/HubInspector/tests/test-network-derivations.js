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
// hub_inspector_ui.html by name, so the test stays bound to the shipped code.
//
// Run: node apps/HubInspector/tests/test-network-derivations.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');

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
  ['zwPerRate', 'zwPerTxt', 'zwProblemNodes', 'zbWeakNeighbors', 'zbStaleNeighbors', 'lqiBandColor', 'zwMeshStats', 'zbMeshDetails',
   'hexBytesReversed', 'isOwnPan', 'neighborPans', 'neighborPanCount', 'chRanges'].map(extractFn).join('\n') +
  '\nmodule.exports = { zwPerRate, zwPerTxt, zwProblemNodes, zbWeakNeighbors, zbStaleNeighbors, lqiBandColor, zwMeshStats, zbMeshDetails, isOwnPan, neighborPans, neighborPanCount, chRanges };';
const tmp = path.join(os.tmpdir(), 'hd_net_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const N = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); }
  catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

console.log('network-mesh derivations');

// ---- zwProblemNodes (Z-Wave) ----
// per counts undelivered messages, msgCount delivered ones: the rate is per / (msgCount + per).
const nodes = [
  { name: 'OK Node',   deviceId: 1, nodeId: 2, state: 'OK',      per: 2,  msgCount: 10000 },  // 0.02%
  { name: 'Bad State', deviceId: 3, nodeId: 4, state: 'FAILED',  per: 0,  msgCount: 0 },
  { name: 'High PER',  deviceId: 5, nodeId: 6, state: 'OK',      per: 10, msgCount: 122 },    // 7.6%
  { name: 'Both',      deviceId: 7, nodeId: 8, state: 'PENDING', per: 3,  msgCount: 97 },     // 3.0%
  { name: 'JS node',   deviceId: 9, nodeId: 10, state: 'OK',     per: 50, msgCount: 10, perRate: null }
];
t('flags non-OK state and failure rate>crit; low rate and Z-Wave JS count excluded', () => {
  const p = N.zwProblemNodes(nodes);
  assert.deepStrictEqual(p.map(x => x.name).sort(), ['Bad State', 'Both', 'High PER']);
});
t('assembles issue strings (State / failed messages with rate, both)', () => {
  const by = Object.fromEntries(N.zwProblemNodes(nodes).map(x => [x.name, x.issues]));
  assert.strictEqual(by['Bad State'], 'State: FAILED');
  assert.strictEqual(by['High PER'], 'Failed 10 of 132 (7.6%)');
  assert.strictEqual(by['Both'], 'State: PENDING, Failed 3 of 100 (3.0%)');
});
t('zwPerRate / zwPerTxt: rate from counts, hub value wins, "—" on Z-Wave JS', () => {
  assert.strictEqual(N.zwPerRate({ per: 0, msgCount: 0 }), 0);
  assert.strictEqual(N.zwPerRate({ per: 2, msgCount: 10000 }), 0);
  assert.strictEqual(N.zwPerRate({ per: 10, msgCount: 122 }), 7.6);
  assert.strictEqual(N.zwPerRate({ per: 10, msgCount: 122, perRate: 7.6 }), 7.6);
  assert.strictEqual(N.zwPerTxt({ per: 50, perRate: null }), '—');
  assert.strictEqual(N.zwPerTxt({ per: null, perRate: null }), '—');
  assert.strictEqual(N.zwPerTxt({ per: 10, msgCount: 122 }), '10 of 132 (7.6%)');
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

// ---- lqiBandColor (5-band gradient for the Zigbee Neighbor Details LQI column) ----
t('lqiBandColor: null lqi -> muted', () => {
  assert.strictEqual(N.lqiBandColor(null), 'var(--muted)');
});
t('lqiBandColor: band boundaries (strict <)', () => {
  assert.strictEqual(N.lqiBandColor(0),   'var(--lqi-poor)');
  assert.strictEqual(N.lqiBandColor(29),  'var(--lqi-poor)');
  assert.strictEqual(N.lqiBandColor(30),  'var(--lqi-marginal)');
  assert.strictEqual(N.lqiBandColor(69),  'var(--lqi-marginal)');
  assert.strictEqual(N.lqiBandColor(70),  'var(--lqi-acceptable)');
  assert.strictEqual(N.lqiBandColor(129), 'var(--lqi-acceptable)');
  assert.strictEqual(N.lqiBandColor(130), 'var(--lqi-good)');
  assert.strictEqual(N.lqiBandColor(199), 'var(--lqi-good)');
  assert.strictEqual(N.lqiBandColor(200), 'var(--lqi-excellent)');
  assert.strictEqual(N.lqiBandColor(255), 'var(--lqi-excellent)');
});

// ---- zwMeshStats (Z-Wave rollups + S0 flag from the raw node list) ----
t('zwMeshStats: rollups; null routeChanges/rssi excluded; S0 flag skips locks', () => {
  const zw = { mesh: { nodes: [
    { per: 0, msgCount: 100, routeChanges: 3,    rssi: -60, security: 'S0',   zwaveType: 'BINARY_SWITCH' },
    { per: 2, msgCount: 48,  routeChanges: null, rssi: -71, security: 'S0',   zwaveType: 'DOOR_LOCK' },
    { per: 0, msgCount: 50,  routeChanges: 0,    rssi: null, security: 'None', zwaveType: '' }
  ] } };
  N.zwMeshStats(zw);
  const m = zw.mesh;
  assert.strictEqual(m.nodeCount, 3);
  assert.strictEqual(m.totalRouteChanges, 3);
  assert.strictEqual(m.nodesWithErrors, 1);
  assert.ok(Math.abs(m.failRate - 1) < 1e-9, 'failRate = all PER / all messages = 2 / 200');
  assert.strictEqual(m.totalPer, 2);
  assert.deepStrictEqual(m.nodes.map(n => n.perRate), [0, 4, 0]);
  assert.strictEqual(m.avgRssi, -65);
  assert.deepStrictEqual(m.nodes.map(n => n.s0Flag), [true, false, false]);
});
t('zwMeshStats: Z-Wave JS shows no per-derived figure at all', () => {
  const zw = { zwaveJS: true, mesh: { nodes: [{ state: 'OK', per: 50, msgCount: 10 }, { state: 'OK', per: 1, msgCount: 0 }] } };
  N.zwMeshStats(zw);
  assert.strictEqual(zw.mesh.failRate, null);
  assert.strictEqual(zw.mesh.totalPer, null);
  assert.strictEqual(zw.mesh.nodesWithErrors, null);
  assert.deepStrictEqual(zw.mesh.nodes.map(n => [n.per, n.perRate]), [[null, null], [null, null]]);
  assert.deepStrictEqual(zw.mesh.nodes.map(N.zwPerTxt), ['—', '—']);
  assert.deepStrictEqual(N.zwProblemNodes(zw.mesh.nodes), []);
});
t('zwMeshStats: payload that already carries rollups is left as is', () => {
  const zw = { mesh: { failRate: 0.5, totalRouteChanges: 9, nodes: [{ per: 0, s0Flag: true }] } };
  N.zwMeshStats(zw);
  assert.strictEqual(zw.mesh.failRate, 0.5);
  assert.strictEqual(zw.mesh.totalRouteChanges, 9);
  assert.strictEqual(zw.mesh.nodes[0].s0Flag, true);
  N.zwMeshStats(null); N.zwMeshStats({ mesh: {} });
});

// ---- zbMeshDetails (LQI rollup + stale flag from the raw neighbor list) ----
t('zbMeshDetails: avg/min/max LQI and stale at age >= 7', () => {
  const zb = { mesh: { neighborList: neighbors.slice() } };
  N.zbMeshDetails(zb);
  assert.strictEqual(zb.mesh.avgLqi, 185);
  assert.strictEqual(zb.mesh.minLqi, 100);
  assert.strictEqual(zb.mesh.maxLqi, 255);
  assert.deepStrictEqual(zb.mesh.neighborDetails.filter(n => n.stale).map(n => n.shortId), ['C3']);
});
t('zbMeshDetails: keeps LQI rollup an older payload already carries', () => {
  const zb = { mesh: { avgLqi: 1, minLqi: 1, maxLqi: 1, neighborList: neighbors.slice() } };
  N.zbMeshDetails(zb);
  assert.strictEqual(zb.mesh.avgLqi, 1);
});

// ---- channel scan: the hub's own network is not a neighbour ----
// The scan reports extendedPanId byte-reversed relative to /hub/zigbeeDetails/json.
const own = { channel: 15, panId: '1A2B', extendedPanId: '0011223344556677' };
const scan = { results: [
  { channel: 15, panId: '1A2B', extendedPanId: '7766554433221100', lastHopRssi: -45 },
  { channel: 20, panId: '3C4D', extendedPanId: '8899AABBCCDDEEFF', lastHopRssi: -70 },
  { channel: 25, panId: null, extendedPanId: null, lastHopRssi: 0 }
] };
t('isOwnPan: matches the byte-reversed extendedPanId', () => {
  assert.strictEqual(N.isOwnPan(scan.results[0], own), true);
  assert.strictEqual(N.isOwnPan(scan.results[1], own), false);
});
t('isOwnPan: matches the same-order extendedPanId', () => {
  assert.strictEqual(N.isOwnPan({ channel: 15, panId: '1A2B', extendedPanId: '0011223344556677' }, own), true);
});
t('isOwnPan: falls back to panId + channel without an extendedPanId', () => {
  assert.strictEqual(N.isOwnPan({ channel: 15, panId: '1a2b' }, own), true);
  assert.strictEqual(N.isOwnPan({ channel: 20, panId: '1A2B' }, own), false);
});
t('neighborPans / neighborPanCount exclude the own network and empty rows', () => {
  assert.deepStrictEqual(N.neighborPans(scan, own).map(r => r.panId), ['3C4D']);
  assert.strictEqual(N.neighborPanCount(scan, own), '1 other Zigbee network detected');
  assert.strictEqual(N.neighborPanCount({ results: [scan.results[0]] }, own), 'no other Zigbee networks detected');
});
t('chRanges: collapses consecutive channels', () => {
  assert.strictEqual(N.chRanges([7, 8, 9, 10]), '7–10');
  assert.strictEqual(N.chRanges([12, 13]), '12, 13');
  assert.strictEqual(N.chRanges([1]), '1');
});

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
