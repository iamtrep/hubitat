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

t('firmwareDrift flags mixed firmware, ignores singletons + unknown-only, sorts mixed first', () => {
  const rows = [
    {manufacturer:'Acme',model:'M1',firmware:'1.0',hub:'a'},
    {manufacturer:'Acme',model:'M1',firmware:'1.1',hub:'b'},   // mixed group
    {manufacturer:'Acme',model:'M1',firmware:'1.0',hub:'c'},
    {manufacturer:'Beta',model:'B1',firmware:'2.0',hub:'a'},
    {manufacturer:'Beta',model:'B1',firmware:'2.0',hub:'b'},   // consistent group
    {manufacturer:'Solo',model:'S1',firmware:'9',hub:'a'},     // singleton -> excluded
  ];
  const d = C.firmwareDrift(rows);
  assert.strictEqual(d.length, 2);                 // M1 + B1, not S1
  assert.strictEqual(d[0].mixed, true);            // mixed sorts first
  assert.strictEqual(d[0].model, 'M1');
  assert.strictEqual(d[0].count, 3);
  assert.strictEqual(d[1].mixed, false);
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
