package com.axgu.phonealarm;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/** 全屏响铃界面：脉冲光圈动画 + 大字消息；锁屏可显示；停止 / 贪睡。
 *  singleInstance：重复触发走 onNewIntent 刷新内容；停止响铃时由 RingService 关闭本页。 */
public class RingActivity extends Activity {

    private static RingActivity instance;

    private TextView time;
    private TextView date;
    private TextView label;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_ring);
        instance = this;

        time = findViewById(R.id.ring_time);
        date = findViewById(R.id.ring_date);
        label = findViewById(R.id.ring_label);

        startPulse(findViewById(R.id.pulse1), 0);
        startPulse(findViewById(R.id.pulse2), 1000);

        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            RingService.stopRinging();
            finish();
        });

        findViewById(R.id.btn_snooze).setOnClickListener(v -> {
            Calendar t = Calendar.getInstance();
            t.add(Calendar.MINUTE, 5);
            AlarmStore.Alarm a = new AlarmStore.Alarm();
            a.hour = t.get(Calendar.HOUR_OF_DAY);
            a.minute = t.get(Calendar.MINUTE);
            String msg = RingService.RING_MESSAGE;
            a.label = "贪睡 · " + (msg == null || msg.isEmpty() ? "闹钟" : msg);
            a.sound = RingService.RING_SOUND;
            AlarmStore.create(this, a);
            RingService.stopRinging();
            finish();
        });

        refreshUi();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshUi(); // 已在展示时又被触发：刷新时间/消息
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** 停止响铃时由 RingService 调用：避免界面残留显示过期内容。 */
    public static void finishIfShowing() {
        RingActivity act = instance;
        if (act != null) act.finish();
    }

    private void refreshUi() {
        Calendar now = Calendar.getInstance();
        time.setText(String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)));

        String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        date.setText(String.format(Locale.CHINA, "%d月%d日 %s",
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                week[(now.get(Calendar.DAY_OF_WEEK) + 5) % 7]));

        String msg = RingService.RING_MESSAGE;
        String soundName = SoundCatalog.find(this, RingService.RING_SOUND).name;
        String combined = (msg == null || msg.isEmpty() ? "闹钟" : msg);
        label.setText(soundName + " · " + combined);
    }

    /** 光圈从中心扩散淡出，双圈错相呼吸。 */
    private void startPulse(View v, long delay) {
        long dur = 2000;
        TimeInterpolator interp = new AccelerateDecelerateInterpolator();
        ObjectAnimator sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 0.55f, 1.12f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.55f, 1.12f);
        ObjectAnimator al = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0f);
        for (ObjectAnimator an : new ObjectAnimator[]{sx, sy, al}) {
            an.setDuration(dur);
            an.setInterpolator(interp);
            an.setRepeatCount(ObjectAnimator.INFINITE);
            an.setRepeatMode(ObjectAnimator.RESTART);
            an.setStartDelay(delay);
            an.start();
        }
    }
}
