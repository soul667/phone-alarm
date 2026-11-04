package com.axgu.phonealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/** 常驻前台服务：承载内嵌 HTTP API（agent 入口），并在开机/重启后恢复闹钟调度。 */
public class ApiService extends Service {

    public static final int PORT = 8787;
    public static volatile boolean running = false;
    public static volatile String lastError = "";

    private static HttpServer http;

    /** 主页状态卡展示用：本进程累计处理的 HTTP 请求数。 */
    public static int servedCount() {
        return http == null ? 0 : http.requestsServed;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();
        try {
            http = new HttpServer(PORT, this::route);
            http.start();
            lastError = "";
        } catch (Exception e) {
            lastError = "HTTP 启动失败: " + e;
        }
        running = true;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (http != null) http.stop();
        super.onDestroy();
    }

    /** 若服务未跑则拉起（兼容各系统版本的前台服务启动方式）。 */
    public static void ensure(Context c) {
        if (running) return;
        Intent it = new Intent(c, ApiService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
        else c.startService(it);
    }

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("api", "闹钟 API 服务",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("供局域网内 agent 调用的闹钟接口");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "api")
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("手机闹钟 API 运行中")
                .setContentText("http://" + lanIps().split(" / ")[0] + ":" + PORT)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            // targetSdk 34 起 FGS 必须带类型；manifest 已声明 specialUse
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    /** 枚举接口取站点内 IPv4：按接口名优先级排序（wlan 最优），排除 VPN/蜂窝/共享等虚拟口。
     *  返回 "ip1 / ip2"（最优在前）；无可用地址返回 "0.0.0.0"。 */
    public static String lanIps() {
        java.util.List<String[]> found = new java.util.ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nif.getName().toLowerCase();
                if (!nif.isUp() || nif.isLoopback()) continue;
                boolean virt = name.startsWith("tun") || name.startsWith("vgate")
                        || name.startsWith("rmnet") || name.startsWith("ppp")
                        || name.startsWith("swlan") || name.contains("dummy");
                int pri = virt ? 2 : (name.startsWith("wlan") ? 0 : 1);
                for (java.net.InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr.isSiteLocalAddress() && addr.getAddress().length == 4) {
                        found.add(new String[]{pri + "_" + name, addr.getHostAddress()});
                    }
                }
            }
        } catch (Exception ignored) { }
        if (found.isEmpty()) return "0.0.0.0";
        found.sort((a, b) -> a[0].compareTo(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] f : found) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(f[1]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 路由

    private HttpServer.Response route(HttpServer.Request req) throws Exception {
        String p = req.path;
        if (!p.startsWith("/api/")) {
            return HttpServer.Response.json(404, err("not found"));
        }

        if (p.equals("/api/ping")) { // 免 token，仅回基本握手信息，便于排障
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("app", "phone-alarm");
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("alarm_count", AlarmStore.load(this).size());
            o.put("now", System.currentTimeMillis());
            return HttpServer.Response.json(200, o.toString());
        }

        String token = Token.get(this);
        String auth = req.headers.get("authorization");
        String given = auth != null && auth.startsWith("Bearer ")
                ? auth.substring(7).trim() : req.param("token");
        if (!token.equals(given) || given == null) {
            return HttpServer.Response.json(401, err("token 不匹配：请用 App 主页显示的 token"));
        }

        switch (p) {
            case "/api/alarms": {
                if ("GET".equals(req.method)) return listAlarms();
                if ("POST".equals(req.method)) return createAlarm(req);
                return HttpServer.Response.json(405, err("method not allowed"));
            }
            case "/api/status": {
                JSONObject o = new JSONObject();
                o.put("api_running", running && http != null && http.running);
                o.put("uptime_sec", http == null ? 0 : (System.currentTimeMillis() - http.startedAt) / 1000);
                o.put("requests_served", http == null ? 0 : http.requestsServed);
                o.put("ringing", RingService.RINGING);
                o.put("ring_message", RingService.RING_MESSAGE);
                o.put("ring_sound", RingService.RING_SOUND);
                o.put("tts_ready", SpeakManager.isReady());
                o.put("audio_playing", audioPlayer != null);
                long next = AlarmStore.nextTriggerAt(this);
                o.put("next_ring", next);
                if (next > 0) {
                    o.put("next_ring_human", new SimpleDateFormat(
                            "MM-dd HH:mm", Locale.CHINA).format(new Date(next)));
                }
                return HttpServer.Response.json(200, o.toString());
            }
            case "/api/say": {
                // 端侧 TTS 播报：agent 让手机直接"说话"（系统引擎，离线，走闹钟音量）
                if (!"POST".equals(req.method)) return HttpServer.Response.json(405, err("method not allowed"));
                String text;
                boolean interrupt = true;
                try {
                    JSONObject body = new JSONObject(req.body);
                    text = body.optString("text", "").trim();
                    if (body.has("interrupt")) interrupt = body.optBoolean("interrupt", true);
                } catch (Exception e) {
                    text = req.body == null ? "" : req.body.trim();
                }
                if (text.isEmpty()) return HttpServer.Response.json(400, err("text 不能为空"));
                if (text.length() > 500) text = text.substring(0, 500);
                final String t = text;
                final boolean itp = interrupt;
                SpeakManager.ensure(this, ok -> {
                    if (ok) SpeakManager.speak(t, itp);
                });
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("tts_ready", SpeakManager.isReady());
                o.put("hint", SpeakManager.isReady() ? "正在播报"
                        : "TTS 引擎初始化中，文本已排队，稍后自动播报");
                return HttpServer.Response.json(200, o.toString());
            }
            case "/api/audio": {
                // agent 上传自产音频（mp3/wav/ogg）：?name=xxx&save=1 存为自定义铃声，&play=1 立即播放
                if (!"POST".equals(req.method)) return HttpServer.Response.json(405, err("method not allowed"));
                if (req.bodyBytes == null || req.bodyBytes.length == 0) {
                    return HttpServer.Response.json(400, err("body 为空：请用 --data-binary 上传音频原始字节"));
                }
                boolean save = "1".equals(req.param("save"));
                boolean play = "1".equals(req.param("play"));
                String name = req.param("name");
                File target;
                String key;
                if (save) {
                    if (name == null || !name.matches("[\\w\\u4e00-\\u9fa5-]{1,32}")) {
                        return HttpServer.Response.json(400,
                                err("save=1 时 name 必填且只能含中英文/数字/_-，长度1-32"));
                    }
                    target = new File(SoundCatalog.audioDir(this), name + SoundCatalog.EXT);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(target)) {
                        fo.write(req.bodyBytes);
                    }
                    key = SoundCatalog.CUSTOM_PREFIX + name;
                } else {
                    target = new File(getCacheDir(), "api_audio_play." + SoundCatalog.EXT);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(target)) {
                        fo.write(req.bodyBytes);
                    }
                    key = "";
                }
                String played = "no";
                if (play) {
                    played = playFile(target) ? "started" : "failed";
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                if (save) {
                    o.put("saved", key);
                    o.put("hint", "可用 sound=\"" + key + "\" 用于闹钟/响铃");
                }
                o.put("played", played);
                o.put("bytes", req.bodyBytes.length);
                return HttpServer.Response.json(200, o.toString());
            }
            case "/api/ring": {
                if (!"POST".equals(req.method)) return HttpServer.Response.json(405, err("method not allowed"));
                String msg = "";
                String sound = SoundCatalog.DEFAULT_RING_NOW;
                boolean speak = true;
                try {
                    JSONObject body = new JSONObject(req.body);
                    msg = body.optString("message", "");
                    if (body.has("sound") && SoundCatalog.isValid(this, body.getString("sound"))) {
                        sound = body.getString("sound");
                    }
                    if (body.has("speak")) speak = body.optBoolean("speak", true);
                } catch (Exception ignored) { }
                RingService.start(this, msg, sound, speak);
                try {
                    Intent ui = new Intent(this, RingActivity.class);
                    ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(ui);
                } catch (Exception ignored) { }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("ringing", true);
                return HttpServer.Response.json(200, o.toString());
            }
            case "/api/stop": {
                if (!"POST".equals(req.method)) return HttpServer.Response.json(405, err("method not allowed"));
                RingService.stopRinging();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("ringing", false);
                return HttpServer.Response.json(200, o.toString());
            }
            case "/api/sounds": {
                if (!"GET".equals(req.method)) return HttpServer.Response.json(405, err("method not allowed"));
                JSONArray arr = new JSONArray();
                for (SoundCatalog.Item it : SoundCatalog.all(this)) {
                    JSONObject s = new JSONObject();
                    s.put("key", it.key);
                    s.put("name", it.name);
                    arr.put(s);
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("default_ring_now", SoundCatalog.DEFAULT_RING_NOW);
                o.put("default_sound", SoundCatalog.defaultKey(this));
                o.put("sounds", arr);
                return HttpServer.Response.json(200, o.toString());
            }
            case "/api/settings": {
                if ("GET".equals(req.method)) {
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("default_sound", SoundCatalog.defaultKey(this));
                    return HttpServer.Response.json(200, o.toString());
                }
                if ("POST".equals(req.method)) {
                    JSONObject body = new JSONObject(req.body);
                    if (!body.has("default_sound")) {
                        return HttpServer.Response.json(400, err("缺少 default_sound"));
                    }
                    String s = body.getString("default_sound");
                    if (!SoundCatalog.isValid(this, s)) {
                        return HttpServer.Response.json(400, err("default_sound 须为: system/nuke/siren/klaxon/pulse/birds/epic/rock 之一"));
                    }
                    SoundCatalog.setDefaultKey(this, s);
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("default_sound", s);
                    return HttpServer.Response.json(200, o.toString());
                }
                return HttpServer.Response.json(405, err("method not allowed"));
            }
        }

        // /api/alarms/{id} 与 /api/alarms/{id}/toggle
        if (p.startsWith("/api/alarms/")) {
            String rest = p.substring("/api/alarms/".length());
            boolean toggle = rest.endsWith("/toggle");
            if (toggle) rest = rest.substring(0, rest.length() - "/toggle".length());
            long id;
            try {
                id = Long.parseLong(rest);
            } catch (NumberFormatException e) {
                return HttpServer.Response.json(404, err("bad alarm id"));
            }
            if (toggle) {
                if (!"POST".equals(req.method)) return HttpServer.Response.json(405, err("method not allowed"));
                JSONArray arr = listArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (arr.getJSONObject(i).optLong("id") == id) {
                        boolean cur = arr.getJSONObject(i).optBoolean("enabled", true);
                        JSONObject patch = new JSONObject();
                        patch.put("enabled", !cur);
                        AlarmStore.Alarm a = AlarmStore.patch(this, id, patch);
                        return HttpServer.Response.json(200, (a == null ? err("not found") : a.toJson().toString()));
                    }
                }
                return HttpServer.Response.json(404, err("alarm not found"));
            }
            if ("POST".equals(req.method)) {
                JSONObject patch = new JSONObject(req.body);
                AlarmStore.Alarm a = AlarmStore.patch(this, id, patch);
                return HttpServer.Response.json(a == null ? 404 : 200,
                        a == null ? err("alarm not found") : a.toJson().toString());
            }
            if ("DELETE".equals(req.method)) {
                boolean ok = AlarmStore.delete(this, id);
                return HttpServer.Response.json(ok ? 200 : 404,
                        ok ? "{\"ok\":true}" : err("alarm not found"));
            }
            return HttpServer.Response.json(405, err("method not allowed"));
        }

        return HttpServer.Response.json(404, err("not found"));
    }

    private JSONObject alarmJson(AlarmStore.Alarm a) throws Exception {
        JSONObject o = a.toJson();
        long next = AlarmStore.nextTrigger(a);
        o.put("next_ring", next);
        if (next > 0) {
            o.put("next_ring_human", new SimpleDateFormat(
                    "MM-dd HH:mm EEEE", Locale.CHINA).format(new Date(next)));
        }
        return o;
    }

    private JSONArray listArray() throws Exception {
        JSONArray arr = new JSONArray();
        for (AlarmStore.Alarm a : AlarmStore.load(this)) arr.put(alarmJson(a));
        return arr;
    }

    private HttpServer.Response listAlarms() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("alarms", listArray());
        return HttpServer.Response.json(200, o.toString());
    }

    private HttpServer.Response createAlarm(HttpServer.Request req) throws Exception {
        JSONObject body = new JSONObject(req.body);
        AlarmStore.Alarm a = new AlarmStore.Alarm();
        a.hour = body.optInt("hour", -1);
        a.minute = body.optInt("minute", -1);
        if (a.hour < 0 || a.hour > 23 || a.minute < 0 || a.minute > 59) {
            return HttpServer.Response.json(400, err("hour 须为 0-23，minute 须为 0-59"));
        }
        a.label = body.optString("label", "");
        a.enabled = body.optBoolean("enabled", true);
        if (body.has("sound")) {
            String s = body.getString("sound");
            if (!SoundCatalog.isValid(this, s)) {
                return HttpServer.Response.json(400, err("sound 须为内置铃声 key 或 custom:名字（先 /api/audio?save=1 上传）。GET /api/sounds 可查全部"));
            }
            a.sound = s;
        } else {
            a.sound = SoundCatalog.defaultKey(this); // 不指定就用 App 里设的默认铃声
        }
        if (body.has("tts")) a.tts = body.optBoolean("tts", true);
        if (body.has("days")) {
            boolean[] d = new boolean[7];
            JSONArray arr = body.getJSONArray("days");
            for (int i = 0; i < arr.length(); i++) {
                int v = arr.getInt(i);
                if (v < 1 || v > 7) return HttpServer.Response.json(400, err("days 取值 1(周一)-7(周日)"));
                d[v - 1] = true;
            }
            a.days = d;
        }
        AlarmStore.create(this, a);
        return HttpServer.Response.json(201, alarmJson(a).toString());
    }

    private static String err(String msg) {
        return "{\"error\":" + HttpServer.jsonString(msg) + "}";
    }

    // ------------------------------------------------------------ 独立音频播放（/api/audio play=1）

    private static MediaPlayer audioPlayer;

    /** 在闹钟音量通道播放文件；返回是否成功启动。 */
    private boolean playFile(File f) {
        stopAudioPlayer();
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setDataSource(this, Uri.fromFile(f));
            p.setOnCompletionListener(mp -> {
                mp.release();
                if (audioPlayer == mp) audioPlayer = null;
            });
            p.prepare();
            p.start();
            audioPlayer = p;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void stopAudioPlayer() {
        try {
            if (audioPlayer != null) {
                audioPlayer.stop();
                audioPlayer.release();
            }
        } catch (Exception ignored) { }
        audioPlayer = null;
    }
}
