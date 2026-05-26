#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for the Multi-Hub Inventory correlation functions.
// Extracts the SHIPPED functions from multi_hub_inventory_ui.html by name
// (brace-matched), so the tests stay bound to the real code, not a copy.
// Run: node apps/MultiHubInventory/tests/test-correlation.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const HTML = path.join(__dirname, '..', 'multi_hub_inventory_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

function extractFn(name) {
  const i = src.indexOf('function ' + name + '(');
  assert(i >= 0, 'fn not found in HTML: ' + name);
  let depth = 0, started = false;
  for (let j = i; j < src.length; j++) {
    const c = src[j];
    if (c === '{') { depth++; started = true; }
    else if (c === '}') { depth--; if (started && depth === 0) return src.slice(i, j + 1); }
  }
  throw new Error('unbalanced braces for ' + name);
}

// Grab a single-line `const NAME = ...;` definition verbatim (for the CD map + integLabel helper
// that fleetSummary/the table depend on), so the harness uses the real shipped definitions.
function extractConstLine(prefix) {
  const i = src.indexOf(prefix);
  assert(i >= 0, 'const not found in HTML: ' + prefix);
  const j = src.indexOf('\n', i);
  return src.slice(i, j < 0 ? src.length : j);
}

const FNS = ['filterLinked', 'mergeFleet', 'fwBasis', 'firmwareDrift', 'driverDrift', 'attentionItems', 'fleetSummary'];
const harness = extractConstLine('const CD =') + '\n' + extractConstLine('const integLabel') + '\n'
  + FNS.map(extractFn).join('\n') + '\nmodule.exports = { ' + FNS.join(', ') + ' };';
const tmp = path.join(os.tmpdir(), 'mhi_corr_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const C = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(name, fn) {
  try { fn(); pass++; console.log('  ok   ' + name); }
  catch (e) { fail++; console.log('  FAIL ' + name + '\n         ' + e.message); }
}

t('filterLinked drops protocol === Linked', () => {
  const rows = [{id:1,protocol:'Zigbee'},{id:2,protocol:'Linked'},{id:3,protocol:'Z-Wave'}];
  const out = C.filterLinked(rows);
  assert.deepStrictEqual(out.map(r=>r.id), [1,3]);
});

t('mergeFleet flattens allDevices, tags hub, drops Linked, records hub status', () => {
  const hubResults = [
    { label:'pro', ok:true, data:{ hubName:'Pro', generatedMs:1747999200000,
        hubModel:'C-8 Pro', hubFirmware:'2.4.4.155',
        allDevices:{ '1':{id:1,protocol:'Zigbee',manufacturer:'X'}, '2':{id:2,protocol:'Linked'} } } },
    { label:'main', ok:false, error:'unreachable', data:null },
  ];
  const m = C.mergeFleet(hubResults);
  assert.strictEqual(m.rows.length, 1);
  assert.strictEqual(m.rows[0].hub, 'pro');
  assert.strictEqual(m.hubs[0].deviceCount, 1);
  assert.strictEqual(m.hubs[0].hubName, 'Pro');
  assert.strictEqual(m.hubs[0].hubModel, 'C-8 Pro');
  assert.strictEqual(m.hubs[0].hubFirmware, '2.4.4.155');
  assert.strictEqual(typeof m.hubs[0].generatedAt, 'string');   // generatedMs (epoch) formatted for display
  assert.ok(/\d/.test(m.hubs[0].generatedAt));
  assert.strictEqual(m.hubs[1].ok, false);
  assert.strictEqual(m.hubs[1].error, 'unreachable');
  assert.strictEqual(m.hubs[1].hubModel, null);
  assert.strictEqual(m.hubs[1].hubFirmware, null);
});

t('firmwareDrift — real drift (same source, different values)', () => {
  // Two M1 devices, same firmwareSource, different firmware values, no OTA -> mixed
  const rows = [
    {manufacturer:'Acme',model:'M1',firmware:'1.0',firmwareSource:'softwareBuild',firmwareOta:null,hub:'a'},
    {manufacturer:'Acme',model:'M1',firmware:'1.1',firmwareSource:'softwareBuild',firmwareOta:null,hub:'b'},
  ];
  const d = C.firmwareDrift(rows);
  assert.strictEqual(d.length, 1);
  assert.strictEqual(d[0].model, 'M1');
  assert.strictEqual(d[0].mixed, true,        'M1: same source, different values -> mixed');
  assert.strictEqual(d[0].inconsistent, false,'M1: only one basis used -> not inconsistent');
});

t('firmwareDrift — inconsistent fields (different sources, no OTA)', () => {
  // Two S1 devices: one reports via softwareBuild, other via application -> inconsistent, not mixed
  const rows = [
    {manufacturer:'Sinope',model:'S1',firmware:'1.0.7',firmwareSource:'softwareBuild',firmwareOta:null,hub:'a'},
    {manufacturer:'Sinope',model:'S1',firmware:'08',   firmwareSource:'application',  firmwareOta:null,hub:'b'},
  ];
  const d = C.firmwareDrift(rows);
  assert.strictEqual(d.length, 1);
  assert.strictEqual(d[0].model, 'S1');
  assert.strictEqual(d[0].mixed,        false,'S1: different fields -> not mixed');
  assert.strictEqual(d[0].inconsistent, true, 'S1: different fields -> inconsistent');
});

t('firmwareDrift — OTA trumps source: same OTA on devices with different firmwareSource -> NOT returned', () => {
  // O1 devices: different firmwareSource but same firmwareOta -> basis OTA, one value -> consistent -> excluded
  const rows = [
    {manufacturer:'Osram',model:'O1',firmware:'1.0.7',firmwareSource:'softwareBuild',firmwareOta:'500',hub:'a'},
    {manufacturer:'Osram',model:'O1',firmware:'08',   firmwareSource:'application',  firmwareOta:'500',hub:'b'},
  ];
  const d = C.firmwareDrift(rows);
  assert.strictEqual(d.length, 0, 'O1 same OTA -> consistent -> not returned');
});

t('firmwareDrift — consistent group and singleton are excluded; interesting groups returned', () => {
  // C1: consistent (same source, same value) -> excluded
  // Solo: singleton -> excluded
  // M1 + S1 from above-style inputs -> returned
  const rows = [
    // C1 consistent
    {manufacturer:'Cree',model:'C1',firmware:'2.0',firmwareSource:'softwareBuild',firmwareOta:null,hub:'a'},
    {manufacturer:'Cree',model:'C1',firmware:'2.0',firmwareSource:'softwareBuild',firmwareOta:null,hub:'b'},
    // Solo singleton
    {manufacturer:'Solo',model:'X1',firmware:'9.0',firmwareSource:'softwareBuild',firmwareOta:null,hub:'a'},
    // M1 real drift
    {manufacturer:'Acme',model:'M1',firmware:'1.0',firmwareSource:'softwareBuild',firmwareOta:null,hub:'a'},
    {manufacturer:'Acme',model:'M1',firmware:'1.1',firmwareSource:'softwareBuild',firmwareOta:null,hub:'b'},
    // S1 inconsistent fields
    {manufacturer:'Sinope',model:'S1',firmware:'1.0.7',firmwareSource:'softwareBuild',firmwareOta:null,hub:'a'},
    {manufacturer:'Sinope',model:'S1',firmware:'08',   firmwareSource:'application',  firmwareOta:null,hub:'b'},
  ];
  const d = C.firmwareDrift(rows);
  // Only M1 (mixed) and S1 (inconsistent) should appear; C1 and Solo excluded
  assert.strictEqual(d.length, 2, 'exactly M1 + S1 returned');
  const m1 = d.find(g => g.model === 'M1');
  const s1 = d.find(g => g.model === 'S1');
  assert(m1, 'M1 group present');
  assert(s1, 'S1 group present');
  assert.strictEqual(m1.mixed, true);
  assert.strictEqual(m1.inconsistent, false);
  assert.strictEqual(s1.mixed, false);
  assert.strictEqual(s1.inconsistent, true);
  // mixed sorts before inconsistent-only
  assert.strictEqual(d[0].model, 'M1', 'mixed (M1) sorts before inconsistent-only (S1)');
  // devices arrays
  assert.strictEqual(m1.devices.length, 2);
  assert.strictEqual(s1.devices.length, 2);
});

t('attentionItems classifies by reason with injectable now + staleDays', () => {
  const NOW = 1_000_000_000_000, day = 86400000;
  const rows = [
    {id:1, lastActivityTimeMs: NOW - 10*day, appsUsingCount:1, dashboards:[1]}, // stale (referenced -> not unreferenced)
    {id:2, orphan:true,   lastActivityTimeMs: NOW, appsUsingCount:1, dashboards:[1]}, // orphaned only
    {id:3, disabled:true, lastActivityTimeMs: NOW, appsUsingCount:0, dashboards:[]},  // disabled + unreferenced
    {id:4, lastActivityTimeMs: NOW, appsUsingCount:2, dashboards:[]},                // nothing
  ];
  const a = C.attentionItems(rows, { now: NOW, staleDays: 7 });
  assert.deepStrictEqual(a.stale.map(r=>r.id), [1]);
  assert.deepStrictEqual(a.orphaned.map(r=>r.id), [2]);
  assert.deepStrictEqual(a.disabled.map(r=>r.id), [3]);
  assert.deepStrictEqual(a.unreferenced.map(r=>r.id), [3]);
});

t('fleetSummary counts by hub/integration/manufacturer + attention totals', () => {
  // lastActivityTimeMs:Date.now() keeps every row non-stale so attentionCounts isolates disabled/unreferenced.
  // Two-axis model (mirrors HubDiagnostics): byIntegration counts only devices with a real parent
  // integration (row 1). Standalone devices (rows 2-3: integration null) are omitted from
  // byIntegration and reported under byConnection instead — no connection-label fallback.
  const merged = { rows: [
    {hub:'a', integration:'Lutron', connectionType:'lan_bridge', manufacturer:'X', appsUsingCount:1, dashboards:[1], lastActivityTimeMs:Date.now()},
    {hub:'a', connectionType:'zigbee', manufacturer:'X', appsUsingCount:0, dashboards:[],  lastActivityTimeMs:Date.now()},
    {hub:'b', connectionType:'zigbee', manufacturer:'Y', disabled:true,    appsUsingCount:1, dashboards:[1], lastActivityTimeMs:Date.now()},
  ], hubs: [] };
  const s = C.fleetSummary(merged);
  assert.strictEqual(s.total, 3);
  assert.strictEqual(s.byHub.a, 2);
  assert.strictEqual(s.byIntegration.Lutron, 1);          // real parent integration
  assert.strictEqual(s.byIntegration.Zigbee, undefined);  // standalone radios omitted from byIntegration
  assert.strictEqual(s.byConnection.Zigbee, 2);           // …counted under byConnection instead
  assert.strictEqual(s.byManufacturer.X, 2);
  assert.strictEqual(s.attentionCounts.disabled, 1);
  assert.strictEqual(s.attentionCounts.unreferenced, 1);
});

t('driverDrift — same mfr+model, two different drivers → returned', () => {
  const rows = [
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'Tuya Temp/Humidity', hub:'a'},
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'Generic Zigbee Temp', hub:'b'},
  ];
  const d = C.driverDrift(rows);
  assert.strictEqual(d.length, 1, 'one group returned');
  assert.strictEqual(d[0].model, 'TH01');
  assert.strictEqual(d[0].drivers.length, 2, 'two distinct drivers');
});

t('driverDrift — same mfr+model, same driver → not returned', () => {
  const rows = [
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'Tuya Temp/Humidity', hub:'a'},
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'Tuya Temp/Humidity', hub:'b'},
  ];
  const d = C.driverDrift(rows);
  assert.strictEqual(d.length, 0, 'consistent drivers → not returned');
});

t('driverDrift — singleton → not returned', () => {
  const rows = [
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'Tuya Temp/Humidity', hub:'a'},
  ];
  const d = C.driverDrift(rows);
  assert.strictEqual(d.length, 0, 'singleton → not returned');
});

t('driverDrift — two devices, one blank deviceTypeName → only one distinct driver → not returned', () => {
  const rows = [
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'Tuya Temp/Humidity', hub:'a'},
    {manufacturer:'Tuya', model:'TH01', deviceTypeName:'',                   hub:'b'},
  ];
  const d = C.driverDrift(rows);
  assert.strictEqual(d.length, 0, 'blank deviceTypeName → only one named driver → not returned');
});

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
