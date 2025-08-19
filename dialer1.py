from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import requests
import time
import threading
import os
import paramiko
from datetime import datetime, timedelta

# =======================
# Config
# =======================
LIBRENMS_URL   = "http://192.168.150.136/api/v0"
API_TOKEN      = "4e66b5ae770fa19a0a0b846e18245341"
UNSUPERVISED_IP = "127.0.0.50"
STATE_FILE     = "device_state.json"

# SSH to the LibreNMS host
SSH_HOST = "192.168.150.136"
SSH_USER = "test"
SSH_PASS = "test"  # secure this (env var / file) in production

# If your user still needs sudo for docker, set this True
SUDO_FOR_DOCKER = False
DOCKER_CONTAINER = "librenms"

RECOVERY_INTERVAL_SEC = 60  # 20 minutes

# =======================
# Helpers
# =======================
def log(msg):
    print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - {msg}")

def load_state():
    if os.path.exists(STATE_FILE):
        try:
            if os.path.getsize(STATE_FILE) == 0:
                return {}
            with open(STATE_FILE, "r") as f:
                return json.load(f)
        except json.JSONDecodeError:
            log("⚠️ Warning: state file unreadable; resetting.")
            return {}
    return {}

def save_state(state):
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(state, f, indent=2)
    os.replace(tmp, STATE_FILE)

def libre_api(method, endpoint, data=None, params=None):
    headers = {"X-Auth-Token": API_TOKEN, "Content-Type": "application/json"}
    url = f"{LIBRENMS_URL}{endpoint}"

    log(f"\n--- LibreNMS API Request ---")
    log(f"METHOD: {method}")
    log(f"URL:    {url}")
    if params:
        log(f"PARAMS: {params}")
    log(f"DATA:   {json.dumps(data, indent=2) if data else None}")

    r = requests.request(method, url, headers=headers, json=data, params=params)

    log(f"--- LibreNMS API Response ---")
    log(f"STATUS: {r.status_code}")
    try:
        log(f"BODY:   {json.dumps(r.json(), indent=2)}")
    except Exception:
        log(f"BODY:   {r.text}")
    log("-----------------------------\n")

    r.raise_for_status()
    if r.text:
        try:
            return r.json()
        except ValueError:
            return None
    return None

def ssh_poll_device(host_or_id):
    cmd = f"php /opt/librenms/artisan device:poll {host_or_id}"
    full_cmd = cmd

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        ssh.connect(hostname=SSH_HOST, username=SSH_USER, password=SSH_PASS)
        log(f"Running SSH poll command: {full_cmd}")
        stdin, stdout, stderr = ssh.exec_command(full_cmd)
        out = stdout.read().decode()
        err = stderr.read().decode()
        log(f"SSH STDOUT:\n{out}")
        if err.strip():
            log(f"SSH STDERR:\n{err}")
    except Exception as e:
        log(f"⚠️ SSH polling failed: {e}")
    finally:
        ssh.close()

def find_port_id_for_dialer(device_id_or_host, target_ifname="port2"):
    """
    Query LibreNMS using the ports search API:
    GET /api/v0/ports/search/ifName/:search
    Returns the first matching port_id for the given device.
    """
    try:
        resp = libre_api("GET", f"/ports/search/ifName/{target_ifname}",
                         params={"columns": "port_id,device_id,ifName"})
        ports = resp.get("ports", []) if resp else []

        for p in ports:
            if str(p.get("device_id")) == str(device_id_or_host):
                return str(p.get("port_id"))
    except Exception as e:
        log(f"⚠️ Failed to lookup port_id via search API for {device_id_or_host}: {e}")
    return None

def get_port_oper_status(port_id):
    """
    GET /api/v0/ports/:portid → read ifOperStatus
    """
    resp = libre_api("GET", f"/ports/{port_id}")
    if not resp:
        return None
    port_list = resp.get("port") or resp.get("ports") or []
    if isinstance(port_list, dict):
        port_list = [port_list]
    if not port_list:
        return None
    port = port_list[0]
    return port.get("ifOperStatus")

def force_device_down(device_id_or_host):
    libre_api("PATCH", f"/devices/{device_id_or_host}",
              {"field": "overwrite_ip", "data": UNSUPERVISED_IP})
    libre_api("GET", f"/devices/{device_id_or_host}/discover")

def restore_device_ip(device_id_or_host, original_ip):
    libre_api("PATCH", f"/devices/{device_id_or_host}",
              {"field": "overwrite_ip", "data": original_ip})
    libre_api("GET", f"/devices/{device_id_or_host}/discover")

# =======================
# Recovery Manager
# =======================
class RecoveryManager:
    def __init__(self):
        self.lock = threading.Lock()
        self.cv = threading.Condition(self.lock)
        self.running = False
        self.next_run_at = None
        self.thread = threading.Thread(target=self._loop, daemon=True)

    def start_if_needed(self, delay_sec):
        with self.lock:
            now = datetime.now()
            if not self.running:
                self.next_run_at = now + timedelta(seconds=delay_sec)
                self.running = True
                log(f"🕒 Recovery loop scheduled to start at {self.next_run_at}.")
                self.thread.start()
            else:
                if self.next_run_at is None or self.next_run_at > now + timedelta(seconds=delay_sec):
                    self.next_run_at = now + timedelta(seconds=delay_sec)
                    log(f"🕒 Recovery loop next run adjusted to {self.next_run_at}.")
            self.cv.notify_all()

    def _loop(self):
        while True:
            with self.lock:
                state = load_state()
                if not state:
                    log("🛌 State file empty — pausing recovery loop until next alert.")
                    self.running = False
                    self.next_run_at = None
                    self.cv.wait()
                    continue

                if not self.next_run_at:
                    self.next_run_at = datetime.now() + timedelta(seconds=RECOVERY_INTERVAL_SEC)

                now = datetime.now()
                wait_s = (self.next_run_at - now).total_seconds()
                if wait_s > 0:
                    self.cv.wait(timeout=wait_s)

            self._do_recovery_pass()

            with self.lock:
                self.next_run_at = datetime.now() + timedelta(seconds=RECOVERY_INTERVAL_SEC)

    def _do_recovery_pass(self):
        state = load_state()
        if not state:
            return

        log("🔁 Starting recovery pass for all tracked devices.")
        changed = False

        for device_id, info in list(state.items()):
            hostname = info.get("hostname")
            original_ip = info.get("ip")
            port_id = info.get("port_id")

            log(f"\n--- Recovery Check for {hostname} (ID: {device_id}, PortID: {port_id}) ---")

            try:
                restore_device_ip(device_id, original_ip)
                ssh_poll_device(device_id)

                if not port_id:
                    log("ℹ️ No port_id in state; attempting to re-detect.")
                    port_id = find_port_id_for_dialer(device_id)
                    if port_id:
                        state[device_id]["port_id"] = port_id
                        save_state(state)

                status = get_port_oper_status(port_id) if port_id else None
                log(f"Dialer 1 ifOperStatus = {status}")

                if status and status.lower() == "up":
                    log(f"✅ {hostname} recovered (Dialer 1 is UP). Removing from state.")
                    del state[device_id]
                    save_state(state)
                    changed = True
                else:
                    log(f"❌ {hostname} still not healthy; forcing UNSUPERVISED_IP again.")
                    force_device_down(device_id)

            except requests.HTTPError as e:
                log(f"⚠️ HTTP error during recovery for {hostname}: {e}")
            except Exception as e:
                log(f"⚠️ Unexpected error during recovery for {hostname}: {e}")

        if not load_state():
            log("🎉 All devices recovered; recovery loop will pause until next alert.")
        elif changed:
            log("💾 State updated; remaining devices will be retried next cycle.")

RECOVERY = RecoveryManager()

# =======================
# HTTP Handler
# =======================
class AlertHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(content_length)

        log("\n### RAW ALERT PAYLOAD ###")
        log(raw.decode(errors="replace"))
        log("#########################\n")

        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as e:
            log(f"⚠️ JSON decode error: {e}")
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"Invalid JSON")
            return

        log("\n### ALERT RECEIVED ###")
        log(json.dumps(payload, indent=2))

        device_id = payload.get("device_id") or payload.get("device", {}).get("device_id")
        hostname  = payload.get("host")      or payload.get("device", {}).get("hostname")
        ip        = payload.get("ip")        or payload.get("device", {}).get("ip") or payload.get("device", {}).get("overwrite_ip")

        log(f"Device ID: {device_id}")
        log(f"Hostname:  {hostname}")
        log(f"IP:        {ip}")

        if not all([device_id, hostname, ip]):
            log("⚠️ Missing device_id/hostname/ip in alert; ignoring.")
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"Missing required fields")
            return

        port_id = find_port_id_for_dialer(device_id, "port2")
        log(f"Detected Dialer 1 port_id: {port_id}")

        state = load_state()
        state[str(device_id)] = {
            "hostname": hostname,
            "ip": ip,
            "port_id": port_id,
            "added_at": datetime.now().isoformat()
        }
        save_state(state)

        try:
            force_device_down(device_id)
        except Exception as e:
            log(f"⚠️ Failed to force device down initially: {e}")

        RECOVERY.start_if_needed(delay_sec=RECOVERY_INTERVAL_SEC)

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"OK")

# =======================
# Main
# =======================
if __name__ == "__main__":
    log("HTTP server listening on 0.0.0.0:5000 for LibreNMS alerts...")
    server = HTTPServer(("0.0.0.0", 5000), AlertHandler)
    server.serve_forever()
