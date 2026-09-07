package com.vizuzik.app;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    // Read by EdgeOverlayController: the overlay only has a reason to exist while Vizuzik's own
    // full-screen player isn't already showing the same thing. Defaults to false, which is
    // exactly right the one time it matters most — the app's process spun up only to host
    // NowPlayingListenerService (notification access already granted, MainActivity never
    // created this run) because the user went straight to Deezer without ever opening Vizuzik.
    private static volatile boolean foreground;

    static boolean isForeground() {
        return foreground;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DeezerMediaPlugin.class);
        super.onCreate(savedInstanceState);
        EdgeOverlayController.getInstance().init(getApplicationContext());
        // Also started from NowPlayingListenerService, which is the earlier of the two whenever
        // notification access is granted. Started here too for the process that comes up via the
        // Activity instead: the sooner it listens, the smaller the window in which the tracked
        // app's one-shot session broadcast can be missed (see AudioSessionRegistry).
        AudioSessionRegistry.getInstance().start(getApplicationContext());
        // Normally already started by NowPlayingListenerService, but not always: notification
        // access can be granted while the system hasn't bound that service yet (the case
        // requestListenerRebind() exists for). Without this, the player screen would show with no
        // audio source ever constructed — and there is no second source left to fall back on.
        TrackedAudioCapture.getInstance().start(getApplicationContext());
        // Vizuzik is meant to be looked at, not touched — propped up as a car/desk display for
        // as long as the music plays. Without this the screen times out and locks like any
        // other app, which defeats the whole point of it being on screen at all.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullScreen();
    }

    // Capacitor's BridgeActivity declares both public (not the usual protected from Activity
    // itself), so overriding with the normally-expected protected fails to compile — Java
    // forbids narrowing an overridden method's access.
    @Override
    public void onResume() {
        super.onResume();
        foreground = true;
        EdgeOverlayController.getInstance().sync();
    }

    @Override
    public void onPause() {
        super.onPause();
        foreground = false;
        EdgeOverlayController.getInstance().sync();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullScreen();
        }
    }

    private void enterFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
