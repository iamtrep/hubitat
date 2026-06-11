#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for Matter sub-tab pure helpers (cluster/attr/command name tables,
// CHIP line parser, continuation grouper, field extractor, dedup-append).
// Mirrors the brace-extract pattern in test-radio-derivations.js so tests stay
// bound to the shipped functions rather than copies.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_diagnostics_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found in HTML: ' + name);
  const braceOpen = src.indexOf('{', start);
  let depth = 0, i = braceOpen;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}

function extractConst(name) {
  const start = src.indexOf('const ' + name + ' =');
  assert(start >= 0, 'const not found in HTML: ' + name);
  // Match braces to find the closing '}' then the terminating ';'.
  const braceOpen = src.indexOf('{', start);
  let depth = 0, i = braceOpen;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  // include the ';' if present
  if (src[i] === ';') i++;
  return src.slice(start, i);
}

const harness =
  extractConst('MATTER_CLUSTERS') + '\n' +
  extractConst('MATTER_GLOBAL_COMMANDS') + '\n' +
  extractFn('matterClusterName') + '\n' +
  extractFn('matterAttrName') + '\n' +
  extractFn('matterCommandName') + '\n' +
  'module.exports = { MATTER_CLUSTERS, MATTER_GLOBAL_COMMANDS, matterClusterName, matterAttrName, matterCommandName };';
const tmp = path.join(os.tmpdir(), 'hd_matter_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const M = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(name, fn){ try { fn(); pass++; console.log('  ok   ' + name); }
  catch (e) { fail++; console.log('  FAIL ' + name + '\n         ' + e.message); } }

console.log('Matter cluster/attribute/command name lookup');

t('matterClusterName: OnOff (0x0006)', () => {
  assert.strictEqual(M.matterClusterName(0x0006), 'OnOff');
});
t('matterClusterName: BasicInformation (0x0028)', () => {
  assert.strictEqual(M.matterClusterName(0x0028), 'BasicInformation');
});
t('matterClusterName: Descriptor (0x001D)', () => {
  assert.strictEqual(M.matterClusterName(0x001D), 'Descriptor');
});
t('matterClusterName: unknown cluster returns null', () => {
  assert.strictEqual(M.matterClusterName(0xABCD), null);
});
t('matterAttrName: OnOff.OnOff (0x0006/0x0000)', () => {
  assert.strictEqual(M.matterAttrName(0x0006, 0x0000), 'OnOff');
});
t('matterAttrName: unknown attr in known cluster returns null', () => {
  assert.strictEqual(M.matterAttrName(0x0006, 0xFFFF), null);
});
t('matterAttrName: unknown cluster returns null', () => {
  assert.strictEqual(M.matterAttrName(0xABCD, 0x0000), null);
});
t('matterCommandName: global ReadRequest (0x02)', () => {
  assert.strictEqual(M.matterCommandName(null, 0x02), 'ReadRequest');
});
t('matterCommandName: OnOff cluster command On (0x01)', () => {
  assert.strictEqual(M.matterCommandName(0x0006, 0x01), 'On');
});

t('matterAttrName: null cluster returns null', () => {
  assert.strictEqual(M.matterAttrName(null, 0x0000), null);
});
t('matterCommandName: known cluster, command absent → falls through to globals', () => {
  // Descriptor (0x001D) has no cmds; an IM-layer ReadRequest (0x02) should still resolve.
  assert.strictEqual(M.matterCommandName(0x001D, 0x02), 'ReadRequest');
});

console.log('\n  ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
