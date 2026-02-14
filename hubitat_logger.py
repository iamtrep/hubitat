import asyncio
import websockets
import json
import os
import sys

def get_hub_ip():
    config_path = os.path.join(os.path.dirname(__file__), 'hubitat-config.json')
    try:
        with open('hubitat-config.json', 'r') as f:
            config = json.load(f)
        return config.get('hub_ip'), config.get('hub_session')
    except FileNotFoundError:
        print("Error: hubitat-config.json not found. Please create it with your hub's IP address and session cookie.", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError:
        print("Error: Invalid JSON in hubitat-config.json. Please check its content.", file=sys.stderr)
        sys.exit(1)

async def stream_logs():
    hub_ip, hub_session = get_hub_ip()
    if not hub_ip or hub_ip == "PLEASE_REPLACE_WITH_YOUR_HUBITAT_IP":
        print("Error: Hub IP not configured in hubitat-config.json. Please update it.", file=sys.stderr)
        sys.exit(1)
    if not hub_session or hub_session == "PLEASE_REPLACE_WITH_YOUR_HUBSESSION_COOKIE":
        print("Error: HUBSESSION cookie not configured in hubitat-config.json. Please update it.", file=sys.stderr)
        print("You can get this from your browser's developer tools when logged into the Hubitat web interface.", file=sys.stderr)
        sys.exit(1)

    uri = f"ws://{hub_ip}/logsocket"
    headers = {
        'Cookie': f'HUBSESSION={hub_session}'
    }
    print(f"Connecting to Hubitat log stream at {uri}...")

    try:
        async with websockets.connect(uri, ping_interval=None, extra_headers=headers) as websocket:
            print("Connected. Streaming logs (Press Ctrl+C to stop):")
            while True:
                message = await websocket.recv()
                print(message)
    except websockets.exceptions.ConnectionClosedOK:
        print("Log stream closed gracefully.")
    except websockets.exceptions.ConnectionClosedError as e:
        print(f"Log stream connection closed with error: {e}", file=sys.stderr)
    except ConnectionRefusedError:
        print(f"Error: Connection refused. Is the Hubitat hub online and reachable at {hub_ip}?", file=sys.stderr)
        print("Ensure 'Settings -> Hubitat Log Socket' is enabled if you have a custom firewall or proxy.", file=sys.stderr)
        sys.exit(1)
    except asyncio.exceptions.TimeoutError:
        print(f"Error: Connection to Hubitat log stream timed out. Is the IP correct?", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"An unexpected error occurred: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    try:
        asyncio.run(stream_logs())
    except KeyboardInterrupt:
        print("
Log streaming stopped by user.")
    except ModuleNotFoundError:
        print("
Error: The 'websockets' library is not installed.", file=sys.stderr)
        print("Please install it using: pip install websockets", file=sys.stderr)
        sys.exit(1)
