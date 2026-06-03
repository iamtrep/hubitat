<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# FGLair LAN Mode — investigation notes

Captured 2026-06-02 with Charles Proxy on iOS running the official FGLair app
(`FGLair/3.4.0 (iPhone; iOS 26.5)`) on the same LAN as the indoor unit. Filed
here so a future v0.2.0 session can pick this up cold without re-running the
discovery.

## TL;DR

The FGLair iOS app, while on LAN, **writes setpoint and mode via an
encrypted local channel directly to the unit, never via Ayla cloud**.
Confirmed by capture 4: two deliberate setpoint changes happened during the
capture window, and zero corresponding cloud `datapoints` POSTs were sent.
The Ayla cloud is still used for reads (polling) and bootstrap (it hands the
app the LAN encryption key via `/apiv1/dsns/<DSN>/lan.json`), but is not on
the control path while LAN is active.

This means the entire "Ayla cloud is the integration surface" model that the
current HE integration is built on — and that we spent a debug session
unjamming on 2026-06-02 — is one channel of three the FGLair app uses, and
not the channel that carries primary control. Our v0.1.3 manager correctly
mirrors the FGLair app's cloud *read* pattern (plain GET, no trigger writes)
but our cloud *writes* exercise a code path the app intentionally avoids on
LAN.

## What was captured

| File | Window | Contents |
|---|---|---|
| `forks/iamtrep/tmp/fglair1.chlz` | ~4 min, LAN-only | 31 POST/PUT to unit's `/local_reg.json` |
| `forks/iamtrep/tmp/fglair2.chlz` | ~1 min, mixed | 11 LAN POST/PUT + 27 HTTPS CONNECT tunnels, all tunnelled (Ayla not on Charles SSL Locations) |
| `forks/iamtrep/tmp/fglair3.chlz` | ~2 min, mixed, **SSL-intercepted Ayla** | Plain GETs to `/devices.json`, `/properties.json`, `/lan.json`; subscription POST; LAN heartbeats. No user-driven changes. |
| `forks/iamtrep/tmp/fglair4.chlz` | ~1.5 min, mixed, **SSL-intercepted Ayla, with deliberate setpoint changes** | Two `adjust_temperature` changes by the user (240→245→250). Zero corresponding cloud datapoint POSTs. |

## What's confirmed about LAN mode

The Ayla Local LAN Mode handshake, on the unencrypted app→unit direction:

```
POST  http://<unit-ip>/local_reg.json
      { "local_reg": { "uri": "/local_lan", "notify": 0,
                       "ip": <app-ip>, "port": <app-port> } }

PUT   http://<unit-ip>/local_reg.json
      { "local_reg": { ..., "notify": 1, ... } }   <- "wake me on next change"

PUT   http://<unit-ip>/local_reg.json
      { "local_reg": { ..., "notify": 0, ... } }   <- ack / data received
```

- Response is always `202 Accepted` (empty body) when the unit accepts.
- Heartbeat cadence: roughly every 10 s, with state-driven flips between
  `notify: 0` and `notify: 1`. The `notify: 1` is a long-poll signal; the unit
  pushes property data when it has news, then the app flips back to `notify: 0`.
- The capture's port (`10275`) is dynamically assigned by the app on each
  session; the registered `uri` (`/local_lan`) is constant.

## What's NOT captured

The **unit ↔ app data exchange on the encrypted `/local_lan` channel**, in
either direction. Capture 4 shows the user changed setpoint twice yet no
app→unit POST carrying property data appears anywhere. The only app→unit
traffic is the registration heartbeats on `/local_reg.json` with the `notify`
flag. The actual command payload must therefore be flowing on a path Charles
on the phone cannot see — i.e. the unit pulls the command (initiates the
TCP connection out to `<app-ip>:<port>/local_lan`), which makes the phone the
server, which makes the request invisible to a phone-side proxy.

The likely shape, inferred:

1. User taps setpoint in app.
2. App queues the write internally and flips `notify: 1` on the next heartbeat.
3. The unit's WLAN module connects out to the app's `:<port>/local_lan`
   listener.
4. App responds in-line with the queued command, AES-encrypted with `lanip_key`.
5. Unit applies the command, then pushes the new property state back to the
   app over the same listener.
6. Cloud sees the change on the unit's own independent uplink within seconds.

Open items not yet captured:

1. The wire format on `/local_lan` (encryption scheme details, framing,
   sequence numbers).
2. The encryption key derivation — `lanip_key` is the shared secret, but
   typically an Ayla LAN session derives a per-session key from it via HKDF or
   similar; verify.
3. Whether the app falls back to cloud `datapoints` when LAN registration is
   absent / failed / cellular. (Today's capture is all on-LAN, so we don't
   see the fallback path.)

Without 1 and 2, we cannot implement a LAN-mode driver. Both are recoverable
via the listener-impersonation approach in §"How to fill the missing data".

## Parallel cloud activity — now decoded

Captures 3 and 4 had Ayla hosts added to Charles' SSL Proxying Locations, so
the HTTPS bodies decoded to plaintext HTTP. The picture is now complete on the
cloud side. The FGLair app uses these Ayla endpoints:

| Endpoint | Direction | Purpose |
|---|---|---|
| `POST user-field/users/refresh_token.json` | write | Auth — refresh access token |
| `GET  user-field/users/get_user_profile.json` | read  | User profile (display) |
| `GET  ads-field/apiv1/devices.json` | read  | Device discovery / status |
| `GET  ads-field/apiv1/dsns/<DSN>/properties.json` | read  | Full property snapshot (poll) |
| `GET  ads-field/apiv1/dsns/<DSN>/lan.json` | read  | **LAN bootstrap — returns `lanip_key`** |
| `POST mdss-field/api/v1/subscriptions.json` | write | DSS subscription for server-side property push |
| `POST metric-field/api/v1/app/logs` | write | App telemetry |
| `POST ads-field/apiv1/dsns/<DSN>/properties/<prop>/datapoints.json` | — | **Never observed.** This is the lib's property-write endpoint. The FGLair app on LAN does not use it. |

Read pattern on `/properties.json` is **plain GET — no wake-trigger write
before it.** This matches `ayla-iot-unofficial`'s `device.async_update()` and
matches our manager v0.1.3. Earlier integration versions (v0.1.1 `refresh=1`,
v0.1.2 `get_prop=1`) wrote something before each GET; the FGLair app does not.

### The `lan.json` payload (entry [15] in capture 3, [15] in capture 4)

```
GET  https://ads-field.aylanetworks.com/apiv1/dsns/<DSN>/lan.json
200  { "lanip": { "lanip_key_id": <int>,
                  "lanip_key": "<base64 ~20-byte secret>",
                  "keep_alive": 30, "auto_sync": 1, "status": "enable" } }
```

This is the **shared secret for the encrypted LAN data channel**. Ayla Local
LAN Mode is therefore *cloud-bootstrapped, locally encrypted* — not
cloud-independent. The app must authenticate with Ayla and fetch `lan.json`
before it can register with the unit.

### Bootstrap order (consistent across captures 3 and 4)

1. Cloud auth (`refresh_token.json` or sign-in)
2. Cloud `devices.json` (find the DSN)
3. Cloud `properties.json` (initial snapshot)
4. Cloud `lan.json` (fetch `lanip_key`)
5. LAN `POST /local_reg.json` (register app as callback target on unit)
6. Steady-state: LAN `PUT /local_reg.json` heartbeats + cloud `properties.json`
   polls + DSS push subscription, all in parallel.

## Decisive evidence — writes go LAN, not cloud

Capture 4 has the user deliberately toggling `adjust_temperature` twice while
the phone was on LAN:

```
[14] 23:14:02  poll → adjust_temperature = 240   (24.0°C, starting state)
[27] 23:14:18  poll → adjust_temperature = 245   ← user changed setpoint
[57] 23:15:32  poll → adjust_temperature = 250   ← user changed setpoint again
```

Cloud POSTs to `/properties/adjust_temperature/datapoints.json` in that window:
**zero**. Total Ayla writes in the entire capture: 6, all of them auth /
telemetry / subscription create — none property-bearing.

So on this hardware/account, setpoint writes from the FGLair app on LAN
travel **exclusively over the encrypted LAN channel**. The cloud sees the
value update on the next poll because the unit's own cloud uplink propagates
it independently, not because the app wrote to the cloud.

## v0.2.0 architecture sketch

Confirm specifics after Gap B is filled, but the overall shape is now clear:

1. **Cloud bootstrap** (same as v0.1.x) — auth, fetch `devices.json`,
   `properties.json`, `lan.json` to obtain `lanip_key` per DSN.
2. **Hubitat HTTP endpoint** — register a route on the hub (OAuth or app
   endpoint) that the unit can POST to. The hub's LAN IP + that port becomes
   the callback target sent in `local_reg.json`.
3. **Registration loop** — manager periodically POSTs/PUTs `/local_reg.json`
   to each unit with the hub's IP/port and a `notify: 1` flag. Cadence ~10 s
   per the captures. Re-register on hub reboot or unit reboot.
4. **Inbound `/local_lan` handler** — receives encrypted property updates
   from the unit, decrypts with `lanip_key`, parses, dispatches to child
   driver. Replaces the cloud polling we do now.
5. **Outbound writes** — when the driver wants to set a property, queue the
   command on the manager. On the next unit-initiated `/local_lan` connection
   (or proactively after flipping `notify: 1`), reply with the encrypted
   command. Removes our cloud `datapoint` writes entirely — and therefore
   removes the per-DSN cloud queue jam risk.
6. **Cloud fallback** — keep the v0.1.3 cloud path as the off-LAN fallback
   (the FGLair app likely does the same; capture an off-LAN session to
   verify).

### Why bother (over cloud-only)

- Sub-second updates instead of 60 s poll lag.
- No per-DSN cloud queue jam ever; no risk of locking the FGLair app on cloud.
- Works during Ayla outages.
- No empirical workarounds; mirrors what the official app actually does.

### v0.2.0 open questions

- **Multiple concurrent LAN registrations.** Can the unit accept the FGLair
  app *and* Hubitat as simultaneous LAN listeners, or does the second
  `local_reg.json` evict the first? If eviction, we need a hand-off model.
  Test by running a fake listener while the FGLair app is also on LAN.
- **Hub-side routing for inbound POSTs.** Hubitat's app-endpoint and
  OAuth-endpoint surfaces both accept inbound HTTP; choose the one that
  matches multi-instance/multi-unit dispatch needs.
- **`lanip_key` rotation.** Does Ayla rotate the key, and at what cadence?
  Implement a re-fetch on auth refresh and on any decrypt failure.
- **Authoritative source of truth.** When both LAN push and cloud DSS
  subscription report a property change, which is authoritative? Probably
  LAN; verify the timing.

## How to fill the missing data

Gap A (cloud calls while on LAN) is now closed by captures 3 and 4. Two gaps
remain:

### Gap B — the encrypted `/local_lan` exchange

Lowest-friction option: run a minimal HTTP listener that registers itself with
the unit as a fake app, then logs exactly what the unit pushes. `local_reg.json`
is unauthenticated; the unit will happily push to any registered caller — but
**don't run this concurrent with the FGLair app**, since registering a new
listener may evict the app's session (open question — see §"v0.2.0 open
questions"). To force a write payload to be captured, register, set
`notify: 1`, then change the setpoint via the IR remote so the unit will push
state back.

```python
# rough sketch — not a usable implementation
import http.server, json, requests
UNIT_IP = "192.168.1.110"
MY_IP   = "192.168.1.X"
MY_PORT = 10275
requests.post(f"http://{UNIT_IP}/local_reg.json",
              json={"local_reg":{"uri":"/local_lan","notify":1,
                                 "ip":MY_IP,"port":MY_PORT}})
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length",0)))
        print(self.path, body)
        self.send_response(200); self.end_headers()
http.server.HTTPServer((MY_IP, MY_PORT), H).serve_forever()
```

Run this with the unit warm and toggle a few settings via the IR remote and
the FGLair app to observe the pushes that arrive.

## Status

**Deferred.** Cloud-mode integration is stable as of FGLair driver v0.1.2 +
manager v0.1.3. LAN mode is the long-term-right architecture but a meaningful
lift that's not blocking anything today. Pick up by filling the missing capture
first.

## Related

- `README.md` — current cloud-mode integration overview.
- `TODO.md` — other deferred work (Tier 3 swing/mode-toggles, errorCode/opStatus
  decoding, testing harness).
- Captures: `forks/iamtrep/tmp/fglair1.chlz`, `forks/iamtrep/tmp/fglair2.chlz`.
