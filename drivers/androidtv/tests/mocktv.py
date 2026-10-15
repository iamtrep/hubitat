# Mock Android TV (Remote protocol v2) for rehearsing the Hubitat driver.
# Encodes/decodes with androidtvremote2's compiled protobuf modules. Ports: pairing 6467, remote 6466.
# usage: mocktv.py <cert.pem> <key.pem> <workdir>
import hashlib, os, random, socket, sys, threading, time
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
QUIRKS = os.environ.get("MOCKTV_QUIRKS", "")
EBOX = "ebox" in QUIRKS.split(",")

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
            enc = r.options.input_encodings.add(); enc.type = Options.Encoding.ENCODING_TYPE_HEXADECIMAL; enc.symbol_length = 6
            r.options.preferred_role = Options.RoleType.ROLE_TYPE_INPUT
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
    log("remote: TLS", conn.get_protocol_version_name())
    st = {"on": True, "level": 20, "max": 100, "muted": False, "ping": 0, "pong": 0, "alive": True}

    def push_state(volume=True):
        m = RemoteMessage(); m.remote_start.started = st["on"]; send(conn, m)
        if volume:
            v = RemoteMessage(); v.remote_set_volume_level.volume_max = st["max"]; v.remote_set_volume_level.volume_level = st["level"]
            v.remote_set_volume_level.volume_muted = st["muted"]; send(conn, v)

    def pinger():
        while st["alive"]:
            time.sleep(5)
            if not st["alive"]:
                return
            st["ping"] += 1
            m = RemoteMessage(); m.remote_ping_request.val1 = st["ping"]; m.remote_ping_request.val2 = 1
            try:
                send(conn, m)
            except Exception:
                return

    m = RemoteMessage(); m.remote_configure.code1 = 622
    m.remote_configure.device_info.model = "MockTV"; m.remote_configure.device_info.vendor = "Mock"
    m.remote_configure.device_info.app_version = "1.0"; send(conn, m)
    threading.Thread(target=pinger, daemon=True).start()
    for raw in frames(conn):
        msg = RemoteMessage(); msg.ParseFromString(raw)
        if msg.HasField("remote_ping_response"):
            st["pong"] = msg.remote_ping_response.val1
            log(f"remote: pong {st['pong']} (ping {st['ping']})")
            continue
        log("remote rx:", text_format.MessageToString(msg, as_one_line=True)[:200])
        if msg.HasField("remote_configure"):
            s = RemoteMessage(); s.remote_set_active.active = 622; send(conn, s)
        elif msg.HasField("remote_set_active"):
            push_state(volume=not EBOX)
            a = RemoteMessage(); a.remote_ime_key_inject.app_info.app_package = "com.google.android.youtube.tv"; send(conn, a)
        elif msg.HasField("remote_key_inject"):
            k = msg.remote_key_inject.key_code
            if k == 26: st["on"] = not st["on"]
            elif k == 24: st["level"] = min(st["max"], st["level"] + 1)
            elif k == 25: st["level"] = max(0, st["level"] - 1)
            elif k == 164: st["muted"] = not st["muted"]
            elif k == 3:
                a = RemoteMessage(); a.remote_ime_key_inject.app_info.app_package = "com.google.android.tvlauncher"; send(conn, a)
                continue
            push_state(volume=(not EBOX) or k == 26)
        elif msg.HasField("remote_app_link_launch_request"):
            link = msg.remote_app_link_launch_request.app_link
            if EBOX and not link.startswith(("http://", "https://")):
                e = RemoteMessage(); e.remote_error.value = True; e.remote_error.message.CopyFrom(msg); send(conn, e)
                log("remote: rejected", link, "; closing in 10 s")
                threading.Timer(10, lambda: conn.shutdown()).start()
                continue
            a = RemoteMessage(); a.remote_ime_key_inject.app_info.app_package = (
                "com.google.android.youtube.tv" if "youtube" in link else "launched:" + link)
            send(conn, a)
    st["alive"] = False
    log("remote: connection closed")

def serve(port, handler):
    ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ls.bind(("0.0.0.0", port)); ls.listen(4)
    while True:
        s, addr = ls.accept()
        def run(s=s):
            conn = SSL.Connection(ctx(), s); conn.set_accept_state()
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

threading.Thread(target=serve, args=(PAIR_PORT, handle_pairing), daemon=True).start()
log("mock TV listening on", PAIR_PORT, REMOTE_PORT)
serve(REMOTE_PORT, handle_remote)
