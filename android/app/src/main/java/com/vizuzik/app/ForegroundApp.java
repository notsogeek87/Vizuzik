package com.vizuzik.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * Answers "is the tracked music app the one actually on screen right now?", so Edge Visualizer
 * can stay out of the way of every other app instead of drawing over whatever happens to be in
 * front (see EdgeConfig.onlyOverMusicApp).
 *
 * An overlay window cannot see what is underneath it, and nothing in the media session says
 * whether its app is on screen — a paused-in-the-background Deezer looks exactly like a
 * foregrounded one from there. UsageStatsManager is the only way to know without an
 * accessibility service, and it needs the "usage access" special permission, granted per-app in
 * system Settings (see requestUsageAccess() in DeezerMediaPlugin). Until it is granted, nothing
 * here can answer, and the caller is expected to keep drawing rather than hide on a guess.
 *
 * The last foreground package is kept rather than re-derived: usage events are emitted at the
 * moment an app is resumed and never repeated, so a window covering only the last few seconds
 * is empty whenever someone has been sitting in the same app — which is most of the time. The
 * first query therefore reaches back a day, and every one after it only asks for what is new.
 */
final class ForegroundApp {

    private static final String TAG = "ForegroundApp";
    /** Cheap enough at this rate, and a second of lag leaving Deezer is not worth more. */
    private static final long POLL_INTERVAL_MS = 900;
    private static final long FIRST_LOOKBACK_MS = 24 * 60 * 60 * 1000L;
    /** Same constant as the deprecated MOVE_TO_FOREGROUND (1); javac inlines it, so naming the
     *  newer one here costs nothing on older releases. */
    private static final int EVENT_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED;

    private static String lastKnownPackage;
    private static long lastEventAtMs;
    private static long lastPolledAtMs;

    /** Whether the "usage access" special permission is currently granted to Vizuzik. */
    static boolean hasUsageAccess(Context context) {
        if (context == null) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                : appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            // Some OEM builds throw rather than answer for an op they don't implement. Treated as
            // "can't tell", which the caller reads as "don't hide anything".
            Log.w(TAG, "hasUsageAccess", e);
            return false;
        }
    }

    /**
     * True when the tracked app is in front, and also true whenever that can't be established —
     * no app chosen yet, permission missing, or no usage event ever seen. A decorative overlay
     * that silently refuses to appear is a much worse failure than one that appears too often.
     */
    static boolean isTrackedAppInForeground(Context context) {
        if (context == null) return true;
        String tracked = MusicAppPreference.getPackage(context);
        if (tracked == null) return true;
        String current = currentPackage(context);
        return current == null || current.equals(tracked);
    }

    private static synchronized String currentPackage(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (lastPolledAtMs != 0 && now - lastPolledAtMs < POLL_INTERVAL_MS) return lastKnownPackage;
        lastPolledAtMs = now;
        poll(context);
        return lastKnownPackage;
    }

    private static void poll(Context context) {
        try {
            UsageStatsManager usage =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usage == null) return;

            long now = System.currentTimeMillis();
            long from = lastEventAtMs > 0 ? lastEventAtMs + 1 : now - FIRST_LOOKBACK_MS;
            // A clock moved backwards (time zone, NTP correction) would otherwise leave `from`
            // permanently ahead of `now` and the window empty for good.
            if (from > now) from = now - FIRST_LOOKBACK_MS;

            UsageEvents events = usage.queryEvents(from, now);
            if (events == null) return;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != EVENT_RESUMED) continue;
                if (event.getTimeStamp() < lastEventAtMs) continue;
                lastEventAtMs = event.getTimeStamp();
                lastKnownPackage = event.getPackageName();
            }
        } catch (Exception e) {
            // Never worth a crash: this only decides whether a decoration is visible.
            Log.w(TAG, "poll", e);
        }
    }

    private ForegroundApp() {}
}
