# Hub Diagnostics — TODO

## Shipped: integration-override lean rebuild (v5.56.0)

`INTEGRATION_OVERRIDES` is now a map of **connection-type exceptions only** — the handful the
`isNetwork`-derivation can't infer — not a roster of integrations:

| Key(s) | conn | Basis |
|--------|------|-------|
| `philips hue` / `hue bridge` | `lan_bridge` | bridge fronts the bulbs; children report `isNetwork=true` ⇒ would mis-derive `lan_direct` |
| `lutron` | `lan_bridge` | behind the Lutron bridge (`isNetwork=true`) |
| `bond` | `lan_bridge` | Bond Bridge fronts the devices (`isNetwork=true`) |
| `airplay` | `lan_direct` | MAC-format DNI with `isNetwork=false` ⇒ would mis-derive `cloud` |

Everything else rides on the derivation: name from `cleanIntegrationName`, built-in vs community from
`appInfo.user`, conn from `isNetwork`. No name overrides. The old `homekit→paired` guess was dropped
(HomeKit Controller is `isNetwork=true` ⇒ `lan_direct`, already correct). `integration_overrides.json`
is now a documented template (`_README` + one commented `_example`) — the escape hatch for
user-discovered exceptions; the loader ignores any `_`-prefixed key. `tests/test_classification.py`
asserts the 5 exceptions + derivation-with-no-entry proofs (47 cases). Also added a Versions-page
warning when hub firmware is below `MIN_FW_SUPPORTED` (2.5.0).

## Open (optional, user-gated — production hubs are off-limits to autonomous access)

Data-verify maison/chalet/andree for LAN integrations maison-pro lacks (Sonos, Kasa, Govee,
Chromecast, UniFi, Yeelight, Shelly, LIFX, Wiz) — any with a MAC/IP DNI but `isNetwork=false` is
another `lan_direct` exception to fold into the override config. The user runs the fetch via `!`
(login + `/hub2/devicesList` per hub); then analyse the saved `/tmp/dev_*.json` and add confirmed
exceptions. So far only maison-pro (the open test hub) has been verified.
