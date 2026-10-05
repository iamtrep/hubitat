<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# VisiblAir Standalone Driver (deprecated)

**Deprecated and no longer maintained.** Use the [VisiblAir integration](../../integrations/visiblair/) instead: its manager app discovers every sensor on your account, picks a model-specific driver for each, and reports a `healthStatus` attribute when the VisiblAir cloud or a sensor stops reporting.

Standalone single-device Hubitat driver for a [VisiblAir](https://visiblair.com/) indoor air quality sensor.

## Driver

- `visiblair.groovy` — **VisiblAir Sensor**: configure with your VisiblAir user ID, access token and sensor UUID; the driver polls the VisiblAir cloud API

## License

MIT — see the file header for the full license text.

See the parent [drivers/README.md](../README.md) for the full driver index.
