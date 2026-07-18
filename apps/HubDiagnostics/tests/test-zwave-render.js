#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 (pure-JS) unit test for the Z-Wave "Route Changes" cell renderer.
//
// The Groovy layer emits routeChanges as null when the hub reports no route-change
// count — Z-Wave Long Range nodes have no mesh routing, and nodes with no accumulated
// traffic report none yet. routeChangesCell() must render that null as an em-dash, not
// as a number (the old code leaked a "-1" sentinel here). This EXTRACTS routeChangesCell
// from hub_diagnostics_ui.html by name (brace-matched) so the test stays bound to the
// shipped function rather than a copy.
//
// Run: node apps/HubDiagnostics/tests/test-zwave-render.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_diagnostics_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

// Extract a `function NAME(...){ ... }` from the HTML by brace matching.
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

const harness = extractFn('routeChangesCell') + '\nmodule.exports = { routeChangesCell };';
const tmp = path.join(os.tmpdir(), 'hd_zwrender_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const { routeChangesCell } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

const DASH = '—'; // em-dash used as the table's no-data marker
let pass = 0, fail = 0;
function t(name, got, want) {
  if (got === want) { pass++; console.log('  [PASS] ' + name); }
  else { fail++; console.log('  [FAIL] ' + name + '  got=' + JSON.stringify(got) + ' want=' + JSON.stringify(want)); }
}

console.log('routeChangesCell (Z-Wave Route Changes cell renderer)');

t('mesh node keeps its count',            routeChangesCell({ routeChanges: 3 }), '3');
t('legit zero renders as "0" not dash',   routeChangesCell({ routeChanges: 0 }), '0');
t('null -> em-dash (LR / no count)',       routeChangesCell({ routeChanges: null }), DASH);
t('undefined field -> em-dash',            routeChangesCell({ routeChanges: undefined }), DASH);
t('absent field -> em-dash',               routeChangesCell({}), DASH);

console.log('\n=== ' + pass + '/' + (pass + fail) + ' passed ===');
process.exit(fail === 0 ? 0 : 1);
