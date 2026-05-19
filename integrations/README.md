<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Integrations

Parent-app + child-driver integrations for vendor cloud APIs. Each subfolder is a self-contained integration: a manager app that handles authentication, discovery, and polling, plus the child drivers that surface the discovered devices on Hubitat.

## Blink

Hubitat integration for [Blink](https://blinkforhome.com/) home security cameras — OAuth 2.0 + PKCE + 2FA authentication, network arm/disarm, per-camera motion detection and motion-enable, battery and temperature, real recorded-clip URLs, and account-wide notification settings. See [`Blink/`](Blink/README.md) for details.

## FGLair

Hubitat integration for Fujitsu mini-split heat pumps over the [FGLair](https://www.fujitsu-general.com/global/products/fglair/) cloud (Ayla Networks IoT platform) — email/password sign-in, auto token refresh, periodic property polling, and one child device per indoor unit. The child driver implements the standard `Thermostat` and `MotionSensor` capabilities plus a parallel custom surface for Fujitsu-specific modes (`dry`, `fan_only`) and fan speeds (`quiet` / `low` / `medium` / `high`). See [`FGLair/`](FGLair/README.md) for details.

## VisiblAir

Hubitat integration for [VisiblAir](https://visiblair.com/) indoor air quality sensors — auto-discovers sensors and creates model-specific child devices. See [`visiblair/`](visiblair/README.md) for details.

## License

MIT — see individual source files for the full license text.
