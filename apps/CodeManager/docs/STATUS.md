# Code Manager — status

Where the app stands against the release plan in [DESIGN.md](DESIGN.md) §14. Update this file at the end of every release, not during one.

## Now: 0.1.1, read-only inventory

0.1 shipped the whole inventory release: the four bulk passes and per-item detail fetch, package classification over all four origins, the usage index and safe-to-remove list, HPM import in `observe` mode, the bounded sweep with hash and version freshness, broken-source strikes, the label badge and notifications, the SPA over `/api/*`, and the export. Nothing outside the app's own catalog is written. Everything in DESIGN §14's 0.1 line is present except the child-type source scan in `safeToRemove`, which is parked below.

0.1.1 is a correctness pass over what 0.1 reported, driven by reading the shipped UI against a real hub. A bundle installed by an HPM package is now owned by that package and its members are folded into it, instead of the bundle standing as a second package under its own name; bundle components bind by `sourceUrl` and File Manager files by name, both re-checked every pass. The `required` flag HPM already stored is now honoured, so a component a package declares but never installed reads `not-installed` rather than `missing` — HPM manifests declare every component a repository ships and mark most optional, so the old reading called most of a healthy hub broken. `unmanaged` (no upstream to compare against) is now distinct from `unknown` (an upstream exists, not yet fetched), and no component can read `current` without a comparison having happened. The SPA separates real packages from one-file packages, links File Manager entries to `/local/<name>`, and carries a legend defining every status under each table.

## Verified

Mode 2 API test (`tests/test-code-manager-api.sh`, `RUN_SLOW_TESTS=1` for the sweep) covers every endpoint against `/hub2/*` ground truth, and asserts the model's invariants: one component never belongs to two packages, an installed bundle is bound wherever it is declared, a file component is bound exactly when the file exists, absent components report by their `required` flag, and both status vocabularies stay closed. Mode 4 extraction tests cover the version heuristics (`test-version-compare.groovy`), source normalization and hashing (`test-source-hash.groovy`), and the status rules (`test-status.groovy`). Mode 3 extraction tests cover the SPA's pure helpers — classification, legends, links, removability, escaping, the limiter, and app/UI version lockstep.

## Parked

- **Registry size.** `state.pkgs` sits near 80 KB on a large hub against the 60 KB DESIGN §2.4 first estimated. It fits, but the registry moves to a File Manager document in 0.2 rather than growing further in `state`.
- **Child-type scan.** `safeToRemove` does not yet scan installed sources for `addChildDevice`/`addChildApp` references to a component, so a driver used only as a child type can be listed as removable. The list is advisory and the hub refuses in-use deletes regardless.
- **HPM panel error shape.** A transient failure to read HPM's status renders as a bare mode line rather than an explicit "could not read HPM" state.
- **Housekeeping.** A `cm_pkg_` document whose delete fails is never retried, and the sweep and job caches are never pruned.
- **Mock data.** The workbench mock still carries `usedBy` names, where the live payload carries ids only.

## Next: 0.2, apply for `url` and `local` origins

Preview, diff, snapshot, write, verify, rollback; install from URL; per-package unattended opt-in for these origins, under the write lease and operation journal in DESIGN §6.6. HPM packages stay read-only until 0.3. The parked items above land in 0.2 unless noted.
