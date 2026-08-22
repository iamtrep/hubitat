#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Hostile-fixture test for the pure escaping helpers in code_manager_ui.html (DESIGN.md §10 "escaping").
// Run: node apps/CodeManager/tests/test-escaping.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'code_manager_ui.html'), 'utf8');
function extractFn(name) {
  const start = src.indexOf('function ' + name + '('); assert(start >= 0, 'function not found: ' + name);
  const open = src.indexOf('{', start); let depth = 0, i = open;
  for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } } }
  return src.slice(start, i);
}
const tmp = path.join(os.tmpdir(), 'cm_esc_' + process.pid + '.js');
fs.writeFileSync(tmp, ['safeHref', 'csvCell', 'escText'].map(extractFn).join('\n') + '\nmodule.exports = { safeHref, csvCell, escText };');
const { safeHref, csvCell, escText } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });
let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); } catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

t('https passes', () => assert.strictEqual(safeHref('https://raw.githubusercontent.com/a/b.groovy'), 'https://raw.githubusercontent.com/a/b.groovy'));
t('javascript: rejected', () => assert.strictEqual(safeHref('javascript:alert(1)'), ''));
t('http rejected', () => assert.strictEqual(safeHref('http://x/y'), ''));
t('quote-breaking URL rejected', () => assert.strictEqual(safeHref('https://x/y" onmouseover="alert(1)'), ''));
t('null/undefined -> empty', () => { assert.strictEqual(safeHref(null), ''); assert.strictEqual(safeHref(undefined), ''); });
t('escText neutralizes tags', () => assert.strictEqual(escText('<script>x</script>'), '&lt;script&gt;x&lt;/script&gt;'));
t('escText neutralizes attributes', () => assert.strictEqual(escText('" onerror="alert(1)'), '&quot; onerror=&quot;alert(1)'));
t('csv formula prefix neutralized', () => { for (const p of ['=', '+', '-', '@']) assert.ok(csvCell(p + 'cmd()').startsWith('"\'' + p)); });
t('csv quotes doubled and wrapped', () => assert.strictEqual(csvCell('a "b", c'), '"a ""b"", c"'));
t('csv plain passthrough', () => assert.strictEqual(csvCell('plain'), 'plain'));
console.log(`\n${pass} passed, ${fail} failed, 0 warnings`); process.exit(fail ? 1 : 0);
