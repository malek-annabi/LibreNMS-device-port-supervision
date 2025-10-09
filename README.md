# LibreNMSAlertHandler v7 - Process Overview & Code Specification

## Java Version Specification 

---

## For Python Version Specification (see below in this file)

---

## Overview

`LibreNMSAlertHandler.java` is a Java service that acts as a webhook receiver for LibreNMS alerts and manages device state and automatic recovery using the LibreNMS API. When a problem is detected, it "forces down" a device by setting its `overwrite_ip` to a special IP. It then periodically attempts to recover the device by restoring its original IP, rediscovering, polling for SNMP status, and monitoring the operational status of a specific port.

## Process Flow

1. **Startup**
   - Reads configuration from a `.env` file (API URL, token, special IP, etc).
   - Loads persistent device state from a JSON file.
   - Starts a background recovery loop thread.
   - Starts an HTTP server on port 5000 for receiving LibreNMS alert POSTs.

2. **On Alert**
   - Receives an alert as a POST request with JSON payload.
   - Extracts `device_id`, `host`, and `ip` from the alert.
   - Looks up the relevant port (e.g., `port2`) by API.
   - Stores device info (including port_id) in the state file.
   - Immediately "forces down" the device: sets `overwrite_ip` to the unsupervised IP via the API, and calls the discover endpoint (no polling here).
   - Triggers the recovery loop if it was idle.

3. **Recovery Loop**
   - Every `RECOVERY_INTERVAL_SEC` seconds, for each tracked device:
     1. **Restore IP**: Set `overwrite_ip` to the original IP via the API.
     2. **Rediscover**: Call the API `/devices/{id}/discover` endpoint.
     3. **Poll**: Run `php /opt/librenms/artisan device:poll {device_id}` locally to update SNMP/port status.
     4. **Check Port**: Query the API for the port status (`ifOperStatus`).
     5. **Decision**:
         - If the port is `"up"`, remove the device from state (recovery succeeded).
         - If not, force device down again (repeat cycle).
   - The loop continues until **all tracked ports** are up.

4. **Logging**
   - All events, API calls, and device state changes are written to a log file with timestamps.

## Code Specifications

- **No external dependencies** other than Java SDK and `com.sun.net.httpserver`.
- **No org.json, Gson, or Jackson**: JSON is parsed and generated manually for the needed fields.
- **PATCH** HTTP requests are emulated using POST + `X-HTTP-Method-Override: PATCH` for compatibility.
- Only polls after restoring the original IP (never after forcing down).
- State is stored in a simple JSON file for persistence and recovery on restart.
- Designed to run on the same host as LibreNMS, so `device:poll` uses a direct local ProcessBuilder call.
- All logic is robust against missing fields and unexpected API responses.

## Configuration (.env file)

Example `.env`:

```
LIBRENMS_URL=http://192.168.150.136/api/v0
API_TOKEN=YOUR_API_TOKEN
UNSUPERVISED_IP=127.0.0.50
STATE_FILE=device_state.json
LOG_FILE=alert_handler.log
RECOVERY_INTERVAL_SEC=60
```

## Security & Production Notes

- API tokens and sensitive fields should be protected.
- For production, consider using a more robust web server and structured logging.
- The polling command assumes the script runs as a user with access to `/opt/librenms/artisan`.

## Extending

- To monitor additional ports, modify `findPortIdForDialer`.
- To change alert fields or payload formats, adjust the alert handler's JSON parsing.
- For more advanced recovery actions, extend the recovery loop logic.

---

## Java Version Specification (see above in this file)

---

## Python Version Specification

---

### Overview

The Python handler is a complete solution for LibreNMS device/port alert automation. It listens for HTTP POST alerts, manages a persistent state, and uses both the LibreNMS API and SSH to the LibreNMS host to control device status and trigger immediate SNMP polling for recovery.

### Process Flow

1. **Startup:**
   - Reads configuration (API URL, token, etc.) from variables or `.env` style definitions.
   - Loads persistent device state from a JSON file.
   - Starts a background recovery loop (thread).
   - Starts an HTTP server for receiving POSTed alerts.

2. **On Alert:**
   - Receives a POST request containing alert JSON (device_id, host, ip).
   - Extracts necessary fields (`device_id`, `host`, `ip`).
   - Looks up the relevant port (`port2` by default) using the LibreNMS API.
   - Writes the device and port info into the persistent state file.
   - "Forces down" the device by setting its `overwrite_ip` to the unsupervised IP via the API, and triggers a discover action.
   - Triggers the recovery loop if not already running.

3. **Recovery Loop:**
   - Runs every `RECOVERY_INTERVAL_SEC` seconds.
   - For every device in the state file:
     1. Restores the original IP (`overwrite_ip`).
     2. Triggers a device discover via the API.
     3. Triggers an immediate SNMP poll via SSH (`php /opt/librenms/artisan device:poll {device_id}`).
     4. Looks up the port operational status (`ifOperStatus`) via the API.
     5. If the port is `"up"`, removes the device from state (recovery succeeded).
     6. If not, forces device down again and repeats the cycle.
   - The loop continues until all tracked ports are "up".

4. **Logging:**
   - All steps, received alerts, state changes, and API/SSH calls are logged with timestamps.

---

### Code Specifications

- **Dependencies:**  
  - Python 3.x  
  - `paramiko` for SSH, `requests` for HTTP API, `json`/`datetime`/`threading`/`os` from standard library.
- **API Integration:**  
  - Uses LibreNMS v0 API for all device and port actions.
  - SSHs into the LibreNMS host for direct polling command execution (`php /opt/librenms/artisan device:poll {device_id}`).
- **State Persistence:**  
  - Device state is stored in a JSON file (`device_state.json`), atomically updated via a `.tmp` file and rename.
- **Port Detection:**  
  - Looks up the relevant port by ifName (default `"port2"`) using `/ports/search/ifName` API.
- **Polling:**  
  - Triggers SNMP polling immediately after restoring the original IP, not after forcing down.
- **Recovery Logic:**  
  - Only removes the device from state if the specific port's `ifOperStatus` is `"up"`.
  - Otherwise, repeats the cycle with another force down, discover, and poll.
- **Threading:**  
  - Recovery loop is a daemon thread, synchronized via a condition variable.
- **Configurable:**  
  - All main parameters (API URL, token, SSH host, user, pass, recovery interval, etc.) are at the top of the script.
- **Extensible:**  
  - Can be adapted for different port names, recovery criteria, or SSH/polling methods as needed.

---

### Security & Production Notes

- The Python handler assumes SSH access to the LibreNMS server. In production, secure your SSH credentials (do not hardcode).
- Protect API tokens and sensitive configuration.
- For production, consider error handling, logging to files, and running behind a process manager (systemd, supervisor, etc).

---

### Example Python Handler Configuration

```python
LIBRENMS_URL   = "http://192.168.150.136/api/v0"
API_TOKEN      = "your_api_token_here"
UNSUPERVISED_IP = "127.0.0.50"
STATE_FILE     = "device_state.json"
SSH_HOST = "192.168.150.136"
SSH_USER = "test"
SSH_PASS = "test"
RECOVERY_INTERVAL_SEC = 60
```

---

### Key Differences vs Java Version

- Uses SSH to the LibreNMS host for polling (can be run remotely).
- Recovery thread uses condition variables for wake/sleep.
- Pythonic JSON and HTTP handling, robust to empty or corrupt state files.
- Otherwise, the recovery logic and API usage are functionally equivalent.

---
