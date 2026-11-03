package com.axgu.phonealarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 闹钟数据模型 + SharedPreferences 持久化 + AlarmManager 精确调度。 */
public class AlarmStore {

    public static class Alarm {
        public long id;
        public int hour;
        public int minute;
        public String label = "";
        public String sound = "system";      // SoundCatalog key（含 custom:xxx）
        public boolean tts = true;           // 响铃时用端侧 TTS 朗读 label
        public boolean enabled = true;
        public boolean[] days = new boolean[7]; // [0]=周一 … [6]=周日；全 false = 一次性

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("hour", hour);
                o.put("minute", minute);
                o.put("label", label == null ? "" : label);
                o.put("sound", sound == null ? "system" : sound);
                o.put("tts", tts);
                o.put("enabled", enabled);
                JSONArray d = new JSONArray();
                for (int i = 0; i < 7; i++) if (days[i]) d.put(i + 1); // 1=周一 … 7=周日
                o.put("days", d);
            } catch (Exception ignored) { }
            return o;
        }

        public static Alarm fromJson(JSONObject o, Context c) {
            Alarm a = new Alarm();
            a.id = o.optLong("id", System.currentTimeMillis());
            a.hour = o.optInt("hour", 7);
            a.minute = o.optInt("minute", 0);
            a.label = o.optString("label", "");
            a.sound = SoundCatalog.isValid(c, o.optString("sound", "system"))
                    ? o.optString("sound", "system") : "system";
            a.tts = o.optBoolean("tts", true);
            a.enabled = o.optBoolean("enabled", true);
            JSONArray d = o.optJSONArray("days");
            if (d != null) {
                for (int i = 0; i < d.length(); i++) {
                    int v = d.optInt(i, 0);
                    if (v >= 1 && v <= 7) a.days[v - 1] = true;
                }
            }
            return a;
        }
    }

    private static final String PREFS = "phone_alarm";
    private static final String KEY_LIST = "alarms_json";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Alarm> load(Context c) {
        List<Alarm> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Alarm.fromJson(arr.getJSONObject(i), c));
        } catch (Exception ignored) { }
        return out;
    }

    private static synchronized void save(Context c, List<Alarm> list) {
        JSONArray arr = new JSONArray();
        for (Alarm a : list) arr.put(a.toJson());
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static synchronized Alarm create(Context c, Alarm a) {
        List<Alarm> list = load(c);
        a.id = System.currentTimeMillis();
        list.add(a);
        save(c, list);
        if (a.enabled) schedule(c, a);
        return a;
    }

    /** 按 id 部分更新；返回更新后的闹钟，找不到返回 null。 */
    public static synchronized Alarm patch(Context c, long id, JSONObject patch) throws Exception {
        List<Alarm> list = load(c);
        Alarm found = null;
        for (Alarm a : list) if (a.id == id) { found = a; break; }
        if (found == null) return null;
        if (patch.has("hour")) {
            int v = patch.getInt("hour");
            if (v < 0 || v > 23) throw new IllegalArgumentException("hour 超范围 0-23");
            found.hour = v;
        }
        if (patch.has("minute")) {
            int v = patch.getInt("minute");
            if (v < 0 || v > 59) throw new IllegalArgumentException("minute 超范围 0-59");
            found.minute = v;
        }
        if (patch.has("label")) found.label = patch.getString("label");
        if (patch.has("sound")) {
            String s = patch.getString("sound");
            if (!SoundCatalog.isValid(c, s)) throw new IllegalArgumentException(
                    "sound 须为内置铃声 key 或 custom:名字（已上传的自定义铃声）");
            found.sound = s;
        }
        if (patch.has("tts")) found.tts = patch.getBoolean("tts");
        if (patch.has("enabled")) found.enabled = patch.getBoolean("enabled");
        if (patch.has("days")) {
            boolean[] d = new boolean[7];
            JSONArray arr = patch.getJSONArray("days");
            for (int i = 0; i < arr.length(); i++) {
                int v = arr.getInt(i);
                if (v < 1 || v > 7) throw new IllegalArgumentException("days 取值 1(周一)-7(周日)");
                d[v - 1] = true;
            }
            found.days = d;
        }
        save(c, list);
        if (found.enabled) schedule(c, found);
        else cancelScheduled(c, id);
        return found;
    }

    public static synchronized boolean delete(Context c, long id) {
        List<Alarm> list = load(c);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) { list.remove(i); removed = true; }
        }
        if (removed) {
            save(c, list);
            cancelScheduled(c, id);
        }
        return removed;
    }

    public static boolean hasDays(Alarm a) {
        for (boolean d : a.days) if (d) return true;
        return false;
    }

    /** 下一次触发时刻（毫秒 epoch）；不会触发返回 -1。 */
    public static long nextTrigger(Alarm a) {
        if (!a.enabled) return -1;
        Calendar now = Calendar.getInstance();
        for (int offset = 0; offset < 8; offset++) {
            Calendar t = (Calendar) now.clone();
            t.add(Calendar.DAY_OF_YEAR, offset);
            t.set(Calendar.HOUR_OF_DAY, a.hour);
            t.set(Calendar.MINUTE, a.minute);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (!t.after(now)) continue;
            if (hasDays(a)) {
                int dow = t.get(Calendar.DAY_OF_WEEK); // SUNDAY=1 … SATURDAY=7
                int idx = (dow + 5) % 7;               // 0=周一 … 6=周日
                if (!a.days[idx]) continue;
            }
            return t.getTimeInMillis();
        }
        return -1;
    }

    public static long nextTriggerAt(Context c) {
        long best = -1;
        for (Alarm a : load(c)) {
            long t = nextTrigger(a);
            if (t > 0 && (best < 0 || t < best)) best = t;
        }
        return best;
    }

    public static void schedule(Context c, Alarm a) {
        long at = nextTrigger(a);
        if (at <= 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent show = new Intent(c, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(c, 0, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pending(c, a.id, a.label));
    }

    private static PendingIntent pending(Context c, long id, String label) {
        Intent it = new Intent(c, AlarmReceiver.class);
        it.setAction(AlarmReceiver.ACTION_FIRE);
        it.putExtra("id", id);
        it.putExtra("label", label == null ? "" : label);
        return PendingIntent.getBroadcast(c, (int) (id % 1000000000L), it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelScheduled(Context c, long id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c, id, null));
    }

    public static synchronized void rescheduleAll(Context c) {
        for (Alarm a : load(c)) if (a.enabled) schedule(c, a);
    }

    /** 触发后调用：重复闹钟排下一次，一次性闹钟自动关闭。 */
    public static synchronized void onFired(Context c, long id) {
        List<Alarm> list = load(c);
        for (Alarm a : list) {
            if (a.id != id) continue;
            if (hasDays(a)) {
                schedule(c, a);
            } else {
                a.enabled = false;
                save(c, list);
                cancelScheduled(c, id);
            }
            break;
        }
    }
}
