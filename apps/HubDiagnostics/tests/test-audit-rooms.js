#!/usr/bin/env node
// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for the SPA's Devices-by-room derivation (Audit tab).
//
// The hub ships getRooms() as is: child devices included, no "Unassigned" room, and ids of
// deleted devices can linger. buildAuditRooms() drops ids the scan never saw and gathers the
// unassigned devices. This EXTRACTS buildAuditRooms from hub_diagnostics_ui.html by name, so
// the test stays bound to the shipped code.
//
// Run: node apps/HubDiagnostics/tests/test-audit-rooms.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_diagnostics_ui.html'), 'utf8');

// Brace-matched extraction of a (possibly multi-line) `function NAME(...){ ... }`.
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
const tmp = path.join(os.tmpdir(), 'hd_rooms_' + process.pid + '.js');
fs.writeFileSync(tmp, extractFn('buildAuditRooms') + '\nmodule.exports = { buildAuditRooms };');
const R = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

let pass = 0, fail = 0;
function t(n, fn) { try { fn(); pass++; console.log('  ok   ' + n); }
  catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }

console.log('audit rooms');

// allDevices keys are strings (JSON map keys); getRooms() deviceIds are numbers.
const allDevices = { '1': {}, '2': {}, '3': {}, '10': {}, '11': {}, '20': {} };
const failed = [{ id: 30, reason: 'timeout' }];
const rooms = [
  { id: 5, name: 'Cameras', deviceIds: [10, 11] },   // 11: child device, flattened by getRooms()
  { id: 6, name: 'Salon', deviceIds: [1, 2, 750] },  // 750: deleted device
  { id: 7, name: 'Empty', deviceIds: [] }
];

t('drops ids the scan never saw', () => {
  const salon = R.buildAuditRooms(rooms, allDevices, failed).find(r => r.name === 'Salon');
  assert.deepStrictEqual(salon.deviceIds, [1, 2]);
});
t('keeps child devices and empty rooms', () => {
  const out = R.buildAuditRooms(rooms, allDevices, failed);
  assert.deepStrictEqual(out.find(r => r.name === 'Cameras').deviceIds, [10, 11]);
  assert.deepStrictEqual(out.find(r => r.name === 'Empty').deviceIds, []);
});
t('gathers unassigned devices, failed scans included, as a last row', () => {
  const out = R.buildAuditRooms(rooms, allDevices, failed);
  const last = out[out.length - 1];
  assert.strictEqual(last.name, 'Unassigned');
  assert.strictEqual(last.id, null);
  assert.deepStrictEqual(last.deviceIds.sort((a, b) => a - b), [3, 20, 30]);
});
t('rooms ordered by name, case-insensitive, Unassigned last', () => {
  const out = R.buildAuditRooms([{ id: 1, name: 'salon', deviceIds: [1] }, { id: 2, name: 'Bureau', deviceIds: [2] }],
                                { '1': {}, '2': {}, '3': {} }, []);
  assert.deepStrictEqual(out.map(r => r.name), ['Bureau', 'salon', 'Unassigned']);
});
t('no Unassigned row when every device has a room', () => {
  const out = R.buildAuditRooms([{ id: 1, name: 'All', deviceIds: [1, 2] }], { '1': {}, '2': {} }, []);
  assert.deepStrictEqual(out.map(r => r.name), ['All']);
});
t('null inputs -> []', () => {
  assert.deepStrictEqual(R.buildAuditRooms(null, null, null), []);
});

console.log(`\n${pass}/${pass + fail} passed${fail ? `, ${fail} failed` : ''}`);
process.exit(fail ? 1 : 0);
