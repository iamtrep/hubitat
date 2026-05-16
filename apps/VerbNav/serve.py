#!/usr/bin/env python3
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

"""
Proxy server for Verb Nav prototype.
Serves static files locally and proxies /hub/* and /ws/* to the Hubitat hub,
bypassing browser CORS restrictions.

Usage: python3 serve.py [hub_ip] [port]
  Default hub_ip: 192.0.2.10
  Default port: 8000

Then open: http://localhost:8000/verb_nav_prototype.html
"""
import sys, os, http.server, urllib.request, urllib.error, socketserver, threading, hashlib, base64, struct, socket, select

HUB_IP = sys.argv[1] if len(sys.argv) > 1 else '192.0.2.10'
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
HUB_BASE = f'http://{HUB_IP}'

# Paths that get proxied to the hub
PROXY_PREFIXES = ('/hub/', '/hub2/', '/device/', '/installedapp/', '/logs/', '/driver/', '/app/')

class ProxyHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=os.path.dirname(os.path.abspath(__file__)), **kwargs)

    def do_GET(self):
        # WebSocket upgrade for /logsocket
        if self.path == '/logsocket':
            self.proxy_websocket()
            return
        # Proxy hub API requests
        if any(self.path.startswith(p) for p in PROXY_PREFIXES):
            self.proxy_request()
            return
        # Serve static files
        super().do_GET()

    def proxy_request(self):
        url = HUB_BASE + self.path
        try:
            req = urllib.request.Request(url)
            # Forward relevant headers
            for key in ('Accept', 'Content-Type'):
                val = self.headers.get(key)
                if val:
                    req.add_header(key, val)
            with urllib.request.urlopen(req, timeout=10) as resp:
                body = resp.read()
                self.send_response(resp.status)
                ct = resp.headers.get('Content-Type', 'application/octet-stream')
                self.send_header('Content-Type', ct)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(f'Proxy error: {e.code} {e.reason}'.encode())
        except Exception as e:
            self.send_response(502)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(f'Proxy error: {e}'.encode())

    def proxy_websocket(self):
        """Upgrade to WebSocket and relay frames between browser and hub."""
        # Validate upgrade request
        if self.headers.get('Upgrade', '').lower() != 'websocket':
            self.send_error(400, 'Not a WebSocket request')
            return
        ws_key = self.headers.get('Sec-WebSocket-Key', '')
        if not ws_key:
            self.send_error(400, 'Missing Sec-WebSocket-Key')
            return

        # Connect to hub WebSocket
        try:
            hub_sock = socket.create_connection((HUB_IP, 80), timeout=5)
        except Exception as e:
            self.send_error(502, f'Cannot connect to hub: {e}')
            return

        # Send WebSocket upgrade to hub
        hub_req = (
            f'GET /logsocket HTTP/1.1\r\n'
            f'Host: {HUB_IP}\r\n'
            f'Upgrade: websocket\r\n'
            f'Connection: Upgrade\r\n'
            f'Sec-WebSocket-Version: 13\r\n'
            f'Sec-WebSocket-Key: {ws_key}\r\n'
            f'\r\n'
        )
        hub_sock.sendall(hub_req.encode())

        # Read hub upgrade response
        hub_resp = b''
        while b'\r\n\r\n' not in hub_resp:
            chunk = hub_sock.recv(4096)
            if not chunk:
                hub_sock.close()
                self.send_error(502, 'Hub closed during handshake')
                return
            hub_resp += chunk

        # Check hub accepted
        if b'101' not in hub_resp.split(b'\r\n')[0]:
            hub_sock.close()
            self.send_error(502, 'Hub rejected WebSocket upgrade')
            return

        # Compute accept key for browser
        GUID = '258EAFA5-E914-47DA-95CA-5AB5DC799073'
        accept = base64.b64encode(hashlib.sha1((ws_key + GUID).encode()).digest()).decode()

        # Send upgrade response to browser
        response = (
            'HTTP/1.1 101 Switching Protocols\r\n'
            'Upgrade: websocket\r\n'
            'Connection: Upgrade\r\n'
            f'Sec-WebSocket-Accept: {accept}\r\n'
            '\r\n'
        )
        self.wfile.write(response.encode())
        self.wfile.flush()

        # Get the raw browser socket
        browser_sock = self.request

        # Relay data bidirectionally
        try:
            # Check if hub sent extra data after headers
            extra = hub_resp.split(b'\r\n\r\n', 1)[1]
            if extra:
                browser_sock.sendall(extra)

            while True:
                readable, _, errored = select.select([browser_sock, hub_sock], [], [browser_sock, hub_sock], 30)
                if errored:
                    break
                if not readable:
                    # Send ping to keep alive (optional, just continue)
                    continue
                for sock in readable:
                    data = sock.recv(65536)
                    if not data:
                        raise ConnectionError('Socket closed')
                    if sock is browser_sock:
                        hub_sock.sendall(data)
                    else:
                        browser_sock.sendall(data)
        except Exception:
            pass
        finally:
            try: hub_sock.close()
            except: pass

    def log_message(self, format, *args):
        # Quieter logging - only show proxied requests
        path = args[0].split()[1] if args else ''
        if any(path.startswith(p) for p in PROXY_PREFIXES) or 'logsocket' in path:
            print(f'  proxy → {path}')

class ThreadedServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

if __name__ == '__main__':
    print(f'Verb Nav Prototype Server')
    print(f'  Hub: {HUB_BASE}')
    print(f'  Open: http://localhost:{PORT}/verb_nav_prototype.html')
    print(f'  Press Ctrl+C to stop\n')
    with ThreadedServer(('', PORT), ProxyHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print('\nStopped.')
