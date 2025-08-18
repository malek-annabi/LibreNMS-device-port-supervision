# LibreNMS Device Supervision Automation

This project implements a **LibreNMS alert handler and recovery manager** that supervises devices based on their port operational status (specifically **Dialer 1**).  
When LibreNMS raises an alert that a device/port is down, the script **automatically forces the device into an unsupervised state**. After a configurable period (20 minutes), it begins recovery attempts until the device is confirmed healthy again.

---

## 🚀 Features

### Alert-Driven Automation
- **Listens** for LibreNMS alerts via HTTP POST.
- **Extracts** `device_id`, `hostname`, and `ip` from the alert payload.

### Port Supervision
- **Queries LibreNMS** for the `port_id` of Dialer 1.
- Uses `ifOperStatus` of that port to determine whether the device has recovered.

### Device State Management
- **On alert:** Overwrites device IP with a dummy unsupervised IP (`127.0.0.50`) to force it "down".
- Runs `discover` after every overwrite to apply state quickly.
- Polls the device with `artisan device:poll` via SSH inside the LibreNMS Docker container.
- Maintains a local JSON file (`device_state.json`) to persist tracked devices across restarts.

### Recovery Loop
- **Waits 20 minutes** after an alert, then every 20 minutes:
  - Restores original IP.
  - Runs `discover` + SSH poll.
  - Checks Dialer 1 `ifOperStatus`.
  - If **UP** → device is removed from state (no longer forced down).
  - If **DOWN** → device is forced back to unsupervised IP and retried next cycle.
- Automatically **pauses** when no devices remain in state.
- Automatically **resumes** when a new alert is received.

### Detailed Logging
- Every API request/response is **logged with timestamps**.
- Recovery progress and state transitions are **clearly visible in the logs**.

---

## 🛠️ Requirements

- **Python 3.9+**
- **LibreNMS API access**
- **Dockerized LibreNMS instance** (or adapt SSH commands for your environment)
- Python dependencies:
  ```bash
  pip install requests paramiko
  ```

---

## ⚙️ Configuration

Edit the script to match your environment:

```python
LIBRENMS_URL   = "http://192.168.150.135:8000/api/v0"
API_TOKEN      = "your_librenms_api_token"

UNSUPERVISED_IP = "127.0.0.50"       # dummy IP used to force device down
STATE_FILE     = "device_state.json" # tracks supervised devices

SSH_HOST = "192.168.150.135"         # host running LibreNMS Docker
SSH_USER = "librenms"
SSH_PASS = "librenms"                # store securely in production
DOCKER_CONTAINER = "librenms"
SUDO_FOR_DOCKER = False              # True if your user requires sudo for docker

RECOVERY_INTERVAL_SEC = 20 * 60      # 20 minutes
```

---

## ▶️ Usage

1. **Run the script** on a host that LibreNMS can send alerts to:
    ```bash
    python3 server.py
    ```
2. The script starts an HTTP server listening on **port 5000**:
    ```
    HTTP server listening on 0.0.0.0:5000 for LibreNMS alerts...
    ```
3. **Configure LibreNMS alert transport** to POST alerts to:
    ```
    http://<your-server>:5000/
    ```
4. **When an alert for a device is received:**
    - The device is forced into an unsupervised state.
    - Added to `device_state.json`.
    - A recovery loop is scheduled (20 minutes later).

5. **When the recovery loop runs:**
    - If Dialer 1 is **up**, the device is restored and removed from tracking.
    - If still **down**, it is forced to unsupervised IP again and retried later.

---

## 📂 Files

- `server.py` &rarr; main script (HTTP server, alert handler, recovery manager).
- `device_state.json` &rarr; local file tracking supervised devices (auto-generated).

---

## 🔍 Example Log Output

```yaml
2025-08-18 14:00:01 - ### ALERT RECEIVED ###
2025-08-18 14:00:01 - Device ID: 42
2025-08-18 14:00:01 - Hostname: router1
2025-08-18 14:00:01 - IP: 192.168.1.10
2025-08-18 14:00:01 - Detected Dialer 1 port_id: 123

2025-08-18 14:00:01 - Forcing device down (overwrite_ip=127.0.0.50)...
2025-08-18 14:00:01 - 🕒 Recovery loop scheduled to start at 2025-08-18 14:20:01.

2025-08-18 14:20:01 - 🔁 Starting recovery pass for all tracked devices.
2025-08-18 14:20:01 - Dialer 1 ifOperStatus = down
2025-08-18 14:20:01 - ❌ router1 still not healthy; forcing UNSUPERVISED_IP again.

2025-08-18 14:40:01 - Dialer 1 ifOperStatus = up
2025-08-18 14:40:01 - ✅ router1 recovered (Dialer 1 is UP). Removing from state.
2025-08-18 14:40:01 - 🎉 All devices recovered; recovery loop will pause until next alert.
```

---

## 🔒 Security Notes

- Store `API_TOKEN` and `SSH_PASS` securely (e.g., environment variables, vaults).
- If possible, configure **SSH key-based authentication** instead of passwords.
- If sudo is required for Docker commands, ensure **NOPASSWD** is configured in sudoers.

---

## 📌 Roadmap

- [ ] Configurable supervised interface name (not just "Dialer 1")
- [ ] Exponential backoff for recovery attempts
- [ ] Metrics export (Prometheus/Grafana) for recovery cycles
- [ ] Web dashboard for supervised device state

---

> **Would you like an ASCII flow diagram added to this README to help contributors visualize the workflow?**
