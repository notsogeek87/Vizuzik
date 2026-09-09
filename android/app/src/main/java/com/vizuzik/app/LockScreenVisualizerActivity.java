package com.vizuzik.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * The "fake AOD": a full-screen Activity shown over the lock screen, screen kept on, while music
 * plays and the real screen would otherwise have gone to sleep. There is no public API on Android
 * or One UI for a third-party app to draw on the real Always-On Display hardware panel — see
 * docs/architecture/2026-09-09-visualiseur-ecran-verrouille.md for why that was checked rather
 * than assumed. This is the closest visual equivalent that public APIs actually allow: the same
 * trick an incoming-call or alarm screen uses (setShowWhenLocked + setTurnScreenOn), never
 * dismissing the keyguard itself — pressing back, tapping anywhere, or the system's own gestures
 * all fall through to it exactly as if this Activity had never shown up.
 *
 * Costs real battery that a genuine AOD panel doesn't: the display stays fully driven rather than
 * dropping into the hardware's own low-power ambient refresh path. Never started on its own — see
 * LockScreenVisualizerController for the one place that decides to show it, only once the setting
 * is explicitly turned on in the settings panel.
 *
 * Hosts the same EdgeGlowView the overlay uses, in "standalone" mode (see EdgeGlowView.
 * setStandalone()): no other app underneath to hide for, and no Deezer layout to anchor "cocoon"
 * against, since this *is* the whole screen — "cocoon" falls back the same way it would over any
 * other app (see activeStyle()). "Vinyle" and the lock screen's own "Cassette" style don't need
 * that layout at all: they simply centre themselves on the screen instead (see artRect()'s
 * standalone branch), and are the two options — along with "Barres" — offered by this screen's
 * own style picker (see setStandaloneStyle() below and LockScreenVisualizerPreference). Feeds it
 * directly from the same two bridges OverlayEdgeGlowService listens to — the app's one audio
 * source and one now-playing source, never a second capture of either.
 */
public class LockScreenVisualizerActivity extends AppCompatActivity
    implements DeezerMediaBridge.Listener, AudioLevelsBridge.Listener {

    private static final String TAG = "LockScreenVisualizer";
    private static final int NOTIFICATION_ID = 4244;
    /** Same reasoning as EdgeOverlayController.PAUSE_GRACE_MS: a track change routinely passes
     *  through a moment of "not playing", and reacting to that instantly would drop the screen
     *  back into a real sleep/AOD cycle only to relight it a moment later for the next track. */
    private static final long PAUSE_GRACE_MS = 1_500;

    private EdgeGlowView glowView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pauseGraceExpired = this::finishIfStillPaused;
    private String lastTrackKey;
    private boolean lastIsPlaying;
    private boolean hasLastIsPlaying;
    /** The other end of LockScreenVisualizerController.onDisabled(): a toggle flipped off while
     *  this exact screen is showing has no other way to reach it. */
    private final BroadcastReceiver disabledReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The one thing this Activity exists for: show up over the lock screen, without
        // dismissing it, and bring the display back on to do it. setShowWhenLocked()/
        // setTurnScreenOn() (API 27+) replace the older window-flag pair below, which still
        // works but is deprecated; minSdk here is 24, so both paths are kept.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Deliberately never calls KeyguardManager.requestDismissKeyguard(): that method is how
        // an app asks the system to unlock the device (dismissing it outright if there's no PIN,
        // prompting for credentials if there is) — the opposite of what this screen is for. It
        // shows *over* the keyguard, same as an incoming call, and never touches it: pressing
        // home, the back gesture, or tapping this screen (see the click listener below) all leave
        // whatever lock the phone had exactly as locked as it was.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
            new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        EdgeGlowView view = new EdgeGlowView(this);
        view.setStandalone(true);
        view.setBackgroundColor(Color.BLACK);
        // Any tap drops straight back to whatever would normally be there — the real lock screen,
        // its own AOD if the device has one. This Activity has no controls of its own to protect
        // from an accidental touch; showing up at all was already the deliberate act.
        view.setClickable(true);
        view.setOnClickListener(v -> finish());
        setContentView(view);
        glowView = view;

        // Cancel the full-screen-intent notification that got this Activity here — see
        // LockScreenVisualizerController.postFullScreenNotification(). It has done its job the
        // moment this onCreate() runs, and a full-screen-intent notification otherwise lingers in
        // the shade for no reason once its Activity is already showing.
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.w(TAG, "cancel notification", e);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        glowView.applyConfig(EdgeConfig.read(this));
        // Its own, shorter style choice (Barres/Cassette/Disque) rather than EdgeConfig's own
        // "style" field the line above just read — the two pickers are deliberately separate, see
        // LockScreenVisualizerPreference and EdgeGlowView.setStandaloneStyle().
        glowView.setStandaloneStyle(LockScreenVisualizerPreference.getStyle(this));
        DeezerMediaBridge.getInstance().addListener(this);
        AudioLevelsBridge.getInstance().addListener(this);
        ContextCompat.registerReceiver(
            this,
            disabledReceiver,
            new IntentFilter(LockScreenVisualizerController.ACTION_DISABLED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // launchMode="singleTask" (see AndroidManifest.xml) means a second trigger while this is
        // already showing lands here instead of starting another instance — nothing to do beyond
        // acknowledging it: the view is already live and already current.
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Anything taking this out of the foreground — the user actually unlocking, the real
        // keyguard's own bouncer appearing over it, a call — means this Activity has finished the
        // one thing it exists to do. There is nothing to resume back into.
        handler.removeCallbacks(pauseGraceExpired);
        DeezerMediaBridge.getInstance().removeListener(this);
        AudioLevelsBridge.getInstance().removeListener(this);
        try {
            unregisterReceiver(disabledReceiver);
        } catch (IllegalArgumentException e) {
            // Already unregistered (e.g. this onStop() runs twice) — nothing left to undo.
        }
        finish();
    }

    @Override
    public void onNowPlayingChanged(DeezerMediaBridge.NowPlaying nowPlaying) {
        if (glowView == null || nowPlaying == null) return;
        glowView.setPlaying(nowPlaying.isPlaying);

        String trackKey = nowPlaying.title + "::" + nowPlaying.artist;
        boolean isNewTrack = !trackKey.equals(lastTrackKey);
        if (isNewTrack) {
            lastTrackKey = trackKey;
            try {
                glowView.setPalette(OverlayPalette.extract(nowPlaying.albumArt));
                glowView.setAlbumArt(nowPlaying.albumArt);
            } catch (Exception e) {
                Log.w(TAG, "onNowPlayingChanged", e);
            }
            glowView.pulse(1f);
        } else if (hasLastIsPlaying && nowPlaying.isPlaying != lastIsPlaying) {
            glowView.pulse(0.55f);
        }
        lastIsPlaying = nowPlaying.isPlaying;
        hasLastIsPlaying = true;

        handler.removeCallbacks(pauseGraceExpired);
        if (!nowPlaying.isPlaying) {
            // Playback stopping is the other reason this screen exists to go away — staying lit
            // on a silent phone is exactly the battery cost this feature is upfront about, spent
            // on nothing. Given a grace period rather than finishing immediately, same reasoning
            // as EdgeOverlayController.PAUSE_GRACE_MS: a track change routinely passes through a
            // moment of "not playing", and finishIfStillPaused() re-checks the real state when it
            // fires rather than trusting this one snapshot.
            handler.postDelayed(pauseGraceExpired, PAUSE_GRACE_MS);
        }
    }

    private void finishIfStillPaused() {
        DeezerMediaBridge.NowPlaying current = DeezerMediaBridge.getInstance().getLastNowPlaying();
        if (current == null || !current.isPlaying) finish();
    }

    @Override
    public void onLevels(float[] levels) {
        try {
            if (glowView != null) glowView.pushLevels(levels);
        } catch (Exception e) {
            Log.w(TAG, "onLevels", e);
        }
    }

    @Override
    public void onCaptureStopped() {
        if (glowView != null) glowView.clearLevels();
    }

    // No back-press override: the system default (finish this Activity) is exactly right here —
    // it drops straight back to whatever is normally underneath, the same as the tap handler
    // above.
}
