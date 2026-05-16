#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Unit tests for diffStats (hub_diagnostics_ui.html)
// Run: node tests/test-diffStats.js
//
// These tests exercise the comparison/diff logic in isolation.
// Update the function body below whenever diffStats changes in the HTML.

let passed = 0, failed = 0;
function ok(msg)   { passed++; console.log(`  [PASS] ${msg}`); }
function fail(msg) { failed++; console.error(`  [FAIL] ${msg}`); }
function section(msg) { console.log(`\n--- ${msg} ---`); }

// ── Copy from hub_diagnostics_ui.html (keep in sync) ──────────────────

function parseRuntimeMs(v){
  if(v==null)return 0;if(typeof v==='number')return v;
  const s=String(v);if(s.endsWith('ms'))return parseInt(s)||0;
  let ms=0;
  const hr=s.match(/(\d+)h/);if(hr)ms+=parseInt(hr[1])*3600000;
  const mn=s.match(/(\d+)m(?!s)/);if(mn)ms+=parseInt(mn[1])*60000;
  const sc=s.match(/([\d.]+)s/);if(sc)ms+=parseFloat(sc[1])*1000;
  return ms||(parseInt(s)||0);
}
function fmsDuration(ms){
  if(ms==null)return'N/A';
  const neg=ms<0;ms=Math.abs(ms);
  const h=Math.floor(ms/3600000);ms%=3600000;
  const m=Math.floor(ms/60000);ms%=60000;
  const s=(ms/1000).toFixed(1);
  const r=h>0?h+'h '+m+'m '+s+'s':m>0?m+'m '+s+'s':s+'s';
  return neg?'-'+r:r;
}
function diffStats(base, comp) {
  if (!base || !comp) return comp;
  const res = JSON.parse(JSON.stringify(comp));
  // base.timestampMs is 0 for "startup" baseline (falsy) — fall back to comp's uptimeSeconds.
  const elapsedMs = (comp.timestampMs && base.timestampMs)
    ? comp.timestampMs - base.timestampMs
    : (comp.uptimeSeconds ? comp.uptimeSeconds * 1000 : 0);
  ['deviceStats', 'appStats'].forEach(key => {
    if (comp[key] && base[key]) {
      const baseMap = {};
      base[key].forEach(item => { if (item.id) baseMap[item.id] = item; });
      res[key] = comp[key].map(item => {
        const bItem = baseMap[item.id];
        if (!bItem) return item;
        const diffed = JSON.parse(JSON.stringify(item));
        ['total', 'count', 'hubActionCount', 'cloudCallCount', 'stateSize'].forEach(field => {
          if (typeof item[field] === 'number' && typeof bItem[field] === 'number') {
            diffed[field] = item[field] - bItem[field];
          }
        });
        diffed.average = diffed.count > 0 ? (diffed.total / diffed.count) : 0;
        diffed.pctTotal = null;
        diffed.pct = elapsedMs > 0 ? (diffed.total / elapsedMs * 100) : null;
        return diffed;
      });
    }
  });
  const devMs = parseRuntimeMs(comp.totalDevicesRuntime) - parseRuntimeMs(base.totalDevicesRuntime);
  const appMs = parseRuntimeMs(comp.totalAppsRuntime) - parseRuntimeMs(base.totalAppsRuntime);
  res.totalDevicesRuntime = devMs;
  res.totalAppsRuntime = appMs;
  if (elapsedMs > 0) {
    res.uptime = fmsDuration(elapsedMs);
    res.uptimeSeconds = elapsedMs / 1000;
    res.devicePct = (devMs / elapsedMs * 100).toFixed(3) + '%';
    res.appPct = (appMs / elapsedMs * 100).toFixed(3) + '%';
  } else {
    res.uptime = 'N/A (missing timestamp)';
    res.devicePct = null;
    res.appPct = null;
  }
  if (comp.radioStats && base.radioStats) {
    const elapsedMin = elapsedMs / 60000;
    res.radioStats = {};
    ['zwave', 'zigbee'].forEach(proto => {
      if (comp.radioStats[proto] && base.radioStats[proto]) {
        const baseMap = {};
        base.radioStats[proto].forEach(d => { baseMap[d.id] = d; });
        res.radioStats[proto] = comp.radioStats[proto].map(d => {
          const bd = baseMap[d.id];
          const diffMsgs = (d.msgCount || 0) - (bd ? (bd.msgCount || 0) : 0);
          const r = { ...d, msgCount: diffMsgs, msgPerMin: elapsedMin > 0 ? diffMsgs / elapsedMin : 0 };
          if (d.routeChanges != null && bd) r.routeChanges = (d.routeChanges || 0) - (bd.routeChanges || 0);
          return r;
        });
      }
    });
    res.radioElapsedMin = elapsedMin;
  }
  return res;
}

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

// ── Summary ───────────────────────────────────────────────────────────
console.log(`\n=== Results: ${passed}/${passed + failed} passed${failed ? `, ${failed} failed` : ''} ===`);
process.exit(failed ? 1 : 0);
