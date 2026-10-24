// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 3 unit test for the interface text of hilo-history.html and hilo-winter-credit.html: in
// each page the English and French catalogs hold the same keys with the same {placeholders},
// and every key the script names with t('…') is in the catalog.
//
// Run: node integrations/Hilo/tests/test-i18n.js

const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function check(name, cond, detail = '') {
    if (cond) { pass++; console.log(`  [PASS] ${name}`); } else { fail++; console.log(`  [FAIL] ${name}${detail ? ': ' + detail : ''}`); }
}
const placeholders = s => new Set([...s.matchAll(/\{(\w+)\}/g)].map(m => m[1]));
const same = (a, b) => a.size === b.size && [...a].every(x => b.has(x));

// The object literal after `const I18N = `, by brace matching.
function catalog(src) {
    const start = src.indexOf('const I18N = {');
    if (start < 0) return null;
    const open = src.indexOf('{', start);
    let depth = 0;
    for (let i = open; i < src.length; i++) {
        if (src[i] === '{') depth++;
        else if (src[i] === '}' && --depth === 0) return { text: src.slice(open, i + 1), start, end: i + 1 };
    }
    return null;
}

// minKeys: how many keys the script names at least, so a broken pattern can't pass.
for (const [file, minKeys] of [['hilo-history.html', 20], ['hilo-winter-credit.html', 20]]) {
    console.log(file);
    const src = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
    const c = catalog(src);
    check('has a catalog', !!c);
    if (!c) continue;
    const cat = new Function(`return (${c.text});`)();
    check('English and French are the two languages', same(new Set(Object.keys(cat)), new Set(['en', 'fr'])), Object.keys(cat).join(','));
    const en = new Set(Object.keys(cat.en)), fr = new Set(Object.keys(cat.fr));
    const onlyEn = [...en].filter(k => !fr.has(k)), onlyFr = [...fr].filter(k => !en.has(k));
    check('French has every English key', !onlyEn.length, onlyEn.join(', '));
    check('English has every French key', !onlyFr.length, onlyFr.join(', '));
    const mismatched = [...en].filter(k => fr.has(k) && !same(placeholders(cat.en[k]), placeholders(cat.fr[k])));
    check('each key has the same placeholders in both', !mismatched.length, mismatched.join(', '));
    check('no text is empty', Object.values(cat).every(m => Object.values(m).every(v => typeof v === 'string' && v.trim())));

    const code = src.slice(0, c.start) + src.slice(c.end);
    const used = new Set([...code.matchAll(/\bt\(\s*['"`]([a-z]+\.[A-Za-z]+)['"`]/g)].map(m => m[1]));
    check(`the script names at least ${minKeys} keys`, used.size >= minKeys, String(used.size));
    const missing = [...used].filter(k => !en.has(k));
    check('every key the script names is in the catalog', !missing.length, missing.join(', '));
}

console.log(`\n=== ${pass}/${pass + fail} passed ===`);
process.exit(fail ? 1 : 0);
