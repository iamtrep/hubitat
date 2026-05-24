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

const FNS = ['filterLinked', 'mergeFleet', 'firmwareDrift', 'attentionItems', 'fleetSummary'];
const harness = FNS.map(extractFn).join('\n') + '\nmodule.exports = { ' + FNS.join(', ') + ' };';
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
    { label:'pro', ok:true, data:{ hubName:'Pro', generatedAt:'2026-05-23 10:00 UTC',
        allDevices:{ '1':{id:1,protocol:'Zigbee',manufacturer:'X'}, '2':{id:2,protocol:'Linked'} } } },
    { label:'main', ok:false, error:'unreachable', data:null },
  ];
  const m = C.mergeFleet(hubResults);
  assert.strictEqual(m.rows.length, 1);
  assert.strictEqual(m.rows[0].hub, 'pro');
  assert.strictEqual(m.hubs[0].deviceCount, 1);
  assert.strictEqual(m.hubs[0].hubName, 'Pro');
  assert.strictEqual(m.hubs[1].ok, false);
  assert.strictEqual(m.hubs[1].error, 'unreachable');
});

t('firmwareDrift flags mixed firmware, ignores singletons, marks unknown-only as not-mixed, sorts mixed first', () => {
  const rows = [
    {manufacturer:'Acme',model:'M1',firmware:'1.0',firmwareOta:'100',hub:'a'},
    {manufacturer:'Acme',model:'M1',firmware:'1.1',firmwareOta:'101',hub:'b'},   // mixed group (different OTA)
    {manufacturer:'Acme',model:'M1',firmware:'1.0',firmwareOta:'100',hub:'c'},
    {manufacturer:'Beta',model:'B1',firmware:'2.0',firmwareOta:'200',hub:'a'},
    {manufacturer:'Beta',model:'B1',firmware:'2.0',firmwareOta:'200',hub:'b'},   // consistent group
    {manufacturer:'Solo',model:'S1',firmware:'9',firmwareOta:'900',hub:'a'},     // singleton -> excluded
    {manufacturer:'Ghost',model:'G1',firmware:'',hub:'a'},
    {manufacturer:'Ghost',model:'G1',firmware:'',hub:'b'},     // unknown-only group (blank firmware, no OTA)
    {manufacturer:'Ghost',model:'G1',firmware:'',hub:'c'},
  ];
  const d = C.firmwareDrift(rows);
  assert.strictEqual(d.length, 3);                 // M1 + B1 + G1, not S1
  assert.strictEqual(d[0].mixed, true);            // mixed sorts first
  assert.strictEqual(d[0].model, 'M1');
  assert.strictEqual(d[0].count, 3);
  assert.strictEqual(d[1].mixed, false);
  const ghost = d.find(g => g.model === 'G1');
  assert(ghost, 'Ghost/G1 group should be present');
  assert.strictEqual(ghost.mixed, false);          // unknown-only: all version='unknown', not mixed
  // devices array is exposed on each group
  assert(d.find(g => g.model === 'M1').devices.length === 3);
  assert(d.find(g => g.model === 'B1').devices.length === 2);
  assert(ghost.devices.length === 3);
});

t('firmwareDrift uses firmwareOta for variant grouping — same OTA despite different firmware strings is NOT mixed', () => {
  // Two devices with different firmware labels but same firmwareOta -> one OTA variant -> not mixed.
  // A third device with a different firmwareOta -> two OTA variants -> mixed.
  const rows = [
    {manufacturer:'Widgets',model:'W1',firmware:'1.0',  firmwareOta:'500',hub:'a'},
    {manufacturer:'Widgets',model:'W1',firmware:'1.01', firmwareOta:'500',hub:'b'},  // same OTA as above
    {manufacturer:'Widgets',model:'W1',firmware:'1.5',  firmwareOta:'501',hub:'c'},  // different OTA
  ];
  const d = C.firmwareDrift(rows);
  assert.strictEqual(d.length, 1);
  assert.strictEqual(d[0].model, 'W1');
  assert.strictEqual(d[0].mixed, true);   // OTA 500 vs 501 -> mixed
  assert.strictEqual(d[0].variants.length, 2);
  // OTA 500 group has count 2 (both firmware:'1.0' and firmware:'1.01' share it)
  const v500 = d[0].variants.find(v => v.version === '500');
  assert(v500, 'variant 500 should exist');
  assert.strictEqual(v500.count, 2);
  // If we remove the third device, same-OTA group should NOT be mixed
  const rows2 = [
    {manufacturer:'Widgets',model:'W1',firmware:'1.0',  firmwareOta:'500',hub:'a'},
    {manufacturer:'Widgets',model:'W1',firmware:'1.01', firmwareOta:'500',hub:'b'},
  ];
  const d2 = C.firmwareDrift(rows2);
  assert.strictEqual(d2.length, 1);
  assert.strictEqual(d2[0].mixed, false, 'same OTA on both devices must NOT be mixed');
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

t('fleetSummary counts by hub/protocol/manufacturer + attention totals', () => {
  // lastActivityTimeMs:Date.now() keeps every row non-stale so attentionCounts isolates disabled/unreferenced.
  const merged = { rows: [
    {hub:'a', protocol:'Zigbee', manufacturer:'X', appsUsingCount:1, dashboards:[1], lastActivityTimeMs:Date.now()},
    {hub:'a', protocol:'Z-Wave', manufacturer:'X', appsUsingCount:0, dashboards:[],  lastActivityTimeMs:Date.now()},
    {hub:'b', protocol:'Zigbee', manufacturer:'Y', disabled:true,    appsUsingCount:1, dashboards:[1], lastActivityTimeMs:Date.now()},
  ], hubs: [] };
  const s = C.fleetSummary(merged);
  assert.strictEqual(s.total, 3);
  assert.strictEqual(s.byHub.a, 2);
  assert.strictEqual(s.byProtocol.Zigbee, 2);
  assert.strictEqual(s.byManufacturer.X, 2);
  assert.strictEqual(s.attentionCounts.disabled, 1);
  assert.strictEqual(s.attentionCounts.unreferenced, 1);
});

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
