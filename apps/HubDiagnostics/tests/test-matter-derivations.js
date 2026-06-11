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
  extractFn('stripAnsi') + '\n' +
  extractFn('parseChipLine') + '\n' +
  extractFn('groupChipEntries') + '\n' +
  'module.exports = { MATTER_CLUSTERS, MATTER_GLOBAL_COMMANDS, matterClusterName, matterAttrName, matterCommandName, stripAnsi, parseChipLine, groupChipEntries };';
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

console.log('\nstripAnsi / parseChipLine');

t('stripAnsi: removes color codes', () => {
  assert.strictEqual(M.stripAnsi('[0;34mhello[0m world'), 'hello world');
});
t('stripAnsi: plain string unchanged', () => {
  assert.strictEqual(M.stripAnsi('no codes here'), 'no codes here');
});
t('parseChipLine: head line with single-digit pid:tid', () => {
  const r = M.parseChipLine('[1716491823.456] [1234:1235] [DMG] AttributeReportIBs =');
  assert.strictEqual(r.isHead, true);
  assert.strictEqual(r.epoch, 1716491823);
  assert.strictEqual(r.ms, 456);
  assert.strictEqual(r.pid, '1234');
  assert.strictEqual(r.tid, '1235');
  assert.strictEqual(r.component, 'DMG');
  assert.strictEqual(r.body, 'AttributeReportIBs =');
  assert.strictEqual(r.ansi, null);
});
t('parseChipLine: head line stripped of ANSI is parsed', () => {
  const r = M.parseChipLine('[0;34m[1716491823.456] [1234:1235] [EM] Sent message[0m');
  assert.strictEqual(r.isHead, true);
  assert.strictEqual(r.component, 'EM');
  assert.strictEqual(r.body, 'Sent message');
  assert.strictEqual(r.ansi, '0;34');
});
t('parseChipLine: continuation line (indented, no prefix)', () => {
  const r = M.parseChipLine('    Endpoint = 0x1,');
  assert.strictEqual(r.isHead, false);
  assert.strictEqual(r.body, '    Endpoint = 0x1,');
});
t('parseChipLine: blank line is a non-head (continuation passthrough)', () => {
  const r = M.parseChipLine('');
  assert.strictEqual(r.isHead, false);
  assert.strictEqual(r.body, '');
});
t('parseChipLine: head with empty body', () => {
  const r = M.parseChipLine('[1716491823.001] [1:2] [DMG] ');
  assert.strictEqual(r.isHead, true);
  assert.strictEqual(r.body, '');
});

console.log('\ngroupChipEntries');

const sample = [
  '[1716491823.456] [1:2] [DMG] AttributeReportIBs =',
  '[1716491823.457] [1:2] [DMG] {',
  '   Endpoint = 0x1,',
  '   Cluster = 0x6,',
  '   Attribute = 0x0,',
  '}',
  '[1716491824.000] [1:2] [EM] Sent message Exchange:42',
  '   to 0xABC',
  '[1716491825.000] [1:2] [DMG] solo line'
];

t('groupChipEntries: head + continuations = one entry; next head closes', () => {
  const g = M.groupChipEntries(sample);
  // The second line is also a head (CHIP commonly emits a sequence of heads). We get 4 entries:
  //   head 0 (AttributeReportIBs =) — no continuations
  //   head 1 ('{') + 4 continuations (Endpoint/Cluster/Attribute/})
  //   head 2 (Sent message) + 1 continuation (to 0xABC)
  //   head 3 (solo line) — no continuations
  assert.strictEqual(g.length, 4);
  assert.strictEqual(g[0].continuations.length, 0);
  assert.strictEqual(g[1].continuations.length, 4);
  assert.strictEqual(g[1].body, '{');
  assert.strictEqual(g[2].continuations.length, 1);
  assert.strictEqual(g[2].continuations[0], '   to 0xABC');
  assert.strictEqual(g[3].continuations.length, 0);
});
t('groupChipEntries: leading orphan continuations are dropped (no head context)', () => {
  const g = M.groupChipEntries(['   orphan line', '[1716491823.456] [1:2] [DMG] ok']);
  assert.strictEqual(g.length, 1);
  assert.strictEqual(g[0].body, 'ok');
});
t('groupChipEntries: empty input returns []', () => {
  assert.deepStrictEqual(M.groupChipEntries([]), []);
});
t('groupChipEntries: all-continuations-no-heads returns []', () => {
  assert.deepStrictEqual(M.groupChipEntries(['   orphan1', '   orphan2', '   orphan3']), []);
});
t('groupChipEntries: full raw block reconstructible from head + continuations', () => {
  const g = M.groupChipEntries(sample);
  // Reconstruct the second entry's full block: head raw + continuations joined with \n.
  const reconstructed = g[1].raw + '\n' + g[1].continuations.join('\n');
  assert.ok(reconstructed.indexOf('Endpoint = 0x1,') >= 0);
});

console.log('\n  ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
