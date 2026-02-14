# Hubitat Development Skill

This skill provides tools to facilitate Hubitat Elevation app and driver development by allowing code pushes and real-time log streaming directly from the Gemini CLI.

## Tools

### 1. `hubitat_push_code`

Pushes a local `.groovy` file (app or driver) to the Hubitat Elevation hub. The script automatically determines if it's an app or a driver based on the parent directory (e.g., `apps/` or `drivers/`).

**Parameters:**
- `file_path`: (string, required) The path to the `.groovy` file to push.

**Example Usage:**
```
hubitat_push_code(file_path='apps/MyNewApp.groovy')
hubitat_push_code(file_path='drivers/MyDeviceDriver.groovy')
```

### 2. `hubitat_stream_logs`

Connects to the Hubitat Elevation hub's log socket and streams real-time log messages to the console. This tool runs continuously until manually stopped (e.g., by pressing Ctrl+C in the terminal).

**Parameters:**
(None)

**Example Usage:**
```
hubitat_stream_logs()
```

## Workflow Guidance for Gemini Agent

When a user requests to develop for Hubitat, or specifically asks to push code or view logs, use the following workflow:

1.  **Code Push & Log Stream:** If the user asks to push code and then observe the results, first use `hubitat_push_code` with the specified file. Upon successful completion of the push, immediately follow up with `hubitat_stream_logs()` to provide real-time feedback.
    *   Example User Prompt: "Push my `drivers/MySensor.groovy` and show me the logs."
    *   Agent Action:
        1.  `hubitat_push_code(file_path='drivers/MySensor.groovy')`
        2.  `hubitat_stream_logs()` (after successful push)

2.  **Code Push Only:** If the user only asks to push code, use `hubitat_push_code`.
    *   Example User Prompt: "Please update `apps/MyCoolApp.groovy` on the hub."
    *   Agent Action:
        1.  `hubitat_push_code(file_path='apps/MyCoolApp.groovy')`

3.  **Log Stream Only:** If the user only asks to see logs, use `hubitat_stream_logs`.
    *   Example User Prompt: "I need to see the Hubitat logs."
    *   Agent Action:
        1.  `hubitat_stream_logs()`

4.  **Configuration:** Ensure `hubitat-config.json` exists in the project root and contains a valid `hub_ip`. If `hub_ip` is `PLEASE_REPLACE_WITH_YOUR_HUBITAT_IP` or missing, inform the user to update it before using the tools.

## Setup Requirements

*   **`hubitat-config.json`:** Must be present in the project root with the correct `hub_ip`.
*   **Python Dependencies:** The Python scripts (`hubitat_pusher.py`, `hubitat_logger.py`) require `requests` and `websockets`. If not installed, inform the user to run:
    ```bash
    pip install requests websockets
    ```
*   **Scripts Location:** `hubitat_pusher.py` and `hubitat_logger.py` should be in the project root.
