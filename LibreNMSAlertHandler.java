import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

public class LibreNMSAlertHandler {

    // =======================
    // Config (from .env)
    // =======================
    private static String LIBRENMS_URL;
    private static String API_TOKEN;
    private static String UNSUPERVISED_IP;
    private static String STATE_FILE;
    private static String LOG_FILE = "alert_handler.log";
    private static int RECOVERY_INTERVAL_SEC = 60;

    private static FileWriter logWriter;
    // Map: device_id -> Map<key, value>
    private static Map<String, Map<String, String>> state = new ConcurrentHashMap<>();

    // Recovery loop control
    private static final Object recoveryLock = new Object();
    private static volatile boolean recoveryActive = false;

    // =======================
    // Utilities
    // =======================
    private static void initLogger() {
        try {
            logWriter = new FileWriter(LOG_FILE, true);
            log("Logger initialized.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static synchronized void log(String msg) {
        try {
            String timestamp = new Date().toString();
            if (logWriter != null) {
                logWriter.write(timestamp + " - " + msg + "\n");
                logWriter.flush();
            } else {
                System.err.println(timestamp + " - " + msg);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadEnv() throws IOException {
        File envFile = new File(".env");
        if (!envFile.exists()) throw new FileNotFoundException(".env file not found!");
        BufferedReader br = new BufferedReader(new FileReader(envFile));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf('=');
            if (idx == -1) continue;
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            switch (key) {
                case "LIBRENMS_URL": LIBRENMS_URL = value; break;
                case "API_TOKEN": API_TOKEN = value; break;
                case "UNSUPERVISED_IP": UNSUPERVISED_IP = value; break;
                case "STATE_FILE": STATE_FILE = value; break;
                case "LOG_FILE": LOG_FILE = value; break;
                case "RECOVERY_INTERVAL_SEC":
                    try { RECOVERY_INTERVAL_SEC = Integer.parseInt(value); }
                    catch (NumberFormatException ignore) {}
                    break;
            }
        }
        br.close();
    }

    private static void loadState() {
        File f = new File(STATE_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString().trim();
            if (json.isEmpty() || json.equals("{}")) {
                state.clear();
                return;
            }
            // Manual parse: {"id":{"hostname":"a","ip":"b","port_id":"c","added_at":"ts"}, ...}
            state.clear();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1); // remove {}
                // Split on top-level commas (not inside inner objects)
                int braceCount = 0;
                int lastSplit = 0;
                List<String> entries = new ArrayList<>();
                for (int i = 0; i < json.length(); i++) {
                    char ch = json.charAt(i);
                    if (ch == '{') braceCount++;
                    if (ch == '}') braceCount--;
                    if (ch == ',' && braceCount == 0) {
                        entries.add(json.substring(lastSplit, i));
                        lastSplit = i + 1;
                    }
                }
                if (lastSplit < json.length()) entries.add(json.substring(lastSplit));

                for (String entry : entries) {
                    entry = entry.trim();
                    if (entry.isEmpty()) continue;
                    int colIdx = entry.indexOf(':');
                    if (colIdx < 0) continue;
                    String key = unquote(entry.substring(0, colIdx).trim());
                    String val = entry.substring(colIdx + 1).trim();
                    Map<String, String> info = parseFlatJsonObject(val);
                    state.put(key, info);
                }
            }
        } catch (Exception ex) {
            log("⚠️ Failed to load state: " + ex);
        }
    }

    private static void saveState() {
        try (FileWriter fw = new FileWriter(STATE_FILE)) {
            // Write as {"id":{"hostname":"...","ip":"...","port_id":"...","added_at":"..."},...}
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Map<String, String>> e : state.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(e.getKey())).append("\":");
                sb.append(flatJsonObj(e.getValue()));
            }
            sb.append("}");
            fw.write(sb.toString());
        } catch (IOException ex) {
            log("⚠️ Failed to save state: " + ex);
        }
    }

    // Minimal JSON: expects {"k":"v","k2":"v2"}
    private static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> map = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
        String[] parts = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String part : parts) {
            int idx = part.indexOf(':');
            if (idx < 0) continue;
            String k = unquote(part.substring(0, idx).trim());
            String v = unquote(part.substring(idx + 1).trim());
            map.put(k, v);
        }
        return map;
    }

    private static String flatJsonObj(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append("\"").append(escapeJson(e.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) return s.substring(1, s.length() - 1);
        return s;
    }

    // =======================
    // LibreNMS API (manual/minimal JSON parsing)
    // =======================
    private static String libreApi(String method, String endpoint, String data, Map<String, String> params) throws IOException {
        StringBuilder urlBuilder = new StringBuilder(LIBRENMS_URL + endpoint);
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            for (Map.Entry<String, String> e : params.entrySet()) {
                urlBuilder.append(URLEncoder.encode(e.getKey(), "UTF-8"));
                urlBuilder.append("=");
                urlBuilder.append(URLEncoder.encode(e.getValue(), "UTF-8"));
                urlBuilder.append("&");
            }
            urlBuilder.setLength(urlBuilder.length() - 1); // remove trailing &
        }
        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        // PATCH workaround: use POST + X-HTTP-Method-Override
        if ("PATCH".equals(method)) {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            conn.setRequestMethod(method);
        }
        conn.setRequestProperty("X-Auth-Token", API_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoInput(true);
        if (data != null && !"GET".equals(method)) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(data.getBytes(StandardCharsets.UTF_8));
            os.close();
        }

        int status = conn.getResponseCode();
        InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder response = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) response.append(line);
        br.close();

        log("LibreNMS API " + method + " " + url + " -> " + status);
        log("Response: " + response.toString());

        if (status >= 400) throw new IOException("HTTP error: " + status);

        return response.toString();
    }

    // Find port_id for a device and targetIfName, expects JSON {"ports":[{"port_id":...,"device_id":...,"ifName":...}]}
    private static String findPortIdForDialer(String deviceIdOrHost, String targetIfName) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("columns", "port_id,device_id,ifName");
            String resp = libreApi("GET", "/ports/search/ifName/" + targetIfName, null, params);
            int arrIdx = resp.indexOf("[");
            int arrEnd = resp.indexOf("]", arrIdx);
            if (arrIdx < 0 || arrEnd < 0) return null;
            String arr = resp.substring(arrIdx + 1, arrEnd);
            String[] objs = arr.split("\\},\\{");
            for (String obj : objs) {
                String objStr = obj;
                if (!obj.startsWith("{")) objStr = "{" + obj;
                if (!obj.endsWith("}")) objStr = obj + "}";
                Map<String, String> map = parseFlatJsonObject(objStr);
                if (map.get("device_id") != null && map.get("device_id").equals(deviceIdOrHost)) {
                    return map.get("port_id");
                }
            }
        } catch (Exception e) {
            log("⚠️ Failed to lookup port_id: " + e);
        }
        return null;
    }

    // Expects JSON like {"port":{"ifOperStatus":"up",...}} or {"ports":[{"ifOperStatus":"up", ...}]}
    private static String getPortOperStatus(String portId) {
        try {
            String resp = libreApi("GET", "/ports/" + portId, null, null);
            int idx = resp.indexOf("\"ifOperStatus\"");
            if (idx < 0) return null;
            int colon = resp.indexOf(":", idx);
            if (colon < 0) return null;
            int quote1 = resp.indexOf("\"", colon);
            int quote2 = resp.indexOf("\"", quote1 + 1);
            if (quote1 < 0 || quote2 < 0) return null;
            return resp.substring(quote1 + 1, quote2);
        } catch (Exception e) {
            log("⚠️ Failed to get port oper status: " + e);
        }
        return null;
    }

    // Poll device using artisan, after restore (NOT after force down!)
    private static void pollDevice(String deviceIdOrHost) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "php", "/opt/librenms/artisan", "device:poll", deviceIdOrHost
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream())
            );
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = proc.waitFor();
            log("Ran artisan device:poll " + deviceIdOrHost + ", exit=" + exitCode);
            log("Output:\n" + output.toString());
        } catch (Exception e) {
            log("⚠️ Failed to poll device: " + e);
        }
    }

    // No polling after forcing device down
    private static void forceDeviceDown(String deviceIdOrHost) {
        try {
            String patchData = "{\"field\":\"overwrite_ip\",\"data\":\"" + escapeJson(UNSUPERVISED_IP) + "\"}";
            libreApi("PATCH", "/devices/" + deviceIdOrHost, patchData, null);
            libreApi("GET", "/devices/" + deviceIdOrHost + "/discover", null, null);
            // NO pollDevice(deviceIdOrHost) here!
        } catch (Exception e) {
            log("⚠️ Failed to force device down: " + e);
        }
    }

    private static void restoreDeviceIp(String deviceIdOrHost, String originalIp) {
        try {
            String patchData = "{\"field\":\"overwrite_ip\",\"data\":\"" + escapeJson(originalIp) + "\"}";
            libreApi("PATCH", "/devices/" + deviceIdOrHost, patchData, null);
            // DO NOT rediscover or poll here; do it in the recovery loop after this call!
        } catch (Exception e) {
            log("⚠️ Failed to restore device IP: " + e);
        }
    }

    // =======================
    // HTTP Handler
    // =======================
    static class AlertHttpHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String body = sb.toString();

            log("### RAW ALERT PAYLOAD ###");
            log(body);
            log("#########################");

            // Manual JSON parsing for alert payload
            Map<String, String> payload = parseFlatJsonObject(body);

            // Try to handle nested "device" if present
            String deviceId = null, hostname = null, ip = null;

            if (payload.containsKey("device_id")) deviceId = payload.get("device_id");
            if (payload.containsKey("host")) hostname = payload.get("host");
            if (payload.containsKey("ip")) ip = payload.get("ip");

            // If not found, try for device: {...}
            if ((deviceId == null || hostname == null || ip == null) && body.contains("\"device\"")) {
                int dIdx = body.indexOf("\"device\"");
                int brace1 = body.indexOf("{", dIdx);
                int brace2 = body.indexOf("}", brace1);
                if (brace1 > 0 && brace2 > brace1) {
                    String nested = body.substring(brace1, brace2 + 1);
                    Map<String, String> nestedMap = parseFlatJsonObject(nested);
                    if (deviceId == null) deviceId = nestedMap.get("device_id");
                    if (hostname == null) hostname = nestedMap.get("hostname");
                    if (ip == null) {
                        ip = nestedMap.get("ip") != null ? nestedMap.get("ip") : nestedMap.get("overwrite_ip");
                    }
                }
            }

            log("Device ID: " + deviceId);
            log("Hostname:  " + hostname);
            log("IP:        " + ip);

            if (deviceId == null || hostname == null || ip == null) {
                log("⚠️ Missing device_id/hostname/ip in alert; ignoring.");
                exchange.sendResponseHeaders(400, 0);
                OutputStream os = exchange.getResponseBody();
                os.write("Missing required fields".getBytes(StandardCharsets.UTF_8));
                os.close();
                return;
            }

            String portId = findPortIdForDialer(deviceId, "port2");
            log("Detected Dialer 1 port_id: " + portId);

            Map<String, String> info = new HashMap<>();
            info.put("hostname", hostname);
            info.put("ip", ip);
            info.put("port_id", portId != null ? portId : "");
            info.put("added_at", new Date().toString());
            state.put(deviceId, info);
            saveState();

            try {
                forceDeviceDown(deviceId);
            } catch (Exception e) {
                log("⚠️ Failed to force device down initially: " + e);
            }

            // Notify recovery loop
            synchronized (recoveryLock) {
                recoveryActive = true;
                recoveryLock.notify();
            }

            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write("OK".getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }

    // =======================
    // Recovery Loop (sequence: restore IP -> rediscover -> poll -> check port)
    // The loop ends only when ALL tracked ports are UP, not just device status!
    // =======================
    static class RecoveryLoop implements Runnable {
        public void run() {
            while (true) {
                synchronized (recoveryLock) {
                    while (!recoveryActive) {
                        try { recoveryLock.wait(); }
                        catch (InterruptedException e) { log("⚠️ Recovery loop interrupted: " + e); }
                    }
                }

                try { Thread.sleep(RECOVERY_INTERVAL_SEC * 1000); }
                catch (InterruptedException e) { log("⚠️ Recovery sleep interrupted: " + e); }

                log("🔁 Starting recovery pass...");

                boolean changed = false;
                for (String deviceId : new ArrayList<>(state.keySet())) {
                    Map<String, String> info = state.get(deviceId);
                    String hostname = info.getOrDefault("hostname", deviceId);
                    String originalIp = info.get("ip");
                    String portId = info.get("port_id");

                    log("Checking device " + hostname + " (Port: " + portId + ")");

                    try {
                        // 1. Restore original device IP
                        restoreDeviceIp(deviceId, originalIp);

                        // 2. Optional: Rediscover (safe)
                        libreApi("GET", "/devices/" + deviceId + "/discover", null, null);

                        // 3. Poll the device (this updates SNMP status in LibreNMS)
                        pollDevice(deviceId);

                        // 4. Detect portId if missing
                        if (portId == null || portId.isEmpty()) {
                            log("No port_id in state; attempting to re-detect.");
                            portId = findPortIdForDialer(deviceId, "port2");
                            if (portId != null) {
                                info.put("port_id", portId);
                                saveState();
                            }
                        }

                        // 5. Check port status
                        String status = portId != null ? getPortOperStatus(portId) : null;
                        log("Dialer 1 ifOperStatus = " + status);

                        if (status != null && status.equalsIgnoreCase("up")) {
                            log("✅ " + hostname + " recovered (Dialer 1 is UP). Removing from state.");
                            state.remove(deviceId);
                            saveState();
                            changed = true;
                        } else {
                            log("❌ " + hostname + " still not healthy; forcing UNSUPERVISED_IP again.");
                            forceDeviceDown(deviceId); // Will NOT poll after setting down
                        }
                    } catch (Exception e) {
                        log("⚠️ Error during recovery for " + hostname + ": " + e);
                    }
                }

                // Only end recovery loop if ALL tracked ports are up
                if (state.isEmpty()) {
                    log("🎉 All devices recovered; pausing recovery loop until next alert.");
                    synchronized (recoveryLock) { recoveryActive = false; }
                } else if (changed) {
                    log("💾 State updated; remaining devices will be retried next cycle.");
                }
            }
        }
    }

    // =======================
    // Main
    // =======================
    public static void main(String[] args) {
        try {
            loadEnv();
            initLogger();
            loadState();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        new Thread(new RecoveryLoop()).start();

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(5000), 0);
            server.createContext("/", new AlertHttpHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            log("HTTP server listening on 0.0.0.0:5000 for LibreNMS alerts...");
            server.start();
        } catch (Exception e) {
            log("❌ Failed to start HTTP server: " + e);
            e.printStackTrace();
        }
    }
}
