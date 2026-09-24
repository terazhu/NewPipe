package org.schabi.newpipe.learning;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AiLearningDialog {
    private static final String KEY_API_KEY = "tubecache_ark_api_key";
    private static final int ASK_AI_MENU_ID = 0x544149;
    private static final long CAPTION_UPDATE_MS = 400;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private AiLearningDialog() {
    }

    public interface PlaybackController {
        long getPositionMs();

        void seekTo(long positionMs);

        boolean isPlaying();

        void setPlaying(boolean playing);

        default void setLearningMode(final boolean enabled) {
        }
    }

    public static void show(@NonNull final Context context, @NonNull final String title,
                            @NonNull final SubtitleRepository.CachedSubtitle subtitle,
                            @NonNull final PlaybackController playbackController) {
        new Session(context, title, subtitle, playbackController).show();
    }

    static String normalizeLookupWord(final String raw) {
        return raw == null ? "" : raw.trim()
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private static final class Session {
        private final Context context;
        private final String title;
        private final SubtitleRepository.CachedSubtitle subtitle;
        private final PlaybackController playback;
        private final SharedPreferences preferences;
        private final JSONArray history = new JSONArray();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<TextView> cueViews = new ArrayList<>();
        private final Runnable captionUpdater = this::updateActiveCaption;
        private ScrollView subtitleScroll;
        private ScrollView answerScroll;
        private TextView answer;
        private ProgressBar progress;
        private EditText question;
        private ImageButton playPause;
        private AlertDialog dialog;
        private int activeCue = -1;
        private boolean requesting;
        private String selectedSubtitleText = "";

        private Session(final Context context, final String title,
                        final SubtitleRepository.CachedSubtitle subtitle,
                        final PlaybackController playback) {
            this.context = context;
            this.title = title;
            this.subtitle = subtitle;
            this.playback = playback;
            preferences = PreferenceManager.getDefaultSharedPreferences(context);
        }

        private void show() {
            final LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(6), dp(12), dp(8));
            root.addView(buildHeader());
            root.addView(buildPlaybackControls());
            root.addView(buildSubtitles(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
            root.addView(buildConversation(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(buildQuickActions());
            root.addView(buildQuestionRow());

            dialog = new AlertDialog.Builder(context)
                    .setView(root)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(ignored -> {
                handler.removeCallbacks(captionUpdater);
                playback.setLearningMode(false);
            });
            playback.setLearningMode(true);
            dialog.show();
            final Window window = dialog.getWindow();
            if (window != null) {
                window.setGravity(Gravity.BOTTOM);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                final int workspaceHeight = Math.round(
                        context.getResources().getDisplayMetrics().heightPixels * 0.72f);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        workspaceHeight);
            }
            handler.post(captionUpdater);
        }

        private View buildHeader() {
            final LinearLayout row = horizontalRow();
            final TextView heading = new TextView(context);
            heading.setText("AI 学习");
            heading.setTextSize(18);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(heading, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            final TextView modelLabel = new TextView(context);
            modelLabel.setText("DeepSeek V4.1");
            modelLabel.setTextSize(14);
            modelLabel.setPadding(dp(8), 0, dp(8), 0);
            row.addView(modelLabel);
            final Button close = compactButton("×");
            close.setContentDescription("关闭");
            close.setOnClickListener(v -> dialog.dismiss());
            row.addView(close);
            return row;
        }

        private View buildPlaybackControls() {
            final LinearLayout row = horizontalRow();
            row.setGravity(Gravity.CENTER);
            final Button rewind = compactButton("《 2秒");
            rewind.setContentDescription("后退 2 秒");
            rewind.setOnClickListener(v ->
                    playback.seekTo(Math.max(0, playback.getPositionMs() - 2000)));
            row.addView(rewind);

            playPause = new ImageButton(context);
            playPause.setBackgroundResource(android.R.color.transparent);
            playPause.setContentDescription("播放或暂停");
            playPause.setPadding(dp(12), dp(8), dp(12), dp(8));
            playPause.setOnClickListener(v -> {
                playback.setPlaying(!playback.isPlaying());
                updatePlayPauseIcon();
            });
            row.addView(playPause, new LinearLayout.LayoutParams(dp(56), dp(48)));

            final Button forward = compactButton("2秒 》");
            forward.setContentDescription("前进 2 秒");
            forward.setOnClickListener(v ->
                    playback.seekTo(playback.getPositionMs() + 2000));
            row.addView(forward);
            updatePlayPauseIcon();
            return row;
        }

        private View buildSubtitles() {
            subtitleScroll = new ScrollView(context);
            subtitleScroll.setFillViewport(true);
            final LinearLayout cues = new LinearLayout(context);
            cues.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < subtitle.cues.size(); i++) {
                final SubtitleRepository.CaptionCue cue = subtitle.cues.get(i);
                final TextView cueView = new TextView(context);
                final String cueText = String.format(Locale.ROOT, "[%02d:%02d]  %s",
                        cue.startMs / 60000, cue.startMs / 1000 % 60, cue.text);
                cueView.setText(new SpannableString(cueText), TextView.BufferType.SPANNABLE);
                cueView.setTextSize(15);
                cueView.setLongClickable(true);
                cueView.setPadding(dp(8), dp(5), dp(8), dp(5));
                cueView.setContentDescription("字幕 " + (i + 1));
                final ActionMode.Callback selectionAction = installSelectionAction(cueView);
                installSubtitleGestures(cueView, cue, selectionAction);
                cueViews.add(cueView);
                cues.addView(cueView);
            }
            subtitleScroll.addView(cues);
            return subtitleScroll;
        }

        private View buildConversation() {
            answer = new TextView(context);
            answer.setTextIsSelectable(true);
            answer.setText("");
            answer.setTextSize(15);
            answer.setPadding(dp(4), dp(6), dp(4), dp(6));
            answerScroll = new ScrollView(context);
            answerScroll.setFillViewport(true);
            answerScroll.addView(answer);

            final LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.addView(answerScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            progress = new ProgressBar(context, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(true);
            progress.setVisibility(View.GONE);
            wrapper.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            return wrapper;
        }

        private View buildQuickActions() {
            final LinearLayout row = horizontalRow();
            final Button explain = weightedButton("当前讲解");
            final Button summary = weightedButton("AI 总结");
            final Button focus = weightedButton("重点学习");
            explain.setOnClickListener(v -> request("请讲解【当前字幕语境】中的高阶表达、"
                    + "口语省略、笑点或文化背景；没有难点就直说。"));
            summary.setOnClickListener(v -> request("请输出结构化中文总结：一句话主题、"
                    + "3-6条核心要点、2-3句英文金句及翻译、3-5个值得学的地道表达。"));
            focus.setOnClickListener(v -> request("筛选真正值得中国高级学习者精学的难点。"
                    + "只保留六级以上的高阶词汇、熟词僻义、习语俚语、复杂句或连读难点；"
                    + "逐条给原文、等级、原因和中文讲解。"));
            row.addView(explain);
            row.addView(summary);
            row.addView(focus);
            return row;
        }

        private View buildQuestionRow() {
            final LinearLayout row = horizontalRow();
            question = new EditText(context);
            question.setHint("问视频内容或英语表达");
            question.setSingleLine(false);
            question.setMaxLines(2);
            row.addView(question, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            final Button send = compactButton("发送");
            send.setOnClickListener(v -> {
                final String value = question.getText().toString().trim();
                if (!value.isEmpty()) {
                    request(value);
                    question.setText("");
                }
            });
            row.addView(send);
            return row;
        }

        private void installSubtitleGestures(
                final TextView cueView,
                final SubtitleRepository.CaptionCue cue,
                final ActionMode.Callback selectionAction) {
            final GestureDetector detector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(@NonNull final MotionEvent event) {
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(@NonNull final MotionEvent event) {
                            playback.seekTo(cue.startMs);
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(@NonNull final MotionEvent event) {
                            final String word = wordAt(cueView, event);
                            if (!word.isEmpty()) {
                                openDictionary(word);
                            }
                            return true;
                        }

                        @Override
                        public void onLongPress(@NonNull final MotionEvent event) {
                            final int start = cueView.getText().toString().indexOf("]") + 1;
                            final int end = cueView.length();
                            if (start > 0 && start < end
                                    && cueView.getText() instanceof Spannable) {
                                selectedSubtitleText = cueView.getText()
                                        .subSequence(start, end).toString().trim();
                                Selection.setSelection((Spannable) cueView.getText(),
                                        start, end);
                                cueView.startActionMode(selectionAction,
                                        ActionMode.TYPE_FLOATING);
                            }
                        }
                    });
            cueView.setOnTouchListener((view, event) -> detector.onTouchEvent(event));
        }

        private ActionMode.Callback installSelectionAction(final TextView cueView) {
            final ActionMode.Callback callback = new ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(final ActionMode mode, final Menu menu) {
                    menu.add(0, android.R.id.copy, 0, "复制");
                    menu.add(0, ASK_AI_MENU_ID, 1, "问 AI");
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(final ActionMode mode, final Menu menu) {
                    return false;
                }

                @Override
                public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
                    final int start = Math.max(0, cueView.getSelectionStart());
                    final int end = Math.max(0, cueView.getSelectionEnd());
                    final String selection = cueView.getText().subSequence(
                            Math.min(start, end), Math.max(start, end)).toString().trim();
                    final String selected = selection.isEmpty()
                            ? selectedSubtitleText : selection;
                    if (selected.isEmpty()) {
                        return false;
                    }
                    if (item.getItemId() == ASK_AI_MENU_ID) {
                        request("请结合视频上下文讲解这段字幕：\n" + selected);
                    } else if (item.getItemId() == android.R.id.copy) {
                        final ClipboardManager clipboard = (ClipboardManager)
                                context.getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("subtitle", selected));
                    } else {
                        return false;
                    }
                    mode.finish();
                    return true;
                }

                @Override
                public void onDestroyActionMode(final ActionMode mode) {
                }
            };
            cueView.setCustomSelectionActionModeCallback(callback);
            return callback;
        }

        private String wordAt(final TextView textView, final MotionEvent event) {
            final int[] range = wordRangeAt(textView, event);
            return normalizeLookupWord(textView.getText()
                    .subSequence(range[0], range[1]).toString());
        }

        private int[] wordRangeAt(final TextView textView, final MotionEvent event) {
            final Layout layout = textView.getLayout();
            if (layout == null) {
                return new int[]{0, 0};
            }
            final int line = layout.getLineForVertical(
                    Math.round(event.getY()) - textView.getTotalPaddingTop());
            final int offset = layout.getOffsetForHorizontal(line,
                    event.getX() - textView.getTotalPaddingLeft());
            final String text = textView.getText().toString();
            int start = Math.min(offset, text.length());
            int end = start;
            while (start > 0 && isWordCharacter(text.charAt(start - 1))) {
                start--;
            }
            while (end < text.length() && isWordCharacter(text.charAt(end))) {
                end++;
            }
            return new int[]{start, end};
        }

        private boolean isWordCharacter(final char value) {
            return Character.isLetterOrDigit(value) || value == '\'' || value == '-';
        }

        private void openDictionary(final String word) {
            final Intent eudic = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("eudic://dict/" + Uri.encode(word)));
            eudic.setPackage("com.eusoft.eudic");
            try {
                context.startActivity(eudic);
            } catch (final ActivityNotFoundException ignored) {
                context.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://dict.eudic.net/dicts/en/" + Uri.encode(word))));
            }
        }

        private void updateActiveCaption() {
            if (dialog == null || !dialog.isShowing()) {
                return;
            }
            final int next = findActiveCue(playback.getPositionMs());
            if (next != activeCue && next >= 0) {
                if (activeCue >= 0) {
                    cueViews.get(activeCue).setTypeface(Typeface.DEFAULT);
                    cueViews.get(activeCue).setBackgroundColor(0x00000000);
                }
                activeCue = next;
                final TextView active = cueViews.get(activeCue);
                active.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                active.setBackgroundColor(0x223F51B5);
                subtitleScroll.post(() -> subtitleScroll.smoothScrollTo(
                        0, Math.max(0, active.getTop() - subtitleScroll.getHeight() / 2)));
            }
            updatePlayPauseIcon();
            handler.postDelayed(captionUpdater, CAPTION_UPDATE_MS);
        }

        private int findActiveCue(final long positionMs) {
            int low = 0;
            int high = subtitle.cues.size() - 1;
            int result = -1;
            while (low <= high) {
                final int middle = (low + high) >>> 1;
                if (subtitle.cues.get(middle).startMs <= positionMs) {
                    result = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return result;
        }

        private void updatePlayPauseIcon() {
            if (playPause == null) {
                return;
            }
            playPause.setImageDrawable(AppCompatResources.getDrawable(context,
                    playback.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow));
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
            final String selectedModel = ArkAiClient.DEFAULT_MODEL;
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
                    + subtitle.contextAt(playback.getPositionMs())
                    + "\n完整字幕（可能截断）：\n" + transcript;
            final JSONArray messages = new JSONArray().put(message("system", system));
            for (int i = Math.max(0, history.length() - 8); i < history.length(); i++) {
                messages.put(history.opt(i));
            }
            messages.put(message("user", prompt));
            return messages;
        }

        private void finishRequest(final String result) {
            requesting = false;
            progress.setVisibility(View.GONE);
            appendConversation("AI", result);
        }

        private void appendConversation(final String role, final String text) {
            final String old = answer.getText().toString();
            final String prefix = old.isEmpty() ? "" : old + "\n\n";
            answer.setText(prefix + role + "：\n" + text);
            answerScroll.post(() -> answerScroll.fullScroll(View.FOCUS_DOWN));
        }

        private LinearLayout horizontalRow() {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            return row;
        }

        private Button weightedButton(final String text) {
            final Button button = compactButton(text);
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return button;
        }

        private Button compactButton(final String text) {
            final Button button = new Button(context);
            button.setText(text);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(10), 0, dp(10), 0);
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
