package com.axgu.phonealarm;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/** API 访问 token：首次使用时随机生成，长期不变。 */
public final class Token {

    private static final String PREFS = "phone_alarm";
    private static final String KEY = "api_token";

    private Token() { }

    public static synchronized String get(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = p.getString(KEY, null);
        if (t == null) {
            Random r = new Random();
            StringBuilder sb = new StringBuilder(16);
            String chars = "abcdef0123456789";
            for (int i = 0; i < 16; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            t = sb.toString();
            p.edit().putString(KEY, t).apply();
        }
        return t;
    }
}
