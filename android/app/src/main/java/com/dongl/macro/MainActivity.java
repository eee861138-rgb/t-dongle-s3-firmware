package com.dongl.macro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int VERSION_CODE = 5;
    private static final String VERSION_NAME = "1.4";
    private static final String API_BASE = "http://31.76.20.227";
    private static final String NOTEPAD_DUCKY =
            "DELAY 1000\n" +
            "GUI r\n" +
            "DELAY 500\n" +
            "STRING notepad\n" +
            "DELAY 200\n" +
            "ENTER\n" +
            "DELAY 1000";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            sendTelemetry("/api/heartbeat");
            main.postDelayed(this, 60000);
        }
    };
    private EditText hostInput;
    private EditText ssidInput;
    private EditText wifiPassInput;
    private EditText macroInput;
    private TextView statusText;
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deviceId = getOrCreateDeviceId();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(0xFFF7F3EA);

        TextView title = new TextView(this);
        title.setText("Macro Controller");
        title.setTextSize(24);
        title.setTextColor(0xFF17301C);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setText("Connect Android to Wi-Fi: DONGL / dongl1234");
        statusText.setTextSize(14);
        statusText.setTextColor(0xFF4C5B4F);
        statusText.setPadding(0, 18, 0, 18);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setText("172.0.0.1");
        hostInput.setHint("Dongle IP");
        root.addView(hostInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout templates = new LinearLayout(this);
        templates.setOrientation(LinearLayout.HORIZONTAL);
        templates.setPadding(0, 18, 0, 8);
        root.addView(templates, new LinearLayout.LayoutParams(-1, -2));

        Button hello = makeButton("Notepad");
        hello.setOnClickListener(v -> macroInput.setText(NOTEPAD_DUCKY));
        templates.addView(hello, new LinearLayout.LayoutParams(0, -2, 1));

        Button text = makeButton("Text");
        text.setOnClickListener(v -> macroInput.setText("STRING Hello World\nENTER"));
        templates.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        Button wait = makeButton("Wait");
        wait.setOnClickListener(v -> macroInput.setText("DELAY 1000"));
        templates.addView(wait, new LinearLayout.LayoutParams(0, -2, 1));

        macroInput = new EditText(this);
        macroInput.setMinLines(10);
        macroInput.setGravity(Gravity.TOP | Gravity.START);
        macroInput.setText(NOTEPAD_DUCKY);
        macroInput.setHint("Ducky Script");
        root.addView(macroInput, new LinearLayout.LayoutParams(-1, 0, 1));

        Button send = makeButton("Send macro");
        send.setTextSize(18);
        send.setOnClickListener(this::sendMacro);
        root.addView(send, new LinearLayout.LayoutParams(-1, -2));

        Button cloudDemo = makeButton("Queue cloud macro");
        cloudDemo.setTextSize(18);
        cloudDemo.setOnClickListener(this::runCloudDemo);
        root.addView(cloudDemo, new LinearLayout.LayoutParams(-1, -2));

        TextView wifiTitle = new TextView(this);
        wifiTitle.setText("Wi-Fi");
        wifiTitle.setTextSize(18);
        wifiTitle.setTextColor(0xFF17301C);
        wifiTitle.setPadding(0, 18, 0, 8);
        root.addView(wifiTitle, new LinearLayout.LayoutParams(-1, -2));

        ssidInput = new EditText(this);
        ssidInput.setSingleLine(true);
        ssidInput.setHint("Network name");
        root.addView(ssidInput, new LinearLayout.LayoutParams(-1, -2));

        wifiPassInput = new EditText(this);
        wifiPassInput.setSingleLine(true);
        wifiPassInput.setHint("Password");
        root.addView(wifiPassInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout wifiButtons = new LinearLayout(this);
        wifiButtons.setOrientation(LinearLayout.HORIZONTAL);
        wifiButtons.setPadding(0, 8, 0, 0);
        root.addView(wifiButtons, new LinearLayout.LayoutParams(-1, -2));

        Button scanWifi = makeButton("Scan Wi-Fi");
        scanWifi.setOnClickListener(this::scanWifi);
        wifiButtons.addView(scanWifi, new LinearLayout.LayoutParams(0, -2, 1));

        Button connectWifi = makeButton("Connect");
        connectWifi.setOnClickListener(this::connectWifi);
        wifiButtons.addView(connectWifi, new LinearLayout.LayoutParams(0, -2, 1));

        Button resetWifi = makeButton("Reset");
        resetWifi.setOnClickListener(this::resetWifi);
        wifiButtons.addView(resetWifi, new LinearLayout.LayoutParams(0, -2, 1));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        sendTelemetry("/api/register");
        checkForUpdates();
        main.postDelayed(heartbeatRunnable, 60000);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(heartbeatRunnable);
        super.onDestroy();
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private String telemetryBody() {
        return "{\"device_id\":\"" + deviceId + "\",\"version_code\":" + VERSION_CODE +
                ",\"version_name\":\"" + VERSION_NAME + "\"}";
    }

    private void sendTelemetry(String path) {
        executor.execute(() -> {
            try {
                URL url = new URL(API_BASE + path);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = telemetryBody().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                connection.getResponseCode();
                connection.disconnect();
            } catch (Exception ignored) {
            }
        });
    }

    private void checkForUpdates() {
        executor.execute(() -> {
            try {
                URL url = new URL(API_BASE + "/api/version");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    String body = readBody(connection.getInputStream());
                    int latest = intJson(body, "version_code");
                    String apkUrl = stringJson(body, "apk_url");
                    String notes = stringJson(body, "release_notes");
                    if (latest > VERSION_CODE && apkUrl.length() > 0) {
                        main.post(() -> showUpdateDialog(apkUrl, notes));
                    }
                }
                connection.disconnect();
            } catch (Exception ignored) {
            }
        });
    }

    private int intJson(String body, String key) {
        String marker = "\"" + key + "\":";
        int start = body.indexOf(marker);
        if (start < 0) return 0;
        start += marker.length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String readBody(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private String stringJson(String body, String key) {
        String marker = "\"" + key + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = body.indexOf('"', start);
        if (end < 0) return "";
        return body.substring(start, end).replace("\\/", "/");
    }

    private void showUpdateDialog(String apkUrl, String notes) {
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage(notes.length() > 0 ? notes : "A new version is available.")
                .setPositiveButton("Download", (dialog, which) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))))
                .setNegativeButton("Later", null)
                .show();
    }

    private void sendMacro(View view) {
        String host = hostInput.getText().toString().trim();
        String macro = macroInput.getText().toString();
        statusText.setText("Sending...");

        executor.execute(() -> {
            try {
                URL url = new URL("http://" + host + "/macro");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                byte[] bytes = macro.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int code = connection.getResponseCode();
                main.post(() -> {
                    if (code >= 200 && code < 300) {
                        statusText.setText("Macro running.");
                    } else {
                        statusText.setText("Rejected by dongle. Check allowed commands.");
                    }
                });
                connection.disconnect();
            } catch (Exception ex) {
                main.post(() -> statusText.setText("Cannot reach dongle. Join Wi-Fi DONGL first."));
            }
        });
    }

    private void runCloudDemo(View view) {
        String macro = macroInput.getText().toString();
        statusText.setText("Queuing cloud macro...");
        executor.execute(() -> {
            try {
                URL url = new URL(API_BASE + "/api/macro/run");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String payload = "{\"macro\":\"" + jsonEscape(macro) + "\"}";
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int code = connection.getResponseCode();
                String body = code >= 200 && code < 300 ? readBody(connection.getInputStream()) : "";
                main.post(() -> {
                    if (code >= 200 && code < 300) {
                        String device = stringJson(body, "device_id");
                        statusText.setText(device.length() > 0
                                ? "Cloud macro queued for " + device + "."
                                : "Cloud macro queued. Dongle must be online.");
                    } else {
                        statusText.setText("Cloud macro rejected. Check allowed commands.");
                    }
                });
                connection.disconnect();
            } catch (Exception ex) {
                main.post(() -> statusText.setText("Cannot reach cloud server."));
            }
        });
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private String dongleBaseUrl() {
        String host = hostInput.getText().toString().trim();
        if (host.length() == 0) host = "172.0.0.1";
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        return "http://" + host;
    }

    private void scanWifi(View view) {
        statusText.setText("Scanning Wi-Fi...");
        executor.execute(() -> {
            try {
                URL url = new URL(dongleBaseUrl() + "/wifi/scan");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(10000);
                int code = connection.getResponseCode();
                String body = code >= 200 && code < 300 ? readBody(connection.getInputStream()) : "";
                connection.disconnect();
                if (code < 200 || code >= 300) {
                    main.post(() -> statusText.setText("Wi-Fi scan failed."));
                    return;
                }
                ArrayList<String> names = parseWifiNames(body);
                main.post(() -> showWifiList(names));
            } catch (Exception ex) {
                main.post(() -> statusText.setText("Cannot scan. Connect phone to DONGL first."));
            }
        });
    }

    private ArrayList<String> parseWifiNames(String body) throws Exception {
        ArrayList<String> names = new ArrayList<>();
        JSONObject root = new JSONObject(body);
        JSONArray networks = root.optJSONArray("networks");
        if (networks == null) return names;
        for (int i = 0; i < networks.length(); i++) {
            JSONObject item = networks.optJSONObject(i);
            if (item == null) continue;
            String ssid = item.optString("ssid", "");
            if (ssid.length() > 0 && !names.contains(ssid)) {
                int rssi = item.optInt("rssi", 0);
                names.add(rssi == 0 ? ssid : ssid + " (" + rssi + " dBm)");
            }
        }
        return names;
    }

    private void showWifiList(ArrayList<String> names) {
        if (names.isEmpty()) {
            statusText.setText("No Wi-Fi networks found.");
            return;
        }
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Choose Wi-Fi")
                .setItems(items, (dialog, which) -> {
                    String selected = items[which].replaceFirst(" \\(-?\\d+ dBm\\)$", "");
                    ssidInput.setText(selected);
                    statusText.setText("Selected " + selected + ". Enter password and tap Connect.");
                })
                .show();
        statusText.setText("Found " + names.size() + " networks.");
    }

    private void connectWifi(View view) {
        String ssid = ssidInput.getText().toString().trim();
        String pass = wifiPassInput.getText().toString();
        if (ssid.length() == 0) {
            statusText.setText("Enter or scan Wi-Fi name first.");
            return;
        }
        statusText.setText("Connecting dongle to Wi-Fi...");
        executor.execute(() -> {
            try {
                String body = "ssid=" + URLEncoder.encode(ssid, "UTF-8") +
                        "&pass=" + URLEncoder.encode(pass, "UTF-8");
                URL url = new URL(dongleBaseUrl() + "/wifi/connect");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(15000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int code = connection.getResponseCode();
                String response = code >= 200 && code < 300 ? readBody(connection.getInputStream()) : "";
                connection.disconnect();
                main.post(() -> {
                    if (code >= 200 && code < 300) {
                        String ip = stringJson(response, "ip");
                        if (ip.length() > 0) hostInput.setText(ip);
                        statusText.setText(ip.length() > 0
                                ? "Dongle connected. New IP: " + ip
                                : "Dongle connected. Cloud control should work now.");
                    } else {
                        statusText.setText("Wi-Fi connect failed. Check password.");
                    }
                });
            } catch (Exception ex) {
                main.post(() -> statusText.setText("Cannot connect Wi-Fi. Stay on DONGL during setup."));
            }
        });
    }

    private void resetWifi(View view) {
        statusText.setText("Resetting dongle Wi-Fi...");
        executor.execute(() -> {
            try {
                URL url = new URL(dongleBaseUrl() + "/wifi/reset");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                connection.setRequestMethod("POST");
                int code = connection.getResponseCode();
                connection.disconnect();
                main.post(() -> statusText.setText(code >= 200 && code < 300
                        ? "Wi-Fi reset. Reconnect phone to DONGL."
                        : "Wi-Fi reset failed."));
            } catch (Exception ex) {
                main.post(() -> statusText.setText("Cannot reach dongle for reset."));
            }
        });
    }
}
