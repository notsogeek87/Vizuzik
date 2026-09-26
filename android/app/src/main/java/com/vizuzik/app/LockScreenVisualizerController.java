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
    /**
     * "sleep" mode's loop guard. Once the visualizer closes, the lock screen it hands back to
     * times out and the screen goes off again — another ACTION_SCREEN_OFF, which would bring the
     * visualizer straight back, forever. So it shows once per lock: set when shown, cleared only by
     * ACTION_USER_PRESENT, the system's own "the device was just unlocked" broadcast. Official
     * signals only, no timing guesses — an earlier attempt to trigger on the AOD appearing relied on
     * undocumented One UI behaviour and could not tell a tap from a notification (see the
     * architecture doc's "Piste non retenue").
     */
    private boolean shownSinceUnlock;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The trigger setting decides which broadcast matters: "continuous" and "sleep" react
            // to the screen going off, "wake" to the user fully waking it (side button, double tap
            // to wake). Each method below checks the trigger itself and ignores the others'
            // broadcasts — which also keeps "wake" from reacting to the SCREEN_ON the visualizer's
            // own setTurnScreenOn() causes in the other two modes.
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                maybeShow();
                maybeShowOnSleep();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                maybeShowOnWake();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                shownSinceUnlock = false;
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
            filter.addAction(Intent.ACTION_USER_PRESENT);
            // ACTION_SCREEN_OFF/ON are protected system broadcasts — nothing but the system can ever
            // send them — so NOT_EXPORTED (no other app may address this receiver directly) is the
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
        // "continuous" only: "sleep" has its own once-per-lock path (maybeShowOnSleep()) and
        // "wake" only shows on ACTION_SCREEN_ON (maybeShowOnWake()) — in neither may a track
        // change on an idle lock screen bring it back up.
        if (!LockScreenVisualizerPreference.TRIGGER_CONTINUOUS.equals(
                LockScreenVisualizerPreference.getTrigger(appContext))) return;
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
     * "wake" mode's only way in: the user has just woken the screen and is looking at the real
     * lock screen while music plays — show the visualizer over it, for the time
     * LockScreenVisualizerActivity reads from LockScreenVisualizerPreference.getDurationSec().
     * isKeyguardLocked() rather than isInteractive(): the screen is on by definition here; what
     * matters is that it woke onto the lock screen, not onto an unlocked phone.
     */
    void maybeShowOnWake() {
        if (appContext == null) return;
        if (!LockScreenVisualizerPreference.isEnabled(appContext)) return;
        if (!LockScreenVisualizerPreference.TRIGGER_WAKE.equals(
                LockScreenVisualizerPreference.getTrigger(appContext))) return;
        if (MainActivity.isForeground()) return;
        // A second wake while it is already up (e.g. the side button pressed again) must not
        // post a notification no new Activity launch would ever cancel.
        if (LockScreenVisualizerActivity.isShowing()) return;

        KeyguardManager keyguardManager =
            (KeyguardManager) appContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) return;

        DeezerMediaBridge.NowPlaying nowPlaying = DeezerMediaBridge.getInstance().getLastNowPlaying();
        if (nowPlaying == null || !nowPlaying.isPlaying) return;

        postFullScreenNotification();
    }

    /**
     * "sleep" mode: the screen has just gone off (the user locking, or the screen timing out)
     * while music plays — relight it once with the visualizer, for getDurationSec() seconds. See
     * shownSinceUnlock for why only once per lock.
     */
    void maybeShowOnSleep() {
        if (appContext == null) return;
        if (!LockScreenVisualizerPreference.isEnabled(appContext)) return;
        if (!LockScreenVisualizerPreference.TRIGGER_SLEEP.equals(
                LockScreenVisualizerPreference.getTrigger(appContext))) return;
        if (shownSinceUnlock) return;
        if (MainActivity.isForeground()) return;

        DeezerMediaBridge.NowPlaying nowPlaying = DeezerMediaBridge.getInstance().getLastNowPlaying();
        if (nowPlaying == null || !nowPlaying.isPlaying) return;

        shownSinceUnlock = true;
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
