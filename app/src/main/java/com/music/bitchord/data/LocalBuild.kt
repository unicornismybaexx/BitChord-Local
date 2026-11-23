package com.music.bitchord.data

/**
 * The Local edition of BitChord: the same player, without the catalogue behind it.
 *
 * This build is a sideloadable companion to the official app — its own package
 * (`com.music.bitchord.local`) and name, so both can sit on one phone — and it
 * plays only what is already on the device. The cloud half of the app (the
 * YouTube Music account and its library, the home and explore feeds, online
 * search, streaming sources) is not reachable from here; local playback,
 * embedded and LRC lyrics, the equalizer and every tag read off the files stay.
 */
object LocalBuild {
    const val OFFLINE_ONLY = true

    /** The device folder that is the whole local library: Songs, Albums, Artists, Playlists. */
    const val LIBRARY_BROWSE_ID = "local:all"
}
