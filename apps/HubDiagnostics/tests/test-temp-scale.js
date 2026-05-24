#!/usr/bin/env node
/*
 * Unit test for Hub Diagnostics temperature-scale handling (SPA side).
 *
 * Design: the reading AND the warn/crit thresholds are ALWAYS Celsius internally
 * (TH.warnTempC/critTempC). Comparisons are done in Celsius so changing the hub's temperature
 * scale never reinterprets a stored threshold. getTemperatureScale() is used only to (a) format
 * values for display and (b) convert the config inputs the user enters in their scale to Celsius.
 *
 * This test EXTRACTS the real helper functions from hub_diagnostics_ui.html by name and runs
 * them in a controlled `TH` context, so it stays bound to the shipped code, not a copy.
 *
 * Run:  node apps/HubDiagnostics/tests/test-temp-scale.js
 * Exits non-zero on any failure.
 */
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const HTML = path.join(__dirname, '..', 'hub_diagnostics_ui.html');
const src = fs.readFileSync(HTML, 'utf8');

// Grab a single-line `function NAME(...) {...}` from the source by name.
function fnLine(name) {
  const i = src.indexOf('function ' + name + '(');
  assert(i >= 0, 'helper not found in HTML: ' + name);
  const nl = src.indexOf('\n', i);
  return src.slice(i, nl < 0 ? undefined : nl);
}

const HELPERS = ['tScale', 'tSym', 'c2u', 'u2c', 'c2uD', 'cClamp', 'ftemp', 'ftempD', 'ftempBoth', 'sev'];
const harness =
  'let TH = {};\n' +
  HELPERS.map(fnLine).join('\n') + '\n' +
  'module.exports = { setTH: v => { TH = v; }, ' + HELPERS.join(', ') + ' };';

const tmp = path.join(os.tmpdir(), 'hd_temp_helpers_' + process.pid + '.js');
fs.writeFileSync(tmp, harness);
const H = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });

// ---- tiny test runner ----
let pass = 0, fail = 0;
function t(name, fn) {
  try { fn(); pass++; console.log('  ok   ' + name); }
  catch (e) { fail++; console.log('  FAIL ' + name + '\n         ' + e.message); }
}
function near(a, b, eps = 0.05) { assert(Math.abs(a - b) <= eps, `${a} != ${b} (±${eps})`); }

console.log('temperature-scale helpers');

// ---- default / absent scale ----
t('tScale defaults to C when scale absent', () => {
  H.setTH({});
  assert.strictEqual(H.tScale(), 'C');
  assert.strictEqual(H.tSym(), '°C');
});

// ---- conversions ----
t('c2u: °C -> hub scale (display)', () => {
  H.setTH({ temperatureScale: 'C' }); near(H.c2u(35.8), 35.8);
  H.setTH({ temperatureScale: 'F' }); near(H.c2u(0), 32); near(H.c2u(100), 212); near(H.c2u(35.8), 96.44);
});
t('u2c: hub scale -> °C (inverse of c2u)', () => {
  H.setTH({ temperatureScale: 'C' }); near(H.u2c(50), 50);
  H.setTH({ temperatureScale: 'F' }); near(H.u2c(122), 50); near(H.u2c(212), 100); near(H.u2c(32), 0);
});
t('c2uD: delta has no +32 offset', () => {
  H.setTH({ temperatureScale: 'F' }); near(H.c2uD(2), 3.6); near(H.c2uD(-1.5), -2.7);
  H.setTH({ temperatureScale: 'C' }); near(H.c2uD(2), 2);
});

// ---- formatting ----
t('ftemp: formats + handles null', () => {
  H.setTH({ temperatureScale: 'F' });
  assert.strictEqual(H.ftemp(35.8), '96.4°F');
  assert.strictEqual(H.ftemp(null), 'N/A');
  H.setTH({ temperatureScale: 'C' });
  assert.strictEqual(H.ftemp(35.8), '35.8°C');
});
t('ftempD: signed, scale-correct, null -> empty', () => {
  H.setTH({ temperatureScale: 'F' });
  assert.strictEqual(H.ftempD(2), '+3.6°F');
  assert.strictEqual(H.ftempD(-1.5), '-2.7°F');
  assert.strictEqual(H.ftempD(null), '');
});
t('ftempBoth: chosen unit leads, other in parens', () => {
  H.setTH({ temperatureScale: 'F' });
  assert.strictEqual(H.ftempBoth(35.8), '96.4°F (35.8°C)');
  H.setTH({ temperatureScale: 'C' });
  assert.strictEqual(H.ftempBoth(35.8), '35.8°C (96.4°F)');
  assert.strictEqual(H.ftempBoth(null), 'N/A');
});

// ---- clamp is in CELSIUS (thresholds are stored in °C regardless of display scale) ----
t('cClamp: clamps a Celsius threshold to 20..100 on either scale', () => {
  H.setTH({ temperatureScale: 'C' });
  assert.strictEqual(H.cClamp(10), 20);
  assert.strictEqual(H.cClamp(150), 100);
  assert.strictEqual(H.cClamp(50), 50);
  H.setTH({ temperatureScale: 'F' });            // still clamps the *Celsius* value
  assert.strictEqual(H.cClamp(10), 20);
  assert.strictEqual(H.cClamp(150), 100);
});

// ---- config round-trip: user enters in hub scale, stored as Celsius ----
t('config round-trip: enter °F -> store °C -> display °F', () => {
  H.setTH({ temperatureScale: 'F' });
  const stored = Math.round(H.cClamp(H.u2c(122)) * 10) / 10;   // save path
  assert.strictEqual(stored, 50);
  assert.strictEqual(Math.round(H.c2u(stored)), 122);          // display path
  H.setTH({ temperatureScale: 'C' });
  const storedC = Math.round(H.cClamp(H.u2c(50)) * 10) / 10;
  assert.strictEqual(storedC, 50);
  assert.strictEqual(Math.round(H.c2u(storedC)), 50);
});

// ---- comparisons are done in Celsius (reading °C vs threshold °C) ----
t('severity fires at the right Celsius reading (thresholds 50/77°C)', () => {
  H.setTH({ temperatureScale: 'C', warnTempC: 50, critTempC: 77 });
  assert.strictEqual(H.sev(49, 50, 77, false), 'var(--ok)');
  assert.strictEqual(H.sev(55, 50, 77, false), 'var(--warn)');
  assert.strictEqual(H.sev(80, 50, 77, false), 'var(--crit)');
});

// ---- THE FIX: severity is scale-independent. Same reading + same °C thresholds -> same
//      severity whether the hub displays °C or °F (a scale change can't trigger false alerts). ----
t('severity is identical regardless of hub display scale', () => {
  const readings = [20, 35.8, 49.9, 50, 50.1, 60, 76.9, 77, 77.1, 85];
  for (const c of readings) {
    H.setTH({ temperatureScale: 'C', warnTempC: 50, critTempC: 77 });
    const asC = H.sev(c, 50, 77, false);
    H.setTH({ temperatureScale: 'F', warnTempC: 50, critTempC: 77 });   // only display scale differs
    const asF = H.sev(c, 50, 77, false);
    assert.strictEqual(asF, asC, `reading ${c}°C: severity differs by display scale (${asF} vs ${asC})`);
  }
});

t('regression: a normal 36°C reading never alerts after a C->F switch', () => {
  // The pre-fix bug treated a stored 50 as 50°F (=10°C) after a scale switch, firing false warns.
  H.setTH({ temperatureScale: 'F', warnTempC: 50, critTempC: 77 });  // thresholds remain °C
  assert.strictEqual(H.sev(36, 50, 77, false), 'var(--ok)');
});

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
