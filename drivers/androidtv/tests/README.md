# Android TV mock

A mock Google TV / Android TV that speaks the Android TV Remote protocol v2 (pairing on 6467, remote
session on 6466). It encodes and decodes with the compiled protobuf modules shipped in
[androidtvremote2](https://github.com/tronikos/androidtvremote2), so a driver is checked against the
reference implementation, not against its own encoder.

- Pairing: accepts any client certificate, writes the code it "shows" to `<workdir>/code.txt`, and
  checks the secret with the reference hash. Paired certificates persist in `<workdir>/paired.txt`.
- Remote: refuses unpaired certificates, sends configure / set_active, pings every 5 s, reports
  power, volume and current app, and reacts to POWER, volume, mute and app-link messages.

```sh
python3 -m venv .venv && .venv/bin/pip install androidtvremote2 pyOpenSSL
openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.pem -days 365 -subj "/CN=mocktv"
.venv/bin/python drivers/androidtv/tests/mocktv.py tv.pem tv.key <workdir>
```

Point the driver's TV IP preference at the machine running the mock.

`MOCKTV_QUIRKS=ebox` makes the mock act like the EBOX set-top box: it rejects every app link that is
not `http(s)://` with a `remote_error` and drops the session 10 s later, and it reports volume only
while POWER changes the power state.

## Android TV Remote Tester

`AndroidTVRemoteTester.groovy` is an app that runs the driver through a fixed set of tests against a
real box and writes a report to paste in a forum post: each test's observations, whether they match
what the driver expects, and the decoded protocol lines for any test that differs. Install it, select
the Android TV Remote device (and optionally the smart plug feeding the box), and press Run.

Each test checks both what the box sent (the driver's trace lines) and what the driver made of it (the
device's events). On a device that isn't paired the run is a single Pairing test that records how far
pairing gets; a paired device that doesn't connect gets a Connection result with the driver's error.

- `test-tester-helpers.groovy`: unit tests for its pure helpers, no hub needed
  (`groovy drivers/androidtv/tests/test-tester-helpers.groovy`).
- `test-live-tv.sh [@hub]`: runs it through its endpoints (turn on Script access in the app first) and
  prints `[PASS]` per test, `[FAIL]` where the driver differs and `[WARN]` where only the box does;
  `RUN_SLOW_TESTS=1` runs tests 1–8, `ATV_OPTS="standby=5&cut=true&wrongcode=true"` adds the opt-in ones.
