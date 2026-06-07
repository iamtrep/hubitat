<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# VisiblAir Standalone Driver

Standalone single-device Hubitat driver for a [VisiblAir](https://visiblair.com/) indoor air quality sensor. Useful when you only need to monitor one sensor and don't want the parent/child setup of the full integration.

## Driver

- `visiblair.groovy` — **VisiblAir Sensor**: configure with the sensor's IP address; the driver polls the local HTTP API directly

## When to use this vs. the full integration

- Use this standalone driver if you have **one** VisiblAir sensor and want the lightest possible setup
- Use the parent/child **[VisiblAir integration](../../integrations/visiblair/)** if you have multiple sensors, want auto-discovery, or want model-specific drivers (Sensor C / E / O / X / XW)

## License

MIT — see the file header for the full license text.

See the parent [drivers/README.md](../README.md) for the full driver index.
