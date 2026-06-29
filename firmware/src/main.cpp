#include <Arduino.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <USB.h>
#include <USBHIDKeyboard.h>
#include <WiFi.h>
#include <WebServer.h>

USBHIDKeyboard Keyboard;
WebServer server(80);
Preferences prefs;

static const int CONFIRM_BUTTON_PIN = 0;
static const size_t MAX_MACRO_BYTES = 2048;
static const uint32_t CONFIRM_WINDOW_MS = 30000;
static const uint16_t DEFAULT_LINE_DELAY_MS = 80;
static const uint16_t DEFAULT_CHAR_DELAY_MS = 20;
static IPAddress AP_IP(172, 0, 0, 1);
static IPAddress AP_GATEWAY(172, 0, 0, 1);
static IPAddress AP_SUBNET(255, 255, 255, 0);
static const char *CLOUD_BASE = "http://31.76.20.227";
static const uint32_t CLOUD_POLL_MS = 2000;

static const char *NOTEPAD_DEMO_SCRIPT =
  "DELAY 1000\n"
  "GUI r\n"
  "DELAY 500\n"
  "STRING notepad\n"
  "DELAY 200\n"
  "ENTER\n"
  "DELAY 1000\n"
  "STRING Hello World\n"
  "ENTER\n"
  "DELAY 200\n"
  "CTRL s\n"
  "DELAY 500\n"
  "STRING test.txt\n"
  "DELAY 200\n"
  "ENTER";

String pendingMacro;
String lastStatus = "Ready";
uint32_t pendingSince = 0;
bool hasPendingMacro = false;
bool apRunning = false;
bool staConnected = false;
String deviceId;
uint32_t lastCloudPoll = 0;

struct ParseError {
  int line;
  String message;
};

static String jsonEscape(const String &value) {
  String out;
  out.reserve(value.length() + 8);
  for (size_t i = 0; i < value.length(); i++) {
    char c = value[i];
    if (c == '"' || c == '\\') {
      out += '\\';
      out += c;
    } else if (c == '\n') {
      out += "\\n";
    } else if (c == '\r') {
      out += "\\r";
    } else {
      out += c;
    }
  }
  return out;
}

static void sendJson(int code, const String &body) {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
  server.send(code, "application/json", body);
}

static String urlDecode(const String &value) {
  String out;
  out.reserve(value.length());
  for (size_t i = 0; i < value.length(); i++) {
    char c = value[i];
    if (c == '+') {
      out += ' ';
    } else if (c == '%' && i + 2 < value.length()) {
      char hex[3] = { value[i + 1], value[i + 2], 0 };
      out += char(strtol(hex, nullptr, 16));
      i += 2;
    } else {
      out += c;
    }
  }
  return out;
}

static void startAp() {
  if (apRunning) return;
  WiFi.softAPConfig(AP_IP, AP_GATEWAY, AP_SUBNET);
  WiFi.softAP(SAFE_MACRO_AP_SSID, SAFE_MACRO_AP_PASS);
  apRunning = true;
}

static void stopAp() {
  if (!apRunning) return;
  WiFi.softAPdisconnect(true);
  apRunning = false;
}

static bool connectSta(const String &ssid, const String &pass, uint32_t timeoutMs = 15000) {
  if (ssid.length() == 0) return false;
  WiFi.begin(ssid.c_str(), pass.c_str());
  uint32_t start = millis();
  while (millis() - start < timeoutMs) {
    if (WiFi.status() == WL_CONNECTED) {
      staConnected = true;
      return true;
    }
    delay(250);
  }
  staConnected = false;
  return false;
}

static String trimCopy(String value) {
  value.trim();
  return value;
}

static bool isDangerousText(const String &text) {
  String lower = text;
  lower.toLowerCase();
  const char *blocked[] = {
    "powershell", "cmd.exe", "terminal", "curl ", "wget ", "http://", "https://",
    "reg add", "del ", "format ", "shutdown", "net user", "sudo ", "bash "
  };
  for (const char *word : blocked) {
    if (lower.indexOf(word) >= 0) {
      return true;
    }
  }
  return false;
}

static bool parseQuotedText(const String &line, String &text) {
  int first = line.indexOf('"');
  int last = line.lastIndexOf('"');
  if (first < 0 || last <= first) {
    return false;
  }
  text = line.substring(first + 1, last);
  return true;
}

static bool isModifierToken(const String &token) {
  return token == "CTRL" || token == "CONTROL" || token == "ALT" || token == "SHIFT" ||
         token == "GUI" || token == "WINDOWS" || token == "WIN" || token == "META" ||
         token == "COMMAND" || token == "OPTION" || token == "RCTRL" ||
         token == "RCONTROL" || token == "RALT" || token == "RSHIFT" ||
         token == "RGUI" || token == "RWINDOWS" || token == "RWIN" ||
         token == "RMETA" || token == "RCOMMAND" || token == "ROPTION";
}

static bool keyTokenSupported(const String &token) {
  if (token.length() == 0) return false;
  if (isModifierToken(token)) return true;
  if (token.length() == 1) return true;
  if (token.length() >= 2 && token[0] == 'F') {
    int n = token.substring(1).toInt();
    if (n >= 1 && n <= 24) return true;
  }
  return token == "ENTER" || token == "RETURN" || token == "TAB" || token == "ESC" ||
         token == "ESCAPE" || token == "SPACE" || token == "BACKSPACE" ||
         token == "DELETE" || token == "DEL" || token == "INSERT" || token == "INS" ||
         token == "HOME" || token == "END" || token == "PAGEUP" || token == "PAGE_UP" ||
         token == "PAGEDOWN" || token == "PAGE_DOWN" || token == "UP" ||
         token == "UPARROW" || token == "UP_ARROW" || token == "DOWN" ||
         token == "DOWNARROW" || token == "DOWN_ARROW" || token == "LEFT" ||
         token == "LEFTARROW" || token == "LEFT_ARROW" || token == "RIGHT" ||
         token == "RIGHTARROW" || token == "RIGHT_ARROW" || token == "CAPSLOCK" ||
         token == "CAPS_LOCK" || token == "PRINTSCREEN" || token == "PRINT_SCREEN" ||
         token == "PRTSC" || token == "SCROLLLOCK" || token == "SCROLL_LOCK" ||
         token == "PAUSE" || token == "BREAK" || token == "NUMLOCK" ||
         token == "NUM_LOCK" || token == "MENU" || token == "APP" ||
         token == "APPLICATION" || token == "KP_SLASH" || token == "KP_ASTERISK" ||
         token == "KP_MINUS" || token == "KP_PLUS" || token == "KP_ENTER" ||
         token == "KP_DOT" || token == "KP_PERIOD" || token == "KP_0" ||
         token == "KP_1" || token == "KP_2" || token == "KP_3" || token == "KP_4" ||
         token == "KP_5" || token == "KP_6" || token == "KP_7" || token == "KP_8" ||
         token == "KP_9";
}

static bool validateKeyCombo(const String &line) {
  String combo = line;
  combo.toUpperCase();
  combo.trim();
  int parts = 0;
  int nonModifiers = 0;
  int start = 0;
  while (start < combo.length()) {
    int space = combo.indexOf(' ', start);
    String token = space < 0 ? combo.substring(start) : combo.substring(start, space);
    token.trim();
    if (token.length() > 0) {
      parts++;
      if (!keyTokenSupported(token)) return false;
      if (!isModifierToken(token)) nonModifiers++;
    }
    if (space < 0) break;
    start = space + 1;
  }
  return parts >= 1 && nonModifiers <= 6;
}

static bool validateMacro(const String &macro, ParseError &error) {
  if (macro.length() == 0 || macro.length() > MAX_MACRO_BYTES) {
    error.line = 0;
    error.message = "Macro must be 1-2048 bytes";
    return false;
  }

  int lineNo = 1;
  int start = 0;
  while (start <= macro.length()) {
    int end = macro.indexOf('\n', start);
    if (end < 0) {
      end = macro.length();
    }
    String line = trimCopy(macro.substring(start, end));
    if (line.length() > 0 && !line.startsWith("#")) {
      String upper = line;
      upper.toUpperCase();

      if (upper.startsWith("REM ")) {
      } else if (upper.startsWith("DEFAULT_DELAY ") || upper.startsWith("DEFAULTDELAY ")) {
        int firstSpace = line.indexOf(' ');
        int ms = line.substring(firstSpace + 1).toInt();
        if (ms < 0 || ms > 5000) {
          error = {lineNo, "DEFAULT_DELAY must be 0-5000 ms"};
          return false;
        }
      } else if (upper.startsWith("DEFAULT_CHAR_DELAY ") || upper.startsWith("DEFAULTCHARDELAY ")) {
        int firstSpace = line.indexOf(' ');
        int ms = line.substring(firstSpace + 1).toInt();
        if (ms < 0 || ms > 1000) {
          error = {lineNo, "DEFAULTCHARDELAY must be 0-1000 ms"};
          return false;
        }
      } else if (upper.startsWith("TYPE ")) {
        String text;
        if (!parseQuotedText(line, text)) {
          error = {lineNo, "TYPE must use quotes"};
          return false;
        }
        if (text.length() > 160 || isDangerousText(text)) {
          error = {lineNo, "TYPE text is too long or blocked"};
          return false;
        }
      } else if (upper.startsWith("STRING ")) {
        String text = line.substring(7);
        if (text.length() > 160 || isDangerousText(text)) {
          error = {lineNo, "STRING text is too long or blocked"};
          return false;
        }
      } else if (upper.startsWith("DELAY ")) {
        int ms = line.substring(6).toInt();
        if (ms < 0 || ms > 5000) {
          error = {lineNo, "DELAY must be 0-5000 ms"};
          return false;
        }
      } else if (upper.startsWith("HOTKEY ")) {
        if (!validateKeyCombo(line.substring(7))) {
          error = {lineNo, "HOTKEY is not supported"};
          return false;
        }
      } else if (upper.startsWith("GUI ") || upper.startsWith("WINDOWS ") ||
                 upper.startsWith("WIN ") || upper.startsWith("COMMAND ")) {
        int firstSpace = line.indexOf(' ');
        if (!validateKeyCombo("GUI " + line.substring(firstSpace + 1))) {
          error = {lineNo, "GUI hotkey is not supported"};
          return false;
        }
      } else if (upper.startsWith("REPEAT ")) {
        int count = line.substring(7).toInt();
        if (count < 1 || count > 100) {
          error = {lineNo, "REPEAT must be 1-100"};
          return false;
        }
      } else if (validateKeyCombo(line)) {
      } else {
        error = {lineNo, "Unknown command"};
        return false;
      }
    }
    if (end == macro.length()) {
      break;
    }
    start = end + 1;
    lineNo++;
  }
  return true;
}

static void pressKeyToken(const String &token) {
  String upper = token;
  upper.toUpperCase();
  upper.trim();

  if (upper == "CTRL" || upper == "CONTROL") Keyboard.press(KEY_LEFT_CTRL);
  else if (upper == "RCTRL" || upper == "RCONTROL") Keyboard.press(KEY_RIGHT_CTRL);
  else if (upper == "ALT" || upper == "OPTION") Keyboard.press(KEY_LEFT_ALT);
  else if (upper == "RALT" || upper == "ROPTION") Keyboard.press(KEY_RIGHT_ALT);
  else if (upper == "SHIFT") Keyboard.press(KEY_LEFT_SHIFT);
  else if (upper == "RSHIFT") Keyboard.press(KEY_RIGHT_SHIFT);
  else if (upper == "GUI" || upper == "WINDOWS" || upper == "WIN" ||
           upper == "META" || upper == "COMMAND") Keyboard.press(KEY_LEFT_GUI);
  else if (upper == "RGUI" || upper == "RWINDOWS" || upper == "RWIN" ||
           upper == "RMETA" || upper == "RCOMMAND") Keyboard.press(KEY_RIGHT_GUI);
  else if (upper == "ENTER" || upper == "RETURN") Keyboard.press(KEY_RETURN);
  else if (upper == "TAB") Keyboard.press(KEY_TAB);
  else if (upper == "ESC" || upper == "ESCAPE") Keyboard.press(KEY_ESC);
  else if (upper == "SPACE") Keyboard.press(' ');
  else if (upper == "BACKSPACE") Keyboard.press(KEY_BACKSPACE);
  else if (upper == "DELETE" || upper == "DEL") Keyboard.press(KEY_DELETE);
  else if (upper == "INSERT" || upper == "INS") Keyboard.press(KEY_INSERT);
  else if (upper == "HOME") Keyboard.press(KEY_HOME);
  else if (upper == "END") Keyboard.press(KEY_END);
  else if (upper == "PAGEUP" || upper == "PAGE_UP") Keyboard.press(KEY_PAGE_UP);
  else if (upper == "PAGEDOWN" || upper == "PAGE_DOWN") Keyboard.press(KEY_PAGE_DOWN);
  else if (upper == "UP" || upper == "UPARROW" || upper == "UP_ARROW") Keyboard.press(KEY_UP_ARROW);
  else if (upper == "DOWN" || upper == "DOWNARROW" || upper == "DOWN_ARROW") Keyboard.press(KEY_DOWN_ARROW);
  else if (upper == "LEFT" || upper == "LEFTARROW" || upper == "LEFT_ARROW") Keyboard.press(KEY_LEFT_ARROW);
  else if (upper == "RIGHT" || upper == "RIGHTARROW" || upper == "RIGHT_ARROW") Keyboard.press(KEY_RIGHT_ARROW);
  else if (upper == "CAPSLOCK" || upper == "CAPS_LOCK") Keyboard.press(KEY_CAPS_LOCK);
  else if (upper == "F1") Keyboard.press(KEY_F1);
  else if (upper == "F2") Keyboard.press(KEY_F2);
  else if (upper == "F3") Keyboard.press(KEY_F3);
  else if (upper == "F4") Keyboard.press(KEY_F4);
  else if (upper == "F5") Keyboard.press(KEY_F5);
  else if (upper == "F6") Keyboard.press(KEY_F6);
  else if (upper == "F7") Keyboard.press(KEY_F7);
  else if (upper == "F8") Keyboard.press(KEY_F8);
  else if (upper == "F9") Keyboard.press(KEY_F9);
  else if (upper == "F10") Keyboard.press(KEY_F10);
  else if (upper == "F11") Keyboard.press(KEY_F11);
  else if (upper == "F12") Keyboard.press(KEY_F12);
  else if (upper == "F13") Keyboard.press(KEY_F13);
  else if (upper == "F14") Keyboard.press(KEY_F14);
  else if (upper == "F15") Keyboard.press(KEY_F15);
  else if (upper == "F16") Keyboard.press(KEY_F16);
  else if (upper == "F17") Keyboard.press(KEY_F17);
  else if (upper == "F18") Keyboard.press(KEY_F18);
  else if (upper == "F19") Keyboard.press(KEY_F19);
  else if (upper == "F20") Keyboard.press(KEY_F20);
  else if (upper == "F21") Keyboard.press(KEY_F21);
  else if (upper == "F22") Keyboard.press(KEY_F22);
  else if (upper == "F23") Keyboard.press(KEY_F23);
  else if (upper == "F24") Keyboard.press(KEY_F24);
  else if (upper == "PRINTSCREEN" || upper == "PRINT_SCREEN" || upper == "PRTSC") Keyboard.pressRaw(0x46);
  else if (upper == "SCROLLLOCK" || upper == "SCROLL_LOCK") Keyboard.pressRaw(0x47);
  else if (upper == "PAUSE" || upper == "BREAK") Keyboard.pressRaw(0x48);
  else if (upper == "NUMLOCK" || upper == "NUM_LOCK") Keyboard.pressRaw(0x53);
  else if (upper == "KP_SLASH") Keyboard.pressRaw(0x54);
  else if (upper == "KP_ASTERISK") Keyboard.pressRaw(0x55);
  else if (upper == "KP_MINUS") Keyboard.pressRaw(0x56);
  else if (upper == "KP_PLUS") Keyboard.pressRaw(0x57);
  else if (upper == "KP_ENTER") Keyboard.pressRaw(0x58);
  else if (upper == "KP_1") Keyboard.pressRaw(0x59);
  else if (upper == "KP_2") Keyboard.pressRaw(0x5A);
  else if (upper == "KP_3") Keyboard.pressRaw(0x5B);
  else if (upper == "KP_4") Keyboard.pressRaw(0x5C);
  else if (upper == "KP_5") Keyboard.pressRaw(0x5D);
  else if (upper == "KP_6") Keyboard.pressRaw(0x5E);
  else if (upper == "KP_7") Keyboard.pressRaw(0x5F);
  else if (upper == "KP_8") Keyboard.pressRaw(0x60);
  else if (upper == "KP_9") Keyboard.pressRaw(0x61);
  else if (upper == "KP_0") Keyboard.pressRaw(0x62);
  else if (upper == "KP_DOT" || upper == "KP_PERIOD") Keyboard.pressRaw(0x63);
  else if (upper == "MENU" || upper == "APP" || upper == "APPLICATION") Keyboard.pressRaw(0x65);
  else if (token.length() == 1 && token[0] >= 'A' && token[0] <= 'Z') Keyboard.press(char(token[0] + 32));
  else if (token.length() == 1) Keyboard.press(token[0]);
}

static void pressKeyCombo(const String &value) {
  String combo = value;
  combo.trim();
  int start = 0;
  while (start < combo.length()) {
    int space = combo.indexOf(' ', start);
    String token = space < 0 ? combo.substring(start) : combo.substring(start, space);
    token.trim();
    if (token.length() > 0) pressKeyToken(token);
    if (space < 0) break;
    start = space + 1;
  }
  delay(120);
  Keyboard.releaseAll();
}

static void typeText(const String &text, uint16_t charDelay) {
  for (size_t i = 0; i < text.length(); i++) {
    Keyboard.write(text[i]);
    delay(charDelay);
  }
}

static bool executeMacroLine(const String &line, int &lineDelay, int &charDelay) {
  String upper = line;
  upper.toUpperCase();

  if (line.length() == 0 || line.startsWith("#") || upper.startsWith("REM ")) {
    return false;
  }
  if (upper.startsWith("DEFAULT_DELAY ") || upper.startsWith("DEFAULTDELAY ")) {
    int firstSpace = line.indexOf(' ');
    lineDelay = line.substring(firstSpace + 1).toInt();
    return false;
  }
  if (upper.startsWith("DEFAULT_CHAR_DELAY ") || upper.startsWith("DEFAULTCHARDELAY ")) {
    int firstSpace = line.indexOf(' ');
    charDelay = line.substring(firstSpace + 1).toInt();
    return false;
  }
  if (upper.startsWith("TYPE ")) {
    String text;
    parseQuotedText(line, text);
    typeText(text, charDelay);
  } else if (upper.startsWith("STRING ")) {
    typeText(line.substring(7), charDelay);
  } else if (upper.startsWith("DELAY ")) {
    delay(line.substring(6).toInt());
  } else if (upper.startsWith("HOTKEY ")) {
    pressKeyCombo(line.substring(7));
  } else if (upper.startsWith("GUI ") || upper.startsWith("WINDOWS ") ||
             upper.startsWith("WIN ") || upper.startsWith("COMMAND ")) {
    int firstSpace = line.indexOf(' ');
    pressKeyCombo("GUI " + line.substring(firstSpace + 1));
  } else {
    pressKeyCombo(line);
  }
  delay(lineDelay);
  return true;
}

static void executeMacro(const String &macro) {
  int lineDelay = DEFAULT_LINE_DELAY_MS;
  int charDelay = DEFAULT_CHAR_DELAY_MS;
  String lastExecutableLine = "";
  int start = 0;
  while (start <= macro.length()) {
    int end = macro.indexOf('\n', start);
    if (end < 0) end = macro.length();
    String line = trimCopy(macro.substring(start, end));
    String upper = line;
    upper.toUpperCase();

    if (upper.startsWith("REPEAT ")) {
      int count = line.substring(7).toInt();
      for (int i = 0; i < count; i++) {
        executeMacroLine(lastExecutableLine, lineDelay, charDelay);
      }
    } else if (executeMacroLine(line, lineDelay, charDelay)) {
      lastExecutableLine = line;
    }

    if (end == macro.length()) break;
    start = end + 1;
  }
}

static void handleOptions() {
  sendJson(200, "{\"ok\":true}");
}

static void handleRoot() {
  server.send(200, "text/html; charset=utf-8", R"HTML(
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Macro Controller</title>
  <style>
    :root { color-scheme: dark; font-family: Arial, sans-serif; }
    body { margin: 0; background: #101214; color: #f2f2f2; }
    main { max-width: 760px; margin: 0 auto; padding: 18px; }
    h1 { font-size: 24px; margin: 8px 0 14px; }
    textarea {
      width: 100%; min-height: 310px; box-sizing: border-box; resize: vertical;
      border: 1px solid #333b43; border-radius: 8px; padding: 12px;
      background: #171b1f; color: #f7f7f7; font: 15px/1.4 monospace;
    }
    button, a.button {
      width: 100%; box-sizing: border-box; display: block; margin-top: 10px;
      border: 0; border-radius: 8px; padding: 14px 16px; text-align: center;
      background: #2f8cff; color: white; font-size: 17px; text-decoration: none;
    }
    a.button { background: #242a31; }
    .row { display: flex; gap: 10px; }
    .row button { flex: 1; }
    #status { min-height: 22px; margin: 12px 0 0; color: #b9c7d5; }
    .hint { color: #9aa8b5; font-size: 13px; margin: 8px 0 14px; }
  </style>
</head>
<body>
<main>
  <h1>Macro Controller</h1>
  <div class="hint">Cloud demo polling works after connecting the dongle to external Wi-Fi.</div>
  <div class="hint">Wi-Fi: DONGL / dongl1234, адрес: 172.0.0.1</div>
  <textarea id="macro" spellcheck="false">DELAY 1000
GUI r
DELAY 500
STRING notepad
DELAY 200
ENTER
DELAY 1000
STRING Hello World
ENTER
DELAY 200
CTRL s
DELAY 500
STRING test.txt
DELAY 200
ENTER</textarea>
  <button id="send">Send macro</button>
  <div class="row">
    <button id="statusBtn" type="button">Status</button>
    <button id="clearBtn" type="button">Clear</button>
  </div>
  <h1>Wi-Fi</h1>
  <button id="scanBtn" type="button">Scan networks</button>
  <select id="ssid" style="width:100%;margin-top:10px;padding:12px;background:#171b1f;color:#f7f7f7;border:1px solid #333b43;border-radius:8px"></select>
  <input id="pass" type="password" placeholder="Password" style="width:100%;box-sizing:border-box;margin-top:10px;padding:12px;background:#171b1f;color:#f7f7f7;border:1px solid #333b43;border-radius:8px">
  <button id="connectBtn" type="button">Connect to Wi-Fi</button>
  <button id="resetWifiBtn" type="button">Reset saved Wi-Fi</button>
  <div id="status">Ready</div>
</main>
<script>
const macro = document.getElementById('macro');
const statusEl = document.getElementById('status');
function setStatus(text) { statusEl.textContent = text; }
document.getElementById('send').onclick = async () => {
  setStatus('Sending...');
  try {
    const res = await fetch('/macro', {
      method: 'POST',
      headers: {'Content-Type': 'text/plain; charset=utf-8'},
      body: macro.value
    });
    const data = await res.json();
    setStatus(data.error || data.message || (res.ok ? 'Done' : 'Rejected'));
  } catch (e) {
    setStatus('Cannot reach dongle');
  }
};
document.getElementById('statusBtn').onclick = async () => {
  try {
    const res = await fetch('/status');
    const data = await res.json();
    setStatus(data.status || 'Ready');
  } catch (e) {
    setStatus('Cannot reach dongle');
  }
};
document.getElementById('clearBtn').onclick = () => {
  macro.value = '';
  setStatus('Cleared');
};
document.getElementById('scanBtn').onclick = async () => {
  setStatus('Scanning...');
  try {
    const res = await fetch('/wifi/scan');
    const data = await res.json();
    const select = document.getElementById('ssid');
    select.innerHTML = '';
    data.networks.forEach(n => {
      const opt = document.createElement('option');
      opt.value = n.ssid;
      opt.textContent = `${n.ssid} (${n.rssi} dBm)`;
      select.appendChild(opt);
    });
    setStatus(`Found ${data.networks.length} networks`);
  } catch (e) {
    setStatus('Scan failed');
  }
};
document.getElementById('connectBtn').onclick = async () => {
  const ssid = document.getElementById('ssid').value;
  const pass = document.getElementById('pass').value;
  setStatus('Connecting...');
  try {
    const res = await fetch('/wifi/connect', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: `ssid=${encodeURIComponent(ssid)}&pass=${encodeURIComponent(pass)}`
    });
    const data = await res.json();
    setStatus(data.message || data.error || 'Done');
  } catch (e) {
    setStatus('Connect failed');
  }
};
document.getElementById('resetWifiBtn').onclick = async () => {
  await fetch('/wifi/reset', {method: 'POST'});
  setStatus('Saved Wi-Fi removed; reboot dongle if needed');
};
</script>
</body>
</html>
)HTML");
}

static void handleStatus() {
  String body = "{\"ok\":true,\"pending\":" + String(hasPendingMacro ? "true" : "false") +
                ",\"status\":\"" + jsonEscape(lastStatus) + "\",\"ssid\":\"" SAFE_MACRO_AP_SSID "\"" +
                ",\"device_id\":\"" + jsonEscape(deviceId) + "\"" +
                ",\"sta_connected\":" + String(WiFi.status() == WL_CONNECTED ? "true" : "false") +
                ",\"sta_ip\":\"" + (WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "") + "\"}";
  sendJson(200, body);
}

static void handleWifiScan() {
  int count = WiFi.scanNetworks();
  String body = "{\"ok\":true,\"networks\":[";
  for (int i = 0; i < count; i++) {
    if (i > 0) body += ",";
    body += "{\"ssid\":\"" + jsonEscape(WiFi.SSID(i)) + "\",\"rssi\":" + String(WiFi.RSSI(i)) + "}";
  }
  body += "]}";
  sendJson(200, body);
}

static void handleWifiConnect() {
  String ssid = server.arg("ssid");
  String pass = server.arg("pass");
  ssid = urlDecode(ssid);
  pass = urlDecode(pass);
  if (ssid.length() == 0) {
    sendJson(400, "{\"ok\":false,\"error\":\"SSID required\"}");
    return;
  }
  lastStatus = "Connecting to external Wi-Fi";
  bool ok = connectSta(ssid, pass);
  if (ok) {
    prefs.begin("wifi", false);
    prefs.putString("ssid", ssid);
    prefs.putString("pass", pass);
    prefs.end();
    stopAp();
    lastStatus = "Connected to Wi-Fi " + WiFi.localIP().toString();
    sendJson(200, "{\"ok\":true,\"message\":\"Connected. Open cloud dashboard from internet.\"}");
  } else {
    startAp();
    lastStatus = "External Wi-Fi failed";
    sendJson(400, "{\"ok\":false,\"error\":\"Could not connect\"}");
  }
}

static void handleWifiReset() {
  prefs.begin("wifi", false);
  prefs.clear();
  prefs.end();
  WiFi.disconnect(true);
  startAp();
  lastStatus = "Saved Wi-Fi removed";
  sendJson(200, "{\"ok\":true,\"message\":\"Saved Wi-Fi removed\"}");
}

static void handleMacro() {
  String body = server.arg("plain");
  ParseError error;
  if (!validateMacro(body, error)) {
    lastStatus = "Rejected line " + String(error.line) + ": " + error.message;
    sendJson(400, "{\"ok\":false,\"error\":\"" + jsonEscape(lastStatus) + "\"}");
    return;
  }
  hasPendingMacro = false;
  pendingMacro = "";
  lastStatus = "Running macro";
  sendJson(200, "{\"ok\":true,\"message\":\"Macro running.\"}");
  executeMacro(body);
  lastStatus = "Done";
}

static void runNotepadDemo() {
  lastStatus = "Running cloud demo";
  executeMacro(NOTEPAD_DEMO_SCRIPT);
  lastStatus = "Cloud demo done";
}

static void cloudPoll() {
  if (WiFi.status() != WL_CONNECTED) return;
  if (millis() - lastCloudPoll < CLOUD_POLL_MS) return;
  lastCloudPoll = millis();

  HTTPClient http;
  String url = String(CLOUD_BASE) + "/api/dongle/poll?device_id=" + deviceId;
  http.begin(url);
  int code = http.GET();
  String body = code > 0 ? http.getString() : "";
  http.end();

  if (code == 200 && body.indexOf("\"command\":\"notepad_demo\"") >= 0) {
    runNotepadDemo();
    HTTPClient result;
    result.begin(String(CLOUD_BASE) + "/api/dongle/result");
    result.addHeader("Content-Type", "application/json");
    String payload = "{\"device_id\":\"" + deviceId + "\",\"status\":\"done\"}";
    result.POST(payload);
    result.end();
  }
}

void setup() {
  pinMode(CONFIRM_BUTTON_PIN, INPUT_PULLUP);
  Serial.begin(115200);

  Keyboard.begin();
  USB.begin();

  deviceId = WiFi.macAddress();
  deviceId.replace(":", "");
  WiFi.mode(WIFI_AP_STA);

  prefs.begin("wifi", true);
  String savedSsid = prefs.getString("ssid", "");
  String savedPass = prefs.getString("pass", "");
  prefs.end();

  if (savedSsid.length() > 0 && connectSta(savedSsid, savedPass)) {
    stopAp();
    lastStatus = "Connected to Wi-Fi " + WiFi.localIP().toString();
  } else {
    startAp();
    lastStatus = "AP started at " + WiFi.softAPIP().toString();
  }

  server.on("/", HTTP_GET, handleRoot);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/wifi/scan", HTTP_GET, handleWifiScan);
  server.on("/wifi/connect", HTTP_POST, handleWifiConnect);
  server.on("/wifi/reset", HTTP_POST, handleWifiReset);
  server.on("/macro", HTTP_OPTIONS, handleOptions);
  server.on("/macro", HTTP_POST, handleMacro);
  server.begin();

  Serial.println(lastStatus);
}

void loop() {
  server.handleClient();
  cloudPoll();

  if (hasPendingMacro && millis() - pendingSince > CONFIRM_WINDOW_MS) {
    hasPendingMacro = false;
    pendingMacro = "";
    lastStatus = "Pending macro expired";
  }

  static bool wasPressed = false;
  bool pressed = digitalRead(CONFIRM_BUTTON_PIN) == LOW;
  if (hasPendingMacro && pressed && !wasPressed) {
    String macro = pendingMacro;
    hasPendingMacro = false;
    pendingMacro = "";
    lastStatus = "Running macro";
    executeMacro(macro);
    lastStatus = "Done";
  }
  wasPressed = pressed;
}
