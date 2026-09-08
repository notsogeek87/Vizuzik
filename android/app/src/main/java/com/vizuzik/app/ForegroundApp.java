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
    // Each poll is one incremental query for events newer than the last one seen — normally none
    // at all — so this can be asked often, and it has to be: leaving the music app drops the
    // styles drawn on its cover back to their fallback, and that has to happen with the app
    // switch rather than a beat behind it.
    private static final long POLL_INTERVAL_MS = 300;
    private static final long FIRST_LOOKBACK_MS = 24 * 60 * 60 * 1000L;
    /** How far back the second opinion looks — long enough to have recorded the app someone is
     *  sitting in, short enough that yesterday's session says nothing about right now. */
    private static final long CROSS_CHECK_MS = 60 * 1000L;
    /** How often it is asked again while it is the one overruling the event stream. */
    private static final long CROSS_CHECK_AGAIN_MS = 1_500;
    /** How long after a fold (or any display change) the event stream is treated as suspect. */
    private static final long CROSS_CHECK_WINDOW_MS = 15_000;
    /** Same constant as the deprecated MOVE_TO_FOREGROUND (1); javac inlines it, so naming the
     *  newer one here costs nothing on older releases. */
    private static final int EVENT_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED;

    private static String lastKnownPackage;
    private static long lastEventAtMs;
    private static long lastPolledAtMs;
    /** The package the second opinion was last asked about, and what it said — see
     *  isTrackedAppInForeground(). */
    private static String crossCheckedAgainst;
    private static boolean crossCheckSaidTracked;
    private static long lastCrossCheckAtMs;
    /** Until when the event stream counts as suspect — set by invalidate(), zero the rest of the
     *  time, which is when the events are simply believed. */
    private static long crossCheckUntilMs;

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
    static synchronized boolean isTrackedAppInForeground(Context context) {
        if (context == null) return true;
        String tracked = MusicAppPreference.getPackage(context);
        if (tracked == null) return true;
        String current = currentPackage(context);
        if (current == null || current.equals(tracked)) {
            crossCheckedAgainst = null;
            return true;
        }

        // Something else is in front, says the event stream — and the event stream is believed,
        // immediately, because that is what makes the overlay let go of an app the moment someone
        // leaves it rather than a beat later.
        //
        // Except just after a fold. There, and only there, the events are known to lie: every app
        // moves to another display, and the burst that follows can end on a system package being
        // resumed after the music app, which then stays the answer for as long as nobody switches
        // apps again. So for a few seconds after such a change, and never otherwise, the verdict
        // is put to usage statistics — a coarser source, several seconds behind at times, which
        // is exactly why it is not allowed anywhere near the ordinary case.
        long now = SystemClock.elapsedRealtime();
        if (crossCheckUntilMs == 0 || now > crossCheckUntilMs) return false;
        boolean sameSituation = current.equals(crossCheckedAgainst);
        if (!sameSituation || now - lastCrossCheckAtMs >= CROSS_CHECK_AGAIN_MS) {
            crossCheckedAgainst = current;
            lastCrossCheckAtMs = now;
            crossCheckSaidTracked = recentlyUsedIsTracked(context, tracked);
            // Believed enough to correct the latch itself, not merely to answer this one
            // question. Shadowing it would only postpone the fault: the package resumed in
            // passing during the fold is still what the events say once the window above closes,
            // and nothing resumes again to displace it. Corrected here, the next poll takes the
            // cheap path, and a real app switch still overwrites it the moment one happens.
            if (crossCheckSaidTracked) lastKnownPackage = tracked;
        }
        return crossCheckSaidTracked;
    }

    /**
     * Forgets which app was last seen in front, so the next question is answered from fresh
     * events rather than from a latch.
     *
     * Folding a phone like a Z Fold moves every app to another display, and the burst of activity
     * that follows can end on a system package being resumed *after* the music app — which is
     * then what this class believes is in front, for good, since nothing resumes again while
     * someone simply keeps watching the app they were already in. That left the overlay drawing
     * its fallback style over a perfectly foregrounded Deezer.
     */
    static synchronized void invalidate() {
        lastKnownPackage = null;
        lastPolledAtMs = 0;
        crossCheckedAgainst = null;
        crossCheckUntilMs = SystemClock.elapsedRealtime() + CROSS_CHECK_WINDOW_MS;
    }

    private static synchronized String currentPackage(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (lastPolledAtMs != 0 && now - lastPolledAtMs < POLL_INTERVAL_MS) return lastKnownPackage;
        lastPolledAtMs = now;
        poll(context);
        return lastKnownPackage;
    }

    /**
     * Whether usage *statistics* — as opposed to the event stream above — still make the tracked
     * app the most recently used one. The two sources fail in different ways: events are precise
     * but can be latched onto a package that was resumed in passing, statistics are coarse but
     * aggregate. Only their agreement is allowed to hide anything; where they disagree this
     * returns true, i.e. "can't be sure", which the caller reads as "carry on drawing".
     */
    private static boolean recentlyUsedIsTracked(Context context, String tracked) {
        try {
            UsageStatsManager usage =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usage == null) return false;
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> stats =
                usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - CROSS_CHECK_MS, now);
            if (stats == null || stats.isEmpty()) return false;
            android.app.usage.UsageStats mostRecent = null;
            for (android.app.usage.UsageStats candidate : stats) {
                if (mostRecent == null || candidate.getLastTimeUsed() > mostRecent.getLastTimeUsed()) {
                    mostRecent = candidate;
                }
            }
            return mostRecent != null && tracked.equals(mostRecent.getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "recentlyUsedIsTracked", e);
            return false;
        }
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
