#!/usr/bin/env node
// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Fails when the Groovy and SPA CODE_VERSION constants drift (DESIGN.md §10 "Lockstep").
// Run: node apps/CodeManager/tests/test-lockstep.js
'use strict';
const fs = require('fs'), path = require('path');
const g = fs.readFileSync(path.join(__dirname, '..', 'CodeManager.groovy'), 'utf8').match(/CODE_VERSION = "([^"]+)"/);
const u = fs.readFileSync(path.join(__dirname, '..', 'code_manager_ui.html'), 'utf8').match(/const CODE_VERSION = "([^"]+)"/);
if (g && u && g[1] === u[1]) { console.log('  ok   CODE_VERSION in lockstep (' + g[1] + ')\n\n1 passed, 0 failed, 0 warnings'); process.exit(0); }
console.log('  FAIL CODE_VERSION drift: groovy=' + (g && g[1]) + ' ui=' + (u && u[1]) + '\n\n0 passed, 1 failed, 0 warnings'); process.exit(1);
