<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# ThirdReality Presence Sensor R3 (3RPL01084Z) — TODO

Remaining work after the v0.2.9 review/verification pass.

## Color control
- [x] Color model confirmed (live, fw counter 0x28). Legacy Hue/Saturation works: `moveTo­Hue­And­Saturation`
      (cmd 0x06) advances CurrentHue (0x0000) and it reads back. Device is enhanced-capable
      (EnhancedCurrentHue 0x4000, enhancedColorMode→3 after cmd 0x40) but legacy 8-bit is adequate
      for HE's 0-100 hue, so we stay on legacy/0x0000. No xy/enhanced migration needed.
- [x] Fixed setHue/setSaturation stale-cache clobber (0.2.10): they rebuilt the full HS pair from
      `currentValue()` of the other channel, so a back-to-back setHue+setSaturation clobbered the
      first move via the stale async cache. Now single-channel: setHue=Move to Hue (0x00),
      setSaturation=Move to Saturation (0x03). setColor unchanged (already atomic).

## Radar config (firmware-dependent)
- [x] Verified live (fw counter 0x28): detect distance (F002), motion/presence sensitivity
      (F004/F005), hold time (F006), TVOC threshold (F003), and alert enable (F007) all accept
      writes (status 00, no 0x86) and read back. The "pre-1.00.35" note is unsourced lore — works
      on lower counters. Also: device auto-reports F001-F007; F001 (write-only calibrate) has no
      read handler and logs "Unknown radar cluster attribute F001" (cosmetic).
- [ ] Confirm the detect-distance unit label — Z2M names F002 `presenceSensorDetectDistanceLevel`
      (a level 1-6), but the pref is labelled "meters". Likely mislabelled; reword to "level".

## Validation
- [ ] Exercise updated()/Save Preferences end-to-end (covers the non-numeric setting
      guard and the 0x86 write-status logging).

## Light control
- [ ] On/off, level, color, and color-temperature commands — confirm against a device
      with the night light on.
