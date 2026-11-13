# Mock Android TV (Remote protocol v2) for rehearsing the Hubitat driver.
# Encodes/decodes with androidtvremote2's compiled protobuf modules. Ports: pairing 6467, remote 6466.
# usage: [MOCKTV_PROFILE=<profile.json>] [MOCKTV_MDNS_NAME=<name> [MOCKTV_BT=<mac>]] mocktv.py <cert.pem> <key.pem> <workdir>
# A profile describes a box with the tester report's field names; mock-profiles/default.json fills what it leaves out.
# A plain-HTTP side port (MOCKTV_HTTP_PORT, default 6480) answers GET /mock/state with what the mock last saw:
# {power: "on"|"off", asleep, app, lastText, keys: [[atMs, code], ...] (last 50), launches: [[atMs, link], ...] (last 20),
#  pairingCode (the code last shown on pairing, null before), field: {open, value} (the text field in front),
#  playing: "playing"|"paused"|"stopped"|null}. lastText is the last text taken from a message 21.
# GET /mock/field?open=1|0 gives or takes away focus of a text field in the app in front, as a person opening a search box
# does; GET /mock/play?app=<package> puts that app (default YouTube) in front, playing. Both act on the live session and
# answer {ok: false} without one. While playing is set, keys 127, 126 and 86 make it paused, playing, stopped; HOME clears it.
import hashlib, http.server, json, os, random, signal, socket, struct, sys, threading, time, urllib.parse
from OpenSSL import SSL, crypto
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes
from google.protobuf import text_format
from androidtvremote2.polo_pb2 import OuterMessage, Options
from androidtvremote2.remotemessage_pb2 import RemoteMessage

CERT, KEY, WORK = sys.argv[1], sys.argv[2], sys.argv[3]
PAIR_PORT, REMOTE_PORT = 6467, 6466
paired = set()          # SHA-256 fingerprints of paired client certs
PAIRED_FILE = os.path.join(WORK, "paired.txt")
if os.path.exists(PAIRED_FILE):
    paired.update(l.strip().encode() for l in open(PAIRED_FILE) if l.strip())
lock = threading.Lock()
PROFILE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock-profiles")
P = json.load(open(os.path.join(PROFILE_DIR, "default.json")))
if os.environ.get("MOCKTV_PROFILE"):
    P.update(json.load(open(os.environ["MOCKTV_PROFILE"])))
ORDER = [int(t) for t in P["firstMessages"].split(",")]
ENCODINGS = {"unknown": 0, "alphanumeric": 1, "numeric": 2, "hexadecimal": 3, "qrcode": 4}
ROLES = {"input": 1, "output": 2}
assert ORDER[:2] == [1, 2] and set(ORDER[2:]) <= {20, 40, 50}, "firstMessages must start 1,2 then use 20, 40, 50"
ASLEEP = threading.Event()  # a box with sessionKept false, turned off: it answers no connection until woken

HTTP_PORT = int(os.environ.get("MOCKTV_HTTP_PORT", "6480"))
MS = {"power": "on", "app": None, "lastText": None, "keys": [], "launches": [], "pairingCode": None,
      "field": {"open": False, "value": ""}, "playing": None}   # under lock
SESSION = {}            # the live remote session's actions, for the HTTP side port; empty without one

def note(kind, item, cap):
    with lock:
        MS[kind].append([int(time.time() * 1000), item]); del MS[kind][:-cap]

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)

def ctx():
    c = SSL.Context(SSL.TLS_SERVER_METHOD)
    c.set_min_proto_version(SSL.TLS1_2_VERSION)
    c.use_certificate_file(CERT)
    c.use_privatekey_file(KEY)
    c.set_verify(SSL.VERIFY_PEER | SSL.VERIFY_FAIL_IF_NO_PEER_CERT, lambda *a: True)   # accept any client cert
    return c

def mod_exp(x509):
    n = x509.get_pubkey().to_cryptography_key().public_numbers()
    return n.n, n.e

def frames(conn):
    buf = b""
    while True:
        try:
            chunk = conn.recv(4096)
        except (SSL.ZeroReturnError, SSL.SysCallError):
            return
        except SSL.WantReadError:
            continue
        if not chunk:
            return
        buf += chunk
        while buf:
            try:
                ln, pos = _DecodeVarint(buf, 0)
            except IndexError:
                break
            if len(buf) < pos + ln:
                break
            yield buf[pos:pos + ln]
            buf = buf[pos + ln:]

def send(conn, msg):
    data = msg.SerializeToString()
    conn.sendall(_VarintBytes(len(data)) + data)

def send_raw(conn, data):
    conn.sendall(_VarintBytes(len(data)) + data)

def outer(**kw):
    m = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK)
    return m

def handle_pairing(conn):
    peer = conn.get_peer_certificate()
    log("pair: TLS", conn.get_protocol_version_name(), "client CN", peer.get_subject().CN)
    expected = None
    for raw in frames(conn):
        msg = OuterMessage(); msg.ParseFromString(raw)
        log("pair rx:", text_format.MessageToString(msg, as_one_line=True)[:200])
        r = outer()
        if msg.HasField("pairing_request"):
            r.pairing_request_ack.server_name = "mocktv"
        elif msg.HasField("options"):
            for key, field in (("inputEncodings", r.options.input_encodings), ("outputEncodings", r.options.output_encodings)):
                for e in filter(None, P[key].split(",")):
                    name, length = e.split("/")
                    enc = field.add(); enc.type = ENCODINGS[name]; enc.symbol_length = int(length)
            if P["preferredRole"]:
                r.options.preferred_role = ROLES[P["preferredRole"]]
        elif msg.HasField("configuration"):
            cm, ce = mod_exp(peer)
            sm, se = mod_exp(crypto.load_certificate(crypto.FILETYPE_PEM, open(CERT, "rb").read()))
            suffix = "%04X" % random.randint(0, 0xFFFF)
            h = hashlib.sha256()
            for v in (f"{cm:X}", f"0{ce:X}", f"{sm:X}", f"0{se:X}", suffix):
                h.update(bytes.fromhex(v))
            expected = h.digest()
            code = f"{expected[0]:02X}{suffix}"
            open(os.path.join(WORK, "code.txt"), "w").write(code)
            with lock:
                MS["pairingCode"] = code
            log("pair: CODE ON SCREEN:", code)
            r.configuration_ack.SetInParent()
        elif msg.HasField("secret"):
            if expected is not None and msg.secret.secret == expected:
                with lock:
                    paired.add(peer.digest("sha256"))
                    open(PAIRED_FILE, "a").write(peer.digest("sha256").decode() + "\n")
                r.secret_ack.secret = expected
                log("pair: secret OK, paired")
            else:
                r.status = OuterMessage.Status.STATUS_BAD_SECRET
                log("pair: BAD SECRET")
        else:
            r.status = OuterMessage.Status.STATUS_ERROR
        send(conn, r)

def handle_remote(conn):
    peer = conn.get_peer_certificate()
    with lock:
        ok = peer.digest("sha256") in paired
    if not ok:
        log("remote: unpaired client, closing")
        return
    log("remote: TLS", conn.get_protocol_version_name(), "profile", P["vendor"], P["model"])
    st = {"on": True, "level": P["volumeLevel"], "max": P["volumeRange"], "muted": False, "ping": 0, "pong": 0, "alive": True,
          "app": None, "field": None}
    with lock:
        # A box keeps its app across sessions: a new session reports the app in front, the profile's
        # startApp only on the first one.
        front = MS["app"]
        MS["power"] = "on"

    def power():
        m = RemoteMessage(); m.remote_start.started = st["on"]; send(conn, m)

    def volume():
        v = RemoteMessage(); v.remote_set_volume_level.volume_max = st["max"]; v.remote_set_volume_level.volume_level = st["level"]
        v.remote_set_volume_level.volume_muted = st["muted"]
        if P.get("playerModel"): v.remote_set_volume_level.player_model = P["playerModel"]
        send(conn, v)

    def app(package):
        st["app"] = package; st["field"] = None
        with lock:
            MS["app"] = package; MS["field"] = {"open": False, "value": ""}
        a = RemoteMessage(); a.remote_ime_key_inject.app_info.app_package = package; send(conn, a)

    def field_status(status):
        f = st["field"]
        status.counter_field = f["counter"]; status.value = f["value"]; status.start = status.end = len(f["value"])

    def keyboard(up):
        st["field"]["keyboard"] = up
        b = RemoteMessage(); b.remote_ime_batch_edit.ime_counter = b.remote_ime_batch_edit.field_counter = int(up); send(conn, b)

    # A text field on the search screen, as the EBOX has it: CENTER opens it with the keyboard up, BACK closes the
    # keyboard. Edits apply with counters 0/0, or with a field counter at most 10 behind the box's; others are dropped.
    def text_edit(edit):
        # edit_info is a repeated field in the reference proto; the driver sends one entry
        text = "".join(e.text_field_status.value for e in edit.edit_info)
        with lock:
            MS["lastText"] = text
        f = st["field"]
        if not f:
            return
        ime, counter = edit.ime_counter, edit.field_counter
        if not ((ime, counter) == (0, 0) or f["counter"] - 10 <= counter <= f["counter"]):
            log(f"remote: edit dropped, counters {ime}/{counter}, field at {f['counter']}")
            return
        f["value"] += text
        f["counter"] += len(text)
        with lock:
            MS["field"]["value"] = f["value"]
        m = RemoteMessage(); field_status(m.remote_ime_show_request.remote_text_field_status); send(conn, m)

    def open_field(up):
        """A person opening (or leaving) a text field in the app in front: send what a box sends."""
        if up:
            if not st["app"]:
                app(front or P["startApp"])
            counter = P.get("textFieldCounter")
            st["field"] = {"counter": 1 if counter is None else counter, "value": ""}
            with lock:
                MS["field"] = {"open": True, "value": ""}
            a = RemoteMessage(); a.remote_ime_key_inject.app_info.app_package = st["app"]
            field_status(a.remote_ime_key_inject.text_field_status); send(conn, a)
            keyboard(True)
        elif st["field"]:
            keyboard(False)
            st["field"] = None
            with lock:
                MS["field"] = {"open": False, "value": ""}

    def play(package):
        app(package)
        with lock:
            MS["playing"] = "playing"

    def pinger():
        while st["alive"]:
            time.sleep(P["pingIntervalS"])
            if not st["alive"]:
                return
            if not st["on"] and not P["pingsWhileOff"]:
                continue
            st["ping"] += 1
            m = RemoteMessage(); m.remote_ping_request.val1 = st["ping"]; m.remote_ping_request.val2 = 1
            try:
                send(conn, m)
            except Exception:
                return

    m = RemoteMessage(); m.remote_configure.code1 = P["features"]
    di = m.remote_configure.device_info
    di.model = P["model"]; di.vendor = P["vendor"]; di.unknown2 = P["androidVersion"]
    di.package_name = P["remoteService"]; di.app_version = P["remoteServiceVersion"]
    if P.get("deviceInfo3") is not None: di.unknown1 = P["deviceInfo3"]
    send(conn, m)
    threading.Thread(target=pinger, daemon=True).start()
    SESSION.clear(); SESSION.update(field=open_field, play=play, owner=st)
    for raw in frames(conn):
        msg = RemoteMessage(); msg.ParseFromString(raw)
        if msg.HasField("remote_ping_response"):
            st["pong"] = msg.remote_ping_response.val1
            log(f"remote: pong {st['pong']} (ping {st['ping']})")
            continue
        log("remote rx:", text_format.MessageToString(msg, as_one_line=True)[:200])
        if msg.HasField("remote_configure"):
            s = RemoteMessage(); s.remote_set_active.active = P["features"]; send(conn, s)
        elif msg.HasField("remote_set_active"):
            for t in ORDER[2:]:
                if t == 40: power()
                elif t == 50: volume()
                elif t == 20: app(front or P["startApp"])
            for t in P["extraMessages"]:
                x = RemoteMessage(); getattr(x, RemoteMessage.DESCRIPTOR.fields_by_number[t].name).SetInParent(); send(conn, x)
        elif msg.HasField("remote_key_inject"):
            k = msg.remote_key_inject.key_code
            note("keys", k, 50)
            if k == 26:
                st["on"] = not st["on"]
                with lock:
                    MS["power"] = "on" if st["on"] else "off"
                if not st["on"] and not P["sessionKept"]:
                    # As the TCL TV: no off report, the volume message, then a reset, and nothing answers until woken
                    if P["volumeDuringChange"]: volume()
                    ASLEEP.set()
                    mdns_power(False)
                    log(f"remote: asleep; wake with kill -USR1 {os.getpid()}")
                    threading.Timer(0.5, reset, [conn]).start()
                    continue
                power()
                mdns_power(st["on"])
                if P["volumeDuringChange"]: volume()
            elif k in (24, 25, 164):
                if k == 24: st["level"] = min(st["max"], st["level"] + 1)
                elif k == 25: st["level"] = max(0, st["level"] - 1)
                else: st["muted"] = not st["muted"]
                if P["volumeReported"]: volume()
            elif k == 3:
                app(P["homeApp"])
                with lock:
                    MS["playing"] = None
            elif k == 84:
                app(P["searchApp"])
            elif k == 23 and P.get("textFieldCounter") is not None and st["app"] == P["searchApp"] and not st["field"]:
                st["field"] = {"counter": P["textFieldCounter"], "value": ""}
                with lock:
                    MS["field"] = {"open": True, "value": ""}
                a = RemoteMessage(); a.remote_ime_key_inject.app_info.app_package = st["app"]
                field_status(a.remote_ime_key_inject.text_field_status); send(conn, a)
                keyboard(True)
            elif k in (127, 126, 86) and MS["playing"] is not None:
                with lock:
                    MS["playing"] = {127: "paused", 126: "playing", 86: "stopped"}[k]
            elif k == 4 and st["field"] and st["field"].get("keyboard"):
                keyboard(False)
        elif msg.HasField("remote_ime_batch_edit"):
            text_edit(msg.remote_ime_batch_edit)
        elif msg.HasField("remote_app_link_launch_request"):
            link = msg.remote_app_link_launch_request.app_link
            note("launches", link, 20)
            schemes = P["linkSchemes"]
            if schemes is not None and link.split(":", 1)[0].lower() not in schemes:
                # remote_error: field 1 is a code on real boxes (7 on the EBOX), declared bool in the reference proto.
                echo = msg.SerializeToString()
                body = b"\x08" + _VarintBytes(P["errorCode"]) + b"\x12" + _VarintBytes(len(echo)) + echo
                send_raw(conn, b"\x1a" + _VarintBytes(len(body)) + body)
                log("remote: rejected", link, f"with error {P['errorCode']}; closing in {P['closeAfterErrorS']} s")
                threading.Timer(P["closeAfterErrorS"], lambda: conn.shutdown()).start()
                continue
            app("com.google.android.youtube.tv" if "youtube" in link else "launched:" + link)
    st["alive"] = False
    if SESSION.get("owner") is st:
        SESSION.clear()
    log("remote: connection closed")

def reset(conn):
    """Ends the connection with a TCP reset, as a box going to sleep does."""
    s = conn.get_app_data()
    try:
        s.shutdown(socket.SHUT_RD)             # wakes the reading thread
        s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        s.close()
    except OSError:
        pass

def wake(*_):
    ASLEEP.clear()
    mdns_power(True)
    log("awake")

def serve(port, handler):
    ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ls.bind(("0.0.0.0", port)); ls.listen(4)
    while True:
        s, addr = ls.accept()
        def run(s=s):
            if ASLEEP.is_set():
                # The real box let connects time out; here the TCP connect succeeds and the TLS handshake waits
                while ASLEEP.is_set(): time.sleep(0.5)
                s.close()
                return
            conn = SSL.Connection(ctx(), s); conn.set_accept_state(); conn.set_app_data(s)
            try:
                conn.do_handshake()
                handler(conn)
            except SSL.Error as e:
                log(f"{port}: TLS error {e}")
            except Exception as e:
                log(f"{port}: {type(e).__name__} {e}")
            finally:
                try: conn.shutdown()
                except Exception: pass
                s.close()
        threading.Thread(target=run, daemon=True).start()

MDNS = None     # (zeroconf, service info) while announcing

def mdns_power(on):
    """A box whose profile has announcedOff false stops announcing while off (test 13 announcedOff)."""
    if MDNS is None or P["announcedOff"]:
        return
    zc, info = MDNS
    if on: zc.register_service(info, strict=False)
    else: zc.unregister_service(info)
    log("mDNS announcement", "resumed" if on else "withdrawn")

def announce(name, bt):
    """Advertise the mock as a real TV does: _androidtvremote2._tcp on the remote port, TXT bt."""
    from zeroconf import ServiceInfo, Zeroconf
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.connect(("192.0.2.1", 9))         # picks the LAN interface; nothing is sent
    ip = probe.getsockname()[0]; probe.close()
    info = ServiceInfo("_androidtvremote2._tcp.local.", f"{name}._androidtvremote2._tcp.local.",
                       addresses=[socket.inet_aton(ip)], port=REMOTE_PORT, properties={"bt": bt},
                       server=f"mocktv-{bt.replace(':', '').lower()}.local.")
    zc = Zeroconf(); zc.register_service(info, strict=False)    # the 16-byte type exceeds DNS-SD's 15-byte limit
    log(f"announcing '{name}' (bt {bt}) at {ip}:{REMOTE_PORT}")
    return zc, info

if os.environ.get("MOCKTV_MDNS_NAME"):
    import atexit
    MDNS = announce(os.environ["MOCKTV_MDNS_NAME"], os.environ.get("MOCKTV_BT", "02:00:00:00:00:01"))
    atexit.register(lambda: (MDNS[0].unregister_service(MDNS[1]), MDNS[0].close()))
    # Exit through atexit on a kill, so the goodbye goes out: a hub that saw the name expire without one
    # doesn't find it again when it comes back.
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *a: os._exit(0) if MDNS is None else (MDNS[0].unregister_service(MDNS[1]), MDNS[0].close(), os._exit(0)))

class StateHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(url.query)
        if url.path == "/mock/state":
            with lock:
                body = json.dumps(dict(MS, asleep=ASLEEP.is_set())).encode()
        elif url.path in ("/mock/field", "/mock/play"):
            try:
                if not SESSION:
                    result = {"ok": False, "error": "no session"}
                elif url.path == "/mock/field":
                    SESSION["field"](q.get("open", ["1"])[0] not in ("0", "false")); result = {"ok": True}
                else:
                    SESSION["play"](q.get("app", ["com.google.android.youtube.tv"])[0]); result = {"ok": True}
            except Exception as e:
                result = {"ok": False, "error": f"{type(e).__name__} {e}"}
            body = json.dumps(result).encode()
        else:
            self.send_error(404); return
        self.send_response(200)
        self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def log_message(self, *a):
        pass

http.server.ThreadingHTTPServer.daemon_threads = True
threading.Thread(target=http.server.ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), StateHandler).serve_forever, daemon=True).start()
signal.signal(signal.SIGUSR1, wake)
threading.Thread(target=serve, args=(PAIR_PORT, handle_pairing), daemon=True).start()
log("mock TV listening on", PAIR_PORT, REMOTE_PORT)
serve(REMOTE_PORT, handle_remote)
