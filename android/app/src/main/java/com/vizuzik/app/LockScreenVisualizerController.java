package com.vizuzik.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Decides whether LockScreenVisualizerActivity should be shown — the native counterpart to
 * EdgeOverlayController, for the other of Vizuzik's two "draw over something else" features. Same
 * reason to exist natively: this has to work even if Vizuzik's own Activity/webview has never run
 * this session, since the whole point is to appear the moment the screen would otherwise sleep
 * while music keeps playing, including right after a reboot.
 *
 * There is no broadcast for "the screen is about to sleep" and no way to be notified only when
 * this app matters — ACTION_SCREEN_OFF/ACTION_SCREEN_ON are implicit system broadcasts Android
 * stopped delivering to manifest-declared receivers years ago, so this class is registered at
 * runtime (see init()) from NowPlayingListenerService, the one component guaranteed alive
 * whenever notification access is granted.
 *
 * Showing an Activity over the lock screen from a process with nothing in the foreground is
 * itself restricted since Android 10 — the sanctioned way through that restriction is a
 * high-priority notification with a full-screen intent, exactly what an incoming call or an alarm
 * uses. See postFullScreenNotification() and
 * docs/architecture/2026-09-09-visualiseur-ecran-verrouille.md for what that costs on Android 14+.
 */
final class LockScreenVisualizerController implements DeezerMediaBridge.Listener {

    private static final String TAG = "LockScreenVisualizer";
    private static final LockScreenVisualizerController INSTANCE = new LockScreenVisualizerController();

    private static final String CHANNEL_ID = "vizuzik_lockscreen_visualizer";
    private static final int NOTIFICATION_ID = 4244; // same id LockScreenVisualizerActivity cancels
    /** Sent only within this app's own process/package — see onDisabled() and
     *  LockScreenVisualizerActivity's own receiver for the other end of this. */
    static final String ACTION_DISABLED = "com.vizuzik.app.LOCK_SCREEN_VISUALIZER_DISABLED";

    static LockScreenVisualizerController getInstance() {
        return INSTANCE;
    }

    private Context appContext;
    private boolean receiverRegistered;
    private boolean displayListenerRegistered;
    /** Last state seen for the built-in display (Display.STATE_*), so a change can be read as a
     *  transition — see displayListener. */
    private int lastDisplayState = Display.STATE_UNKNOWN;

    /**
     * Catches the first touch on a sleeping screen in "wake" mode. With Samsung's AOD set to
     * "Appuyer pour afficher", that touch does not wake the phone: it brings up the AOD, a
     * low-power "doze" display state that Android does not count as the screen being on — no
     * ACTION_SCREEN_ON, so maybeShowOnWake() would only run after a second touch fully wakes it.
     * What an app can see is the display itself changing state, OFF → DOZE (or DOZE_SUSPEND, the
     * static AOD variant): that transition is the AOD appearing, and the full-screen intent then
     * brings the visualizer up (its setTurnScreenOn() leaving doze for a real wake).
     *
     * Only OFF → DOZE counts. An AOD set to "Toujours afficher" goes straight ON → DOZE when the
     * screen turns off — reacting to that would bring back exactly the "relit as soon as it goes
     * off" behaviour "wake" mode exists to avoid. Known cost: an AOD that also lights up for an
     * incoming notification makes the same OFF → DOZE transition, and this cannot tell the two
     * apart.
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) return;
            int state = currentDisplayState();
            int previous = lastDisplayState;
            lastDisplayState = state;
            boolean dozing = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
            if (previous == Display.STATE_OFF && dozing) maybeShowOnWake();
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The trigger setting decides which of the two broadcasts matters: "continuous" reacts
            // to the screen going off (relight it at once), "wake" to the user waking it
            // themselves — a sleeping screen delivers no touch to any app, so a screen coming back
            // on (double tap to wake, a tap on the real AOD, the side button) is the closest an
            // app can get to "the user touched the screen". Each mode ignores the other's
            // broadcast, which also keeps "continuous" from reacting to the SCREEN_ON its own
            // setTurnScreenOn() causes.
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                maybeShow();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                maybeShowOnWake();
            }
        }
    };

    private LockScreenVisualizerController() {}

    /** Idempotent — safe to call every time NowPlayingListenerService starts. */
    void init(Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            // ACTION_SCREEN_OFF/ON are protected system broadcasts — nothing but the system can ever
            // send them — so NOT_EXPORTED (no other app may address this receiver directly) is the
            // correct, safe choice. ContextCompat.registerReceiver() folds the pre-Tiramisu/
            // Tiramisu+ branching this needs into one call.
            ContextCompat.registerReceiver(
                appContext, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            );
            receiverRegistered = true;
        }
        if (!displayListenerRegistered) {
            DisplayManager displayManager = appContext.getSystemService(DisplayManager.class);
            if (displayManager != null) {
                lastDisplayState = currentDisplayState();
                displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
                displayListenerRegistered = true;
            }
        }
    }

    private int currentDisplayState() {
        DisplayManager displayManager = appContext.getSystemService(DisplayManager.class);
        Display display = displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null ? Display.STATE_UNKNOWN : display.getState();
    }

    @Override
    public void onNowPlayingChanged(DeezerMediaBridge.NowPlaying nowPlaying) {
        maybeShow();
    }

    /**
     * Re-evaluates and, if everything lines up, posts the full-screen notification that brings
     * LockScreenVisualizerActivity up. Called on every now-playing change and on every screen-off
     * — both are "something changed, worth checking again" rather than two different decisions.
     *
     * Nothing here tracks its own "already shown" state: PowerManager.isInteractive() is asked
     * fresh every time instead. Once the Activity is actually up, the screen reads as interactive
     * (setTurnScreenOn() made it so) and this naturally stops re-triggering on its own — one
     * source of truth rather than a second flag that could drift out of sync with it.
     */
    void maybeShow() {
        if (appContext == null) return;
        if (!LockScreenVisualizerPreference.isEnabled(appContext)) return;
        // "wake" mode only ever shows on ACTION_SCREEN_ON (see maybeShowOnWake()) — neither the
        // screen going off nor a track change on an idle lock screen may bring it back up.
        if (LockScreenVisualizerPreference.isWakeTrigger(appContext)) return;
        // Vizuzik's own player already shows everything this would; showing our fake-AOD screen
        // over it would just be a redundant window on top of the app that's already visible.
        if (MainActivity.isForeground()) return;

        PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isInteractive()) return;

        DeezerMediaBridge.NowPlaying nowPlaying = DeezerMediaBridge.getInstance().getLastNowPlaying();
        if (nowPlaying == null || !nowPlaying.isPlaying) return;

        postFullScreenNotification();
    }

    /**
     * "wake" mode's way in: the user has just woken the screen — fully (ACTION_SCREEN_ON) or only
     * into the AOD (see displayListener) — onto the lock screen while music plays — show the visualizer over it, for the time
     * LockScreenVisualizerActivity reads from LockScreenVisualizerPreference.getDurationSec().
     * isKeyguardLocked() rather than isInteractive(): the screen is on by definition here; what
     * matters is that it woke onto the lock screen, not onto an unlocked phone.
     */
    void maybeShowOnWake() {
        if (appContext == null) return;
        if (!LockScreenVisualizerPreference.isEnabled(appContext)) return;
        if (!LockScreenVisualizerPreference.isWakeTrigger(appContext)) return;
        if (MainActivity.isForeground()) return;
        // Waking into the AOD and then fully (the visualizer's own setTurnScreenOn()) sends both
        // signals one after the other; the second must not post a notification that no new
        // Activity launch would ever cancel.
        if (LockScreenVisualizerActivity.isShowing()) return;

        KeyguardManager keyguardManager =
            (KeyguardManager) appContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) return;

        DeezerMediaBridge.NowPlaying nowPlaying = DeezerMediaBridge.getInstance().getLastNowPlaying();
        if (nowPlaying == null || !nowPlaying.isPlaying) return;

        postFullScreenNotification();
    }

    /**
     * The one sanctioned way left, on modern Android, to bring a full-screen Activity up from a
     * process with nothing in the foreground — the same mechanism an incoming call or an alarm
     * uses. Requires a HIGH-importance channel (a lower one never fires the full-screen intent at
     * all) and, from Android 14, the user having separately granted the "full screen intent"
     * special access (see DeezerMediaPlugin.checkFullScreenIntentPermission()) — without it, or
     * without POST_NOTIFICATIONS, this call still succeeds but the notification either shows as
     * an ordinary heads-up instead of launching anything, or is silently dropped. Either way this
     * never crashes for it: the fake AOD simply doesn't appear, same as any other missing grant
     * in this app degrades to "the feature does nothing" rather than a failure.
     */
    private void postFullScreenNotification() {
        try {
            NotificationManager manager = appContext.getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Visualiseur écran verrouillé",
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription(
                    "Ramène l'écran pour afficher le visualiseur pendant que la musique joue, à la place de la mise en veille."
                );
                manager.createNotificationChannel(channel);
            }

            Intent activityIntent = new Intent(appContext, LockScreenVisualizerActivity.class);
            activityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification notification = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle("Vizuzik")
                .setContentText("Visualiseur écran verrouillé actif")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .build();
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (Android 13+) — see
            // DeezerMediaPlugin.checkFullScreenIntentPermission(). Nothing to show; not a crash.
            Log.w(TAG, "postFullScreenNotification", e);
        } catch (Exception e) {
            Log.w(TAG, "postFullScreenNotification", e);
        }
    }

    /** Only ever called by DeezerMediaPlugin when the user turns the setting off. Cancels a
     *  pending full-screen-intent notification that hasn't fired yet, and tells an already-showing
     *  LockScreenVisualizerActivity to finish immediately — a toggle flipped while looking at that
     *  exact screen has no other way to learn the setting changed under it. */
    void onDisabled() {
        if (appContext == null) return;
        try {
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.w(TAG, "onDisabled/cancel", e);
        }
        try {
            Intent intent = new Intent(ACTION_DISABLED).setPackage(appContext.getPackageName());
            appContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "onDisabled/broadcast", e);
        }
    }
}
