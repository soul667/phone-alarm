package com.axgu.phonealarm;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 铃声目录：内置（system + 预装音频）+ 自定义（/api/audio 上传到 app 私有目录的文件）。
 *  自定义铃声 key 形如 "custom:名字"，文件存 filesDir/audios/。 */
public class SoundCatalog {

    public static class Item {
        public final String key;
        public final String name;
        public final int resId;   // 内置 res/raw 资源；0 = 非 res
        public final String path; // 自定义铃声的绝对路径；null = 非 custom

        Item(String key, String name, int resId, String path) {
            this.key = key;
            this.name = name;
            this.resId = resId;
            this.path = path;
        }
    }

    public static final String CUSTOM_PREFIX = "custom:";
    public static final String EXT = ".mp3";

    public static final List<Item> BUILTIN = Arrays.asList(
            new Item("system", "🔔 系统闹钟", 0, null),
            new Item("nuke", "💥 核爆", R.raw.alarm_nuke, null),
            new Item("siren", "🚨 防空警报", R.raw.alarm_siren, null),
            new Item("klaxon", "🚢 舰船警报", R.raw.alarm_klaxon, null),
            new Item("pulse", "⚡ 紧急脉冲", R.raw.alarm_pulse, null),
            new Item("birds", "🐦 晨间鸟鸣", R.raw.alarm_birds, null),
            new Item("epic", "🥁 史诗鼓点", R.raw.alarm_epic, null),
            new Item("rock", "🎸 运动摇滚", R.raw.alarm_rock, null));

    public static final String DEFAULT_RING_NOW = "nuke"; // /api/ring 找手机用最炸的

    public static File audioDir(Context c) {
        File d = new File(c.getFilesDir(), "audios");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 自定义铃声列表（按文件名排序）。 */
    public static List<Item> customSounds(Context c) {
        List<Item> out = new ArrayList<>();
        File[] files = audioDir(c).listFiles();
        if (files != null) {
            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            Collections.sort(sorted);
            for (File f : sorted) {
                if (!f.isFile() || !f.getName().endsWith(EXT)) continue;
                String name = f.getName().substring(0, f.getName().length() - EXT.length());
                out.add(new Item(CUSTOM_PREFIX + name, "🎵 " + name, 0, f.getAbsolutePath()));
            }
        }
        return out;
    }

    public static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>(BUILTIN);
        out.addAll(customSounds(c));
        return out;
    }

    public static Item find(Context c, String key) {
        if (key == null || key.isEmpty()) return BUILTIN.get(0);
        for (Item it : BUILTIN) if (it.key.equals(key)) return it;
        for (Item it : customSounds(c)) if (it.key.equals(key)) return it;
        return BUILTIN.get(0);
    }

    public static boolean isValid(Context c, String key) {
        if (key == null) return false;
        for (Item it : BUILTIN) if (it.key.equals(key)) return true;
        if (key.startsWith(CUSTOM_PREFIX)) {
            String name = key.substring(CUSTOM_PREFIX.length());
            if (name.isEmpty() || name.contains("/") || name.contains("..")) return false;
            return new File(audioDir(c), name + EXT).isFile();
        }
        return false;
    }

    /** 全局默认铃声（新闹钟缺省用它）：存 SharedPreferences。 */
    public static String defaultKey(Context c) {
        return c.getSharedPreferences("phone_alarm", Context.MODE_PRIVATE)
                .getString("default_sound", "nuke");
    }

    public static void setDefaultKey(Context c, String key) {
        c.getSharedPreferences("phone_alarm", Context.MODE_PRIVATE)
                .edit().putString("default_sound", key).apply();
    }
}
