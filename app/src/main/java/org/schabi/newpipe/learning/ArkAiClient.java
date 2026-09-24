package org.schabi.newpipe.learning;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class ArkAiClient {
    static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    static final String GLM_MODEL = "glm-5.3-flash";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private ArkAiClient() {
    }

    static String ask(@NonNull final String apiKey, @NonNull final String model,
                      @NonNull final JSONArray messages)
            throws Exception {
        final JSONObject body = new JSONObject()
                .put("model", GLM_MODEL.equals(model) ? GLM_MODEL : DEFAULT_MODEL)
                .put("stream", false)
                .put("temperature", 0.4)
                .put("messages", messages);
        if (DEFAULT_MODEL.equals(model)) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
        final Request request = new Request.Builder()
                .url(BuildConfig.ARK_BASE_URL + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            final String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Ark HTTP " + response.code() + ": "
                        + text.substring(0, Math.min(240, text.length())));
            }
            return new JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content");
        }
    }
}
