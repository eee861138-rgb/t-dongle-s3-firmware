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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int VERSION_CODE = 1;
    private static final String VERSION_NAME = "1.0";
    private static final String API_BASE = "http://31.76.20.227";
    private static final String NOTEPAD_DUCKY =
            "DELAY 1000\n" +
            "GUI r\n" +
            "DELAY 500\n" +
            "STRING notepad\n" +
            "DELAY 200\n" +
            "ENTER\n" +
            "DELAY 1000\n" +
            "STRING Hello World";

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

        Button save = makeButton("Save");
        save.setOnClickListener(v -> macroInput.setText("HOTKEY CTRL S\nDELAY 500\nTYPE \"Saved!\"\nENTER"));
        templates.addView(save, new LinearLayout.LayoutParams(0, -2, 1));

        Button tab = makeButton("Tab");
        tab.setOnClickListener(v -> macroInput.setText("HOTKEY ALT TAB"));
        templates.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));

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
}
