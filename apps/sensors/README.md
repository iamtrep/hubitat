# Sensor Aggregator & Sensor Filter

Hubitat Elevation apps for combining multiple sensor readings into a single virtual device.

## Apps

### Sensor Aggregator (parent/child)

Aggregates values from multiple sensors of the same type and writes the result to a single virtual output device.

**Parent:** `SensorAggregator.groovy` — container app that manages child instances.

**Continuous Child:** `SensorAggregatorChild.groovy` — for numeric sensor values.

| Capability | Attribute | Output Driver |
|---|---|---|
| Carbon Dioxide Measurement | `carbonDioxide` (ppm) | Virtual Omni Sensor |
| Illuminance Measurement | `illuminance` (lux) | Virtual Illuminance Sensor |
| Relative Humidity Measurement | `humidity` (%) | Virtual Humidity Sensor |
| Temperature Measurement | `temperature` (°F/°C) | Virtual Temperature Sensor |

Aggregation methods: **average**, **median**, **min**, **max**

Also computes and logs standard deviation across included sensors.

**Discrete Child:** `SensorAggregatorDiscreteChild.groovy` — for binary/enum sensor values.

| Capability | Attribute | Values | Output Driver |
|---|---|---|---|
| Acceleration Sensor | `acceleration` | inactive / active | Virtual Acceleration Sensor |
| Contact Sensor | `contact` | closed / open | Virtual Contact Sensor |
| Motion Sensor | `motion` | inactive / active | Virtual Motion Sensor |
| Presence Sensor | `presence` | not present / present | Virtual Presence Sensor |
| Shock Sensor | `shock` | clear / detected | Virtual Shock Sensor |
| Water Sensor | `water` | dry / wet | Virtual Moisture Sensor |

Aggregation methods:
- **any** — target value if *any* sensor matches
- **all** — target value only if *every* sensor matches
- **majority** — target value if more than 50% match
- **threshold** — target value if a configurable percentage match

#### Common features

- **Inactivity exclusion** — sensors with no activity for a configurable number of minutes are excluded from the calculation.
- **Notifications** — optional alerts (via a notification device) when any sensor is excluded or when all sensors are excluded.
- **Auto child device** — can automatically create a virtual child device as the output if none is selected.
- **Force update** button in the app UI to recalculate on demand.
- Configurable log level (warn / info / debug / trace).

The discrete child also includes a built-in **test framework** (smoke tests and a full 11-test suite) that creates temporary virtual devices, runs aggregation assertions, and cleans up automatically.

**Motion Fusion Child:** `MotionFusionChild.groovy` — combines PIR and mmWave motion inputs from a dual-sensor device (e.g. Aqara FP300) into a single motion output using configurable fusion algorithms.

---

### Sensor Filters (parent/child)

Applies a sliding-window filter to a single sensor's numeric attribute and writes the smoothed value to a virtual device.

**Parent:** `SensorFilterManager.groovy` — container app that manages filter instances.

**Child:** `SensorFilterChild.groovy` — the filter itself.

- **Filter types:** moving average, median
- **Window size:** 3–25 samples (odd numbers)
- **Window decay:** oldest sample is automatically dropped after a configurable number of minutes of inactivity, and the filtered value is recalculated.
- Works with any device attribute (auto-detected from the selected source device).

## Installation

1. Install the **parent** app code on your Hubitat hub (Sensor Aggregator and/or Sensor Filters).
2. Install the corresponding **child** app code (Continuous Child, Discrete Child, and/or Sensor Filter Child).
3. Add the parent app from **Apps > Add User App**.
4. Create child instances from within the parent app's UI.

## License

MIT — see individual source files for the full license text.
