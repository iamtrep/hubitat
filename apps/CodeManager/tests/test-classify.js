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
fs.writeFileSync(tmp, ['classifyComponent', 'removeState', 'basisLabel', 'severity', 'fmtDays', 'isMultiPackage', 'clink', 'escText', 'h', 'badge', 'legend'].map(extractFn).join('\n')
  + '\nconst STATUS_HELP = ' + /const STATUS_HELP=(\{.*?\});/s.exec(src)[1] + ';'
  + '\nconst SEVERITY = ' + /const SEVERITY=(\[.*?\]);/s.exec(src)[1] + ';'
  + '\nmodule.exports = { classifyComponent, removeState, basisLabel, severity, fmtDays, isMultiPackage, clink, legend, STATUS_HELP };');
const { classifyComponent, removeState, basisLabel, severity, fmtDays, isMultiPackage, clink, legend, STATUS_HELP } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });
let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); } catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

t('update-available -> warn color, label', () => { const r = classifyComponent({origin:'url', status:'update-available'}); assert.strictEqual(r.color, 'var(--warn)'); assert.strictEqual(r.label, 'Update available'); });
t('broken-source -> crit', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'broken-source'}).color, 'var(--crit)'));
t('current -> ok', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'current'}).color, 'var(--ok)'));
t('unmanaged -> muted, No upstream', () => { const r = classifyComponent({origin:'local', status:'unmanaged'}); assert.strictEqual(r.color, 'var(--muted)'); assert.strictEqual(r.label, 'No upstream'); });
t('not-checked means not checked, not "no upstream"', () => assert.strictEqual(classifyComponent({origin:'url', status:'not-checked'}).label, 'Not checked'));
t('a missing status reads as not checked', () => assert.strictEqual(classifyComponent({origin:'url'}).label, 'Not checked'));
t('not-installed is muted, not an error', () => { const r = classifyComponent({origin:'hpm', status:'not-installed'}); assert.strictEqual(r.label, 'Not installed'); assert.strictEqual(r.color, 'var(--muted)'); });
t('missing stays critical', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'missing'}).color, 'var(--crit)'));
t('incomplete -> crit', () => assert.strictEqual(classifyComponent({origin:'hpm', status:'incomplete'}).label, 'Incomplete'));
const CLOSED = ['current','update-available','changed-upstream','modified-locally','broken-source','private','unmanaged','not-checked','missing','not-installed','incomplete'];
t('every status in the closed set has a legend line', () => CLOSED.forEach(k => assert(STATUS_HELP[k], 'no help text for ' + k)));
t('no legend line outside the closed set, none for unknown', () => { assert(!STATUS_HELP.unknown); Object.keys(STATUS_HELP).forEach(k => assert(CLOSED.includes(k), 'stray status ' + k)); });
t('severity: broken-source first, not-installed last', () => { assert(severity('broken-source') < severity('current')); assert.strictEqual(severity('broken-source'), 0); assert(severity('not-installed') > severity('private')); });
t('severity order of the audit (C4)', () => { const o = ['broken-source','missing','incomplete','modified-locally','update-available','changed-upstream','not-checked','current','unmanaged','private','not-installed']; for (let i = 1; i < o.length; i++) assert(severity(o[i-1]) < severity(o[i]), o[i-1] + ' before ' + o[i]); });
t('severity of an unlisted status sorts after every listed one', () => assert(severity('whatever') > severity('not-installed')));
t('legend lists only the statuses given', () => { const out = legend(['current']); assert(!out.includes('Broken source')); });
t('legend renders a badge and its explanation', () => { const out = legend(['current']); assert(out.includes('Current')); assert(out.includes(STATUS_HELP['current'])); });
t('modified-locally label', () => assert.strictEqual(classifyComponent({origin:'url', status:'modified-locally'}).label, 'Modified locally'));

const comps = [
  {key:'url:a', kind:'driver', hubId:'1', namespace:'x', name:'Drv A', usedByCount:0, bundleOf:null},
  {key:'url:b', kind:'driver', hubId:'2', namespace:'x', name:'Drv B', usedByCount:3, bundleOf:null},
  {key:'bundle:9', kind:'driver', hubId:'3', namespace:'x', name:'Drv C', usedByCount:0, bundleOf:'9'},
  {key:'bundle:9', kind:'app', hubId:'4', namespace:'x', name:'App C', usedByCount:2, bundleOf:'9'},
];
t('unused, unbundled -> no current use', () => assert.strictEqual(removeState(comps[0], comps), 'no-current-use'));
t('in use -> in-use', () => assert.strictEqual(removeState(comps[1], comps), 'in-use'));
t('bundle member with a used sibling -> in-use', () => assert.strictEqual(removeState(comps[2], comps), 'in-use'));
t('a bundle member whose siblings are all unused -> no current use', () => assert.strictEqual(removeState(comps[2], [comps[2], Object.assign({}, comps[3], {usedByCount:0})]), 'no-current-use'));
t('a declared but not-installed component is absent', () => assert.strictEqual(removeState(Object.assign({}, comps[0], {hubId:null}), comps), 'absent'));

const T0 = Date.UTC(2026, 9, 18, 12, 0, 0);
t('basisLabel: bundle and file facts are live', () => { assert.strictEqual(basisLabel({kind:'file', hubId:'x'}, T0), 'live'); assert.strictEqual(basisLabel({kind:'bundle', hubId:'1'}, T0), 'live'); });
t('basisLabel: valid and provisional read at a date', () => { assert(/^read /.test(basisLabel({kind:'app', hubId:'1', basis:'valid', factsReadAtMs:T0}, T0))); assert(/^read /.test(basisLabel({kind:'app', hubId:'1', basis:'provisional', factsReadAtMs:T0}, T0))); });
t('basisLabel: stale', () => assert.strictEqual(basisLabel({kind:'driver', hubId:'1', basis:'stale', factsReadAtMs:T0}, T0), 'not read since change'));
t('basisLabel: unread', () => assert.strictEqual(basisLabel({kind:'library', hubId:'1', basis:'unread'}, T0), 'not read yet'));
t('basisLabel: a failing read', () => assert.strictEqual(basisLabel({kind:'app', hubId:'1', basis:'unread', factError:'read failed'}, T0), 'could not read'));
t('basisLabel: large and not valid', () => assert.strictEqual(basisLabel({kind:'app', hubId:'1', basis:'stale', sizeBytes:600000}, T0, 524288), 'large: read on request'));
t('basisLabel: large but read is still a read', () => assert(/^read /.test(basisLabel({kind:'app', hubId:'1', basis:'valid', sizeBytes:600000, factsReadAtMs:T0}, T0, 524288))));
t('basisLabel: not on the hub is not read yet', () => assert.strictEqual(basisLabel({kind:'app', hubId:null}, T0), 'not read yet'));

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
