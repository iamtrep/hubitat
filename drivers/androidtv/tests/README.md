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

### State over HTTP

A plain-HTTP port (`MOCKTV_HTTP_PORT`, default 6480) serves `GET /mock/state`, the mock's view of what the driver did:

| Field | Meaning |
|---|---|
| `power` | `on` or `off`, as the last POWER key left it (a new session starts `on`) |
| `asleep` | true while a `sessionKept: false` box is asleep |
| `app` | the package last reported as the current app (null before one) |
| `lastText` | the last text taken from a text-edit message (message 21), whatever the profile does with it |
| `keys` | `[atMs, code]` for the last 50 key codes received, oldest first |
| `launches` | `[atMs, link]` for the last 20 app links received |
| `pairingCode` | the code last shown on pairing (the one written to `code.txt`); null before any |
| `field` | `{open, value}`: whether a text field has focus in the app in front, and what it holds (text from a message 21 adds to it; a new app in front or HOME closes it) |
| `playing` | `playing`, `paused`, `stopped` or null: set by `/mock/play`; while set, key 127 pauses, 126 plays, 86 stops; HOME clears it |

Two more endpoints act on the live session, as a person at the TV would (`{"ok": false}` without a session):

- `GET /mock/field?open=1|0` opens or closes a focused text field in the app in front (a search box). The mock
  sends the field status and keyboard messages a box sends, so the driver's field counters follow, whatever
  the profile's `textFieldCounter` (1 when it has none).
- `GET /mock/play?app=<package>` puts that app in front (YouTube when omitted) with a video playing.

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
| `searchApp` | test 10 `searchApp`: the app the search key opens |
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
| `announcedOff` | test 9: the box keeps its mDNS announcement while off (needs `MOCKTV_MDNS_NAME`) |
| `extraMessages` | message types sent, empty, after the session start; any test's `otherMessages` |
| `inputEncodings`, `outputEncodings`, `preferredRole` | test 14 and 15: the code encodings the TV offers when pairing |
| `source` | where the values come from, and what the profile leaves out; the mock ignores it |

`mock-profiles/ebox.json` is the EBOX set-top box, from tester runs on it. `google-tv-streamer.json`,
`nvidia-shield.json`, `sony-bravia-4k-ae3.json`, `eastlink-frc1.json`, `tcl-smart-tv-pro.json`, `tcl-smart-tv-pro-sleep.json` (the same TV in a lower energy mode) and `onn-4k-plus.json` come from tester reports in the forum thread. The others come from the debug logs of other
projects' issues, named in each `source`: `chromecast-google-tv`, `freebox-player-pop`, `sony-bravia-4k-vh2`,
`tivo-stream-4k` and `xiaomi-mi-tv-stick`. Those logs give the identity, features, message order, current
app and volume message; pings, link handling, errors and pairing keep `default.json`'s values. A volume
message without a range is `volumeRange: 0`. The mock starts every box switched on.

## Android TV Remote Tester

`AndroidTVRemoteTester.groovy` is an app that runs the driver through a fixed set of tests against a
real box and writes a report to paste in a forum post: each test's observations, whether they match
what the driver expects, and the decoded protocol lines for any test that differs. Install it, select
the Android TV Remote device (and optionally the smart plug feeding the box), save, and use **Open
tester** on its page. The tester page (`androidtv-tester-ui.html`, served from File Manager and fetched
from GitHub when missing or for another version) starts the run, shows each step with a countdown, asks
a few questions about what the TV shows, and has a remote pad with a log of the driver's trace for
every press. The app turns OAuth on by itself.

Each test checks both what the box sent (the driver's trace lines) and what the driver made of it (the
device's events). A message type the driver doesn't act on shows as `otherMessages` and makes the test
differ. The report carries the decoded lines of the session start in every run, for building a profile.
On a device that isn't paired the run is a single Pairing test that records how far pairing gets; a
paired device that doesn't connect gets a Connection result with the driver's error.

The tests, in run order:

| # | Test | What it does |
|---|---|---|
| 1 | Session start | disconnects and reconnects; what the box sends first |
| 2 | Pings | time to the first ping |
| 3 | Power off/on | `off`, a hold of at least 12 s, `on`; ping gaps over 12 s (`pingGaps`) |
| 4 | Volume | volume keys and mute |
| 5 | Launch by URL | HOME first, then `https://www.youtube.com` |
| 6 | Launch by package | a package name, which boxes now refuse |
| 7 | Recovery after a box error | a link the box refuses, then the session |
| 8 | HOME | YouTube, then HOME |
| 9 | Discovery | **Find TVs** with the box on and again after 30 s off |
| 10 | Text input | attended: a text field the person opens, then `sendText "hubitat"`; otherwise HOME, SEARCH, `sendText "hubitat"`, 6 s on screen |
| 11 | Media keys | attended: a video the person starts, then `pause`, `play`, `stop`; otherwise `play`, `pause`, `stop` |
| 12 | Long standby | optional: off for the minutes asked |
| 13 | Power cut | optional: needs the plug; off for 30 s, then waits for the box |
| 14 | Wrong pairing code | optional: a pairing dialog on the TV |
| 15 | Pairing | alone, when the device isn't paired |

Test 9 reports whether the box announces itself and whether it still does while off: the driver finds
the box and follows it to a new IP through that announcement. With several TVs on the network it leaves
the TV list on the device.

Test 10 reports the app the search key opened, whether the box reported a focused text field
(`fieldReported`: message 20 with field 2; `editReported`: message 21), whether it echoed the text back
(`textEchoed`: message 22) and whether it refused it, with its decoded lines in every run. When no text
field was reported, the note names the app the search key opened (a voice prompt on some Google TV
boxes). Without an echo, whether the word showed on screen only the person watching can say. The mock
has a text field only with `textFieldCounter` in its profile, opened with CENTER, which test 10 doesn't
press.

Test 10 attended: the first step asks the person to open a text field (YouTube's search box works). With
"A text field is open" the test sends the text with no HOME and no SEARCH, asks whether it appeared, then
BACK and HOME. With "I can't open one", no answer, or nobody at the TV, it runs the steps above. The
"say in your post" note is dropped once the person answered whether the text appeared.

Test 11 sends `play`, `pause` and `stop` (keys 126, 127 and 86) and reports the keys the driver sent
(`keysSent`), whether the box refused one, whether the session held, and the message types the box sent
after the first key (`replies`). The protocol has no playback-state message, so the driver expects no
reply; the mock sends none. Play may resume whatever app last played media; the test ends on stop.
`keysSent` is compared as a set of codes, since the order depends on the path.

Test 11 attended: the first step asks the person to start a video. With "A video is playing" the keys go
pause, play, stop, each followed by a question (did it pause, play again, stop). Otherwise play, pause,
stop, as above. The "say in your post" note is dropped once any of the four questions was answered.

### Questions and setup

With "I'm at the TV" ticked on the page (`attended=true` on `/run`), the tester asks up to eleven questions, each
by clicking a button. An unanswered question is `no answer` in the report and never counts as a
difference. Unticked, nothing is asked, `/run` defaults to this, and the tests run without waiting for
anyone.

| Question | Test | When | Report field |
|---|---|---|---|
| The screen is dark / it stayed on | 3 | while the box is off; the hold lasts until the answer or 20 s | `screenDark` |
| Picture is back / it didn't come back | 3 | right after `on`, 60 s | `pictureMs` (ms from `on`, or `never`) |
| YouTube opened / it didn't | 5 | only when no message 20 named YouTube, 30 s | `appSeen` |
| Home screen is showing / it isn't | 8 | only when the app didn't change, 30 s | `homeSeen` |
| A text field is open / I can't open one | 10 | first step, 90 s | `fieldOpened` |
| hubitat appeared / it didn't | 10 | after the text, only when a field was open, 30 s | `textSeen` |
| A video is playing / I can't start one | 11 | first step, 90 s | `playbackStarted` |
| It paused / it didn't; it played again / it didn't; it stopped / it didn't | 11 | after each key, only when a video was playing, 20 s each | `pauseSeen`, `playSeen`, `stopSeen` |

Report values are `yes`, `no`, `no answer` or `not asked`; a follow-up question that wasn't reached reads
`not asked`.

The page also asks whether the device is a TV with Android TV built in or a box plugged into a TV
(`deviceKind=tv|box|unknown`); the report header shows it as `device: ... · at the TV: yes|no`. Power and
volume on a box go over HDMI-CEC.

### Script access

`/run`, `/status`, `/abort`, `/answer`, `/report`, `/key` and `/padlog/clear` are `GET`s on the app's
endpoint with `access_token` (the page's link carries it). `/run` takes `tests` (comma-separated
numbers above), `attended`, `deviceKind`, `standby` (minutes), `cut=true` and `wrongcode=true`, and
answers `{ok: true, started: true}`, or `{ok: false, error}` with 409 while a run is in progress.
`/report` answers `{status, reason, report, results, profile}`; `profile` holds the report's fields
under the names a mock profile uses (a box's report becomes a profile). `/key` runs one pad command
through the driver (`name` is `on`, `off`, `volumeUp`, `volumeDown`, `play`, `pause`, `stop`, `connect`,
`disconnect`, or `sendKey` with `key`, `sendText` with `text`, `launchApp` with `link`) and is refused
during a run.

- `test-tester-helpers.groovy`: unit tests for its pure helpers, no hub needed
  (`groovy drivers/androidtv/tests/test-tester-helpers.groovy`).
- `test-driver-helpers.groovy`: the same for the driver's TV-matching helpers.
- `test-live-tv.sh [@hub]`: runs it through its endpoints (open the app once so it makes its access
  token) and prints `[PASS]` per test, `[FAIL]` where the driver differs and `[WARN]` where only the box
  does; `RUN_SLOW_TESTS=1` runs tests 1–11, `ATV_OPTS="standby=5&cut=true&wrongcode=true"` adds the
  opt-in ones (12 to 14), and `ATV_TESTS=10` runs only the listed tests.
