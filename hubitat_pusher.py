import requests
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

def push_code(file_path):
    hub_ip, hub_session = get_hub_ip()
    if not hub_ip or hub_ip == "PLEASE_REPLACE_WITH_YOUR_HUBITAT_IP":
        print("Error: Hub IP not configured in hubitat-config.json. Please update it.", file=sys.stderr)
        sys.exit(1)
    if not hub_session or hub_session == "PLEASE_REPLACE_WITH_YOUR_HUBSESSION_COOKIE":
        print("Error: HUBSESSION cookie not configured in hubitat-config.json. Please update it.", file=sys.stderr)
        print("You can get this from your browser's developer tools when logged into the Hubitat web interface.", file=sys.stderr)
        sys.exit(1)

    if not os.path.exists(file_path):
        print(f"Error: File not found at {file_path}", file=sys.stderr)
        sys.exit(1)

    path_parts = file_path.split(os.sep)
    if 'apps' in path_parts:
        script_type = 'app'
    elif 'drivers' in path_parts:
        script_type = 'driver'
    else:
        print(f"Error: Could not determine script type (app or driver) from file path: {file_path}", file=sys.stderr)
        print("Please ensure the file is in an 'apps/' or 'drivers/' subdirectory.", file=sys.stderr)
        sys.exit(1)

    try:
        with open(file_path, 'r') as f:
            code_content = f.read()
    except Exception as e:
        print(f"Error reading file {file_path}: {e}", file=sys.stderr)
        sys.exit(1)

    url = f"http://{hub_ip}/{script_type}/saveOrUpdateJson"
    headers = {
        'Content-Type': 'application/json',
        'Cookie': f'HUBSESSION={hub_session}',
        'X-Requested-With': 'XMLHttpRequest'
    }
    # For new apps/drivers, the version is typically 1, and the hub assigns an ID.
    # For updates, the hub uses the name to find the existing entry.
    payload = {
        'source': code_content,
        'version': 1 # Assuming version 1 for initial creation/update via this method
    }

    print(f"Attempting to push {script_type} code to {url}...")

    try:
        response = requests.post(url, headers=headers, json=payload, timeout=10)
        response.raise_for_status()

        try:
            response_json = response.json()
            if response_json.get('status') == 'success':
                print(f"Successfully pushed {script_type} code. ID: {response_json.get('id')}")
            else:
                print(f"Failed to push {script_type} code. Hubitat response: {response_json}", file=sys.stderr)
                sys.exit(1)
        except json.JSONDecodeError:
            print(f"Failed to parse Hubitat response as JSON. Raw response: {response.text}", file=sys.stderr)
            sys.exit(1)

    except requests.exceptions.Timeout:
        print(f"Error: Request to Hubitat hub timed out. Is the IP correct and the hub online?", file=sys.stderr)
        sys.exit(1)
    except requests.exceptions.ConnectionError:
        print(f"Error: Could not connect to Hubitat hub at {hub_ip}. Is the IP correct?", file=sys.stderr)
        sys.exit(1)
    except requests.exceptions.HTTPError as e:
        print(f"Error pushing code: HTTP {e.response.status_code} - {e.response.text}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"An unexpected error occurred: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 hubitat_pusher.py <path_to_groovy_file>", file=sys.stderr)
        sys.exit(1)
    
    groovy_file_path = sys.argv[1]
    push_code(groovy_file_path)