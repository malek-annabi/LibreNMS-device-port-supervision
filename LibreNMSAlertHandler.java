import java.io.*;
import java.net.*;
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
    private static String LOG_FILE;
    private static int RECOVERY_INTERVAL_SEC = 60;

    private static FileWriter logWriter;
    private static Map<String, Map<String, String>> state = new ConcurrentHashMap<>();
    
    // Recovery loop control
    private static final Object recoveryLock = new Object();
    private static volatile boolean recoveryActive = false;

    // =======================
    // Utilities
    // =======================
    private static void initLogger() {
        try {
            File logFile = new File(LOG_FILE);
            if (logFile.getParentFile() != null) {
                logFile.getParentFile().mkdirs();
            }
            logWriter = new FileWriter(logFile, true);
            log("Logger initialized. Writing logs to " + LOG_FILE);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("❌ Failed to initialize log file: " + LOG_FILE);
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
            }
        }
        br.close();
    }

    private static void loadState() {
        File f = new File(STATE_FILE);
        if (!f.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();
            // Minimal JSON parsing
            if (!json.isEmpty()) {
                state.clear();
                String[] entries = json.replaceAll("[{}\"]", "").split("},");
                for (String e : entries) {
                    String[] kv = e.split(":", 2);
                    if (kv.length < 2) continue;
                    String deviceId = kv[0].trim();
                    Map<String, String> m = new HashMap<>();
                    m.put("hostname", "dummy"); // Placeholder
                    state.put(deviceId, m);
                }
            }
        } catch (IOException ex) {
            log("⚠️ Failed to load state: " + ex);
        }
    }

    private static void saveState() {
        try (FileWriter fw = new FileWriter(STATE_FILE)) {
            fw.write("{}"); // placeholder
        } catch (IOException ex) {
            log("⚠️ Failed to save state: " + ex);
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
                    new InputStreamReader(exchange.getRequestBody(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String body = sb.toString();

            log("### RAW ALERT PAYLOAD ###");
            log(body);
            log("#########################");

            Map<String, String> payload = new HashMap<>();
            body = body.replaceAll("[{}\"]", "");
            for (String item : body.split(",")) {
                String[] kv = item.split(":", 2);
                if (kv.length == 2) payload.put(kv[0].trim(), kv[1].trim());
            }

            String deviceId = payload.get("device_id");
            String hostname = payload.get("host");
            String ip = payload.get("ip");

            log("Device ID: " + deviceId);
            log("Hostname:  " + hostname);
            log("IP:        " + ip);

            if (deviceId == null || hostname == null || ip == null) {
                log("⚠️ Missing device_id/hostname/ip in alert; ignoring.");
                exchange.sendResponseHeaders(400, 0);
                OutputStream os = exchange.getResponseBody();
                os.write("Missing required fields".getBytes("UTF-8"));
                os.close();
                return;
            }

            Map<String, String> info = new HashMap<>();
            info.put("hostname", hostname);
            info.put("ip", ip);
            info.put("port_id", "Dialer 1");
            info.put("added_at", new Date().toString());
            state.put(deviceId, info);
            saveState();

            log("⚠️ Force device down for " + hostname);

            // Notify recovery loop
            synchronized (recoveryLock) {
                recoveryActive = true;
                recoveryLock.notify();
            }

            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write("OK".getBytes("UTF-8"));
            os.close();
        }
    }

    // =======================
    // Recovery Loop
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

                for (String deviceId : new ArrayList<>(state.keySet())) {
                    Map<String, String> info = state.get(deviceId);
                    String hostname = info.get("hostname");
                    String ip = info.get("ip");
                    String portId = info.get("port_id");

                    log("Checking device " + hostname + " (Port: " + portId + ")");
                    log("✅ Device " + hostname + " recovered simulation. Removing from state.");
                    state.remove(deviceId);
                    saveState();
                }

                if (state.isEmpty()) {
                    log("🎉 All devices recovered; pausing recovery loop until next alert.");
                    synchronized (recoveryLock) { recoveryActive = false; }
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
