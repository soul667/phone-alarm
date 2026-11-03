package com.axgu.phonealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟触发 / 开机重排。 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.axgu.phonealarm.FIRE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AlarmStore.rescheduleAll(context);
            ApiService.ensure(context);
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) return;

        long id = intent.getLongExtra("id", -1);
        String label = intent.getStringExtra("label");
        if (label == null) label = "";

        String sound = "system";
        boolean speak = true;
        for (AlarmStore.Alarm a : AlarmStore.load(context)) {
            if (a.id == id) { sound = a.sound; speak = a.tts; break; }
        }

        AlarmStore.onFired(context, id); // 先排下一次 / 关一次性，再响
        RingService.start(context, label, sound, speak);
        try {
            Intent ui = new Intent(context, RingActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(ui); // 后台被拦也没关系，还有全屏意图通知兜底
        } catch (Exception ignored) { }
    }
}
