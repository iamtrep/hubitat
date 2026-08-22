#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 3 extraction test for the pure SPA helpers in code_manager_ui.html (DESIGN.md §3, §10).
// Run: node apps/CodeManager/tests/test-classify.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'code_manager_ui.html'), 'utf8');
function extractFn(name) {
  const start = src.indexOf('function ' + name + '('); assert(start >= 0, 'function not found: ' + name);
  const open = src.indexOf('{', start); let depth = 0, i = open;
  for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } } }
  return src.slice(start, i);
}
const tmp = path.join(os.tmpdir(), 'cm_classify_' + process.pid + '.js');
fs.writeFileSync(tmp, ['classifyComponent', 'safeToRemove', 'fmtDays'].map(extractFn).join('\n') + '\nmodule.exports = { classifyComponent, safeToRemove, fmtDays };');
const { classifyComponent, safeToRemove, fmtDays } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });
let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); } catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

t('update-available -> warn color, label', () => { const r = classifyComponent({origin:'url', status:'update-available'}); assert.strictEqual(r.color, 'var(--warn)'); assert.strictEqual(r.label, 'Update available'); });
t('broken-source -> crit', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'broken-source'}).color, 'var(--crit)'));
t('current -> ok', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'current'}).color, 'var(--ok)'));
t('unknown local -> muted', () => assert.strictEqual(classifyComponent({origin:'local', status:'unknown'}).color, 'var(--muted)'));
t('modified-locally label', () => assert.strictEqual(classifyComponent({origin:'url', status:'modified-locally'}).label, 'Modified locally'));

const comps = [
  {key:'url:a', kind:'driver', hubId:'1', namespace:'x', name:'Drv A', usedByCount:0, bundleOf:null},
  {key:'url:b', kind:'driver', hubId:'2', namespace:'x', name:'Drv B', usedByCount:3, bundleOf:null},
  {key:'bundle:9', kind:'driver', hubId:'3', namespace:'x', name:'Drv C', usedByCount:0, bundleOf:'9'},
  {key:'bundle:9', kind:'app', hubId:'4', namespace:'x', name:'App C', usedByCount:2, bundleOf:'9'},
  {key:'url:p', kind:'app', hubId:'5', namespace:'x', name:'Parent', usedByCount:1, bundleOf:null, childTypes:['x/Child']},
  {key:'url:c', kind:'app', hubId:'6', namespace:'x', name:'Child', usedByCount:0, bundleOf:null},
];
t('unused, unbundled, unreferenced -> removable', () => assert.strictEqual(safeToRemove(comps[0], comps), true));
t('in use -> not removable', () => assert.strictEqual(safeToRemove(comps[1], comps), false));
t('bundle member with a used sibling -> not removable', () => assert.strictEqual(safeToRemove(comps[2], comps), false));
t('child type referenced by an installed parent -> not removable', () => assert.strictEqual(safeToRemove(comps[5], comps), false));

const NOW = 1700000000000, DAY = 86400000;
t('fmtDays: never', () => assert.deepStrictEqual(fmtDays(0, NOW), {text:'Never', color:'var(--muted)'}));
t('fmtDays: 2 days ok', () => assert.deepStrictEqual(fmtDays(NOW - 2*DAY, NOW), {text:'2d', color:'var(--ok)'}));
t('fmtDays: 8 days warn', () => assert.strictEqual(fmtDays(NOW - 8*DAY, NOW).color, 'var(--warn)'));
t('fmtDays: 15 days crit', () => assert.strictEqual(fmtDays(NOW - 15*DAY, NOW).color, 'var(--crit)'));
console.log(`\n${pass} passed, ${fail} failed, 0 warnings`); process.exit(fail ? 1 : 0);
