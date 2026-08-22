#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Concurrency limiter test (DESIGN.md §10 "api() wrapper": at most 3 /api/* calls in flight).
// Run: node apps/CodeManager/tests/test-semaphore.js
'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '..', 'code_manager_ui.html'), 'utf8');
function extractFn(name) {
  const start = src.indexOf('function ' + name + '('); assert(start >= 0, 'function not found: ' + name);
  const open = src.indexOf('{', start); let depth = 0, i = open;
  for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } } }
  return src.slice(start, i);
}
const tmp = path.join(os.tmpdir(), 'cm_sem_' + process.pid + '.js');
fs.writeFileSync(tmp, extractFn('makeLimiter') + '\nmodule.exports = { makeLimiter };');
const { makeLimiter } = require(tmp);
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) {} });
(async () => {
  let pass = 0, fail = 0;
  async function t(n, fn) { try { await fn(); pass++; console.log('  ok   ' + n); } catch (e) { fail++; console.log('  FAIL ' + n + '\n         ' + e.message); } }
  await t('never more than 3 in flight; all 10 complete', async () => {
    const lim = makeLimiter(3); let inflight = 0, peak = 0;
    const job = () => lim.run(() => new Promise(r => { inflight++; peak = Math.max(peak, inflight); setTimeout(() => { inflight--; r(1); }, 10); }));
    const res = await Promise.all(Array.from({ length: 10 }, job));
    assert.strictEqual(res.length, 10); assert.strictEqual(peak, 3);
  });
  await t('a rejected task releases its slot', async () => {
    const lim = makeLimiter(1);
    await lim.run(() => Promise.reject(new Error('x'))).catch(() => {});
    const v = await lim.run(() => Promise.resolve(42)); assert.strictEqual(v, 42);
  });
  console.log(`\n${pass} passed, ${fail} failed, 0 warnings`); process.exit(fail ? 1 : 0);
})();
