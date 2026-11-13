#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// h() attribute-safe escaping, report embedding round trip, liveTimer scoping.
// Run: node apps/HubInspector/tests/test-escaping.js
// HI_UI_SRC=<file> runs the same checks against another copy of the UI.
'use strict';
const fs = require('fs'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(process.env.HI_UI_SRC || path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');

function extractFn(name) {
  const s = src.indexOf('function ' + name + '(');
  assert(s >= 0, 'function not found: ' + name);
  const o = src.indexOf('{', s); let d = 0, i = o;
  for (; i < src.length; i++) { if (src[i] === '{') d++; else if (src[i] === '}') { d--; if (d === 0) { i++; break; } } }
  return src.slice(s, i);
}
let failed = 0;
function t(name, fn) { try { fn(); console.log('  [PASS] ' + name); } catch (e) { failed++; console.error('  [FAIL] ' + name + ': ' + e.message); } }

// document is deliberately absent: h() must not need the DOM.
const h = new Function(extractFn('h') + '; return h;')();
const embedReportData = new Function(extractFn('embedReportData') + '; return embedReportData;')();

t('h escapes double and single quotes', () => assert.strictEqual(h('a"b\'c'), 'a&quot;b&#39;c'));
t('h escapes & first, once', () => assert.strictEqual(h('&lt; & <b>'), '&amp;lt; &amp; &lt;b&gt;'));
t('h null/undefined give empty string', () => { assert.strictEqual(h(null), ''); assert.strictEqual(h(undefined), ''); });
t('h of a number', () => assert.strictEqual(h(0), '0'));
t('h output cannot break out of data-v="..."', () => {
  const out = '<td data-v="' + h('x" onmouseover="alert(1)') + '">';
  assert.strictEqual((out.match(/"/g) || []).length, 2);
});

const tmpl = '<html><head><title>t</title></head><body></body></html>';
t('embedding survives $&, $`, $\' , </SCRIPT> and <!--', () => {
  const data = { a: 'x$&y', b: '$`z$\'', c: '</SCRIPT><script>', d: '<!-- <script>', e: '$$' };
  const out = embedReportData(tmpl, data);
  const m = out.match(/<script type="application\/json" id="report-data">([\s\S]*?)<\/script>/);
  assert(m, 'data block not found');
  assert(!/<!--/.test(m[1]) && !/<\/script/i.test(m[1]), 'raw < left in data block');
  assert.deepStrictEqual(JSON.parse(m[1]), data);
  assert(out.indexOf('</head>') > out.indexOf('report-data'), '</head> kept after the block');
  assert.strictEqual(out.split('</head>').length, 2);
});

t('liveTimer declared at top level, not inside the startup block', () => {
  const decl = src.indexOf('let liveTimer');
  assert(decl >= 0 && decl === src.lastIndexOf('let liveTimer'), 'exactly one let liveTimer');
  assert(decl < src.indexOf('cfg_saveLive'), 'declared before the save handler');
  assert(decl < src.indexOf('// ===== STARTUP ====='), 'declared outside the startup block');
});

if (failed) { console.error(failed + ' failed'); process.exit(1); }
console.log('all passed');
