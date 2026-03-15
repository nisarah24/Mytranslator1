package com.screentranslator.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * AccessibilityService that captures all visible text from the foreground screen.
 *
 * How it works:
 * ─────────────
 * 1. Android grants this service the ability to inspect the View hierarchy of any app.
 * 2. When the floating bubble is tapped/dragged, FloatingBubbleService calls
 *    {@link #extractScreenText()} directly via the singleton instance.
 * 3. We walk every AccessibilityWindow currently on screen (handles split-screen,
 *    overlays, dialogs, etc.), skip invisible/off-screen nodes, and collect every
 *    non-empty text or content-description string.
 * 4. The joined result is returned synchronously — no threading needed because
 *    AccessibilityNodeInfo operations are already thread-safe.
 *
 * Required in AndroidManifest.xml:
 * ─────────────────────────────────
 *   <service android:name=".services.ScreenTextAccessibilityService"
 *       android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *     <intent-filter>
 *       <action android:name="android.accessibilityservice.AccessibilityService"/>
 *     </intent-filter>
 *     <meta-data android:name="android.accessibilityservice"
 *         android:resource="@xml/accessibility_service_config"/>
 *   </service>
 */
public class ScreenTextAccessibilityService extends AccessibilityService {

    private static final String TAG = "ScreenTextA11y";

    // Singleton — set on connect, cleared on destroy
    private static volatile ScreenTextAccessibilityService sInstance;

    /** Returns the live service instance, or null if not yet enabled. */
    public static ScreenTextAccessibilityService getInstance() {
        return sInstance;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        configureService();
        Log.d(TAG, "Accessibility service connected");
    }

    /**
     * Sets up the service programmatically as a belt-and-suspenders approach
     * alongside the XML config. This ensures flags are always applied even if
     * the user has an old XML config cached by the OS.
     */
    private void configureService() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();

        // Listen to window state changes (app switches, dialogs opening)
        // and content changes (new messages appearing, pages loading)
        info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                AccessibilityEvent.TYPE_VIEW_SCROLLED;

        // feedbackGeneric = non-intrusive; we only read, never produce feedback
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;

        // Critical: allows us to read the full window content, not just focused nodes
        info.flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;

        // Throttle events so we don't get flooded (we query on demand anyway)
        info.notificationTimeout = 100;

        // Empty = monitor ALL packages (WhatsApp, Twitter, Chrome, etc.)
        info.packageNames = null;

        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We capture text ON DEMAND (when bubble is tapped), not on every event.
        // If you want to track the "current" app for smarter UX, you can cache
        // event.getPackageName() here.
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        Log.d(TAG, "Accessibility service destroyed");
    }

    // ─── Core: extract all visible text from the screen ───────────────────────

    /**
     * Walks every active AccessibilityWindow (including dialogs, navigation bars,
     * system overlays, split-screen panes) and collects all non-empty, visible text.
     *
     * @return A single string of all screen text, sentences separated by newlines.
     *         Returns an empty string if nothing is found or the service is not ready.
     */
    public String extractScreenText() {
        List<String> collected = new ArrayList<>();

        // getWindows() returns ALL current windows — dialogs, status bar, app content, etc.
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo window : windows) {
                // Skip the system windows that never contain user-readable content
                int windowType = window.getType();
                if (windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;

                AccessibilityNodeInfo rootNode = window.getRoot();
                if (rootNode != null) {
                    collectVisibleText(rootNode, collected);
                    rootNode.recycle();
                }
            }
        } else {
            // Fallback: getRootInActiveWindow() — works when getWindows() returns null
            // (can happen if FLAG_RETRIEVE_INTERACTIVE_WINDOWS is not yet applied)
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                collectVisibleText(rootNode, collected);
                rootNode.recycle();
            }
        }

        return joinTextLines(collected);
    }

    /**
     * Recursively walks the node tree and appends text from visible leaf-level
     * nodes. Skips invisible, off-screen, or empty nodes to avoid garbage output.
     *
     * @param node      Current node (never null when called)
     * @param collected Accumulator list
     */
    private void collectVisibleText(AccessibilityNodeInfo node, List<String> collected) {
        if (node == null) return;

        // Skip nodes that are not visible on screen
        if (!node.isVisibleToUser()) {
            node.recycle();
            return;
        }

        // Skip nodes clipped entirely outside the screen bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.right <= 0 || bounds.bottom <= 0) {
            // Don't recycle here — caller manages lifecycle for multi-child traversal
            return;
        }

        // Collect text from this node
        CharSequence text = node.getText();
        CharSequence hint = node.getHintText();        // API 26+
        CharSequence desc = node.getContentDescription();

        if (text != null && text.length() > 0) {
            String t = text.toString().trim();
            if (!t.isEmpty() && !collected.contains(t)) {
                collected.add(t);
            }
        } else if (desc != null && desc.length() > 0) {
            // Use content-description as fallback (covers icon labels, image alt text)
            String d = desc.toString().trim();
            if (!d.isEmpty() && !collected.contains(d)) {
                collected.add(d);
            }
        }

        // Recurse into children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectVisibleText(child, collected);
                child.recycle();
            }
        }
    }

    /**
     * Joins a list of text fragments into a readable paragraph.
     * Short words (buttons, labels) are joined with spaces; longer phrases
     * get their own lines for readability.
     */
    private String joinTextLines(List<String> texts) {
        if (texts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (String text : texts) {
            if (sb.length() > 0) {
                // Separate longer phrases with newline, short tokens with space
                sb.append(text.length() > 30 ? "\n" : " ");
            }
            sb.append(text);
        }
        return sb.toString().trim();
    }

    // ─── Broadcast helper (optional — for event-driven architecture) ──────────

    /**
     * Extracts screen text and broadcasts it via a local Intent.
     * Use this if you want other components to react to translation results
     * without direct service-to-service calls.
     */
    public void broadcastCurrentScreenText() {
        String text = extractScreenText();
        if (text.isEmpty()) return;

        Intent intent = new Intent("com.screentranslator.SCREEN_TEXT_CAPTURED");
        intent.putExtra("screen_text", text);
        sendBroadcast(intent);
        Log.d(TAG, "Broadcast screen text (" + text.length() + " chars)");
    }
}
