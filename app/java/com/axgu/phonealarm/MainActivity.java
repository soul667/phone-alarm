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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Calendar;

/** 主界面（Aurora Refined · M3 深色）：状态卡 + 默认铃声卡 + 闹钟卡列表 + 添加对话框。 */
public class MainActivity extends Activity {

    private TextView statusTitle;
    private TextView statusAddr;
    private TextView statusText;
    private View statusDot;
    private TextView alarmCount;
    private TextView defaultSoundValue;
    private TextView testBtn;
    private LinearLayout listContainer;
    private View emptyView;
    private LinearLayout statusRow;

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
            showCrashScreen(p, crashTrace);
            return;
        }
        setContentView(R.layout.activity_main);

        statusTitle = findViewById(R.id.status_title);
        statusAddr = findViewById(R.id.status_addr);
        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        alarmCount = findViewById(R.id.alarm_count);
        defaultSoundValue = findViewById(R.id.default_sound_value);
        testBtn = findViewById(R.id.test_btn);
        listContainer = findViewById(R.id.alarm_list);
        emptyView = findViewById(R.id.empty_view);
        statusRow = findViewById(R.id.status_row);

        findViewById(R.id.copy_btn).setOnClickListener(v -> copyApiUrl());
        findViewById(R.id.add_btn).setOnClickListener(v -> showAddDialog());
        testBtn.setOnClickListener(v -> toggleTestRing());
        findViewById(R.id.default_sound_chip).setOnClickListener(v -> cycleDefaultSound());

        requestNotifPermissionIfNeeded();
        ApiService.ensure(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshDefaultChip();
        maybeShowExactAlarmChip();
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

    // ------------------------------------------------------------ 状态卡 / 默认铃声

    private void refreshStatus() {
        boolean up = ApiService.running;
        statusDot.setVisibility(up ? View.VISIBLE : View.INVISIBLE);
        statusTitle.setText(up ? "API 运行中" : "API 未运行" + (ApiService.lastError.isEmpty() ? "" : "（" + ApiService.lastError + "）"));
        String ip = ApiService.lanIps().split(" / ")[0];
        statusAddr.setText(ip + ":" + ApiService.PORT);
        String token = Token.get(this);
        String masked = token.length() > 6 ? token.substring(0, 6) + "…" : token;
        String line = "已处理 " + ApiService.servedCount() + " 次  ·  token " + masked;
        if (RingService.RINGING) line += "  ·  正在响 " + SoundCatalog.find(this, RingService.RING_SOUND).name;
        statusText.setText(line);
        testBtn.setText(RingService.RINGING ? "停止响铃" : "测试响铃");
    }

    private void refreshDefaultChip() {
        defaultSoundValue.setText(SoundCatalog.find(this, SoundCatalog.defaultKey(this)).name);
    }

    /** Android 12+ 未授予精确闹钟时，在状态卡顶行动态插入授权入口。 */
    private void maybeShowExactAlarmChip() {
        if (Build.VERSION.SDK_INT < 31) return;
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am.canScheduleExactAlarms() || statusRow.findViewWithTag("exact_chip") != null) return;
        TextView chip = new TextView(this);
        chip.setTag("exact_chip");
        chip.setText("开精确闹钟");
        chip.setTextSize(12);
        chip.setTypeface(Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        chip.setGravity(Gravity.CENTER);
        chip.setTextColor(0xFF4A4459);
        chip.setBackgroundResource(R.drawable.bg_tonal);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setHeight(dp(32));
        chip.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "请在系统设置中授予「闹钟和提醒」权限", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        statusRow.addView(chip, lp);
    }

    private void cycleDefaultSound() {
        java.util.List<SoundCatalog.Item> catalog = SoundCatalog.all(this);
        int idx = 0;
        String cur = SoundCatalog.defaultKey(this);
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).key.equals(cur)) { idx = i; break; }
        }
        SoundCatalog.Item next = catalog.get((idx + 1) % catalog.size());
        SoundCatalog.setDefaultKey(this, next.key);
        refreshDefaultChip();
        Toast.makeText(this, "新闹钟默认用 " + next.name, Toast.LENGTH_SHORT).show();
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

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ------------------------------------------------------------ 闹钟列表（Aurora 卡）

    private void rebuild() {
        listContainer.removeAllViews();
        java.util.List<AlarmStore.Alarm> alarms = AlarmStore.load(this);
        int enabled = 0;
        for (AlarmStore.Alarm a : alarms) if (a.enabled) enabled++;
        alarmCount.setText(alarms.isEmpty() ? "" : enabled + " 个已启用");
        emptyView.setVisibility(alarms.isEmpty() ? View.VISIBLE : View.GONE);
        for (AlarmStore.Alarm a : alarms) listContainer.addView(buildRow(a));
    }

    private String subText(AlarmStore.Alarm a) {
        StringBuilder sb = new StringBuilder(repeatDesc(a)).append(" · ")
                .append(SoundCatalog.find(this, a.sound).name);
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
        if (n == 5 && a.days[0] && a.days[1] && a.days[2] && a.days[3] && a.days[4]) return "工作日";
        String[] chars = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder("周");
        for (int i = 0; i < 7; i++) if (a.days[i]) sb.append(chars[i]);
        return sb.toString();
    }

    private View buildRow(final AlarmStore.Alarm a) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView time = new TextView(this);
        time.setText(String.format("%02d:%02d", a.hour, a.minute));
        time.setTextSize(32);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setTextColor(a.enabled ? 0xFFE5E1E9 : 0xFF565D75);
        top.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        top.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout del = new LinearLayout(this);
        del.setGravity(Gravity.CENTER);
        del.setBackgroundResource(R.drawable.bg_glass_chip);
        ImageView x = new ImageView(this);
        x.setImageResource(R.drawable.ic_close);
        x.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFC8C5D0));
        del.addView(x, new android.widget.FrameLayout.LayoutParams(dp(14), dp(14)));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        delLp.leftMargin = dp(8);
        del.setOnClickListener(v -> {
            AlarmStore.delete(MainActivity.this, a.id);
            rebuild();
        });
        top.addView(del, delLp);

        TextView label = new TextView(this);
        label.setText(a.label == null || a.label.isEmpty() ? "闹钟" : a.label);
        label.setTextSize(14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(a.enabled ? 0xFFE5E1E9 : 0xFF8A92B2);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(10);
        card.addView(label, labelLp);

        TextView sub = new TextView(this);
        sub.setText(subText(a));
        sub.setTextSize(11);
        sub.setTextColor(0xFFC8C5D0);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        card.addView(sub, subLp);
        return card;
    }

    // ------------------------------------------------------------ 添加对话框（M3 深色卡）

    private void showAddDialog() {
        Calendar dflt = Calendar.getInstance();
        dflt.add(Calendar.HOUR_OF_DAY, 1);
        final int[] picked = {dflt.get(Calendar.HOUR_OF_DAY), dflt.get(Calendar.MINUTE)};
        final int[] soundIdx = {0};
        String defKey = SoundCatalog.defaultKey(this);
        java.util.List<SoundCatalog.Item> catalog = SoundCatalog.all(this);
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).key.equals(defKey)) { soundIdx[0] = i; break; }
        }

        releasePreview();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("新闹钟");
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xFFE5E1E9);
        card.addView(title);

        final TextView timeBtn = new TextView(this);
        timeBtn.setText(String.format("%02d:%02d", picked[0], picked[1]));
        timeBtn.setTextSize(38);
        timeBtn.setGravity(Gravity.CENTER);
        timeBtn.setTextColor(0xFFE5E1E9);
        timeBtn.setBackgroundResource(R.drawable.bg_glass_chip);
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
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(32), 1f);
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
        soundName.setTextColor(0xFFE5E1E9);
        soundRow.addView(soundName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView previewBtn = pill("▶ 试听", R.drawable.bg_glass_chip, 0xFFB9C0E8);
        previewBtn.setOnClickListener(v -> previewSound(catalog.get(soundIdx[0]).resId));
        soundRow.addView(previewBtn, pillLp());

        TextView nextBtn = pill("换一个", R.drawable.bg_glass_chip, 0xFFB9C0E8);
        nextBtn.setOnClickListener(v -> {
            soundIdx[0] = (soundIdx[0] + 1) % catalog.size();
            soundName.setText(catalog.get(soundIdx[0]).name);
            previewSound(catalog.get(soundIdx[0]).resId);
        });
        LinearLayout.LayoutParams nlp = pillLp();
        nlp.leftMargin = dp(8);
        soundRow.addView(nextBtn, nlp);

        soundName.setText(catalog.get(soundIdx[0]).name);
        card.addView(soundRow);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(18);

        TextView cancel = pill("取消", R.drawable.bg_glass_chip, 0xFFB9C0E8);
        cancel.setOnClickListener(v -> {
            releasePreview();
            dialog.dismiss();
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView save = pill("保存", R.drawable.bg_filled, 0xFF4A4459);
        save.setOnClickListener(v -> {
            AlarmStore.Alarm a = new AlarmStore.Alarm();
            a.hour = picked[0];
            a.minute = picked[1];
            a.label = "";
            a.sound = catalog.get(soundIdx[0]).key;
            for (int i = 0; i < 7; i++) a.days[i] = dayChips[i].isSelected();
            AlarmStore.create(MainActivity.this, a);
            releasePreview();
            dialog.dismiss();
            rebuild();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(44), 1f);
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
        tv.setTextSize(11.5f);
        tv.setTextColor(0xFFC8C5D0);
        tv.setPadding(0, dp(14), 0, dp(7));
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
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
    }

    private void styleDayChip(TextView chip) {
        boolean on = chip.isSelected();
        chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_glass_chip);
        chip.setTextColor(on ? 0xFF4A4459 : 0xFFC8C5D0);
        chip.setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
    }

    private void previewSound(int resId) {
        releasePreview();
        if (resId == 0) return;
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
        root.setBackgroundColor(0xFF131318);
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
        copy.setTextColor(0xFF4A4459);
        copy.setBackgroundResource(R.drawable.bg_tonal);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        TextView clear = new TextView(this);
        clear.setText("清除并进入");
        clear.setGravity(Gravity.CENTER);
        clear.setTextColor(0xFF4A4459);
        clear.setBackgroundResource(R.drawable.bg_tonal);
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
