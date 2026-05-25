#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for makeObf() (hub_diagnostics_ui.html) — the UI-wide name aliaser.
// makeObf is dependency-free (word lists live inside it), so it's extracted by
// name (brace-matched) and run directly — bound to the shipped code, not a copy.
//
// Run: node apps/HubDiagnostics/tests/test-obfuscator.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_diagnostics_ui.html'), 'utf8');

function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found: ' + name);
  const open = src.indexOf('{', start);
  let depth = 0, i = open;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}
const tmp = path.join(os.tmpdir(), 'hd_obf_' + process.pid + '.js');
fs.writeFileSync(tmp, extractFn('makeObf') + '\nmodule.exports = { makeObf };');
const { makeObf } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); }
  catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

t('disabled: nm() is passthrough', () => {
  const o = makeObf();
  assert.strictEqual(o.nm('Front Door Sensor'), 'Front Door Sensor');
});

t('enabled: nm() returns adjective-noun alias', () => {
  const o = makeObf(); o.enabled = true;
  const a = o.nm('Front Door Sensor');
  assert.notStrictEqual(a, 'Front Door Sensor');
  assert.ok(/^[a-z]+-[a-z]+$/.test(a), 'unexpected alias format: ' + a);
});

t('deterministic: same name -> same alias', () => {
  const o = makeObf(); o.enabled = true;
  assert.strictEqual(o.nm('Kitchen Light'), o.nm('Kitchen Light'));
});

t('cross-instance determinism (hash-based, not call-order)', () => {
  const a = makeObf(); a.enabled = true;
  const b = makeObf(); b.enabled = true;
  b.nm('zzz'); b.nm('qqq');
  assert.strictEqual(a.nm('Garage Door'), b.nm('Garage Door'));
});

t('uniqueness across a set of names', () => {
  const o = makeObf(); o.enabled = true;
  const names = []; for (let i = 0; i < 200; i++) names.push('Device ' + i);
  const aliases = new Set(names.map(n => o.nm(n)));
  assert.strictEqual(aliases.size, names.length, 'aliases collided');
});

t('empty/null passthrough', () => {
  const o = makeObf(); o.enabled = true;
  assert.strictEqual(o.alias(''), '');
  assert.strictEqual(o.alias(null), null);
});

t('scrub: rewrites registered name embedded in free text', () => {
  const o = makeObf(); o.enabled = true;
  o.register(['Front Door']);
  assert.strictEqual(o.scrub('Front Door went offline'), o.alias('Front Door') + ' went offline');
});

t('scrub: longest-first avoids partial overlap', () => {
  const o = makeObf(); o.enabled = true;
  o.register(['Front', 'Front Door']);
  assert.strictEqual(o.scrub('Front Door'), o.alias('Front Door'));
});

t('scrub: disabled passthrough', () => {
  const o = makeObf();
  o.register(['Front Door']);
  assert.strictEqual(o.scrub('Front Door went offline'), 'Front Door went offline');
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
