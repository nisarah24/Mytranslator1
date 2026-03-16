package com.screentranslator.app.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.screentranslator.app.R;
import com.screentranslator.app.ui.MainActivity;
import com.screentranslator.app.utils.TranslationManager;

import java.util.Locale;

/**
 * FloatingBubbleService — Foreground Service with WindowManager overlay
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Architecture overview
 * ─────────────────────
 * 1. This Service runs in the foreground (required on API 26+ to keep
 *    it alive when the user switches apps).
 * 2. It uses {@link WindowManager} to draw TWO overlay views:
 *    ┌──────────────────────────────────────────────────────────┐
 *    │  bubbleView  — the small draggable translate button      │
 *    │  overlayView — the translation result card (bottom sheet) │
 *    └──────────────────────────────────────────────────────────┘
 *    Both use TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW).
 * 3. When the bubble is released, it calls
 *    {@link ScreenTextAccessibilityService#extractScreenText()} to read
 *    all visible text from the current foreground app.
 * 4. The text is passed to {@link TranslationManager} which uses
 *    Google ML Kit to translate it, then the result is shown in
 *    the overlay card.
 *
 * WindowManager layout params used
 * ─────────────────────────────────
 *  Bubble: FLAG_NOT_FOCUSABLE              → touch events go to bubble only
 *  Overlay: FLAG_NOT_FOCUSABLE |           → overlay is non-interactive for
 *           FLAG_NOT_TOUCH_MODAL           → the area outside the card,
 *           FLAG_WATCH_OUTSIDE_TOUCH       → so the user can still tap the
 *                                            underlying app while card shows
 */
public class FloatingBubbleService extends Service implements TextToSpeech.OnInitListener {

    private static final String TAG              = "FloatingBubble";
    private static final String CHANNEL_ID       = "st_foreground_channel";
    private static final int    NOTIFICATION_ID  = 1001;
    public  static final String ACTION_STOP      = "com.screentranslator.STOP";

    // ─── WindowManager state ─────────────────────────────────────────────────
    private WindowManager               windowManager;
    private View                        bubbleView;
    private View                        overlayView;
    private WindowManager.LayoutParams  bubbleParams;
    private WindowManager.LayoutParams  overlayParams;
    private boolean                     bubbleAttached  = false;
    private boolean                     overlayAttached = false;

    // Screen bounds (for clamping drag position)
    private int screenWidth;
    private int screenHeight;

    // ─── App state ────────────────────────────────────────────────────────────
    private final Handler        mainHandler      = new Handler(Looper.getMainLooper());
    private TranslationManager   translationManager;
    private TextToSpeech         tts;
    private boolean              ttsReady         = false;
    private String               targetLanguage   = "ur";
    private boolean              isTranslating    = false;

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager      = (WindowManager) getSystemService(WINDOW_SERVICE);
        translationManager = new TranslationManager();
        tts                = new TextToSpeech(this, this);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth  = dm.widthPixels;
        screenHeight = dm.heightPixels;

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle stop action from notification button
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra("target_language")) {
            targetLanguage = intent.getStringExtra("target_language");
        }

        // Must call startForeground before doing any UI work
        startForeground(NOTIFICATION_ID, buildNotification());

        // Guard: only add bubble once
        if (!bubbleAttached) {
            showFloatingBubble();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // Not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeBubble();
        removeOverlay();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        Log.d(TAG, "FloatingBubbleService destroyed");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Floating Bubble  (WindowManager view #1)
    // ═════════════════════════════════════════════════════════════════════════

    private void showFloatingBubble() {
        LayoutInflater inflater = LayoutInflater.from(this);
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null);

        /*
         * TYPE_APPLICATION_OVERLAY — the correct overlay type for API 26+.
         * FLAG_NOT_FOCUSABLE       — the bubble does not steal keyboard focus.
         * FLAG_LAYOUT_NO_LIMITS    — allows dragging slightly off-screen edges.
         */
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 60;
        bubbleParams.y = 400;

        windowManager.addView(bubbleView, bubbleParams);
        bubbleAttached = true;

        Log.d(TAG, "Floating bubble added to WindowManager");

        // Wire up touch events for drag + tap
        setupBubbleTouchListener();

        // Close-X button inside the bubble stops the whole service
        ImageButton closeBtn = bubbleView.findViewById(R.id.btn_close_bubble);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> stopSelf());
        }
    }

    /**
     * Handles all touch events on the bubble:
     * ┌─────────────────────────────────────────────────┐
     * │ ACTION_DOWN  → record start position            │
     * │ ACTION_MOVE  → move bubble, clamp to screen     │
     * │ ACTION_UP    → if small drag = tap → translate  │
     * │                if large drag → just reposition  │
     * └─────────────────────────────────────────────────┘
     * A "tap" is defined as total travel < 10px in each axis.
     */
    private void setupBubbleTouchListener() {
        final int[]   startParamsX  = {0};
        final int[]   startParamsY  = {0};
        final float[] startRawX     = {0f};
        final float[] startRawY     = {0f};
        final boolean[] moved       = {false};

        bubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {

                case MotionEvent.ACTION_DOWN:
                    startParamsX[0] = bubbleParams.x;
                    startParamsY[0] = bubbleParams.y;
                    startRawX[0]    = event.getRawX();
                    startRawY[0]    = event.getRawY();
                    moved[0]        = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float deltaX = event.getRawX() - startRawX[0];
                    float deltaY = event.getRawY() - startRawY[0];

                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        moved[0] = true;
                    }

                    // New position, clamped to screen boundaries
                    int newX = clamp(startParamsX[0] + (int) deltaX,
                                     0, screenWidth  - bubbleView.getWidth());
                    int newY = clamp(startParamsY[0] + (int) deltaY,
                                     0, screenHeight - bubbleView.getHeight());

                    bubbleParams.x = newX;
                    bubbleParams.y = newY;

                    if (bubbleAttached) {
                        windowManager.updateViewLayout(bubbleView, bubbleParams);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (!moved[0]) {
                        // Pure tap → translate immediately
                        onBubbleTapped();
                    } else {
                        // Drag released → also translate (Hi Translate behaviour)
                        onBubbleTapped();
                    }
                    return true;
            }
            return false;
        });
    }

    private void removeBubble() {
        if (bubbleAttached && bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing bubble: " + e.getMessage());
            } finally {
                bubbleAttached = false;
                bubbleView     = null;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Translation Flow
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Entry point when the user taps or releases the bubble.
     * Reads screen text → shows loading overlay → requests translation.
     */
    private void onBubbleTapped() {
        if (isTranslating) return;  // Debounce rapid taps

        ScreenTextAccessibilityService a11y = ScreenTextAccessibilityService.getInstance();
        if (a11y == null) {
            showToast("Enable Screen Translator in Accessibility Settings first");
            return;
        }

        String screenText = a11y.extractScreenText();
        Log.d(TAG, "Extracted text (" + screenText.length() + " chars): "
                + screenText.substring(0, Math.min(80, screenText.length())));

        if (screenText.trim().isEmpty()) {
            showToast("No text found on screen");
            return;
        }

        isTranslating = true;
        showOverlayLoading();

        // Trim text to a sensible max to avoid huge ML Kit payloads
        final String textToTranslate = screenText.length() > 3000
                ? screenText.substring(0, 3000) : screenText;

        translationManager.translate(textToTranslate, targetLanguage,
            new TranslationManager.TranslationCallback() {
                @Override
                public void onSuccess(String translated, String detectedLang) {
                    isTranslating = false;
                    mainHandler.post(() -> showOverlayResult(textToTranslate, translated, detectedLang));
                }

                @Override
                public void onFailure(String error) {
                    isTranslating = false;
                    mainHandler.post(() -> {
                        removeOverlay();
                        showToast(error);
                    });
                }
            });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Translation Overlay  (WindowManager view #2)
    // ═════════════════════════════════════════════════════════════════════════

    private void showOverlayLoading() {
        // Dismiss any previous overlay cleanly
        removeOverlay();

        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.layout_translation_overlay, null);

        /*
         * TYPE_APPLICATION_OVERLAY  — same type as bubble; floats above all apps.
         * FLAG_NOT_FOCUSABLE         — overlay does not grab keyboard focus.
         * FLAG_NOT_TOUCH_MODAL       — touches OUTSIDE the overlay card pass
         *                             through to the underlying app. The user
         *                             can still scroll Twitter while reading
         *                             the translation card.
         * FLAG_WATCH_OUTSIDE_TOUCH   — lets us dismiss the card if the user taps
         *                             outside (combined with dispatchTouchEvent).
         */
        overlayParams = new WindowManager.LayoutParams(
                (int) (screenWidth * 0.92f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayParams.y = 100;    // 100px above the navigation bar

        windowManager.addView(overlayView, overlayParams);
        overlayAttached = true;

        // Show loading spinner, hide result
        overlayView.findViewById(R.id.loading_layout).setVisibility(View.VISIBLE);
        overlayView.findViewById(R.id.result_layout).setVisibility(View.GONE);

        // Close button on overlay
        ImageButton closeBtn = overlayView.findViewById(R.id.btn_close_overlay);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> removeOverlay());
        }
    }

    /**
     * Switches the overlay from loading spinner to the actual translation result.
     * Always called on the main thread via mainHandler.post().
     */
    private void showOverlayResult(String original, String translated, String detectedLang) {
        if (!overlayAttached || overlayView == null) return;

        // Hide spinner, show result panel
        overlayView.findViewById(R.id.loading_layout).setVisibility(View.GONE);
        overlayView.findViewById(R.id.result_layout).setVisibility(View.VISIBLE);

        // Language direction label  e.g. "English → Urdu"
        TextView tvLang = overlayView.findViewById(R.id.tv_detected_language);
        if (tvLang != null) {
            tvLang.setText(getLanguageName(detectedLang) + " → " + getLanguageName(targetLanguage));
        }

        // Original text (trimmed to 250 chars for overlay legibility)
        TextView tvOriginal = overlayView.findViewById(R.id.tv_original_text);
        if (tvOriginal != null) {
            String preview = original.length() > 250
                    ? original.substring(0, 250).trim() + "…" : original;
            tvOriginal.setText(preview);
        }

        // Translated text
        TextView tvTranslated = overlayView.findViewById(R.id.tv_translated_text);
        if (tvTranslated != null) {
            tvTranslated.setText(translated);

            // Urdu and Arabic are RTL languages — set text direction accordingly
            boolean isRtl = "ur".equals(targetLanguage) || "ar".equals(targetLanguage);
            tvTranslated.setTextDirection(isRtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            tvTranslated.setTextAlignment(isRtl ? View.TEXT_ALIGNMENT_TEXT_END : View.TEXT_ALIGNMENT_TEXT_START);
        }

        // Text-to-Speech speak button
        ImageButton btnSpeak = overlayView.findViewById(R.id.btn_speak);
        if (btnSpeak != null) {
            final String textToSpeak = translated;
            btnSpeak.setOnClickListener(v -> speakText(textToSpeak));
        }

        // Re-wire close in case overlay was rebuilt
        ImageButton closeBtn = overlayView.findViewById(R.id.btn_close_overlay);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> removeOverlay());
        }
    }

    private void removeOverlay() {
        if (overlayAttached && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay: " + e.getMessage());
            } finally {
                overlayAttached = false;
                overlayView     = null;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Text-to-Speech
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onInit(int status) {
        ttsReady = (status == TextToSpeech.SUCCESS);
        Log.d(TAG, "TTS init: " + (ttsReady ? "ready" : "failed"));
    }

    private void speakText(String text) {
        if (!ttsReady || text == null || text.isEmpty()) {
            showToast("TTS not ready");
            return;
        }

        Locale locale;
        switch (targetLanguage) {
            case "ur": locale = new Locale("ur");   break;
            case "hi": locale = new Locale("hi");   break;
            case "ar": locale = new Locale("ar");   break;
            default:   locale = Locale.ENGLISH;     break;
        }

        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            showToast("TTS: language not available on this device");
            return;
        }

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Foreground Notification
    // ═════════════════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Screen Translator",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the floating bubble alive");
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        // "Stop" action from notification
        Intent stopIntent = new Intent(this, FloatingBubbleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPI = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Tap notification → open settings screen
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPI = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_translate)
                .setContentTitle("Screen Translator Active")
      
