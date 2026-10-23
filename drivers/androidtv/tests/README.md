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
python3 -m venv .venv && .venv/bin/pip install androidtvremote2 pyOpenSSL zeroconf
openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.pem -days 365 -subj "/CN=mocktv"
.venv/bin/python drivers/androidtv/tests/mocktv.py tv.pem tv.key <workdir>
```

Point the driver's TV IP preference at the machine running the mock.

### Discovery

With `MOCKTV_MDNS_NAME=<name>` the mock announces itself as a TV does, `_androidtvremote2._tcp` on port
6466 with the TXT record `bt` (`MOCKTV_BT`, default `02:00:00:00:00:01`), so a device with no IP set
finds it. The hub must be on the same LAN as the machine running the mock. Two mocks on two machines,
with different `MOCKTV_BT`, show up as two TVs.

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
| `searchApp` | test 14 `searchApp`: the app the search key opens |
| `textFieldCounter` | on the search app, CENTER opens a text field with this field counter and the keyboard up, and BACK closes the keyboard. An edit applies with counters 0/0 or a field counter up to 10 behind the field's, as on the EBOX; others are dropped (absent: no text field) |
| `volumeReported` | test 4: volume keys bring a volume message |
| `volumeDuringChange` | test 3: POWER brings a volume message |
| `volumeRange`, `volumeLevel` | test 4, or test 3 `changeVolumeRange` and `changeVolumeLevel` |
| `playerModel` | test 3 and 4 `playerModel` |
| `linkSchemes` | link schemes the box opens (`null`: all); test 5 and 6 `accepted` |
| `errorCode` | test 6 and 7 `errorCode` for a refused link |
| `closeAfterErrorS` | seconds before the box closes the session after a refused link |
| `pingIntervalS` | seconds between pings |
| `pingsWhileOff` | test 3 `pingGaps`: a box that stops pinging while off shows a long gap |
| `sessionKept` | test 3 `sessionKept`: false makes POWER put the box to sleep, as a TCL TV did: no off report, the volume message, a TCP reset, then no answer on either port until `kill -USR1 <mock pid>` wakes it. Connects made while it sleeps succeed and then wait, where the real TV let them time out |
| `announcedOff` | test 13: the box keeps its mDNS announcement while off (needs `MOCKTV_MDNS_NAME`) |
| `extraMessages` | message types sent, empty, after the session start; any test's `otherMessages` |
| `inputEncodings`, `outputEncodings`, `preferredRole` | test 11 and 12: the code encodings the TV offers when pairing |
| `source` | where the values come from, and what the profile leaves out; the mock ignores it |

`mock-profiles/ebox.json` is the EBOX set-top box, from tester runs on it. `google-tv-streamer.json`,
`nvidia-shield.json`, `sony-bravia-4k-ae3.json`, `eastlink-frc1.json`, `tcl-smart-tv-pro.json` and `onn-4k-plus.json` come from tester reports in the forum thread. The others come from the debug logs of other
projects' issues, named in each `source`: `chromecast-google-tv`, `freebox-player-pop`, `sony-bravia-4k-vh2`,
`tivo-stream-4k` and `xiaomi-mi-tv-stick`. Those logs give the identity, features, message order, current
app and volume message; pings, link handling, errors and pairing keep `default.json`'s values. A volume
message without a range is `volumeRange: 0`. The mock starts every box switched on.

## Android TV Remote Tester

`AndroidTVRemoteTester.groovy` is an app that runs the driver through a fixed set of tests against a
real box and writes a report to paste in a forum post: each test's observations, whether they match
what the driver expects, and the decoded protocol lines for any test that differs. Install it, select
the Android TV Remote device (and optionally the smart plug feeding the box), and press Run.

Each test checks both what the box sent (the driver's trace lines) and what the driver made of it (the
device's events). A message type the driver doesn't act on shows as `otherMessages` and makes the test
differ. The report carries the decoded lines of the session start in every run, for building a profile. On a device that isn't paired the run is a single Pairing test that records how far
pairing gets; a paired device that doesn't connect gets a Connection result with the driver's error.

Test 13, Discovery, runs **Find TVs** with the box on and again after 30 s off, and reports whether the
box announces itself and whether it still does while off: the driver finds the box and follows it to a
new IP through that announcement. With several TVs on the network it leaves the TV list on the device.

Test 14, Text input, presses SEARCH, sends `sendText "hubitat"` and leaves it on screen 6 s. It reports
the app the search key opened, whether the box reported a focused text field (`fieldReported`: message
20 with field 2; `editReported`: message 21), whether it echoed the text back (`textEchoed`: message 22)
and whether it refused it, with its decoded lines in every run. Without an echo, whether the word showed
on screen only the person watching can say: YouTube's search on the EBOX shows the text without echoing it. On a box whose search key opens a voice prompt (the EBOX)
there is no text field to type into. The mock has a text field only with `textFieldCounter` in its
profile, opened with CENTER, which test 14 doesn't press.

Test 15, Media keys, sends `play`, `pause` and `stop` (keys 126, 127 and 86) and reports the keys the
driver sent (`keysSent`), whether the box refused one, whether the session held, and the message types
the box sent after the first key (`replies`). The protocol has no playback-state message, so the driver
expects no reply; the mock sends none. Play may resume whatever app last played media; the test ends on
stop.

- `test-tester-helpers.groovy`: unit tests for its pure helpers, no hub needed
  (`groovy drivers/androidtv/tests/test-tester-helpers.groovy`).
- `test-driver-helpers.groovy`: the same for the driver's TV-matching helpers.
- `test-live-tv.sh [@hub]`: runs it through its endpoints (turn on Script access in the app first) and
  prints `[PASS]` per test, `[FAIL]` where the driver differs and `[WARN]` where only the box does;
  `RUN_SLOW_TESTS=1` runs tests 1–8 and 13–15, `ATV_OPTS="standby=5&cut=true&wrongcode=true"` adds the opt-in ones,
  and `ATV_TESTS=14` runs only the listed tests.
