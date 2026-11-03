package com.axgu.phonealarm;

import java.util.Arrays;
import java.util.List;

/** 内置铃声目录：system=系统闹钟铃声，其余为 APK 预装的合成铃声。 */
public class SoundCatalog {

    public static class Item {
        public final String key;
        public final String name;
        public final int resId; // 0 = 系统铃声

        Item(String key, String name, int resId) {
            this.key = key;
            this.name = name;
            this.resId = resId;
        }
    }

    public static final List<Item> ALL = Arrays.asList(
            new Item("system", "🔔 系统闹钟", 0),
            new Item("nuke", "💥 核爆", R.raw.alarm_nuke),
            new Item("siren", "🚨 防空警报", R.raw.alarm_siren),
            new Item("klaxon", "🚢 舰船警报", R.raw.alarm_klaxon),
            new Item("pulse", "⚡ 紧急脉冲", R.raw.alarm_pulse),
            new Item("birds", "🐦 晨间鸟鸣", R.raw.alarm_birds));

    public static final String DEFAULT_RING_NOW = "nuke"; // /api/ring 找手机用最炸的

    public static Item find(String key) {
        if (key == null || key.isEmpty()) return ALL.get(0);
        for (Item it : ALL) if (it.key.equals(key)) return it;
        return ALL.get(0);
    }

    public static boolean isValid(String key) {
        if (key == null) return false;
        for (Item it : ALL) if (it.key.equals(key)) return true;
        return false;
    }
}
