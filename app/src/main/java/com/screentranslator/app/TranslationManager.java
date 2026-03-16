package com.screentranslator.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translates text using the free MyMemory REST API.
 *
 * Endpoint:
 *   https://api.mymemory.translated.net/get?q=<text>&langpair=en|ur
 *
 * No API key required for basic use (up to ~500 words/day anonymous).
 * Source language is always assumed to be English ("en").
 * Target language defaults to Urdu ("ur") but is configurable.
 *
 * Response JSON shape:
 * {
 *   "responseStatus": 200,
 *   "responseData": {
 *     "translatedText": "..."
 *   }
 * }
 */
public class TranslationManager {

    private static final String TAG     = "TranslationManager";
    private static final String API_URL = "https://api.mymemory.translated.net/get";

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public interface TranslationCallback {
        void onSuccess(String translatedText, String detectedLanguage);
        void onFailure(String error);
    }

    /**
     * Translates {@code inputText} from English to {@code targetLang}.
     *
     * @param inputText  Text to translate (max ~500 words for anonymous use)
     * @param targetLang ISO-639-1 language code, e.g. "ur" for Urdu
     * @param callback   Called on the main thread with the result
     */
    public void translate(String inputText, String targetLang, TranslationCallback callback) {
        if (inputText == null || inputText.trim().isEmpty()) {
            mainHandler.post(() -> callback.onFailure("No text to translate"));
            return;
        }

        final String sourceLang = "en";

        executor.execute(() -> {
            try {
                String encoded  = URLEncoder.encode(inputText.trim(), "UTF-8");
                String langPair = URLEncoder.encode(sourceLang + "|" + targetLang, "UTF-8");
                String urlStr   = API_URL + "?q=" + encoded + "&langpair=" + langPair;

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Accept", "application/json");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "MyMemory HTTP " + responseCode);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject json       = new JSONObject(sb.toString());
                int        status     = json.optInt("responseStatus", 0);
                JSONObject data       = json.optJSONObject("responseData");
                String     translated = data != null
                        ? data.optString("translatedText", "") : "";

                if (status == 200 && !translated.isEmpty()) {
                    final String result = translated;
                    mainHandler.post(() -> callback.onSuccess(result, sourceLang));
                } else {
                    String msg = json.optString("responseDetails", "Translation failed");
                    mainHandler.post(() -> callback.onFailure(msg));
                }

            } catch (Exception e) {
                Log.e(TAG, "Translation error: " + e.getMessage());
                mainHandler.post(() ->
                        callback.onFailure("Network error: " + e.getMessage()));
            }
        });
    }
}
