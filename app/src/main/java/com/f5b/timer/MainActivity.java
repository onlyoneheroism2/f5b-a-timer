package com.f5b.timer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private long elapsedBeforeRun = 0L;
    private long runStartedAt = 0L;
    private long lastAnnouncedSecond = -1L;
    private int lastAnnouncedOvertime = -1;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long elapsedSeconds = Math.max(0L, elapsedMillis() / SECOND);
            if (customMode) elapsedSeconds = Math.min(elapsedSeconds, customPlanDuration());
            updateDisplay(elapsedSeconds);
            announceAt(elapsedSeconds);
            if (customMode && elapsedSeconds >= customPlanDuration()) {
                elapsedBeforeRun = customPlanDuration() * SECOND;
                running = false;
                startPauseButton.setText("开始");
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
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(20, 110, 80));

        TextView title = new TextView(this);
        title.setText("F5B  A科目计时器");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout planControls = new LinearLayout(this);
        planControls.setGravity(Gravity.CENTER);
        modeButton = makeButton("模式：F5B竞赛", v -> showModePicker());
        settingsButton = makeButton("方案设置", v -> showPlanSettings());
        LinearLayout.LayoutParams planButtonParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        planButtonParams.setMargins(dp(4), dp(3), dp(4), dp(3));
        planControls.addView(modeButton, planButtonParams);
        planControls.addView(settingsButton, planButtonParams);
        root.addView(planControls, new LinearLayout.LayoutParams(-1, dp(60)));

        clockText = new TextView(this);
        clockText.setText("00:00");
        clockText.setTextColor(Color.WHITE);
        clockText.setTextSize(88);
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockText.setGravity(Gravity.CENTER);
        root.addView(clockText, new LinearLayout.LayoutParams(-1, 0, 1f));

        phaseText = new TextView(this);
        phaseText.setTextColor(Color.WHITE);
        phaseText.setTextSize(21);
        phaseText.setGravity(Gravity.CENTER);
        root.addView(phaseText, new LinearLayout.LayoutParams(-1, dp(72)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startPauseButton = makeButton("开始", v -> toggleRun());
        Button reset = makeButton("重置", v -> resetTimer());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(64), 1f);
        buttonParams.setMargins(dp(6), dp(10), dp(6), dp(8));
        controls.addView(startPauseButton, buttonParams);
        controls.addView(reset, buttonParams);
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(88)));

        setContentView(root);
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setOnClickListener(listener);
        return button;
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
        ArrayList<Segment> draft = copySegments(segments);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(8), dp(18), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义分段方案")
                .setView(scroll)
                .setPositiveButton("保存方案", null)
                .setNegativeButton("取消", null)
                .create();

        Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> {
            list.removeAllViews();
            TextView hint = new TextView(this);
            hint.setText("每段分别设置时长、正/倒计时和报时间隔。间隔设为 1 秒即逐秒报时。");
            hint.setTextSize(14);
            hint.setPadding(0, dp(4), 0, dp(12));
            list.addView(hint);
            for (int index = 0; index < draft.size(); index++) {
                final int itemIndex = index;
                Segment segment = draft.get(index);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                TextView details = new TextView(this);
                details.setText(segmentSummary(index, segment));
                details.setTextSize(16);
                row.addView(details);
                LinearLayout actions = new LinearLayout(this);
                Button edit = makeButton("编辑", v -> showSegmentEditor(draft, itemIndex, refreshRef[0]));
                Button delete = makeButton("删除", v -> {
                    draft.remove(itemIndex);
                    refreshRef[0].run();
                });
                actions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1f));
                actions.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1f));
                row.addView(actions);
                LinearLayout orderActions = new LinearLayout(this);
                Button moveUp = makeButton("上移", v -> {
                    Collections.swap(draft, itemIndex, itemIndex - 1);
                    refreshRef[0].run();
                });
                moveUp.setEnabled(itemIndex > 0);
                Button moveDown = makeButton("下移", v -> {
                    Collections.swap(draft, itemIndex, itemIndex + 1);
                    refreshRef[0].run();
                });
                moveDown.setEnabled(itemIndex < draft.size() - 1);
                orderActions.addView(moveUp, new LinearLayout.LayoutParams(0, dp(40), 1f));
                orderActions.addView(moveDown, new LinearLayout.LayoutParams(0, dp(40), 1f));
                row.addView(orderActions);
                list.addView(row);
                View divider = new View(this);
                divider.setBackgroundColor(Color.LTGRAY);
                list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            Button add = makeButton("＋ 添加时段", v -> {
                Segment initial = new Segment("阶段" + (draft.size() + 1), 60L, false, 10L);
                showSegmentEditor(draft, -1, refreshRef[0], initial);
            });
            LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48));
            addParams.topMargin = dp(10);
            list.addView(add, addParams);
            TextView total = new TextView(this);
            total.setText("方案总时长：" + formatChineseTime(draftDuration(draft)));
            total.setTextSize(16);
            total.setPadding(0, dp(10), 0, 0);
            list.addView(total);
        };
        refreshRef[0].run();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (draft.isEmpty()) {
                Toast.makeText(this, "请至少添加一个时段", Toast.LENGTH_SHORT).show();
                return;
            }
            segments.clear();
            segments.addAll(copySegments(draft));
            customMode = true;
            saveSettings();
            resetTimer();
            updateModeControls();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showSegmentEditor(ArrayList<Segment> draft, int index, Runnable refresh) {
        Segment segment = draft.get(index).copy();
        showSegmentEditor(draft, index, refresh, segment);
    }

    private void showSegmentEditor(ArrayList<Segment> draft, int index, Runnable refresh, Segment initial) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(6), dp(22), dp(4));

        EditText nameInput = addInput(form, "阶段名称", initial.name, InputType.TYPE_CLASS_TEXT);
        LinearLayout durationRow = new LinearLayout(this);
        durationRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText minutesInput = addInput(durationRow, "分钟", String.valueOf(initial.durationSeconds / 60L), InputType.TYPE_CLASS_NUMBER);
        EditText secondsInput = addInput(durationRow, "秒（0–59）", String.valueOf(initial.durationSeconds % 60L), InputType.TYPE_CLASS_NUMBER);
        form.addView(durationRow);

        TextView directionLabel = new TextView(this);
        directionLabel.setText("计时方向");
        directionLabel.setTextSize(14);
        form.addView(directionLabel);
        Spinner direction = new Spinner(this);
        ArrayAdapter<String> directionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"正计时", "倒计时"});
        directionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        direction.setAdapter(directionAdapter);
        direction.setSelection(initial.countdown ? 1 : 0);
        form.addView(direction, new LinearLayout.LayoutParams(-1, dp(48)));

        EditText intervalInput = addInput(form, "报时间隔（秒；1 = 逐秒）", String.valueOf(initial.intervalSeconds), InputType.TYPE_CLASS_NUMBER);

        AlertDialog editor = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "编辑时段" : "添加时段")
                .setView(form)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long minutes = parsePositiveOrZero(minutesInput.getText().toString());
            long seconds = parsePositiveOrZero(secondsInput.getText().toString());
            long interval = parsePositiveOrZero(intervalInput.getText().toString());
            if (minutes < 0L || minutes > 1_440L || seconds < 0L || seconds > 59L || interval <= 0L) {
                Toast.makeText(this, "时长须大于 0，秒数为 0–59，报时间隔至少 1 秒", Toast.LENGTH_SHORT).show();
                return;
            }
            long duration = minutes * 60L + seconds;
            if (duration <= 0L || duration > 24L * 60L * 60L) {
                Toast.makeText(this, "单段时长须在 1 秒到 24 小时之间", Toast.LENGTH_SHORT).show();
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
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        return input;
    }

    private long parsePositiveOrZero(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String segmentSummary(int index, Segment segment) {
        return (index + 1) + ". " + segment.name + " · " + formatChineseTime(segment.durationSeconds)
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
            if (customMode && elapsedBeforeRun >= customPlanDuration() * SECOND) {
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
                clockText.setText("00:00");
                clockText.setTextColor(Color.WHITE);
                root.setBackgroundColor(Color.rgb(150, 20, 28));
                phaseText.setText("方案完成");
                return;
            }
            SegmentPosition position = locateSegment(seconds);
            Segment segment = segments.get(position.index);
            long shownSeconds = segment.countdown
                    ? Math.max(0L, segment.durationSeconds - position.elapsedSeconds)
                    : position.elapsedSeconds;
            setClock(shownSeconds);
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
                    + segment.name + " · " + (segment.countdown ? "倒计时" : "正计时"));
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
        if (seconds >= total) return "时间到";
        SegmentPosition position = locateSegment(seconds);
        Segment segment = segments.get(position.index);
        long localSeconds = position.elapsedSeconds;
        if (localSeconds > 0L && localSeconds % segment.intervalSeconds == 0L) {
            long reportValue = segment.countdown
                    ? Math.max(0L, segment.durationSeconds - localSeconds)
                    : localSeconds;
            return formatChineseTime(reportValue);
        }
        return null;
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
        return draftDuration(segments);
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
        customMode = getPreferences(0).getBoolean(KEY_CUSTOM_MODE, false);
        String encoded = getPreferences(0).getString(KEY_SEGMENTS, null);
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
