# Scripts

Utility scripts for Hubitat hub administration, log analysis, and external integrations.

## Scripts

| Script | Description |
|---|---|
| [hubitat-app-backup.sh](hubitat-app-backup.sh) | Back up installed app configurations (settings, state, subscriptions, jobs) |
| [hydroquebec_peakevent.js](hydroquebec_peakevent.js) | Google Apps Script — detect Hydro-Québec peak event emails and trigger a Hubitat switch |
| [ws_to_file.sh](ws_to_file.sh) | Stream a WebSocket to a file with optional timestamps and auto-reconnect |
| [zigbee-log-analyser.py](zigbee-log-analyser.py) | Analyze Zigbee log captures — cluster activity, attribute reports, device patterns |
| [zigbee-ota-analyser.py](zigbee-ota-analyser.py) | Analyze Zigbee OTA update traffic from log captures |

## perf/

Tools for capturing and analysing hub log streams to diagnose slow Rule Machine automation delays.

| Script | Description |
|---|---|
| [perf/capture_hub_logs.py](perf/capture_hub_logs.py) | Stream `/logsocket` to a JSONL file with periodic memory/CPU samples injected for offline correlation |
| [perf/analyse_rm_delays.py](perf/analyse_rm_delays.py) | Read a JSONL capture and report Trigger→Action timing stats, outlier histogram, and memory context |

See [perf/README.md](perf/README.md) for full usage, argument reference, and output file format.

## License

MIT — see individual source files for the full license text.
