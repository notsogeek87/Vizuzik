package com.vizuzik.app;

/** The music apps Vizuzik knows how to track, launch, and scope audio capture to. */
final class MusicApps {

    static final String DEEZER_KEY = "deezer";
    static final String SPOTIFY_KEY = "spotify";

    static final String DEEZER_PACKAGE = "deezer.android.app";
    static final String SPOTIFY_PACKAGE = "com.spotify.music";

    static String packageForKey(String key) {
        if (DEEZER_KEY.equals(key)) return DEEZER_PACKAGE;
        if (SPOTIFY_KEY.equals(key)) return SPOTIFY_PACKAGE;
        return null;
    }

    static boolean isKnownPackage(String packageName) {
        return DEEZER_PACKAGE.equals(packageName) || SPOTIFY_PACKAGE.equals(packageName);
    }

    /** Any app other than Vizuzik itself: every music app publishes a MediaSession and the audio
     *  session broadcast, so none needs to be listed. */
    static boolean isTrackable(android.content.Context context, String packageName) {
        return packageName != null && !packageName.equals(context.getPackageName());
    }

    private MusicApps() {}
}
