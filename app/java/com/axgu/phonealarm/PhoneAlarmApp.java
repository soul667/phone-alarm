package com.axgu.phonealarm;

import android.app.Application;

/** 进程级崩溃捕获：把未捕获异常栈存进 SharedPreferences，供下次启动时展示。 */
public class PhoneAlarmApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String trace = android.util.Log.getStackTraceString(e);
                if (trace.length() > 60000) trace = trace.substring(0, 60000);
                getSharedPreferences("phone_alarm", MODE_PRIVATE).edit()
                        .putLong("crash_at", System.currentTimeMillis())
                        .putString("crash_trace", trace)
                        .putString("crash_thread", String.valueOf(t))
                        .apply();
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
