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
        // Vizuzik is meant to be looked at, not touched — propped up as a car/desk display for
        // as long as the music plays. Without this the screen times out and locks like any
        // other app, which defeats the whole point of it being on screen at all.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        EdgeOverlayController.getInstance().sync();
    }

    @Override
    protected void onPause() {
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
