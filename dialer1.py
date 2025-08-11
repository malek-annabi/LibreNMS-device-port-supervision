from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import requests
import time
import threading
import os
import paramiko

LIBRENMS_URL = "http://192.168.150.135:8000/api/v0"
API_TOKEN = "cae7c5a4889e5b19335febc4c77d99d9"
UNSUPERVISED_IP = "127.0.0.50"
STATE_FILE = "device_state.json"

# SSH credentials for LibreNMS server (update accordingly)
SSH_HOST = "192.168.150.135"
SSH_USER = "librenms"
SSH_PASS = "librenms"  # consider securing this!

def load_state():
    if os.path.exists(STATE_FILE):
        try:
            if os.path.getsize(STATE_FILE) == 0:
                return {}  # empty file
            with open(STATE_FILE, "r") as f:
                return json.load(f)
        except json.JSONDecodeError:
            print("⚠️ Warning: state file is corrupted or empty, resetting.")
            return {}
    return {}

def save_state(state):
    tmp_file = STATE_FILE + ".tmp"
    with open(tmp_file, "w") as f:
        json.dump(state, f, indent=4)
    os.replace(tmp_file, STATE_FILE)

def libre_api(method, endpoint, data=None):
    headers = {
        "X-Auth-Token": API_TOKEN,
        "Content-Type": "application/json"
    }
    url = f"{LIBRENMS_URL}{endpoint}"

    print(f"\n--- LibreNMS API Request ---")
    print(f"METHOD: {method}")
    print(f"URL:    {url}")
    print(f"DATA:   {json.dumps(data, indent=4) if data else None}")

    r = requests.request(method, url, headers=headers, json=data)

    print(f"--- LibreNMS API Response ---")
    print(f"STATUS: {r.status_code}")
    try:
        print(f"BODY:   {json.dumps(r.json(), indent=4)}")
    except:
        print(f"BODY:   {r.text}")
    print("-----------------------------\n")

    r.raise_for_status()
    if r.text:
        return r.json()
    return None

def ssh_poll_device(device):
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        ssh.connect(hostname=SSH_HOST, username=SSH_USER, password=SSH_PASS)
        cmd = f"docker exec librenms php /opt/librenms/artisan device:poll {device}"
        print(f"Running SSH poll command: {cmd}")
        stdin, stdout, stderr = ssh.exec_command(cmd)
        out = stdout.read().decode()
        err = stderr.read().decode()
        print("SSH STDOUT:", out)
        print("SSH STDERR:", err)
    except Exception as e:
        print(f"⚠️ SSH polling failed: {e}")
    finally:
        ssh.close()

class AlertHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        post_data = self.rfile.read(content_length)

        print("\n### RAW ALERT PAYLOAD ###")
        print(post_data.decode(errors="replace"))
        print("#########################\n")

        try:
            payload = json.loads(post_data)
        except json.JSONDecodeError as e:
            print(f"⚠️ JSON decode error: {e}")
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"Invalid JSON")
            return

        print(f"\n### ALERT RECEIVED ###")
        print(json.dumps(payload, indent=4))

        device_id = payload.get("device_id") or payload.get("device", {}).get("device_id")
        hostname = payload.get("host") or payload.get("device", {}).get("hostname")
        ip = payload.get("ip") or payload.get("device", {}).get("ip") or payload.get("device", {}).get("overwrite_ip")

        print(f"Device ID: {device_id}")
        print(f"Hostname:  {hostname}")
        print(f"IP:        {ip}")

        if not all([device_id, hostname, ip]):
            print("⚠️ Missing one or more required fields in alert payload. Ignoring.")
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"Missing required fields")
            return

        state = load_state()
        state[str(device_id)] = {"hostname": hostname, "ip": ip}
        save_state(state)

        # Force device to appear down by overwriting IP
        libre_api(
            "PATCH",
            f"/devices/{hostname}",
            {"field": "overwrite_ip", "data": UNSUPERVISED_IP}
        )

        # Immediately trigger rediscovery to mark device as down
        libre_api(
            "GET",
            f"/devices/{hostname}/discover"
        )

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"OK")

def recovery_loop():
    while True:
        time.sleep(300)
        state = load_state()
        for device_id, info in list(state.items()):
            hostname = info["hostname"]
            original_ip = info["ip"]
            print(f"\n--- Recovery Check for {hostname} (ID: {device_id}) ---")

            try:
                # Restore original IP
                libre_api(
                    "PATCH",
                    f"/devices/{hostname}",
                    {"field": "overwrite_ip", "data": original_ip}
                )

                # Trigger rediscovery to refresh device status
                libre_api(
                    "GET",
                    f"/devices/{hostname}/discover"
                )

                # Poll device via SSH instead of API
                ssh_poll_device(hostname)

                # Fetch device info to check status
                device_info = libre_api("GET", f"/devices/{hostname}")
                status = device_info.get("status") if device_info else None

                if status == "1":  # Device is UP
                    print(f"✅ Device {hostname} is back online. Removing from state list.")
                    del state[device_id]
                    save_state(state)

            except requests.HTTPError as e:
                print(f"⚠️ HTTP error during recovery for device {hostname}: {e}")
            except Exception as e:
                print(f"⚠️ Unexpected error during recovery for device {hostname}: {e}")

if __name__ == "__main__":
    threading.Thread(target=recovery_loop, daemon=True).start()
    server = HTTPServer(("0.0.0.0", 5000), AlertHandler)
    print("Listening on port 5000 for LibreNMS alerts...")
    server.serve_forever()
