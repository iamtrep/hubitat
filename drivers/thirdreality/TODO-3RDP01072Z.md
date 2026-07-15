<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Third Reality Dual Smart Plug (3RDP01072Z) — TODO

v0.0.1. Remaining before release:

## Hardware validation (blocks mirror publish)
- [x] **Scaling under load.** Verified on a live load — V·I·PF is internally consistent
      with reported power, confirming voltage, current, power, and PF all scale correctly.
      Divisors: V÷10, I÷1000, P÷10, energy÷1000 (Wh→kWh).
- [x] **Power factor under real load.** Real PF passes through above the idle-power clamp.
- [ ] **Report flood reduction.** Re-capture Zigbee traffic and diff the per-device
      0x0B04 frame count against the pre-driver baseline to confirm the reporting profile
      (min 10 s, frequency reporting off, voltage on EP01 only, wide deltas) quieted the
      idle stream.

## Phase 2 — proprietary 0xFF03 features (mfg 0x1407)
- [ ] Per-outlet countdown timers (attr 0x0001 on→off, 0x0002 off→on) as preferences.
- [ ] `resetEnergy` end-to-end: writes attr 0x0000 per endpoint and captures the
      driver-side offset. Verify the offset math against the metering summation.
- [ ] LED brightness readback (genBasic 0xFF01) — confirm the value round-trips.

## After validation
- [ ] Mirror-publish to the other hubs.
- [ ] Bump CODE_VERSION off 0.0.1 once load-validated.
