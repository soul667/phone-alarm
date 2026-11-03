package com.axgu.phonealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** 响铃前台服务：循环播放系统闹钟铃声 + 振动，最长 5 分钟自动停。 */
public class RingService extends Service {

    public static final String ACTION_RING = "ring";
    public static final String ACTION_STOP = "stop";

    public static volatile boolean RINGING = false;
    public static volatile String RING_MESSAGE = "";
    public static volatile String RING_SOUND = "system";
    public static volatile long RING_START_AT = 0;
    private static volatile RingService instance = null;

    private static final long MAX_RING_MS = 5 * 60 * 1000L;
    private static final long WAKELOCK_MS = MAX_RING_MS + 30 * 1000L;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler();
    private final Runnable autoStop = this::stopRingInternal;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_RING;
        if (ACTION_STOP.equals(action)) {
            stopRingInternal();
            return START_NOT_STICKY;
        }
        String msg = intent != null ? intent.getStringExtra("message") : null;
        String sound = intent != null ? intent.getStringExtra("sound") : null;
        startRingForeground(msg == null ? "" : msg);
        RINGING = true;
        RING_MESSAGE = msg == null ? "" : msg;
        RING_SOUND = sound == null ? "system" : sound;
        RING_START_AT = System.currentTimeMillis();
        playSound();
        handler.removeCallbacks(autoStop);
        handler.postDelayed(autoStop, MAX_RING_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static void start(Context c, String message, String soundKey) {
        Intent it = new Intent(c, RingService.class);
        it.setAction(ACTION_RING);
        it.putExtra("message", message == null ? "" : message);
        it.putExtra("sound", soundKey == null ? "system" : soundKey);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
        else c.startService(it);
    }

    /** 任意线程可调：直接停掉正在响的服务（服务在跑时同进程直调最稳）。 */
    public static void stopRinging() {
        RingService s = instance;
        if (s != null) s.stopRingInternal();
    }

    private void startRingForeground(String msg) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("alarm", "闹钟响铃",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("闹钟到点响铃");
            nm.createNotificationChannel(ch);
        }
        Intent ui = new Intent(this, RingActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent fsi = PendingIntent.getActivity(this, 11, ui,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "alarm")
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("闹钟")
                .setContentText(msg.isEmpty() ? "到点了！" : msg)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(fsi, true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(2, n);
        }
    }

    private void playSound() {
        stopPlayer();
        try {
            SoundCatalog.Item item = SoundCatalog.find(RING_SOUND);
            Uri uri;
            if (item.resId != 0) {
                uri = Uri.parse("android.resource://" + getPackageName() + "/" + item.resId);
            } else {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {
            player = null;
        }
        vibrate();
        acquireWakeLock();
    }

    private void vibrate() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null) return;
            long[] pattern = {0, 600, 400};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) { }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonealarm:ring");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKELOCK_MS);
        } catch (Exception ignored) { }
    }

    private void stopPlayer() {
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Exception ignored) { }
        player = null;
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) { }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
    }

    private void stopRingInternal() {
        handler.removeCallbacks(autoStop);
        RINGING = false;
        stopPlayer();
        RingActivity.finishIfShowing(); // 别让响铃界面残留过期内容
        try {
            stopForeground(true);
        } catch (Exception ignored) { }
        stopSelf();
    }
}
