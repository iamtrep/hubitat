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

// --- tests appended in later tasks ---
t('filterLinked drops protocol === Linked', () => {
  const rows = [{id:1,protocol:'Zigbee'},{id:2,protocol:'Linked'},{id:3,protocol:'Z-Wave'}];
  const out = C.filterLinked(rows);
  assert.deepStrictEqual(out.map(r=>r.id), [1,3]);
});

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
