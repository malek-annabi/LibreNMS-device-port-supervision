# LibreNMSAlertHandler v7 - Process Overview & Code Specification

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
