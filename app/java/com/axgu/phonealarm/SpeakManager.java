package com.axgu.phonealarm;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 端侧 TTS：走系统 TextToSpeech 引擎（OnePlus 上是 com.oplus.ttsaccessibilityengine），
 *  音频走 USAGE_ALARM 通道，响铃/播报跟随闹钟音量。进程级单例，懒加载。 */
public class SpeakManager {

    private static volatile TextToSpeech tts;
    private static volatile boolean ready = false;
    private static volatile boolean failed = false;
    private static final AtomicBoolean initializing = new AtomicBoolean(false);

    public interface ReadyCallback {
        void onReady(boolean ok);
    }

    /** 懒初始化；引擎就绪（或失败）后回调（可能已在调用线程异步回来）。 */
    public static void ensure(Context c, ReadyCallback cb) {
        if (ready) {
            if (cb != null) cb.onReady(true);
            return;
        }
        if (failed || !initializing.compareAndSet(false, true)) {
            // 正在初始化：轮询等结果，避免重复创建引擎
            waitReady(cb);
            return;
        }
        try {
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                        int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                        if (r == TextToSpeech.LANG_MISSING_DATA
                                || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault()); // 退回引擎默认语言
                        }
                        ready = true;
                    } catch (Exception e) {
                        failed = true;
                    }
                } else {
                    failed = true;
                }
                initializing.set(false);
                if (cb != null) cb.onReady(ready);
            });
        } catch (Exception e) {
            failed = true;
            initializing.set(false);
            if (cb != null) cb.onReady(false);
        }
    }

    private static void waitReady(ReadyCallback cb) {
        new Thread(() -> {
            for (int i = 0; i < 40 && !ready && !failed; i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            }
            if (cb != null) cb.onReady(ready);
        }).start();
    }

    public static boolean isReady() {
        return ready;
    }

    /** 播报文本；interrupt=true 打断上一段。引擎未就绪时返回 false（调用方先用 ensure）。 */
    public static boolean speak(String text, boolean interrupt) {
        TextToSpeech e = tts;
        if (!ready || e == null || text == null || text.trim().isEmpty()) return false;
        e.speak(text, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                null, "pa_" + System.currentTimeMillis());
        return true;
    }

    public static void shutdown() {
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) { }
        tts = null;
        ready = false;
    }
}
