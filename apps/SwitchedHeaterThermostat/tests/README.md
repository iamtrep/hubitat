<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Switched Heater Thermostat tests

`test_core.groovy` (unit tests, run off the hub under Groovy 2.4.21), `test-sht.sh` and `test-sht-faults.sh` (behavior tests on a hub, generated from `spec-switched-heater-thermostat.yaml` and `spec-switched-heater-thermostat-faults.yaml`: `RUN_SLOW_TESTS=1 bash tests/test-sht.sh [@hub]`, then the same for `test-sht-faults.sh`) and `TEST_PLAN.md` (coverage and known gaps).
