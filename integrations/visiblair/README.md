# VisiblAir Integration

Parent/child integration for [VisiblAir](https://visiblair.com/) indoor air quality sensors. The manager app auto-discovers sensors linked to your account and creates child devices using model-specific drivers.

## Components

| Component | Type | Description |
|---|---|---|
| **VisiblAir Manager** | App | Parent app — discovery, bulk polling, firmware commands |
| **Sensor C** | Driver | CO₂, temperature, humidity |
| **Sensor E** | Driver | CO₂, temperature, humidity, VOC, pressure, PM, AQI |
| **Sensor O** | Driver | CO₂, temperature, humidity, VOC, PM, smoke/vape detection |
| **Sensor X** | Driver | Temperature, humidity, pressure, PM (1 / 2.5 / 4 / 10) |
| **Sensor XW** | Driver | Wind speed, direction, compass heading |

## Standalone Alternative

A [standalone single-device driver](../../drivers/visiblair/visiblair.groovy) is also available if you only need to monitor one sensor without the full parent/child setup.

## License

MIT — see individual source files for the full license text.
