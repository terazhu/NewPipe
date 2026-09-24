package org.schabi.newpipe.learning;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.BuildConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiLearningDialog {
    private static final String KEY_API_KEY = "tubecache_ark_api_key";
    private static final String KEY_MODEL = "tubecache_ark_model";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private AiLearningDialog() {
    }

    public interface PositionProvider {
        long getPositionMs();
    }

    public static void show(@NonNull final Context context, @NonNull final String title,
                            @NonNull final SubtitleRepository.CachedSubtitle subtitle,
                            @NonNull final PositionProvider positionProvider) {
        new Session(context, title, subtitle, positionProvider).show();
    }

    private static final class Session {
        private final Context context;
        private final String title;
        private final SubtitleRepository.CachedSubtitle subtitle;
        private final PositionProvider positionProvider;
        private final SharedPreferences preferences;
        private final JSONArray history = new JSONArray();
        private Spinner model;
        private TextView currentCaption;
        private TextView answer;
        private ProgressBar progress;
        private EditText question;
        private boolean requesting;

        private Session(final Context context, final String title,
                        final SubtitleRepository.CachedSubtitle subtitle,
                        final PositionProvider positionProvider) {
            this.context = context;
            this.title = title;
            this.subtitle = subtitle;
            this.positionProvider = positionProvider;
            preferences = PreferenceManager.getDefaultSharedPreferences(context);
        }

        private void show() {
            final int padding = dp(16);
            final LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(padding, dp(8), padding, 0);

            final LinearLayout modelRow = new LinearLayout(context);
            modelRow.setOrientation(LinearLayout.HORIZONTAL);
            model = new Spinner(context);
            final String[] models = {ArkAiClient.DEFAULT_MODEL, ArkAiClient.GLM_MODEL};
            model.setAdapter(new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_dropdown_item, models));
            model.setSelection(ArkAiClient.GLM_MODEL.equals(preferences.getString(
                    KEY_MODEL, ArkAiClient.DEFAULT_MODEL)) ? 1 : 0);
            modelRow.addView(model, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            final Button keyButton = button("API Key");
            modelRow.addView(keyButton);
            root.addView(modelRow);

            currentCaption = new TextView(context);
            currentCaption.setTextIsSelectable(true);
            currentCaption.setPadding(0, dp(6), 0, dp(8));
            root.addView(currentCaption);
            refreshContext();

            answer = new TextView(context);
            answer.setTextIsSelectable(true);
            answer.setText("可以提问，或选择“当前讲解 / AI 总结 / 重点学习”。");
            answer.setTextSize(16);
            final ScrollView answerScroll = new ScrollView(context);
            answerScroll.addView(answer);
            root.addView(answerScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            progress = new ProgressBar(context);
            progress.setVisibility(View.GONE);
            root.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

            final LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            final Button explain = weightedButton("当前讲解");
            final Button summary = weightedButton("AI 总结");
            final Button focus = weightedButton("重点学习");
            actions.addView(explain);
            actions.addView(summary);
            actions.addView(focus);
            root.addView(actions);

            question = new EditText(context);
            question.setHint("问这集内容、表达、笑点或文化背景");
            question.setMaxLines(3);
            root.addView(question);

            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("AI 英语学习")
                    .setView(root)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("发送", null)
                    .create();
            explain.setOnClickListener(v -> request("请讲解【当前字幕语境】中的高阶表达、"
                    + "口语省略、笑点或文化背景；没有难点就直说。"));
            summary.setOnClickListener(v -> request("请输出结构化中文总结：一句话主题、"
                    + "3-6条核心要点、2-3句英文金句及翻译、3-5个值得学的地道表达。"));
            focus.setOnClickListener(v -> request("筛选真正值得中国高级学习者精学的难点。"
                    + "只保留六级以上的高阶词汇、熟词僻义、习语俚语、复杂句或连读难点；"
                    + "逐条给原文、等级、原因和中文讲解。"));
            keyButton.setOnClickListener(v -> editApiKey());
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        final String value = question.getText().toString().trim();
                        if (!value.isEmpty()) {
                            request(value);
                            question.setText("");
                        }
                    }));
            dialog.show();
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        private void request(final String prompt) {
            if (requesting) {
                return;
            }
            final String savedKey = preferences.getString(KEY_API_KEY, "").trim();
            final String key = savedKey.isEmpty() ? BuildConfig.ARK_API_KEY : savedKey;
            if (key.isEmpty()) {
                Toast.makeText(context, "请先设置火山方舟 API Key",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final String selectedModel = model.getSelectedItem().toString();
            preferences.edit().putString(KEY_MODEL, selectedModel).apply();
            refreshContext();
            requesting = true;
            progress.setVisibility(View.VISIBLE);
            appendConversation("你", prompt);

            final JSONArray messages = messages(prompt);
            EXECUTOR.execute(() -> {
                try {
                    final String result = ArkAiClient.ask(key, selectedModel, messages);
                    history.put(message("user", prompt));
                    history.put(message("assistant", result));
                    answer.post(() -> finishRequest(result));
                } catch (final Exception error) {
                    answer.post(() -> finishRequest("请求失败：" + error.getMessage()));
                }
            });
        }

        private JSONArray messages(final String prompt) {
            final String transcript = subtitle.transcript.substring(
                    0, Math.min(12000, subtitle.transcript.length()));
            final String system = "你是 TubeLingo 英语精听陪练，服务母语为中文的高级学习者。"
                    + "回答简洁，必须结合字幕语境，不要编造。解释词汇时给音标、本处词义、"
                    + "搭配和例句；解释习语、笑点或文化背景时重点说明为什么。\n当前视频："
                    + title + "\n当前字幕语境：\n"
                    + subtitle.contextAt(positionProvider.getPositionMs())
                    + "\n完整字幕（可能截断）：\n" + transcript;
            final JSONArray messages = new JSONArray().put(message("system", system));
            for (int i = Math.max(0, history.length() - 8); i < history.length(); i++) {
                messages.put(history.opt(i));
            }
            messages.put(message("user", prompt));
            return messages;
        }

        private void refreshContext() {
            currentCaption.setText("当前字幕\n"
                    + subtitle.contextAt(positionProvider.getPositionMs()));
        }

        private void finishRequest(final String result) {
            requesting = false;
            progress.setVisibility(View.GONE);
            appendConversation("AI", result);
        }

        private void appendConversation(final String role, final String text) {
            final String old = answer.getText().toString();
            final String prefix = old.startsWith("可以提问") ? "" : old + "\n\n";
            answer.setText(prefix + role + "：\n" + text);
        }

        private void editApiKey() {
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setText(preferences.getString(KEY_API_KEY, ""));
            new AlertDialog.Builder(context)
                    .setTitle("火山方舟 API Key")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (dialog, which) ->
                            preferences.edit().putString(KEY_API_KEY,
                                    input.getText().toString().trim()).apply())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private Button weightedButton(final String text) {
            final Button button = button(text);
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return button;
        }

        private Button button(final String text) {
            final Button button = new Button(context);
            button.setText(text);
            button.setAllCaps(false);
            return button;
        }

        private int dp(final int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    private static JSONObject message(final String role, final String content) {
        try {
            return new JSONObject().put("role", role).put("content", content);
        } catch (final Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
