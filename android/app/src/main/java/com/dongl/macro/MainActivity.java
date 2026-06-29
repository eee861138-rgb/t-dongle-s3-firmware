package com.dongl.macro;

import android.app.Activity;
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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
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
    private EditText hostInput;
    private EditText macroInput;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
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
