#!/usr/bin/env node
/*
 * Unit test for the confirm-text label helper of the checkpoint and snapshot delete buttons
 * (SPA side). Rows are addressed by key (timestampMs); the confirm text names the row's timestamp.
 * Extracts entryLabel from hub_inspector_ui.html by name, so it stays bound to the shipped code.
 *
 * Run:  node apps/HubInspector/tests/test-entry-label.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'hub_inspector_ui.html'), 'utf8');
const i = src.indexOf('function entryLabel(');
assert(i >= 0, 'entryLabel not found in HTML');
const entryLabel = new Function(src.slice(i, src.indexOf('\n', i)) + '\nreturn entryLabel;')();

const rows = [{ key: 3000, timestamp: '2026-11-13 10:00:00' }, { key: 2000, timestamp: '2026-11-13 09:00:00' }];
let pass = 0, fail = 0;
function t(name, f) { try { f(); pass++; console.log('  ok   ' + name); } catch (e) { fail++; console.log('  FAIL ' + name + '\n         ' + e.message); } }

t('names the row timestamp', () => assert.strictEqual(entryLabel(rows, 2000), '2026-11-13 09:00:00'));
t('does not use the position', () => assert.strictEqual(entryLabel(rows, 1), '1'));
t('unknown key falls back to the key', () => assert.strictEqual(entryLabel(rows, 4000), '4000'));
t('missing list falls back to the key', () => assert.strictEqual(entryLabel(undefined, 2000), '2000'));
console.log(pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
