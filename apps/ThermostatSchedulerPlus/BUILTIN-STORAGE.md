<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Built-in Thermostat Scheduler storage

Where the built-in **Thermostat Scheduler 2.0** (version 2.0.3) keeps its configuration, as the importer (`convertBuiltin` in `ThermostatSchedulerPlusProgram.groovy`) reads it. The built-in has no export; the parent reads `/installedapp/configure/json/{id}` (`settings`) and `/installedapp/statusJson/{id}` (`appState`) over the hub's loopback.

Each key below was confirmed by setting the value through the built-in's own page on a test instance and reading it back (firmware 2.5.2.129).

`<P>` is a period name as the user typed it (spaces and accents included), `<g>` a day group number.

| Data | Where |
|---|---|
| Live periods, in order | `appState.timeSort`. Renaming a period moves its keys to the new name; the old `settings` keys stay behind. |
| Live day groups | `appState.dayGroups`: `{"<g>": [7 booleans, Monday first]}`; names in `appState.dayGroupsList`. Keys of groups that no longer exist stay behind. |
| Heating, cooling setpoint | `appState` `heat<P>.<g>`, `cool<P>.<g>` |
| Hub variable instead of a setpoint | `appState` `heat<P>.<g>V`, `cool<P>.<g>V`, holding the variable's name |
| Fan mode | `appState` `fan<P>.<g>`: `Auto`, `Circulate`, `On` |
| Thermostat mode | `appState` `mod<P>.<g>`: `Auto`, `Cool`, `Emergency heat`, `Heat`, `Off` |
| Start kind | `settings` `time<P>.<g>`: `A specific time`, `Sunrise`, `Sunset`, `Variable time` |
| Start values | `settings` `atTime<P>.<g>` (`HH:mm`, or an ISO time), `atSunriseOffset<P>.<g>` / `atSunsetOffset<P>.<g>` (whole minutes, as text), `timeX<P>.<g>` (DateTime variable name) |
| Away values | `appState` `heatAway`, `coolAway`, `fanAway`, `modAway`, `heatAwayV`, `coolAwayV` |
| EcoMode used for Away | `appState.useEcoModeAway` |
| Schedule type | `settings.schedTypeL`: `Time Periods` or `Hub Modes` |
| Hub Modes rows | `appState.modeTable`: `{"<modeId>": {heat, cool, fan, mod, heatV, coolV, used}}`, one entry per mode even when several modes were picked together |
| EcoMode offset, state | `appState.ecoSet`; `appState.inEcoMode` |
| Hold | `appState.manHold` |
| Required separation | `settings.reqOffset` |
| Thermostats | `settings.therm`: `{"<deviceId>": "<name>"}` |
| Restriction switch | `settings.disabled` (one switch): `{"<deviceId>": "<name>"}`; `settings.disabledOff == "true"` means restricted while the switch is off |
| Turn thermostats off when restricted | `settings.turnThermOff` |
| Restriction window | `settings.startingX`, `endingX` (`A specific time`, `Sunrise`, `Sunset`); `starting`, `ending` (times); offsets `startSunriseOffsetnull`, `startSunsetOffsetnull`, `endSunriseOffsetnull`, `endSunsetOffsetnull` (the built-in's own input names). Every instance has `A specific time` with no time by default, which means no window. |
| Restriction days, modes | `settings.days` (day names), `settings.modesR` (mode ids); "only on these days", "only in these modes" |
| Apply on hub start | `settings.setOnStart` |
| Label | `settings.origLabel` |

## Behavior that shapes the import

- A time-period scheduler applies its Away values while the hub mode is named Away. A Hub Modes scheduler ignores them: Away is an ordinary row there, and with no row the thermostats are left alone.
- Choosing *Variable time* with a DateTime hub variable that holds an empty string made the built-in's own page fail on every load.
