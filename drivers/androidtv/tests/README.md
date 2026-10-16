# Android TV mock

A mock Google TV / Android TV that speaks the Android TV Remote protocol v2 (pairing on 6467, remote
session on 6466). It encodes and decodes with the compiled protobuf modules shipped in
[androidtvremote2](https://github.com/tronikos/androidtvremote2), so a driver is checked against the
reference implementation, not against its own encoder.

- Pairing: accepts any client certificate, writes the code it "shows" to `<workdir>/code.txt`, and
  checks the secret with the reference hash. Paired certificates persist in `<workdir>/paired.txt`.
- Remote: refuses unpaired certificates, sends configure / set_active, pings, reports power, volume
  and current app, and reacts to POWER, volume, mute, HOME and app-link messages, as its box profile says.

```sh
python3 -m venv .venv && .venv/bin/pip install androidtvremote2 pyOpenSSL
openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.pem -days 365 -subj "/CN=mocktv"
.venv/bin/python drivers/androidtv/tests/mocktv.py tv.pem tv.key <workdir>
```

Point the driver's TV IP preference at the machine running the mock.

### Box profiles

`MOCKTV_PROFILE=<file.json>` makes the mock act like a given box. A profile uses the field names of the
tester's report, so a report from a new box becomes a profile; `mock-profiles/default.json` fills in
what it leaves out. Running the tester against the mock with that profile should give back the same
report.

| Key | Report field or meaning |
|---|---|
| `vendor`, `model`, `androidVersion`, `remoteService`, `remoteServiceVersion`, `features` | test 1, and the report header |
| `deviceInfo3` | field 3 of the device info in test 1's `rx 1:` trace line |
| `firstMessages` | test 1: message types in the order the box sends them; starts `1,2`, then 20, 40 and 50 |
| `startApp` | the current app reported at session start |
| `homeApp` | test 8 `homeApp` |
| `volumeReported` | test 4: volume keys bring a volume message |
| `volumeDuringChange` | test 3: POWER brings a volume message |
| `volumeRange`, `volumeLevel` | test 4, or test 3 `changeVolumeRange` and `changeVolumeLevel` |
| `playerModel` | test 3 and 4 `playerModel` |
| `linkSchemes` | link schemes the box opens (`null`: all); test 5 and 6 `accepted` |
| `errorCode` | test 6 and 7 `errorCode` for a refused link |
| `closeAfterErrorS` | seconds before the box closes the session after a refused link |
| `pingIntervalS` | seconds between pings |
| `pingsWhileOff` | test 3 `pingGaps`: a box that stops pinging while off shows a long gap |
| `extraMessages` | message types sent, empty, after the session start; any test's `otherMessages` |
| `inputEncodings`, `outputEncodings`, `preferredRole` | test 11 and 12: the code encodings the TV offers when pairing |

`mock-profiles/ebox.json` is the EBOX set-top box.

## Android TV Remote Tester

`AndroidTVRemoteTester.groovy` is an app that runs the driver through a fixed set of tests against a
real box and writes a report to paste in a forum post: each test's observations, whether they match
what the driver expects, and the decoded protocol lines for any test that differs. Install it, select
the Android TV Remote device (and optionally the smart plug feeding the box), and press Run.

Each test checks both what the box sent (the driver's trace lines) and what the driver made of it (the
device's events). A message type the driver doesn't act on shows as `otherMessages` and makes the test
differ. The report carries the decoded lines of the session start in every run, for building a profile. On a device that isn't paired the run is a single Pairing test that records how far
pairing gets; a paired device that doesn't connect gets a Connection result with the driver's error.

- `test-tester-helpers.groovy`: unit tests for its pure helpers, no hub needed
  (`groovy drivers/androidtv/tests/test-tester-helpers.groovy`).
- `test-live-tv.sh [@hub]`: runs it through its endpoints (turn on Script access in the app first) and
  prints `[PASS]` per test, `[FAIL]` where the driver differs and `[WARN]` where only the box does;
  `RUN_SLOW_TESTS=1` runs tests 1–8, `ATV_OPTS="standby=5&cut=true&wrongcode=true"` adds the opt-in ones.
