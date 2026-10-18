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
fs.writeFileSync(tmp, ['classifyComponent', 'safeToRemove', 'fmtDays', 'isMultiPackage', 'clink', 'escText', 'h', 'badge', 'legend'].map(extractFn).join('\n')
  + '\nconst STATUS_HELP = ' + /const STATUS_HELP=(\{.*?\});/s.exec(src)[1] + ';'
  + '\nmodule.exports = { classifyComponent, safeToRemove, fmtDays, isMultiPackage, clink, legend, STATUS_HELP };');
const { classifyComponent, safeToRemove, fmtDays, isMultiPackage, clink, legend, STATUS_HELP } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });
let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); } catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

t('update-available -> warn color, label', () => { const r = classifyComponent({origin:'url', status:'update-available'}); assert.strictEqual(r.color, 'var(--warn)'); assert.strictEqual(r.label, 'Update available'); });
t('broken-source -> crit', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'broken-source'}).color, 'var(--crit)'));
t('current -> ok', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'current'}).color, 'var(--ok)'));
t('unmanaged -> muted, No upstream', () => { const r = classifyComponent({origin:'local', status:'unmanaged'}); assert.strictEqual(r.color, 'var(--muted)'); assert.strictEqual(r.label, 'No upstream'); });
t('unknown means not checked, not "no upstream"', () => assert.strictEqual(classifyComponent({origin:'url', status:'unknown'}).label, 'Not checked'));
t('not-installed is muted, not an error', () => { const r = classifyComponent({origin:'hpm', status:'not-installed'}); assert.strictEqual(r.label, 'Not installed'); assert.strictEqual(r.color, 'var(--muted)'); });
t('missing stays critical', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'missing'}).color, 'var(--crit)'));
t('incomplete -> crit', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'incomplete'}).label, 'Incomplete'));
t('every status has a legend line', () => Object.keys({current:1,'update-available':1,'changed-upstream':1,'modified-locally':1,'broken-source':1,incomplete:1,missing:1,'not-installed':1,private:1,unmanaged:1,unknown:1}).forEach(k => assert(STATUS_HELP[k], 'no help text for ' + k)));
t('legend renders a badge and its explanation', () => { const out = legend(['current']); assert(out.includes('Current')); assert(out.includes(STATUS_HELP['current'])); });
t('modified-locally label', () => assert.strictEqual(classifyComponent({origin:'url', status:'modified-locally'}).label, 'Modified locally'));

const comps = [
  {key:'url:a', kind:'driver', hubId:'1', namespace:'x', name:'Drv A', usedByCount:0, bundleOf:null},
  {key:'url:b', kind:'driver', hubId:'2', namespace:'x', name:'Drv B', usedByCount:3, bundleOf:null},
  {key:'bundle:9', kind:'driver', hubId:'3', namespace:'x', name:'Drv C', usedByCount:0, bundleOf:'9'},
  {key:'bundle:9', kind:'app', hubId:'4', namespace:'x', name:'App C', usedByCount:2, bundleOf:'9'},
];
t('unused, unbundled, unreferenced -> removable', () => assert.strictEqual(safeToRemove(comps[0], comps), true));
t('in use -> not removable', () => assert.strictEqual(safeToRemove(comps[1], comps), false));
t('bundle member with a used sibling -> not removable', () => assert.strictEqual(safeToRemove(comps[2], comps), false));
t('a declared but not-installed component is not removable', () => assert.strictEqual(safeToRemove(Object.assign({}, comps[0], {hubId:null}), comps), false));

t('file components link into File Manager at /local/<name>', () => assert.strictEqual(clink('file', 'watchtower.js', 'watchtower.js'), '<a href="/local/watchtower.js" target="_blank">watchtower.js</a>'));
t('file names are URL-encoded in the link', () => assert(clink('file', 'a b&c.html', 'a b&c.html').includes('href="/local/a%20b%26c.html"')));
t('driver still links to its editor', () => assert(clink('driver', '42', 'D').includes('href="/driver/editor/42"')));
t('an unbound component renders as plain text', () => assert.strictEqual(clink('bundle', null, 'B'), 'B'));

t('hpm package is a real package even with one component', () => assert.strictEqual(isMultiPackage({origin:'hpm', componentCount:1}), true));
t('bundle is a real package', () => assert.strictEqual(isMultiPackage({origin:'bundle', componentCount:1}), true));
t('single url import is standalone', () => assert.strictEqual(isMultiPackage({origin:'url', componentCount:1}), false));
t('local orphan is standalone', () => assert.strictEqual(isMultiPackage({origin:'local', componentCount:1}), false));

const NOW = 1700000000000, DAY = 86400000;
t('fmtDays: never', () => assert.deepStrictEqual(fmtDays(0, NOW), {text:'Never', color:'var(--muted)'}));
t('fmtDays: 2 days ok', () => assert.deepStrictEqual(fmtDays(NOW - 2*DAY, NOW), {text:'2d', color:'var(--ok)'}));
t('fmtDays: 8 days warn', () => assert.strictEqual(fmtDays(NOW - 8*DAY, NOW).color, 'var(--warn)'));
t('fmtDays: 15 days crit', () => assert.strictEqual(fmtDays(NOW - 15*DAY, NOW).color, 'var(--crit)'));
console.log(`\n${pass} passed, ${fail} failed, 0 warnings`); process.exit(fail ? 1 : 0);
