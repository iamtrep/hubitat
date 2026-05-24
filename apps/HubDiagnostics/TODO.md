# Hub Diagnostics — TODO

## Active: integration-override lean rebuild (decided, not yet built)

**Current shipped state:** v5.55.0 (merged to `main` @ `3785ebd`) has a hardcoded built-in
`INTEGRATION_OVERRIDES` (8 Hubitat-native entries) + a 16-entry community config file
(`integration_overrides.json` → File Manager `hub_diagnostics_integration_overrides.json`).
**This approach is superseded** — see below.

**Why it's superseded:** querying the hub's own `/hub2/appsList` (apps with `menu:"Integrations"`)
showed the built-in/community split was largely redundant work:
- `classifyDevice` already derives **built-in vs community** from `appInfo.user` (the hub's own flag).
- The **name** already comes from `cleanIntegrationName(appType)` ("Ecobee Integration" → "Ecobee").
- So the 8 built-in + 16 community entries mostly just restated what the hub + algorithm + `isNetwork`
  derivation already produce (Kasa/Sonos→lan_direct, Ecobee/Blink→cloud, etc.).
- Several were also miscategorised by guessing (Kasa/Sonos/Govee/LIFX/Wiz/**Ecobee/Bond** are *built-in*
  Hubitat integrations, not community — they were in the community config).

**Decision (user):** *Lean + data-verify installed.* The override map should hold **only
connection-type exceptions** `isNetwork`-derivation can't infer — not a roster of integrations.

**Data-verified on maison-pro** (the open test hub; `/hub2/devicesList`, `dni` + `isNetwork`):

| Key | conn | Basis |
|-----|------|-------|
| `lutron` | `lan_bridge` | confirmed: `isNetwork=true`, `Ra2*`/telnet DNI, behind the Lutron bridge (would mis-derive to `lan_direct`) |
| `philips hue` / `hue bridge` | `lan_bridge` | definitional bridge (Hue Bridge fronts the bulbs); not installed on maison-pro |
| `bond` | `lan_bridge` | definitional bridge (Bond Bridge fronts the devices); not installed on maison-pro |
| `airplay` | `lan_direct` | confirmed: MAC-format DNI, `isNetwork=false` → would mis-derive to `cloud` |

- **Drop** the old `homekit→paired` guess: HomeKit Controller devices are `isNetwork=true` → `lan_direct`
  (derivation already correct).
- Everything else (Kasa/Sonos/Ecobee/Blink/FGLair/Govee/LIFX/Wiz/…) needs **no entry** — rides on
  `isNetwork` + `cleanIntegrationName` + `user` flag.

**Build steps (target v5.56.0):**
1. Replace `INTEGRATION_OVERRIDES` with the 5 conn-only entries above (no `name` overrides — let
   `cleanIntegrationName` handle names).
2. Gut `integration_overrides.json` to a documented template (`_README` + a commented example), since
   it's now the escape hatch for user-discovered exceptions, not a redundant integration list.
3. Update `tests/test_classification.py`: drop the 16 community-config + homekit cases; assert the 5
   bridge/AirPlay exceptions; add derivation cases proving Kasa/Sonos/Ecobee classify correctly with
   **no** entry; keep one config-merge case proving a user can still add an override.
4. Doc `device_fullJson.md`: override map = connection-type exceptions only; built-in/community from
   `user` flag, name from `cleanIntegrationName`, conn from `isNetwork`, config file for user exceptions.
5. Bump `APP_VERSION` → `5.56.0`; deploy + verify on maison-pro; merge.

**Optional pre-step (user-gated — production hubs are off-limits to autonomous access):** data-verify
maison/chalet/andree for LAN integrations maison-pro lacks (Sonos, Kasa, Govee, Chromecast, UniFi,
Yeelight, Shelly, LIFX, Wiz) — any with a MAC/IP DNI but `isNetwork=false` is another `lan_direct`
exception to add. The user runs the fetch via `!` (login + `/hub2/devicesList` per hub); then analyse
the saved `/tmp/dev_*.json` and fold confirmed exceptions into the map.
