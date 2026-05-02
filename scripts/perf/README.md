# Hubitat Hub Diagnostics Scripts

Tools for capturing and analysing Hubitat hub log streams to diagnose slow
Rule Machine automation delays.

---

## Scripts

### `capture_hub_logs.py`

Connects to a hub's `/logsocket` WebSocket and streams log entries to a JSONL
file.  Every 5 minutes (configurable) it also polls `/hub/advanced/freeOSMemoryLast`
and injects a `hubstat` line into the same file, so memory and CPU pressure can
be correlated with automation delays during offline analysis — no live hub
connection needed at analysis time.

On startup the script fetches hub metadata (`/hub2/hubData`) to populate a
`capture-header` line (first line of the output file) and to auto-generate a
descriptive output filename when none is supplied.  The script reconnects
automatically on WebSocket disconnects.

**Dependencies:** `websockets` library (`pip install websockets`).

```
capture_hub_logs.py [-t] [--mem-interval SECS] [--username U] [--password P]
                    HUB [OUTPUT_FILE]
```

| Argument | Description |
|---|---|
| `HUB` | `@hubname` (looked up in `.hubitat.json`) or a bare IP address |
| `OUTPUT_FILE` | Optional. Auto-generated as `<name>-<model>-<fw>-<timestamp>.json` if omitted |
| `-t` | Prepend each websocket line with a wall-clock timestamp |
| `--mem-interval` | Seconds between memory/CPU samples (default 300; set ≤0 to disable) |
| `--username` / `--password` | Hub credentials — only needed for secured hubs addressed by bare IP when no `.hubitat.json` is present |

**Examples:**

```bash
# Capture from a named hub in .hubitat.json (filename auto-generated)
./capture_hub_logs.py @maison-pro

# Capture from a bare IP (credentials looked up in .hubitat.json if present)
./capture_hub_logs.py 192.168.1.86

# Capture from a secured hub with no config file
./capture_hub_logs.py 192.168.78.112 --username admin --password secret

# Custom output file, memory sampling every 10 minutes
./capture_hub_logs.py @maison-pro logs/maison-$(date +%Y%m%d).json --mem-interval 600
```

---

### `analyse_rm_delays.py`

Reads a JSONL capture file and reports Trigger→Action timing statistics for
Rule Machine automations.  Identifies outliers (delays above a threshold),
shows a histogram, a timeline of outliers binned over the capture window, and
detailed log context around each outlier.

If the file contains `hubstat` lines (injected by `capture_hub_logs.py`),
memory and CPU samples are shown alongside each outlier without any live hub
connection.  The `--hub` option can supplement this with the hub's full
historical memory data, though note that `/hub/advanced/freeOSMemoryHistory`
reflects the hub's current rolling window — it is only accurate for recent
captures.

If the file has a `capture-header` line, hub name, model, and firmware are
shown in the report header automatically.

```
analyse_rm_delays.py LOGFILE [--hub NAME|IP] [--username U] [--password P]
                     [--threshold SECS] [--mem-window MINS]
                     [--obfuscate [--obfuscate-map FILE]]
```

| Argument | Description |
|---|---|
| `LOGFILE` | Path to the JSONL capture file |
| `--hub` | Hub name (from `.hubitat.json`) or bare IP — fetches full memory history |
| `--username` / `--password` | Credentials for secured hubs addressed by bare IP |
| `--threshold` | Outlier threshold in seconds (default 0.3) |
| `--mem-window` | Minutes of memory history shown around each outlier (default 30) |
| `--obfuscate` | Replace device/app names with random aliases for safe sharing on community forums |
| `--obfuscate-map` | Write the original→alias mapping to this file (keep private) |

**Examples:**

```bash
# Basic analysis
./analyse_rm_delays.py maison-pro-c-8-pro-2.5.0.118-20260502-090000.json

# Lower threshold, wider memory window
./analyse_rm_delays.py capture.json --threshold 0.1 --mem-window 60

# Supplement in-band stats with full memory history from the hub
./analyse_rm_delays.py capture.json --hub @maison-pro

# Produce a shareable report (names obfuscated)
./analyse_rm_delays.py capture.json --obfuscate --obfuscate-map mapping.json
```

---

## Hub configuration

Both scripts resolve hub credentials from `.hubitat.json`, searched from the
working directory upward.  Example structure:

```json
{
  "default_hub": "maison-pro",
  "hubs": {
    "maison-pro": { "hub_ip": "192.168.1.86" },
    "andree":     { "hub_ip": "192.168.78.112", "username": "u", "password": "p" }
  }
}
```

When a bare IP is supplied on the command line, the scripts look up matching
credentials in `.hubitat.json` automatically, so `--username`/`--password`
flags are only needed when no config file is present.

---

## Output file format

The JSONL files contain one JSON object per line:

| `type` | Source | Description |
|---|---|---|
| `capture-header` | `capture_hub_logs.py` | First line — hub name, model, firmware, IP, capture start time |
| `dev` | logsocket | Device state change log entry |
| `app` | logsocket | App (Rule Machine) execution log entry |
| `sys` | logsocket | Hub system message |
| `hubstat` | `capture_hub_logs.py` | Periodic memory/CPU sample from `/hub/advanced/freeOSMemoryLast` |

---

## Also in this directory

- `ws_to_file.sh` — Simple generic bash script to stream any WebSocket to a file.
  Useful for capturing endpoints other than `/logsocket`.
