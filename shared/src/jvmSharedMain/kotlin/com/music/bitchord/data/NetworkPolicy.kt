package com.music.bitchord.data

/**
 * Whether this build is allowed to reach the internet at all.
 *
 * The Local edition is offline by design: its library, its feeds and its
 * search are the device's own files, so there is nothing for a catalogue call
 * to do. The flag is set once at start-up and checked at the innertube client's
 * request boundary, which makes any request that slips past a screen a no-op
 * rather than a leak.
 */
object NetworkPolicy {
    @Volatile
    var offlineOnly: Boolean = false
}
