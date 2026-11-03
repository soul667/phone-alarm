package com.axgu.phonealarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Calendar;

/** 主界面：状态卡 + 闹钟列表 + 自定义添加对话框（星期芯片/铃声试听）。 */
public class MainActivity extends Activity {

    private TextView statusTitle;
    private TextView statusText;
    private View statusDot;
    private TextView exactBtn;
    private TextView testBtn;
    private LinearLayout listContainer;
    private View emptyView;

    private MediaPlayer previewPlayer;

    private final Handler poller = new Handler();
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            poller.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences p = getSharedPreferences("phone_alarm", MODE_PRIVATE);
        String crashTrace = p.getString("crash_trace", null);
        if (crashTrace != null) {
            showCrashScreen(p, crashTrace); // 上次崩了：先展示崩溃栈，不进业务逻辑
            return;
        }
        setContentView(R.layout.activity_main);

        statusTitle = findViewById(R.id.status_title);
        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        exactBtn = findViewById(R.id.exact_btn);
        testBtn = findViewById(R.id.test_btn);
        listContainer = findViewById(R.id.alarm_list);
        emptyView = findViewById(R.id.empty_view);

        findViewById(R.id.copy_btn).setOnClickListener(v -> copyApiUrl());
        findViewById(R.id.add_btn).setOnClickListener(v -> showAddDialog());
        testBtn.setOnClickListener(v -> toggleTestRing());

        exactBtn.setOnClickListener(v -> requestExactAlarm());
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            exactBtn.setVisibility(am.canScheduleExactAlarms() ? View.GONE : View.VISIBLE);
        } else {
            exactBtn.setVisibility(View.GONE);
        }

        requestNotifPermissionIfNeeded();
        ApiService.ensure(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        rebuild();
        poller.post(poll);
    }

    @Override
    protected void onPause() {
        poller.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releasePreview();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 状态卡

    private void refreshStatus() {
        boolean up = ApiService.running;
        statusDot.setVisibility(up ? View.VISIBLE : View.INVISIBLE);
        statusTitle.setText(up ? "API 服务运行中 · 已处理 " + ApiService.servedCount() + " 次请求"
                : "API 服务未运行" + (ApiService.lastError.isEmpty() ? "" : "（" + ApiService.lastError + "）"));
        statusText.setText("API   http://" + ApiService.lanIps() + ":" + ApiService.PORT + "\n"
                + "token " + Token.get(this) + "\n"
                + "响铃  " + (RingService.RINGING
                        ? "正在响 · " + SoundCatalog.find(RingService.RING_SOUND).name
                        : "空闲"));
        testBtn.setText(RingService.RINGING ? "停止响铃" : "测试响铃");
    }

    private void copyApiUrl() {
        String ip = ApiService.lanIps().split(" / ")[0];
        String url = "http://" + ip + ":" + ApiService.PORT;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("phone-alarm-api",
                url + "  token=" + Token.get(this)));
        Toast.makeText(this, "已复制 API 地址和 token", Toast.LENGTH_SHORT).show();
    }

    private void toggleTestRing() {
        if (RingService.RINGING) {
            RingService.stopRinging();
        } else {
            RingService.start(this, "测试响铃", SoundCatalog.DEFAULT_RING_NOW);
            try {
                startActivity(new Intent(this, RingActivity.class));
            } catch (Exception ignored) { }
        }
        refreshStatus();
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "请在系统设置中授予「闹钟和提醒」权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ------------------------------------------------------------ 闹钟列表

    private void rebuild() {
        listContainer.removeAllViews();
        java.util.List<AlarmStore.Alarm> alarms = AlarmStore.load(this);
        emptyView.setVisibility(alarms.isEmpty() ? View.VISIBLE : View.GONE);
        for (AlarmStore.Alarm a : alarms) listContainer.addView(buildRow(a));
    }

    private String subText(AlarmStore.Alarm a) {
        StringBuilder sb = new StringBuilder();
        if (a.label != null && !a.label.isEmpty()) sb.append(a.label).append(" · ");
        sb.append(repeatDesc(a)).append(" · ").append(SoundCatalog.find(a.sound).name);
        long next = AlarmStore.nextTrigger(a);
        if (next > 0) {
            sb.append(" · 下次 ").append(new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(next)));
        }
        return sb.toString();
    }

    private String repeatDesc(AlarmStore.Alarm a) {
        int n = 0;
        for (boolean d : a.days) if (d) n++;
        if (n == 0) return "一次性";
        if (n == 7) return "每天";
        String[] chars = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder("周");
        for (int i = 0; i < 7; i++) if (a.days[i]) sb.append(chars[i]);
        return sb.toString();
    }

    private View buildRow(final AlarmStore.Alarm a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        row.setPadding(dp(18), dp(14), dp(12), dp(14));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(this);
        time.setText(String.format("%02d:%02d", a.hour, a.minute));
        time.setTextSize(30);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setTextColor(a.enabled ? 0xFF1B1D2A : 0xFF8A90A6);
        col.addView(time);

        TextView sub = new TextView(this);
        sub.setText(subText(a));
        sub.setTextSize(12);
        sub.setTextColor(0xFF8A90A6);
        sub.setPadding(0, dp(3), 0, 0);
        col.addView(sub);

        Switch sw = new Switch(this);
        sw.setChecked(a.enabled);
        sw.setOnClickListener(v -> {
            try {
                JSONObject patch = new JSONObject();
                patch.put("enabled", sw.isChecked());
                AlarmStore.patch(MainActivity.this, a.id, patch);
            } catch (Exception ignored) { }
            rebuild();
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView del = new TextView(this);
        del.setText("✕");
        del.setTextSize(16);
        del.setTextColor(0xFF8A90A6);
        del.setPadding(dp(14), dp(10), dp(6), dp(10));
        del.setOnClickListener(v -> {
            AlarmStore.delete(MainActivity.this, a.id);
            rebuild();
        });
        row.addView(del, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ------------------------------------------------------------ 添加对话框

    private void showAddDialog() {
        Calendar dflt = Calendar.getInstance();
        dflt.add(Calendar.HOUR_OF_DAY, 1);
        final int[] picked = {dflt.get(Calendar.HOUR_OF_DAY), dflt.get(Calendar.MINUTE)};
        final int[] soundIdx = {0}; // SoundCatalog.ALL 下标

        releasePreview();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(22);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("新闹钟");
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xFF1B1D2A);
        card.addView(title);

        final TextView timeBtn = new TextView(this);
        timeBtn.setText(String.format("%02d:%02d", picked[0], picked[1]));
        timeBtn.setTextSize(42);
        timeBtn.setTypeface(Typeface.MONOSPACE);
        timeBtn.setGravity(Gravity.CENTER);
        timeBtn.setTextColor(0xFF1B1D2A);
        timeBtn.setBackgroundResource(R.drawable.bg_chip_off);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dp(14);
        card.addView(timeBtn, timeLp);
        timeBtn.setOnClickListener(v -> new TimePickerDialog(MainActivity.this, (tp, h, m) -> {
            picked[0] = h;
            picked[1] = m;
            timeBtn.setText(String.format("%02d:%02d", h, m));
        }, picked[0], picked[1], true).show());

        card.addView(caption("重复（不选 = 一次性）"));

        final TextView[] dayChips = new TextView[7];
        LinearLayout daysRow = new LinearLayout(this);
        daysRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] dayChars = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            final TextView chip = new TextView(this);
            chip.setText(dayChars[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                chip.setSelected(!chip.isSelected());
                styleDayChip(chip);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) clp.leftMargin = dp(6);
            daysRow.addView(chip, clp);
            dayChips[i] = chip;
        }
        card.addView(daysRow);

        card.addView(caption("铃声"));

        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setOrientation(LinearLayout.HORIZONTAL);
        soundRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView soundName = new TextView(this);
        soundName.setTextSize(14);
        soundName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        soundName.setTextColor(0xFF1B1D2A);
        soundRow.addView(soundName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView previewBtn = pill("▶ 试听", R.drawable.bg_chip_off, 0xFF1B1D2A);
        previewBtn.setOnClickListener(v ->
                previewSound(SoundCatalog.ALL.get(soundIdx[0]).resId));
        soundRow.addView(previewBtn, pillLp());

        TextView nextBtn = pill("换一个", R.drawable.bg_chip_off, 0xFF1B1D2A);
        nextBtn.setOnClickListener(v -> {
            soundIdx[0] = (soundIdx[0] + 1) % SoundCatalog.ALL.size();
            soundName.setText(SoundCatalog.ALL.get(soundIdx[0]).name);
            previewSound(SoundCatalog.ALL.get(soundIdx[0]).resId);
        });
        LinearLayout.LayoutParams nlp = pillLp();
        nlp.leftMargin = dp(8);
        soundRow.addView(nextBtn, nlp);

        soundName.setText(SoundCatalog.ALL.get(0).name);
        card.addView(soundRow);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(20);

        TextView cancel = pill("取消", R.drawable.bg_pill_ghost, 0xFF1B1D2A);
        cancel.setOnClickListener(v -> {
            releasePreview();
            dialog.dismiss();
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1f));

        TextView save = pill("保存", R.drawable.bg_pill_primary, 0xFFFFFFFF);
        save.setOnClickListener(v -> {
            AlarmStore.Alarm a = new AlarmStore.Alarm();
            a.hour = picked[0];
            a.minute = picked[1];
            a.label = "";
            a.sound = SoundCatalog.ALL.get(soundIdx[0]).key;
            for (int i = 0; i < 7; i++) a.days[i] = dayChips[i].isSelected();
            AlarmStore.create(MainActivity.this, a);
            releasePreview();
            dialog.dismiss();
            rebuild();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        slp.leftMargin = dp(10);
        btns.addView(save, slp);
        card.addView(btns, btnsLp);

        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> releasePreview());
        dialog.show();
    }

    private TextView caption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF8A90A6);
        tv.setPadding(0, dp(18), 0, dp(8));
        return tv;
    }

    private TextView pill(String text, int bgRes, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(color);
        tv.setBackgroundResource(bgRes);
        return tv;
    }

    private LinearLayout.LayoutParams pillLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
    }

    private void styleDayChip(TextView chip) {
        boolean on = chip.isSelected();
        chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        chip.setTextColor(on ? 0xFFFFFFFF : 0xFF8A90A6);
        chip.setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
    }

    private void previewSound(int resId) {
        releasePreview();
        if (resId == 0) return; // 系统铃声不预览
        try {
            previewPlayer = MediaPlayer.create(this, resId);
            if (previewPlayer != null) previewPlayer.start();
        } catch (Exception ignored) { }
    }

    private void releasePreview() {
        try {
            if (previewPlayer != null) {
                previewPlayer.stop();
                previewPlayer.release();
            }
        } catch (Exception ignored) { }
        previewPlayer = null;
    }

    // ------------------------------------------------------------ 崩溃自报

    private void showCrashScreen(android.content.SharedPreferences p, String trace) {
        long at = p.getLong("crash_at", 0);
        String thread = p.getString("crash_thread", "?");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1B25);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("上次崩溃 @ " + (at > 0
                ? new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(new java.util.Date(at)) : "?")
                + "  线程: " + thread
                + "\n\n点「复制日志」发给 agent 排查；点「清除并进入」忽略。");
        title.setTextSize(14);
        title.setTextColor(0xFFFFC9C9);
        root.addView(title);

        TextView traceView = new TextView(this);
        traceView.setText(trace);
        traceView.setTextSize(11);
        traceView.setTextColor(0xFFD7DAE2);
        traceView.setTypeface(Typeface.MONOSPACE);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(traceView);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.topMargin = dp(12);
        root.addView(sv, svLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = new TextView(this);
        copy.setText("复制日志");
        copy.setGravity(Gravity.CENTER);
        copy.setTextColor(0xFFFFFFFF);
        copy.setBackgroundResource(R.drawable.bg_pill_primary);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        TextView clear = new TextView(this);
        clear.setText("清除并进入");
        clear.setGravity(Gravity.CENTER);
        clear.setTextColor(0xFFFFFFFF);
        clear.setBackgroundResource(R.drawable.bg_pill_ghost);
        clear.setOnClickListener(v -> {
            p.edit().remove("crash_trace").remove("crash_at").remove("crash_thread").apply();
            recreate();
        });
        btns.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        clp2.leftMargin = dp(10);
        btns.addView(clear, clp2);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(12);
        root.addView(btns, btnsLp);

        setContentView(root);
    }

    private int dp(float d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }
}
