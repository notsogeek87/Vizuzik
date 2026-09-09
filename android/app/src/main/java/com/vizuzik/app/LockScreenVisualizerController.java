package com.vizuzik.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.Log;

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

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // ACTION_SCREEN_ON needs no handling of its own: LockScreenVisualizerActivity manages
            // its own lifecycle once shown, and isInteractive() below is asked fresh on every
            // decision rather than tracked here — one source of truth instead of two that could
            // drift apart.
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) maybeShow();
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
            // ACTION_SCREEN_OFF is a protected system broadcast — nothing but the system can ever
            // send it — so NOT_EXPORTED (no other app may address this receiver directly) is the
            // correct, safe choice. ContextCompat.registerReceiver() folds the pre-Tiramisu/
            // Tiramisu+ branching this needs into one call.
            ContextCompat.registerReceiver(
                appContext, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            );
            receiverRegistered = true;
        }
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
