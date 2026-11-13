#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Radio Capture tail: each ~1 Hz render shows only the newest RC_TAIL_MAX_ROWS rows and reuses the rows
// it already built for a frame, instead of parsing the whole buffer and rebuilding every row each time.
// Extracts the shipped rcTailCollect, rcZbRenderTail, rcZwRenderTail and the two constants by name; the
// frame decoders and the paint call are stubs that count their calls.
//
// Run: node apps/HubInspector/tests/test-radio-tail-cap.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');
function extractFn(name) {
  const start = src.indexOf('function ' + name + '(');
  assert(start >= 0, 'function not found in HTML: ' + name);
  let depth = 0, i = src.indexOf('{', start);
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
}
function extractConst(name) {
  const m = src.match(new RegExp('const ' + name + '=[^;]+;'));
  assert(m, 'constant not found in HTML: ' + name);
  return m[0];
}

const harness = [
  extractConst('RC_TAIL_MAX_ROWS'),
  'const RC={zigbee:{},zwave:{},zwaveStack:"legacy"};',
  'const calls={zbRow:0,zwRow:0,paint:0};',
  'function rcZbFrameMatchesFilter(line,f){return !f||!f.q||f.q.every(t=>line.includes(t))}',
  'function rcZbFrameToRow(line){calls.zbRow++;return {_raw:line}}',
  'function rcZwFrameToRow(line){calls.zwRow++;return {_raw:line}}',
  'function rcZwRowMatchesFilter(r,f){return !f||!f.q||f.q.every(t=>r._raw.includes(t))}',
  'function rcTailPaint(){calls.paint++}',
  'function rcZbRenderTailHtml(){return ""}',
  'function rcZwRenderTailHtml(){return ""}',
  extractFn('rcTailCollect'), extractFn('rcZbRenderTail'), extractFn('rcZwRenderTail'),
  'module.exports={RC,calls,rcTailCollect,rcZbRenderTail,rcZwRenderTail,MAX:RC_TAIL_MAX_ROWS};',
].join('\n');
const tmp = path.join(os.tmpdir(), 'hd_tailcap_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const H = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(name, fn){ try { fn(); pass++; console.log('  ok   ' + name); }
  catch (e) { fail++; console.log('  FAIL ' + name + '\n         ' + e.message); } }

const MAX = H.MAX;
function mk(n) { const a = []; for (let i = 0; i < n; i++) a.push({ line: 'f' + i }); return a; }

console.log('Radio Capture tail cap and row reuse');

t('cap is a sane positive number', () => assert(MAX > 0 && MAX <= 2000));

t('Zigbee: a buffer larger than the cap shows only the newest rows, newest first', () => {
  H.calls.zbRow = 0;
  const z = H.RC.zigbee; z.filters = null; z.buf = { frames: mk(MAX * 4) };
  H.rcZbRenderTail({});
  assert.strictEqual(z.displayBuf.length, MAX);
  assert.strictEqual(z.displayBuf[0]._raw, 'f' + (MAX * 4 - 1));
  assert.strictEqual(z.displayBuf[MAX - 1]._raw, 'f' + (MAX * 3));
  assert.strictEqual(z.displayCapped, true);
  assert.strictEqual(H.calls.zbRow, MAX, 'decoder ran on ' + H.calls.zbRow + ' frames');
});

t('Zigbee: the next tick decodes only the frames that arrived', () => {
  const z = H.RC.zigbee; H.calls.zbRow = 0;
  for (let i = 0; i < 7; i++) z.buf.frames.push({ line: 'n' + i });
  H.rcZbRenderTail({ fromTick: true });
  assert.strictEqual(H.calls.zbRow, 7, 'decoder ran on ' + H.calls.zbRow + ' frames');
  assert.strictEqual(z.displayBuf[0]._raw, 'n6');
  assert.strictEqual(z.displayBuf.length, MAX);
});

t('Zigbee: a changed filter decodes afresh and drops the capped flag when few rows match', () => {
  const z = H.RC.zigbee; H.calls.zbRow = 0;
  z.filters = { q: ['n3'] };
  H.rcZbRenderTail({ repin: true });
  assert.deepStrictEqual(z.displayBuf.map(r => r._raw), ['n3']);
  assert.strictEqual(z.displayCapped, false);
});

t('Zigbee: a small buffer is unchanged (all rows, not capped)', () => {
  const z = H.RC.zigbee; z.filters = null; z.buf = { frames: mk(10) }; z._rowCache = null;
  H.rcZbRenderTail({});
  assert.strictEqual(z.displayBuf.length, 10);
  assert.strictEqual(z.displayCapped, false);
});

t('Z-Wave: capped, newest first, and rows reused on the next tick', () => {
  const z = H.RC.zwave; z.filters = null; z.stack = 'legacy'; z.buf = { frames: mk(MAX * 3) };
  H.calls.zwRow = 0;
  H.rcZwRenderTail({});
  assert.strictEqual(z.displayBuf.length, MAX);
  assert.strictEqual(z.displayBuf[0]._raw, 'f' + (MAX * 3 - 1));
  assert.strictEqual(H.calls.zwRow, MAX);
  H.calls.zwRow = 0;
  z.buf.frames.push({ line: 'w1' });
  H.rcZwRenderTail({ fromTick: true });
  assert.strictEqual(H.calls.zwRow, 1);
});

t('Z-Wave: a different stack does not reuse rows built by the other mapper', () => {
  const z = H.RC.zwave; H.calls.zwRow = 0; z.stack = 'js';
  H.rcZwRenderTail({ repin: true });
  assert.strictEqual(H.calls.zwRow, MAX);
});

t('rcTailCollect: frames that do not match are skipped without counting toward the cap', () => {
  const z = {}; const fr = []; for (let i = 0; i < MAX * 2; i++) fr.push({ line: i % 2 ? 'keep' + i : 'drop' + i });
  const out = H.rcTailCollect(z, fr, 'k', l => l.startsWith('keep') ? { l } : null);
  assert.strictEqual(out.length, MAX);
  assert(out.every(r => r.l.startsWith('keep')));
});

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
