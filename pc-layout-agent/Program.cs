using System;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

internal static class Program
{
    private static void Main()
    {
        ServicePointManager.Expect100Continue = false;
        var config = AgentConfig.Load();
        Logger.Configure(config.LogPath);
        Logger.Write("Dongl layout agent started");

        while (true)
        {
            string layout = KeyboardLayoutReader.GetCurrentLayout();
            SendCloud(config, layout);
            SendLocal(config, layout);
            Thread.Sleep(Math.Max(500, config.IntervalMs));
        }
    }

    private static void SendCloud(AgentConfig config, string layout)
    {
        if (string.IsNullOrWhiteSpace(config.CloudUpdateUrl))
        {
            return;
        }

        string json = "{\"layout\":\"" + EscapeJson(layout) + "\",\"device_id\":\"" + EscapeJson(config.DeviceId) + "\"}";
        Post(config.CloudUpdateUrl, "application/json; charset=utf-8", json);
    }

    private static void SendLocal(AgentConfig config, string layout)
    {
        if (!config.SendLocal || string.IsNullOrWhiteSpace(config.LocalUpdateUrl))
        {
            return;
        }

        Post(config.LocalUpdateUrl, "application/x-www-form-urlencoded", "layout=" + Uri.EscapeDataString(layout));
    }

    private static void Post(string url, string contentType, string body)
    {
        try
        {
            byte[] data = Encoding.UTF8.GetBytes(body);
            var request = (HttpWebRequest)WebRequest.Create(url);
            request.Method = "POST";
            request.Timeout = 3000;
            request.ReadWriteTimeout = 3000;
            request.ContentType = contentType;
            request.ContentLength = data.Length;
            using (Stream stream = request.GetRequestStream())
            {
                stream.Write(data, 0, data.Length);
            }
            using (var response = (HttpWebResponse)request.GetResponse())
            {
                if ((int)response.StatusCode >= 400)
                {
                    Logger.Write(url + " failed: " + (int)response.StatusCode);
                }
            }
        }
        catch (Exception ex)
        {
            Logger.Write(url + " error: " + ex.Message);
        }
    }

    private static string EscapeJson(string value)
    {
        return (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
    }
}

internal sealed class AgentConfig
{
    public string CloudUpdateUrl = "http://31.76.20.227/api/layout/update";
    public string LocalUpdateUrl = "http://172.0.0.1/layout";
    public string DeviceId = "default";
    public bool SendLocal = true;
    public int IntervalMs = 2000;
    public string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "DonglLayoutAgent",
        "agent.log");

    public static AgentConfig Load()
    {
        var config = new AgentConfig();
        string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "appsettings.json");
        if (!File.Exists(path))
        {
            return config;
        }

        string json = File.ReadAllText(path, Encoding.UTF8);
        config.CloudUpdateUrl = ReadString(json, "CloudUpdateUrl", config.CloudUpdateUrl);
        config.LocalUpdateUrl = ReadString(json, "LocalUpdateUrl", config.LocalUpdateUrl);
        config.DeviceId = ReadString(json, "DeviceId", config.DeviceId);
        config.SendLocal = ReadBool(json, "SendLocal", config.SendLocal);
        config.IntervalMs = ReadInt(json, "IntervalMs", config.IntervalMs);
        config.LogPath = ReadString(json, "LogPath", config.LogPath);
        return config;
    }

    private static string ReadString(string json, string key, string fallback)
    {
        var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*\"(?<v>(?:\\\\.|[^\"])*)\"");
        return match.Success ? Regex.Unescape(match.Groups["v"].Value) : fallback;
    }

    private static bool ReadBool(string json, string key, bool fallback)
    {
        var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*(?<v>true|false)", RegexOptions.IgnoreCase);
        return match.Success ? string.Equals(match.Groups["v"].Value, "true", StringComparison.OrdinalIgnoreCase) : fallback;
    }

    private static int ReadInt(string json, string key, int fallback)
    {
        var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*(?<v>\\d+)");
        int value;
        return match.Success && int.TryParse(match.Groups["v"].Value, out value) ? value : fallback;
    }
}

internal static class Logger
{
    private static string logPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "DonglLayoutAgent",
        "agent.log");

    public static void Configure(string path)
    {
        if (!string.IsNullOrWhiteSpace(path))
        {
            logPath = path;
        }
    }

    public static void Write(string message)
    {
        try
        {
            string dir = Path.GetDirectoryName(logPath);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }
            File.AppendAllText(logPath, DateTimeOffset.Now.ToString("O") + " " + message + Environment.NewLine);
        }
        catch
        {
        }
    }
}

internal static class KeyboardLayoutReader
{
    private const uint RussianPrimaryLangId = 0x19;
    private const uint EnglishPrimaryLangId = 0x09;
    private const uint PrimaryLangMask = 0x03ff;

    public static string GetCurrentLayout()
    {
        IntPtr window = GetForegroundWindow();
        uint processId;
        uint threadId = window == IntPtr.Zero ? GetCurrentThreadId() : GetWindowThreadProcessId(window, out processId);
        IntPtr hkl = GetKeyboardLayout(threadId);
        uint langId = (uint)hkl.ToInt64() & 0xffff;
        uint primaryLang = langId & PrimaryLangMask;

        if (primaryLang == RussianPrimaryLangId)
        {
            return "RU";
        }
        if (primaryLang == EnglishPrimaryLangId)
        {
            return "ENG";
        }
        return "ENG";
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    [DllImport("user32.dll")]
    private static extern IntPtr GetKeyboardLayout(uint idThread);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
}
