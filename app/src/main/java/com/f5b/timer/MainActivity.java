package com.f5b.timer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final long SECOND = 1_000L;
    private static final long LANDING_START = 270L;
    private static final long LIMIT = 300L;
    private static final String PREFS = "f5b_timer_settings";
    private static final String KEY_CUSTOM_MODE = "custom_mode";
    private static final String KEY_SEGMENTS = "segments";
    private static final String KEY_TOTAL_DURATION = "total_duration_seconds";
    private static final String KEY_OVERTIME_ENABLED = "overtime_enabled";
    private static final String KEY_OVERTIME_DURATION = "overtime_duration_seconds";
    private static final String KEY_OVERTIME_INTERVAL = "overtime_interval_seconds";
    private static final String KEY_OVERTIME_COUNTDOWN = "overtime_countdown";
    private static final int INK = Color.rgb(20, 34, 48);
    private static final int MUTED = Color.rgb(101, 116, 130);
    private static final int ACCENT = Color.rgb(20, 82, 72);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Segment> segments = new ArrayList<>();
    private TextView clockText;
    private TextView phaseText;
    private Button startPauseButton;
    private Button modeButton;
    private Button settingsButton;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean customMode = false;
    private boolean running = false;
    private long totalDurationSeconds = 300L;
    private boolean overtimeEnabled = false;
    private long overtimeDurationSeconds = 60L;
    private long overtimeIntervalSeconds = 5L;
    private boolean overtimeCountdown = false;
    private long elapsedBeforeRun = 0L;
    private long runStartedAt = 0L;
    private long lastAnnouncedSecond = -1L;
    private int lastAnnouncedOvertime = -1;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long elapsedSeconds = Math.max(0L, elapsedMillis() / SECOND);
            if (customMode) elapsedSeconds = Math.min(elapsedSeconds, customMaximumDuration());
            updateDisplay(elapsedSeconds);
            announceAt(elapsedSeconds);
            if (customMode && elapsedSeconds >= customMaximumDuration()) {
                elapsedBeforeRun = customMaximumDuration() * SECOND;
                running = false;
                startPauseButton.setText("开始");
                updateModeControls();
            }
            if (running) handler.postDelayed(this, 100L);
        }
    };

    private static class Segment {
        String name;
        long durationSeconds;
        boolean countdown;
        long intervalSeconds;

        Segment(String name, long durationSeconds, boolean countdown, long intervalSeconds) {
            this.name = name;
            this.durationSeconds = durationSeconds;
            this.countdown = countdown;
            this.intervalSeconds = intervalSeconds;
        }

        Segment copy() {
            return new Segment(name, durationSeconds, countdown, intervalSeconds);
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        loadSettings();
        buildInterface();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
                if (ttsReady) tts.setSpeechRate(1.15f);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    phaseText.setText("请在系统设置中安装中文语音数据");
                }
            }
        });
        updateModeControls();
        updateDisplay(0L);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(22), dp(24), dp(22), dp(20));
        root.setBackgroundColor(Color.rgb(20, 110, 80));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("FLIGHT TIMER  ·  F5B A");
        eyebrow.setTextColor(Color.rgb(220, 239, 230));
        eyebrow.setTextSize(12);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setGravity(Gravity.CENTER);
        root.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(24)));

        TextView title = new TextView(this);
        title.setText("A 科目计时器");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout planControls = new LinearLayout(this);
        planControls.setGravity(Gravity.CENTER);
        modeButton = makeButton("模式：F5B竞赛", v -> showModePicker());
        settingsButton = makeButton("方案设置", v -> showPlanSettings());
        LinearLayout.LayoutParams planButtonParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        planButtonParams.setMargins(dp(4), dp(8), dp(4), dp(8));
        planControls.addView(modeButton, planButtonParams);
        planControls.addView(settingsButton, planButtonParams);
        root.addView(planControls, new LinearLayout.LayoutParams(-1, dp(60)));

        clockText = new TextView(this);
        clockText.setText("00:00");
        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(82);
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockText.setGravity(Gravity.CENTER);

        LinearLayout clockPanel = new LinearLayout(this);
        clockPanel.setOrientation(LinearLayout.VERTICAL);
        clockPanel.setGravity(Gravity.CENTER);
        clockPanel.setBackground(rounded(Color.rgb(18, 42, 45), dp(28)));
        clockPanel.setElevation(dp(8));
        LinearLayout.LayoutParams clockPanelParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        clockPanelParams.setMargins(0, dp(12), 0, dp(14));
        clockPanel.addView(clockText, new LinearLayout.LayoutParams(-1, 0, 1f));

        phaseText = new TextView(this);
        phaseText.setTextColor(Color.WHITE);
        phaseText.setTextColor(Color.rgb(218, 235, 227));
        phaseText.setTextSize(16);
        phaseText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        phaseText.setGravity(Gravity.CENTER);
        phaseText.setPadding(dp(12), 0, dp(12), dp(16));
        clockPanel.addView(phaseText, new LinearLayout.LayoutParams(-1, dp(56)));
        root.addView(clockPanel, clockPanelParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startPauseButton = makeButton("开始", v -> toggleRun());
        Button reset = makeButton("重置", v -> resetTimer());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(60), 1f);
        buttonParams.setMargins(dp(6), dp(8), dp(6), dp(4));
        controls.addView(startPauseButton, buttonParams);
        controls.addView(reset, buttonParams);
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(88)));

        setContentView(root);
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(rounded(ACCENT, dp(16)));
        button.setElevation(dp(2));
        button.setOnClickListener(listener);
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void updateModeControls() {
        if (modeButton == null) return;
        modeButton.setText(customMode ? "模式：自定义" : "模式：F5B竞赛");
        settingsButton.setVisibility(customMode ? View.VISIBLE : View.INVISIBLE);
        modeButton.setEnabled(!running);
        settingsButton.setEnabled(!running);
    }

    private void showModePicker() {
        if (running) return;
        String[] choices = {"F5B A科目规则预设", "自定义分段方案"};
        new AlertDialog.Builder(this)
                .setTitle("选择计时模式")
                .setSingleChoiceItems(choices, customMode ? 1 : 0, (dialog, which) -> {
                    customMode = which == 1;
                    getPreferences(0).edit().putBoolean(KEY_CUSTOM_MODE, customMode).apply();
                    resetTimer();
                    updateModeControls();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPlanSettings() {
        if (running) return;
        ArrayList<Segment> draft = getPreferences(0).contains(KEY_SEGMENTS)
                ? copySegments(segments) : new ArrayList<>();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(14));

        EditText durationInput = addInput(content, "方案总时长（秒）",
                String.valueOf(totalDurationSeconds), InputType.TYPE_CLASS_NUMBER);
        content.addView(formHint("先设置整场时长，例如 5 分钟填 300 秒；分段时长合计必须刚好等于总时长。"));

        TextView allocationSummary = new TextView(this);
        allocationSummary.setTextColor(INK);
        allocationSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        allocationSummary.setTextSize(14);
        allocationSummary.setGravity(Gravity.CENTER_VERTICAL);
        allocationSummary.setPadding(dp(2), dp(8), dp(2), dp(10));
        content.addView(allocationSummary);

        CheckBox overtimeToggle = new CheckBox(this);
        overtimeToggle.setText("总时长结束后继续超时计时");
        overtimeToggle.setTextColor(INK);
        overtimeToggle.setTextSize(15);
        overtimeToggle.setChecked(overtimeEnabled);
        overtimeToggle.setPadding(0, dp(4), 0, dp(2));
        content.addView(overtimeToggle);
        content.addView(formHint("超时独立于分段方案运行，不计入上方分段总时长。"));
        EditText overtimeDurationInput = addInput(content, "最长超时时长（秒）",
                String.valueOf(overtimeDurationSeconds), InputType.TYPE_CLASS_NUMBER);
        EditText overtimeIntervalInput = addInput(content, "超时报时间隔（秒）",
                String.valueOf(overtimeIntervalSeconds), InputType.TYPE_CLASS_NUMBER);
        TextView overtimeDirectionLabel = new TextView(this);
        overtimeDirectionLabel.setText("超时计时方向");
        overtimeDirectionLabel.setTextSize(13);
        overtimeDirectionLabel.setTextColor(MUTED);
        overtimeDirectionLabel.setPadding(dp(2), dp(8), 0, 0);
        content.addView(overtimeDirectionLabel);
        Spinner overtimeDirection = new Spinner(this);
        ArrayAdapter<String> overtimeDirectionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"正计时（从超时 1 秒开始）", "倒计时（从最长时长倒数）"});
        overtimeDirectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        overtimeDirection.setAdapter(overtimeDirectionAdapter);
        overtimeDirection.setSelection(overtimeCountdown ? 1 : 0);
        content.addView(overtimeDirection, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Runnable[] refreshHolder = new Runnable[1];
        Button add = makeButton("＋ 添加时段", v -> {
            long target = parsePositiveOrZero(durationInput.getText().toString());
            long remaining = target - draftDuration(draft);
            if (remaining <= 0L) return;
            Segment initial = new Segment("阶段" + (draft.size() + 1), Math.min(10L, remaining), false, 1L);
            showSegmentEditor(draft, -1, refreshHolder[0], initial);
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(52));
        addParams.topMargin = dp(4);
        content.addView(add, addParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义分段方案")
                .setView(scroll)
                .setPositiveButton("保存方案", null)
                .setNegativeButton("取消", null)
                .create();

        refreshHolder[0] = () -> {
            list.removeAllViews();
            long target = parsePositiveOrZero(durationInput.getText().toString());
            long allocated = draftDuration(draft);
            if (target <= 0L || target > 24L * 60L * 60L) {
                allocationSummary.setText("请输入 1–86400 秒的总时长");
                add.setEnabled(false);
            } else if (allocated < target) {
                allocationSummary.setText("已分配 " + formatChineseTime(allocated) + " / "
                        + formatChineseTime(target) + "  ·  还剩 " + formatChineseTime(target - allocated));
                add.setEnabled(true);
            } else if (allocated > target) {
                allocationSummary.setText("已超出总时长 " + formatChineseTime(allocated - target)
                        + "，请编辑或删除时段");
                add.setEnabled(false);
            } else {
                allocationSummary.setText("分段已覆盖全部总时长  ·  " + draft.size() + " 个时段");
                add.setEnabled(false);
            }
            for (int index = 0; index < draft.size(); index++) {
                final int itemIndex = index;
                Segment segment = draft.get(index);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(10), dp(14), dp(8));
                row.setBackground(rounded(Color.WHITE, dp(18)));
                row.setElevation(dp(2));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.bottomMargin = dp(10);
                list.addView(row, rowParams);
                TextView details = new TextView(this);
                details.setText((index + 1) + "  " + segment.name);
                details.setTextColor(INK);
                details.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                details.setTextSize(17);
                details.setPadding(0, dp(3), 0, dp(2));
                details.setOnClickListener(v -> showSegmentEditor(draft, itemIndex, refreshHolder[0]));
                row.addView(details);
                TextView summary = new TextView(this);
                summary.setText(segmentSummary(index, segment));
                summary.setTextColor(MUTED);
                summary.setTextSize(13);
                summary.setPadding(0, 0, 0, dp(5));
                summary.setOnClickListener(v -> showSegmentEditor(draft, itemIndex, refreshHolder[0]));
                row.addView(summary);
                Button more = makeButton("⋮", v -> showSegmentActions(draft, itemIndex, refreshHolder[0], v));
                more.setTextSize(20);
                more.setElevation(0);
                LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(48), dp(36));
                moreParams.gravity = Gravity.RIGHT;
                row.addView(more, moreParams);
            }
        };
        refreshHolder[0].run();
        durationInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                refreshHolder[0].run();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (draft.isEmpty()) {
                Toast.makeText(this, "请至少添加一个时段", Toast.LENGTH_SHORT).show();
                return;
            }
            long target = parsePositiveOrZero(durationInput.getText().toString());
            if (target <= 0L || target > 24L * 60L * 60L) {
                Toast.makeText(this, "总时长须为 1–86400 秒", Toast.LENGTH_SHORT).show();
                return;
            }
            long allocated = draftDuration(draft);
            if (allocated != target) {
                long difference = Math.abs(target - allocated);
                Toast.makeText(this, allocated < target
                        ? "还差 " + formatChineseTime(difference) + " 未分段"
                        : "分段超出总时长 " + formatChineseTime(difference), Toast.LENGTH_LONG).show();
                return;
            }
            boolean enableOvertime = overtimeToggle.isChecked();
            long overtimeDuration = parsePositiveOrZero(overtimeDurationInput.getText().toString());
            long overtimeInterval = parsePositiveOrZero(overtimeIntervalInput.getText().toString());
            if (enableOvertime && (overtimeDuration <= 0L || overtimeDuration > 24L * 60L * 60L
                    || overtimeInterval <= 0L || overtimeInterval > 24L * 60L * 60L)) {
                Toast.makeText(this, "超时时长和报时间隔须为 1–86400 秒", Toast.LENGTH_LONG).show();
                return;
            }
            segments.clear();
            segments.addAll(copySegments(draft));
            totalDurationSeconds = target;
            overtimeEnabled = enableOvertime;
            if (overtimeDuration > 0L) overtimeDurationSeconds = overtimeDuration;
            if (overtimeInterval > 0L) overtimeIntervalSeconds = overtimeInterval;
            overtimeCountdown = overtimeDirection.getSelectedItemPosition() == 1;
            customMode = true;
            saveSettings();
            resetTimer();
            updateModeControls();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showSegmentActions(ArrayList<Segment> draft, int index, Runnable refresh, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("编辑").setOnMenuItemClickListener(item -> {
            showSegmentEditor(draft, index, refresh);
            return true;
        });
        android.view.MenuItem up = menu.getMenu().add("上移");
        up.setEnabled(index > 0);
        up.setOnMenuItemClickListener(item -> {
            Collections.swap(draft, index, index - 1);
            refresh.run();
            return true;
        });
        android.view.MenuItem down = menu.getMenu().add("下移");
        down.setEnabled(index < draft.size() - 1);
        down.setOnMenuItemClickListener(item -> {
            Collections.swap(draft, index, index + 1);
            refresh.run();
            return true;
        });
        menu.getMenu().add("删除").setOnMenuItemClickListener(item -> {
            draft.remove(index);
            refresh.run();
            return true;
        });
        menu.show();
    }

    private void showSegmentEditor(ArrayList<Segment> draft, int index, Runnable refresh) {
        Segment segment = draft.get(index).copy();
        showSegmentEditor(draft, index, refresh, segment);
    }

    private void showSegmentEditor(ArrayList<Segment> draft, int index, Runnable refresh, Segment initial) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(8));

        EditText nameInput = addInput(form, "时段名称", initial.name, InputType.TYPE_CLASS_TEXT);
        EditText durationInput = addInput(form, "时长（秒）", String.valueOf(initial.durationSeconds), InputType.TYPE_CLASS_NUMBER);
        form.addView(formHint("直接输入秒数，例如 10 秒就填 10；最长 24 小时。"));

        TextView directionLabel = new TextView(this);
        directionLabel.setText("计时方向");
        directionLabel.setTextSize(14);
        directionLabel.setTextColor(MUTED);
        directionLabel.setPadding(dp(2), dp(8), 0, dp(2));
        form.addView(directionLabel);
        Spinner direction = new Spinner(this);
        ArrayAdapter<String> directionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"正计时", "倒计时"});
        directionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        direction.setAdapter(directionAdapter);
        direction.setSelection(initial.countdown ? 1 : 0);
        form.addView(direction, new LinearLayout.LayoutParams(-1, dp(48)));

        EditText intervalInput = addInput(form, "报时间隔（秒）", String.valueOf(initial.intervalSeconds), InputType.TYPE_CLASS_NUMBER);
        form.addView(formHint("例如每 5 秒播报一次填 5；逐秒播报填 1。"));

        AlertDialog editor = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "编辑时段" : "添加时段")
                .setView(form)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long duration = parsePositiveOrZero(durationInput.getText().toString());
            long interval = parsePositiveOrZero(intervalInput.getText().toString());
            if (duration <= 0L || duration > 24L * 60L * 60L || interval <= 0L) {
                Toast.makeText(this, "时长须为 1–86400 秒，报时间隔至少 1 秒", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) name = "阶段" + (index >= 0 ? index + 1 : draft.size() + 1);
            Segment updated = new Segment(name, duration, direction.getSelectedItemPosition() == 1, interval);
            if (index >= 0) draft.set(index, updated);
            else draft.add(updated);
            refresh.run();
            editor.dismiss();
        }));
        editor.show();
    }

    private EditText addInput(LinearLayout parent, String hint, String value, int inputType) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(13);
        label.setTextColor(MUTED);
        label.setPadding(dp(2), dp(8), 0, 0);
        parent.addView(label);
        EditText input = new EditText(this);
        input.setHint("请输入" + hint);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(rounded(Color.rgb(242, 245, 247), dp(12)));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(48));
        inputParams.topMargin = dp(4);
        inputParams.bottomMargin = dp(4);
        parent.addView(input, inputParams);
        return input;
    }

    private TextView formHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextSize(12);
        hint.setTextColor(MUTED);
        hint.setPadding(dp(2), 0, 0, dp(4));
        return hint;
    }

    private long parsePositiveOrZero(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String segmentSummary(int index, Segment segment) {
        return formatChineseTime(segment.durationSeconds)
                + " · " + (segment.countdown ? "倒计时" : "正计时")
                + " · 每 " + segment.intervalSeconds + " 秒报时";
    }


    private void toggleRun() {
        if (running) {
            elapsedBeforeRun = elapsedMillis();
            running = false;
            handler.removeCallbacks(ticker);
            startPauseButton.setText("继续");
            updateDisplay(elapsedBeforeRun / SECOND);
            updateModeControls();
        } else {
            if (customMode && elapsedBeforeRun >= customMaximumDuration() * SECOND) {
                elapsedBeforeRun = 0L;
                resetSpeechState();
            }
            runStartedAt = SystemClock.elapsedRealtime();
            running = true;
            startPauseButton.setText("暂停");
            updateModeControls();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
    }

    private void resetTimer() {
        running = false;
        elapsedBeforeRun = 0L;
        runStartedAt = 0L;
        handler.removeCallbacks(ticker);
        resetSpeechState();
        if (tts != null) tts.stop();
        if (startPauseButton != null) startPauseButton.setText("开始");
        updateModeControls();
        if (clockText != null) updateDisplay(0L);
    }

    private void resetSpeechState() {
        lastAnnouncedSecond = -1L;
        lastAnnouncedOvertime = -1;
    }

    private long elapsedMillis() {
        return elapsedBeforeRun + (running ? SystemClock.elapsedRealtime() - runStartedAt : 0L);
    }

    private void updateDisplay(long seconds) {
        LinearLayout root = (LinearLayout) clockText.getParent();
        if (customMode) {
            long total = customPlanDuration();
            if (seconds >= total) {
                long overtimeElapsed = Math.min(Math.max(0L, seconds - total), overtimeDurationSeconds);
                long displaySeconds = total + (overtimeEnabled ? overtimeElapsed : 0L);
                setClock(displaySeconds);
                root.setBackgroundColor(Color.rgb(150, 20, 28));
                clockText.setTextColor(overtimeEnabled && displaySeconds % 2L != 0L ? Color.YELLOW : Color.WHITE);
                if (!overtimeEnabled) {
                    phaseText.setText("方案完成 · 总计时 " + formatChineseTime(total));
                } else if (overtimeElapsed >= overtimeDurationSeconds) {
                    phaseText.setText("超时计时结束 · 已超时 " + formatOvertime(overtimeElapsed));
                } else {
                    long overtimeValue = overtimeCountdown
                            ? overtimeDurationSeconds - overtimeElapsed : overtimeElapsed;
                    phaseText.setText((overtimeCountdown ? "超时倒计时 · 剩余 " : "超时正计时 · 加时 ")
                            + formatOvertime(overtimeValue));
                }
                return;
            }
            SegmentPosition position = locateSegment(seconds);
            Segment segment = segments.get(position.index);
            setClock(seconds);
            long remaining = total - seconds;
            if (remaining <= 30L) {
                root.setBackgroundColor(Color.rgb(183, 35, 32));
            } else if (remaining <= 60L) {
                root.setBackgroundColor(Color.rgb(190, 145, 20));
            } else {
                root.setBackgroundColor(Color.rgb(20, 110, 80));
            }
            clockText.setTextColor(Color.WHITE);
            phaseText.setText("第 " + (position.index + 1) + "/" + segments.size() + " 段 · "
                    + segment.name + " · " + (segment.countdown ? "本段剩余 "
                    + formatChineseTime(segment.durationSeconds - position.elapsedSeconds)
                    : "本段已过 " + formatChineseTime(position.elapsedSeconds)));
            return;
        }

        setClock(seconds);
        if (seconds >= LIMIT) {
            root.setBackgroundColor(Color.rgb(150, 20, 28));
            clockText.setTextColor(seconds % 2L == 0L ? Color.WHITE : Color.YELLOW);
            phaseText.setText("超时正读 · 加时 " + formatOvertime(seconds - LIMIT));
        } else if (seconds >= LANDING_START) {
            root.setBackgroundColor(Color.rgb(183, 35, 32));
            clockText.setTextColor(Color.WHITE);
            phaseText.setText("落地倒计时");
        } else if (seconds >= 240L) {
            root.setBackgroundColor(Color.rgb(190, 145, 20));
            clockText.setTextColor(Color.WHITE);
            phaseText.setText("最后一分钟准备落地");
        } else {
            root.setBackgroundColor(Color.rgb(20, 110, 80));
            clockText.setTextColor(Color.WHITE);
            phaseText.setText(running ? "比赛计时中" : (seconds == 0 ? "准备就绪" : "已暂停"));
        }
    }

    private void setClock(long seconds) {
        clockText.setText(String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L));
    }

    private static class SegmentPosition {
        final int index;
        final long elapsedSeconds;
        SegmentPosition(int index, long elapsedSeconds) {
            this.index = index;
            this.elapsedSeconds = elapsedSeconds;
        }
    }

    private SegmentPosition locateSegment(long elapsedSeconds) {
        long offset = 0L;
        for (int i = 0; i < segments.size(); i++) {
            long end = offset + segments.get(i).durationSeconds;
            if (elapsedSeconds < end) return new SegmentPosition(i, elapsedSeconds - offset);
            offset = end;
        }
        return new SegmentPosition(Math.max(0, segments.size() - 1), 0L);
    }

    private void announceAt(long seconds) {
        if (seconds == lastAnnouncedSecond) return;
        String message = customMode ? customAnnouncement(seconds) : f5bAnnouncement(seconds);
        lastAnnouncedSecond = seconds;
        if (message != null && ttsReady) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "f5b-" + seconds);
        }
    }

    private String f5bAnnouncement(long seconds) {
        if (seconds >= 1L && seconds <= 10L) {
            return String.valueOf(seconds);
        } else if (seconds >= 30L && seconds < 240L && seconds % 30L == 0L) {
            return formatChineseTime(seconds);
        } else if (seconds == 240L) {
            return "4分钟";
        } else if (seconds > 240L && seconds < LANDING_START && (seconds - 240L) % 5L == 0L) {
            return (LIMIT - seconds) + "秒";
        } else if (seconds >= LIMIT - 10L && seconds < LIMIT) {
            return String.valueOf(LIMIT - seconds);
        } else if (seconds >= LANDING_START && seconds < LIMIT && (LIMIT - seconds) % 5L == 0L) {
            return (LIMIT - seconds) + "秒";
        } else if (seconds == LIMIT) {
            return "时间到";
        } else if (seconds > LIMIT) {
            long overtimeSeconds = seconds - LIMIT;
            if ((int) overtimeSeconds != lastAnnouncedOvertime) {
                lastAnnouncedOvertime = (int) overtimeSeconds;
                return String.valueOf(overtimeSeconds);
            }
        }
        return null;
    }

    private String customAnnouncement(long seconds) {
        long total = customPlanDuration();
        if (seconds >= total) {
            if (lastAnnouncedSecond < total) return "时间到";
            return customOvertimeAnnouncement(seconds - total);
        }
        long segmentStart = 0L;
        for (Segment segment : segments) {
            long localSeconds = seconds - segmentStart;
            long segmentEnd = segmentStart + segment.durationSeconds;
            if (seconds <= segmentEnd) {
                if (localSeconds > 0L && localSeconds % segment.intervalSeconds == 0L) {
                    if (segment.countdown && localSeconds == segment.durationSeconds && segmentEnd < total) {
                        return null;
                    }
                    long reportValue = segment.countdown
                            ? Math.max(0L, segment.durationSeconds - localSeconds)
                            : seconds;
                    return segment.intervalSeconds < 10L
                            ? String.valueOf(reportValue) : formatChineseTime(reportValue);
                }
                return null;
            }
            segmentStart = segmentEnd;
        }
        return null;
    }

    private String customOvertimeAnnouncement(long overtimeElapsed) {
        if (!overtimeEnabled || overtimeElapsed <= 0L || overtimeElapsed > overtimeDurationSeconds) return null;
        if (overtimeElapsed == overtimeDurationSeconds && overtimeCountdown) return "0";
        if (overtimeElapsed % overtimeIntervalSeconds != 0L) {
            return overtimeElapsed == overtimeDurationSeconds ? "超时结束" : null;
        }
        long reportValue = overtimeCountdown
                ? overtimeDurationSeconds - overtimeElapsed : overtimeElapsed;
        return overtimeIntervalSeconds < 10L
                ? String.valueOf(reportValue) : formatChineseTime(reportValue);
    }

    private String formatChineseTime(long seconds) {
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        if (minutes == 0L) return remainder + "秒";
        if (remainder == 0L) return minutes + "分钟";
        return minutes + "分" + remainder + "秒";
    }

    private String formatOvertime(long seconds) {
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        if (minutes == 0L) return remainder + "秒";
        return minutes + "分" + remainder + "秒";
    }

    private long customPlanDuration() {
        return totalDurationSeconds;
    }

    private long customMaximumDuration() {
        return customPlanDuration() + (overtimeEnabled ? overtimeDurationSeconds : 0L);
    }

    private long draftDuration(List<Segment> plan) {
        long total = 0L;
        for (Segment segment : plan) total += segment.durationSeconds;
        return total;
    }

    private ArrayList<Segment> copySegments(List<Segment> source) {
        ArrayList<Segment> copy = new ArrayList<>();
        for (Segment segment : source) copy.add(segment.copy());
        return copy;
    }

    private void loadSettings() {
        android.content.SharedPreferences preferences = getPreferences(0);
        customMode = preferences.getBoolean(KEY_CUSTOM_MODE, false);
        overtimeEnabled = preferences.getBoolean(KEY_OVERTIME_ENABLED, false);
        overtimeDurationSeconds = preferences.getLong(KEY_OVERTIME_DURATION, 60L);
        overtimeIntervalSeconds = preferences.getLong(KEY_OVERTIME_INTERVAL, 5L);
        overtimeCountdown = preferences.getBoolean(KEY_OVERTIME_COUNTDOWN, false);
        String encoded = preferences.getString(KEY_SEGMENTS, null);
        if (encoded != null) {
            try {
                JSONArray array = new JSONArray(encoded);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    long duration = item.optLong("duration", 0L);
                    long interval = item.optLong("interval", 0L);
                    if (duration > 0L && interval > 0L) {
                        segments.add(new Segment(item.optString("name", "阶段" + (i + 1)),
                                duration, item.optBoolean("countdown", false), interval));
                    }
                }
            } catch (Exception ignored) {
                segments.clear();
            }
        }
        if (segments.isEmpty()) segments.add(new Segment("阶段1", 300L, false, 30L));
        long savedTotal = preferences.getLong(KEY_TOTAL_DURATION, -1L);
        long segmentTotal = draftDuration(segments);
        totalDurationSeconds = savedTotal > 0L && savedTotal == segmentTotal
                ? savedTotal : segmentTotal;
    }

    private void saveSettings() {
        JSONArray array = new JSONArray();
        for (Segment segment : segments) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", segment.name);
                item.put("duration", segment.durationSeconds);
                item.put("countdown", segment.countdown);
                item.put("interval", segment.intervalSeconds);
            } catch (Exception ignored) { }
            array.put(item);
        }
        getPreferences(0).edit()
                .putBoolean(KEY_CUSTOM_MODE, customMode)
                .putLong(KEY_TOTAL_DURATION, totalDurationSeconds)
                .putBoolean(KEY_OVERTIME_ENABLED, overtimeEnabled)
                .putLong(KEY_OVERTIME_DURATION, overtimeDurationSeconds)
                .putLong(KEY_OVERTIME_INTERVAL, overtimeIntervalSeconds)
                .putBoolean(KEY_OVERTIME_COUNTDOWN, overtimeCountdown)
                .putString(KEY_SEGMENTS, array.toString())
                .apply();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
