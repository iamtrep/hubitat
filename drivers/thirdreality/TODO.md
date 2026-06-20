<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# ThirdReality Presence Sensor R3 (3RPL01084Z) — TODO

Remaining work after the v0.2.9 review/verification pass.

## Color control
- [ ] Confirm the color model. Z2M exposes the light as `xy` + `enhancedHue`, but the
      driver uses legacy Hue/Saturation (cluster 0x0300 attrs 0x0000/0x0001, command 0x06).
      With the light on, set a color and check whether hue/saturation read back.
- [ ] If the device is xy-native, move setColor and readback to CurrentX/CurrentY
      (0x0003/0x0004) and EnhancedCurrentHue.

## Radar config (firmware-dependent)
- [ ] Verify writes and readbacks for detect distance (F002), motion/presence
      sensitivity (F004/F005), hold time (F006), TVOC threshold (F003), and alert
      enable (F007) on firmware that implements them. Older firmware returns 0x86.
- [ ] Confirm the detect-distance unit label — references treat F002 as a level (1-6),
      not metric meters.

## Validation
- [ ] Exercise updated()/Save Preferences end-to-end (covers the non-numeric setting
      guard and the 0x86 write-status logging).

## Light control
- [ ] On/off, level, color, and color-temperature commands — confirm against a device
      with the night light on.
