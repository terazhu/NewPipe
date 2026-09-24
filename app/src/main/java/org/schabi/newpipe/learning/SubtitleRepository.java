package org.schabi.newpipe.learning;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.util.MimeTypes;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SubtitleRepository {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Pattern TTML_CUE = Pattern.compile(
            "<p\\b([^>]*)>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TTML_TIME = Pattern.compile(
            "\\b(begin|end)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern VTT_TIME = Pattern.compile(
            "^\\s*(?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3}\\s+-->.*$");

    private SubtitleRepository() {
    }

    public interface Callback {
        void onResult(@Nullable CachedSubtitle subtitle, @Nullable Exception error);
    }

    public static final class CachedSubtitle {
        public final Uri uri;
        public final String mimeType;
        public final String language;
        public final String transcript;
        public final List<CaptionCue> cues;

        private CachedSubtitle(final Uri uri, final String mimeType, final String language,
                               final String transcript, final List<CaptionCue> cues) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.language = language;
            this.transcript = transcript;
            this.cues = Collections.unmodifiableList(cues);
        }

        @NonNull
        public String contextAt(final long positionMs) {
            return SubtitleRepository.contextAt(cues, transcript, positionMs);
        }
    }

    public static final class CaptionCue {
        public final long startMs;
        public final long endMs;
        public final String text;

        private CaptionCue(final long startMs, final long endMs, final String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    public static void cache(@NonNull final Context context,
                             @NonNull final StreamInfo info,
                             @Nullable final Callback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                post(appContext, callback, cacheNow(appContext, info), null);
            } catch (final Exception error) {
                post(appContext, callback, null, error);
            }
        });
    }

    public static void cacheFromSource(@NonNull final Context context,
                                       @NonNull final String source,
                                       @Nullable final Callback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                final CachedSubtitle cached = find(appContext, source);
                if (cached != null) {
                    post(appContext, callback, cached, null);
                    return;
                }
                final StreamInfo info = StreamInfo.getInfo(
                        NewPipe.getServiceByUrl(source), source);
                post(appContext, callback, cacheNow(appContext, info), null);
            } catch (final Exception error) {
                post(appContext, callback, null, error);
            }
        });
    }

    private static CachedSubtitle cacheNow(final Context context, final StreamInfo info)
            throws Exception {
        final CachedSubtitle cached = find(context, info.getUrl());
        if (cached != null) {
            return cached;
        }
        final SubtitlesStream stream = chooseEnglish(info.getSubtitles());
        if (stream == null) {
            throw new IllegalStateException("No English subtitles available");
        }
        final String raw = NewPipe.getDownloader().get(stream.getContent()).responseBody();
        final String format = stream.getFormat() == null
                ? "vtt" : stream.getFormat().getSuffix().replace(".", "");
        final File directory = new File(context.getFilesDir(), "subtitles");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create subtitle cache");
        }
        final File file = new File(directory, key(info.getUrl()) + "." + format);
        final File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(raw.getBytes(StandardCharsets.UTF_8));
        }
        if (!temporary.renameTo(file)) {
            if (file.exists()) {
                temporary.delete();
            } else {
                throw new IllegalStateException("Could not finish subtitle cache");
            }
        }
        return fromFile(file, stream.getLanguageTag());
    }

    @Nullable
    public static CachedSubtitle find(@NonNull final Context context,
                                      @NonNull final String source) {
        final File directory = new File(context.getFilesDir(), "subtitles");
        final File[] matches = directory.listFiles((dir, name) ->
                name.startsWith(key(source) + "."));
        if (matches == null || matches.length == 0) {
            return null;
        }
        try {
            return fromFile(matches[0], "en");
        } catch (final Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static SubtitlesStream chooseEnglish(final List<SubtitlesStream> streams) {
        return streams.stream()
                .filter(stream -> stream.getLanguageTag().toLowerCase(Locale.ROOT)
                        .startsWith("en"))
                .min(Comparator.comparing(SubtitlesStream::isAutoGenerated))
                .orElse(streams.isEmpty() ? null : streams.get(0));
    }

    private static CachedSubtitle fromFile(final File file, final String language)
            throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
        }
        final String raw = bytes.toString(StandardCharsets.UTF_8.name());
        final String extension = file.getName().substring(file.getName().lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        final String mime;
        if (extension.equals("ttml") || extension.equals("xml")) {
            mime = MimeTypes.APPLICATION_TTML;
        } else if (extension.equals("srt")) {
            mime = MimeTypes.APPLICATION_SUBRIP;
        } else {
            mime = MimeTypes.TEXT_VTT;
        }
        final List<CaptionCue> cues = parseCues(raw);
        return new CachedSubtitle(Uri.fromFile(file), mime, language,
                cuesToTranscript(cues, raw), cues);
    }

    static List<CaptionCue> parseCues(final String raw) {
        final List<CaptionCue> cues = new ArrayList<>();
        final Matcher ttml = TTML_CUE.matcher(raw);
        while (ttml.find()) {
            long start = 0;
            long end = 0;
            final Matcher times = TTML_TIME.matcher(ttml.group(1));
            while (times.find()) {
                if ("begin".equalsIgnoreCase(times.group(1))) {
                    start = parseTimestamp(times.group(2));
                } else {
                    end = parseTimestamp(times.group(2));
                }
            }
            addCue(cues, start, end, ttml.group(2));
        }
        if (!cues.isEmpty()) {
            return cues;
        }

        final String[] lines = raw.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("-->")) {
                continue;
            }
            final String[] times = lines[i].trim().split("\\s+-->\\s+");
            final long start = parseTimestamp(times[0]);
            final long end = times.length > 1
                    ? parseTimestamp(times[1].split("\\s+")[0]) : start + 3000;
            final StringBuilder text = new StringBuilder();
            while (++i < lines.length && !lines[i].trim().isEmpty()) {
                text.append(lines[i]).append(' ');
            }
            addCue(cues, start, end, text.toString());
        }
        return cues;
    }

    static String contextAt(final List<CaptionCue> cues, final String transcript,
                            final long positionMs) {
        if (cues.isEmpty()) {
            return transcript.substring(0, Math.min(1200, transcript.length()));
        }
        int current = 0;
        for (int i = 0; i < cues.size(); i++) {
            if (cues.get(i).startMs <= positionMs) {
                current = i;
            } else {
                break;
            }
        }
        final StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, current - 2);
             i <= Math.min(cues.size() - 1, current + 2); i++) {
            final CaptionCue cue = cues.get(i);
            result.append(formatTime(cue.startMs)).append(' ')
                    .append(cue.text).append('\n');
        }
        return result.toString().trim();
    }

    private static String cuesToTranscript(final List<CaptionCue> cues, final String raw) {
        final StringBuilder result = new StringBuilder();
        if (cues.isEmpty()) {
            for (final String line : raw.split("\\R")) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.equals("WEBVTT")
                        || trimmed.matches("\\d+") || VTT_TIME.matcher(trimmed).matches()
                        || trimmed.startsWith("NOTE") || trimmed.startsWith("Kind:")
                        || trimmed.startsWith("Language:")) {
                    continue;
                }
                appendText(result, trimmed);
            }
        } else {
            for (final CaptionCue cue : cues) {
                appendText(result, cue.text);
            }
        }
        return result.toString().trim();
    }

    private static void addCue(final List<CaptionCue> cues, final long start, final long end,
                               final String value) {
        final String plain = plainText(value);
        if (!plain.isEmpty()) {
            cues.add(new CaptionCue(start, Math.max(start + 1, end), plain));
        }
    }

    private static void appendText(final StringBuilder result, final String value) {
        final String plain = plainText(value);
        if (!plain.isEmpty() && (result.length() == 0
                || !result.toString().endsWith(plain))) {
            result.append(plain).append('\n');
        }
    }

    private static String plainText(final String value) {
        final String result = TAG.matcher(value.replaceAll("(?i)<br\\s*/?>", " "))
                .replaceAll("")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'");
        final Matcher numeric = NUMERIC_ENTITY.matcher(result);
        final StringBuffer decoded = new StringBuffer();
        while (numeric.find()) {
            final String number = numeric.group(1);
            final int radix = number.startsWith("x") ? 16 : 10;
            final int start = number.startsWith("x") ? 1 : 0;
            final String replacement = decodeNumericEntity(
                    number.substring(start), radix, numeric.group(0));
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(decoded);
        return decoded.toString().replaceAll("\\s+", " ").trim();
    }

    private static String decodeNumericEntity(final String number, final int radix,
                                              final String fallback) {
        try {
            return new String(Character.toChars(Integer.parseInt(number, radix)));
        } catch (final Exception ignored) {
            return fallback;
        }
    }

    static long parseTimestamp(final String value) {
        try {
            final String clean = value.trim().replace(',', '.');
            if (clean.endsWith("ms")) {
                return (long) Double.parseDouble(clean.substring(0, clean.length() - 2));
            }
            if (clean.endsWith("s") && !clean.contains(":")) {
                return (long) (Double.parseDouble(clean.substring(0, clean.length() - 1)) * 1000);
            }
            final String[] parts = clean.split(":");
            double seconds = 0;
            for (final String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return (long) (seconds * 1000);
        } catch (final Exception ignored) {
            return 0;
        }
    }

    private static String formatTime(final long millis) {
        final long seconds = millis / 1000;
        return String.format(Locale.ROOT, "[%02d:%02d]", seconds / 60, seconds % 60);
    }

    private static String key(final String source) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return result.toString();
        } catch (final Exception ignored) {
            return Integer.toHexString(source.hashCode());
        }
    }

    private static void post(final Context context, @Nullable final Callback callback,
                             @Nullable final CachedSubtitle subtitle,
                             @Nullable final Exception error) {
        if (callback != null) {
            new android.os.Handler(context.getMainLooper())
                    .post(() -> callback.onResult(subtitle, error));
        }
    }
}
