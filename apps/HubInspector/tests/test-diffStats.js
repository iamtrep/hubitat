#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Unit tests for diffStats (hub_inspector_ui.html)
// Run: node tests/test-diffStats.js
//
// These tests run the shipped comparison/diff functions, loaded from the HTML by name.

let passed = 0, failed = 0;
function ok(msg)   { passed++; console.log(`  [PASS] ${msg}`); }
function fail(msg) { failed++; console.error(`  [FAIL] ${msg}`); }
function section(msg) { console.log(`\n--- ${msg} ---`); }

// ── Shipped functions, extracted from hub_inspector_ui.html (no copy to keep in sync) ──
const fs = require('fs'), os = require('os'), path = require('path');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');
function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  if (start < 0) throw new Error('function not found: ' + name);
  const open = src.indexOf('{', start);
  let depth = 0, i = open;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}
const tmp = path.join(os.tmpdir(), 'hd_diffstats_' + process.pid + '.js');
fs.writeFileSync(tmp, ['parseRuntimeMs', 'fmsDuration', 'diffStats', 'zeroBaseline'].map(extractFn).join('\n') +
  '\nmodule.exports = { parseRuntimeMs, fmsDuration, diffStats, zeroBaseline };');
const { parseRuntimeMs, fmsDuration, diffStats, zeroBaseline } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

// ── Tests ─────────────────────────────────────────────────────────────

section('diffStats: per-row pct is recomputed as interval percentage');

const base1 = {
  timestampMs: 0,
  totalDevicesRuntime: '100ms', totalAppsRuntime: '0ms',
  deviceStats: [
    { id: 1, name: 'Device A', total: 100, pct: 1.0, count: 10, average: 10, hubActionCount: 0, cloudCallCount: 0, stateSize: 0, pctTotal: 0.1 }
  ],
  appStats: []
};
// comp is 1000ms later; device ran 100ms more in that interval.
// uptimeSeconds=1 acts as fallback for elapsedMs when base.timestampMs=0 (startup baseline).
const comp1 = {
  timestampMs: 1000, uptimeSeconds: 1,
  totalDevicesRuntime: '200ms', totalAppsRuntime: '0ms',
  deviceStats: [
    { id: 1, name: 'Device A', total: 200, pct: 2.0, count: 20, average: 10, hubActionCount: 0, cloudCallCount: 0, stateSize: 0, pctTotal: 0.2 }
  ],
  appStats: []
};

const r1 = diffStats(base1, comp1);
const dev = r1.deviceStats[0];

if (dev.total === 100) ok('device total = interval delta (100ms)');
else fail(`device total expected 100, got ${dev.total}`);

if (dev.pctTotal === null) ok('device pctTotal nulled out');
else fail(`device pctTotal expected null, got ${dev.pctTotal}`);

// Correct interval pct: 100ms / 1000ms * 100 = 10%
// Buggy value would be 2.0 (copied from comp snapshot's lifetime pct)
const expectedPct = 10;
if (dev.pct !== null && Math.abs(dev.pct - expectedPct) < 0.001)
  ok(`device pct recomputed as interval pct (${dev.pct.toFixed(1)}%)`);
else
  fail(`device pct expected ${expectedPct}%, got ${dev.pct} — pct not recomputed from interval`);

if (dev.average === 10) ok('device average recomputed from diffed values');
else fail(`device average expected 10, got ${dev.average}`);

section('diffStats: reversed timestamps produce null pct (not crash)');

const rReversed = diffStats(comp1, base1); // comp is now the "base", base is the "comp"
const devRev = rReversed.deviceStats[0];
if (devRev.pct === null) ok('pct is null when timestamps reversed (elapsedMs <= 0)');
else fail(`expected pct=null for reversed timestamps, got ${devRev.pct}`);
if (rReversed.uptime === 'N/A (missing timestamp)') ok('uptime shows N/A for reversed timestamps');
else fail(`expected uptime='N/A (missing timestamp)', got '${rReversed.uptime}'`);

section('diffStats: items not in baseline are returned as-is (new device)');

const baseNoNew = {
  timestampMs: 0,
  totalDevicesRuntime: '0ms', totalAppsRuntime: '0ms',
  deviceStats: [], appStats: []
};
const compWithNew = {
  timestampMs: 1000,
  totalDevicesRuntime: '50ms', totalAppsRuntime: '0ms',
  deviceStats: [{ id: 2, name: 'New Device', total: 50, pct: 5, count: 5, average: 10, hubActionCount: 0, cloudCallCount: 0, stateSize: 0, pctTotal: 0.05 }],
  appStats: []
};
const rNew = diffStats(baseNoNew, compWithNew);
if (rNew.deviceStats[0].pct === 5) ok('new device (not in baseline) pct returned as-is');
else fail(`new device pct expected 5, got ${rNew.deviceStats[0].pct}`);

section('diffStats: null/missing inputs are handled gracefully');

const rNullBase = diffStats(null, comp1);
if (rNullBase === comp1) ok('null base returns comp unchanged');
else fail('null base did not return comp');

const rNullComp = diffStats(base1, null);
if (rNullComp === null) ok('null comp returns null');
else fail('null comp did not return null');

section('diffStats: uptime and aggregate pcts computed from interval');

if (r1.uptime === '1.0s') ok(`uptime set to interval duration (${r1.uptime})`);
else fail(`uptime expected '1.0s', got '${r1.uptime}'`);

if (r1.devicePct === '10.000%') ok(`devicePct set to interval aggregate pct (${r1.devicePct})`);
else fail(`devicePct expected '10.000%', got '${r1.devicePct}'`);

section('zeroBaseline + diffStats: startup baseline synthesized client-side (replaces hub buildZeroBaseline)');

const compStartup = {
  timestampMs: 60000, uptimeSeconds: 60,
  totalDevicesRuntime: '6000ms', totalAppsRuntime: '600ms',
  temperature: 40, databaseSize: 120,
  resources: { freeOSMemory: 500000, cpuAvg5min: 1.5, totalJavaMemory: 1000, freeJavaMemory: 400, directJavaMemory: 10 },
  deviceStats: [{ id: 1, name: 'Dev A', total: 6000, pct: 10, count: 60, average: 100, hubActionCount: 3, cloudCallCount: 1, stateSize: 50, pctTotal: 0.6 }],
  appStats: [{ id: 11, name: 'App X', total: 600, pct: 1, count: 6, average: 100, hubActionCount: 0, cloudCallCount: 0, stateSize: 5, pctTotal: 0.06 }],
  radioStats: { zwave: [{ id: 'z1', deviceId: 1, name: 'Dev A', msgCount: 120, routeChanges: 2 }], zigbee: [{ id: 'g1', name: 'Dev B', msgCount: 80 }] }
};

const zb = zeroBaseline(compStartup);

if (zb.timestampMs === 0) ok('zeroBaseline timestampMs is 0 (triggers uptimeSeconds elapsed fallback)');
else fail(`zeroBaseline timestampMs expected 0, got ${zb.timestampMs}`);
if (zb.deviceStats.length === 1 && zb.deviceStats[0].id === 1 && zb.deviceStats[0].total === 0)
  ok('zeroBaseline mirrors device ids with zeroed totals');
else fail('zeroBaseline device mirror wrong');
if (zb.radioStats.zwave[0].msgCount === 0 && zb.radioStats.zwave[0].id === 'z1' && zb.radioStats.zigbee[0].msgCount === 0)
  ok('zeroBaseline mirrors radio ids with zeroed msgCount');
else fail('zeroBaseline radio mirror wrong');
if (zb.resources && zb.resources.freeOSMemory === 0) ok('zeroBaseline resources zeroed (drives current-minus-zero resource delta)');
else fail('zeroBaseline resources not zeroed');

// diffStats over the synthesized baseline must reproduce the old server behavior: comp - 0.
const rStartup = diffStats(zb, compStartup);
if (rStartup.deviceStats[0].total === 6000) ok('startup diff: device total = full lifetime (comp - 0)');
else fail(`startup device total expected 6000, got ${rStartup.deviceStats[0].total}`);
// elapsedMs from uptimeSeconds (60s) = 60000ms; pct = 6000/60000*100 = 10%
if (rStartup.deviceStats[0].pct !== null && Math.abs(rStartup.deviceStats[0].pct - 10) < 0.001)
  ok(`startup diff: device pct from uptime interval (${rStartup.deviceStats[0].pct.toFixed(1)}%)`);
else fail(`startup device pct expected 10, got ${rStartup.deviceStats[0].pct}`);
if (rStartup.radioStats.zwave[0].msgCount === 120) ok('startup diff: radio msgCount = full count (comp - 0)');
else fail(`startup radio msgCount expected 120, got ${rStartup.radioStats.zwave[0].msgCount}`);
if (rStartup.uptime === '1m 0s') ok(`startup diff: uptime = interval from uptimeSeconds (${rStartup.uptime})`);
else fail(`startup uptime expected '1m 0s', got '${rStartup.uptime}'`);

// ── Summary ───────────────────────────────────────────────────────────
console.log(`\n=== Results: ${passed}/${passed + failed} passed${failed ? `, ${failed} failed` : ''} ===`);
process.exit(failed ? 1 : 0);
