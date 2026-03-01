# Hub Stress Tests

Hubitat Elevation apps for stress-testing various hub subsystems. These are diagnostic tools — install them temporarily, run the tests, then remove them.

## Apps

### Async HTTP Stress Test (`asyncHttpStressTest.groovy`)

Fires batches of overlapping `asynchttpGet` calls against a configurable URL to test the hub's async HTTP concurrency and callback handling.

| Parameter | Default | Description |
|---|---|---|
| Iterations | 30 | Number of batches to run |
| Calls per batch | 8 | Concurrent async HTTP requests per batch |
| Pacing | 1 ms | Delay between requests within a batch |
| HTTP timeout | 30 s | Per-request timeout |
| Request URL | `https://httpstat.us/200?sleep=60000` | Target endpoint |

Each batch waits for the last callback to arrive before scheduling the next batch via `runIn`.

### Hub Stress Test (`hubStressTests.groovy`)

Combines async HTTP stress testing with File Manager API benchmarking.

- **HTTP stress test** — same overlapping `asynchttpGet` pattern as above, using a hardcoded slow endpoint.
- **File Manager stress test** — benchmarks four file operations in a loop:
  - `getHubFiles()` (built-in API)
  - `downloadHubFile()` (built-in API)
  - `listFiles()` (HTTP JSON endpoint)
  - `readFile()` (HTTP download)

Logs average call duration for each operation.

### UDP Stress Test (`udpStressTest.groovy`)

Sends batches of UDP messages to an echo server and measures round-trip latency.

| Parameter | Default | Description |
|---|---|---|
| Iterations | 30 | Number of batches |
| Calls per batch | 8 | UDP messages per batch |
| Pacing | 1 ms | Delay between messages within a batch |
| Log threshold | 250 ms | Round-trip times above this are logged as warnings |
| Request URL | `192.168.25.100:31337` | Echo server address (`ip:port`) |

Uses `sendHubCommand` with `HubAction` (LAN UDP client). The payload encodes batch/iteration/timestamp; the echo server returns it so the app can compute round-trip time. Runs on a 2-second cron schedule. A **Stop** button in the UI cancels the current run.

### File Manager API Test (`fileManagerTests.groovy`)

Benchmarks the hub's file storage APIs by running a configurable number of iterations of each operation:

1. `getHubFiles()` — built-in API to list files
2. `downloadHubFile()` — built-in API to download a file
3. `listFiles()` — HTTP call to `/hub/fileManager/json`
4. `readFile()` — HTTP download from `/local/{filename}`

Logs average latency per operation. Triggered via a **Start** button in the UI.

## Usage

1. Install the app code on your hub.
2. Add the app from **Apps > Add User App**.
3. Configure parameters and save (or press Start/Stop where available).
4. Check **Logs** for results.
5. Remove the app when testing is complete.

## License

MIT — see individual source files for the full license text.
