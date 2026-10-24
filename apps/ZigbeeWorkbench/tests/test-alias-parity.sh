#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT
# TEST-OFFLINE: runs Hub Inspector's makeObf under node and the Workbench's makeAliases under groovy
#
# test-alias-parity — the Workbench's report aliases must be Hub Inspector's, name for name.
#
# Both functions are brace-extracted from the shipped files (apps/HubInspector/hub_inspector_ui.html
# and apps/ZigbeeWorkbench/ZigbeeWorkbench.groovy) and given one name list: 200 generated names,
# names whose first adjective-noun pick collides, accented names, emoji, an empty string, and enough
# names (3100) to use up the 3080 pairs so the numeric suffix is exercised. Every alias must match.
#
# Usage: bash apps/ZigbeeWorkbench/tests/test-alias-parity.sh
# Exit: 0 all match, 1 a mismatch, 2 harness error (node or groovy missing, extraction failed).

set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$HERE")"
UI="$(dirname "$APP_DIR")/HubInspector/hub_inspector_ui.html"
SRC="$APP_DIR/ZigbeeWorkbench.groovy"
command -v node >/dev/null || { echo "[harness] node not found"; exit 2; }
command -v groovy >/dev/null || { echo "[harness] groovy not found"; exit 2; }
[ -f "$UI" ] && [ -f "$SRC" ] || { echo "[harness] source not found: $UI or $SRC"; exit 2; }
TMP="$(mktemp -d "${TMPDIR:-/tmp}/alias-parity.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# Names, and the Hub Inspector aliases for them.
node - "$UI" "$TMP" <<'JS' || { echo "[harness] node side failed"; exit 2; }
const fs = require('fs'), path = require('path');
const [ui, tmp] = process.argv.slice(2);
const src = fs.readFileSync(ui, 'utf8');
const start = src.indexOf('function makeObf(');
if (start < 0) { console.log('[harness] signature not found: makeObf'); process.exit(2); }
let depth = 0, i = src.indexOf('{', start);
for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}' && --depth === 0) { i++; break; } }
const makeObf = new Function(src.slice(start, i) + '\nreturn makeObf;')();
const names = [];
for (let n = 0; n < 200; n++) names.push('Device ' + n);
// names whose first pick (hash-derived adjective and noun) is the same as an earlier name's
const pick = s => { let h = 2166136261 >>> 0; for (let k = 0; k < s.length; k++) { h ^= s.charCodeAt(k); h = Math.imul(h, 16777619); } h >>>= 0; return (h % 55) + ':' + ((h >>> 8) % 56); };
const seen = new Map(); let collisions = 0;
for (let n = 0; collisions < 20 && n < 100000; n++) {
  const s = 'Sensor ' + n, p = pick(s);
  if (seen.has(p)) { names.push(seen.get(p), s); collisions++; } else seen.set(p, s);
}
names.push('Évier Cuisine', 'Évier cuisine', 'Lampe à pied', '💡 Lamp', 'Lamp 💡', '🌡️', '', 'a', 'B', 'Kitchen Plug', 'Kitchen Plug');
for (let n = 0; n < 3100; n++) names.push('Bulk ' + n);
const o = makeObf();
o.register(names); o.prime();
const out = {};
for (const s of names) if (s !== '') out[s] = o.alias(s);
fs.writeFileSync(path.join(tmp, 'names.json'), JSON.stringify(names));
fs.writeFileSync(path.join(tmp, 'js.json'), JSON.stringify(out));
JS

# The Workbench aliases for the same list.
cat > "$TMP/run.groovy" <<'GROOVY'
String src = new File(args[0]).text
int b = src.indexOf('// ==== Codec begin ====')
int e = src.indexOf('// ==== Engine end ====')
if (b < 0 || e < b) { println '[harness] signature not found: region markers'; System.exit(2) }
int eb = src.indexOf('// ==== Engine begin ====')
def app = new GroovyShell().evaluate("import groovy.transform.CompileStatic\nimport groovy.transform.Field\n" +
    src.substring(b, src.indexOf('// ==== Codec end ====')) + "\n" + src.substring(eb, e) + "\nthis")
List names = new groovy.json.JsonSlurper().parse(new File(args[1], 'names.json'))
new File(args[1], 'groovy.json').text = groovy.json.JsonOutput.toJson(app.makeAliases(names))
GROOVY
groovy "$TMP/run.groovy" "$SRC" "$TMP" || { echo "[harness] groovy side failed"; exit 2; }

node - "$TMP" <<'JS'
const fs = require('fs'), path = require('path');
const tmp = process.argv[2];
const js = JSON.parse(fs.readFileSync(path.join(tmp, 'js.json'), 'utf8'));
const gr = JSON.parse(fs.readFileSync(path.join(tmp, 'groovy.json'), 'utf8'));
const keys = Object.keys(js);
const bad = keys.filter(k => js[k] !== gr[k]);
const extra = Object.keys(gr).filter(k => !(k in js));
const suffixed = keys.filter(k => /-\d+$/.test(js[k])).length;
if (bad.length || extra.length) {
  console.log(`[FAIL] ${bad.length} of ${keys.length} aliases differ, ${extra.length} extra in groovy`);
  bad.slice(0, 5).forEach(k => console.log(`  ${JSON.stringify(k)}: js ${js[k]} groovy ${gr[k]}`));
  process.exit(1);
}
console.log(`[PASS] ${keys.length} names, identical aliases (${suffixed} with the numeric suffix)`);
JS
