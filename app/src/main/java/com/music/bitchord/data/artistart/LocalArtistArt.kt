package com.music.bitchord.data.artistart

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** The two pictures an artist page can be given by hand. */
enum class ArtistArtKind(val suffix: String) {
    /** The full-bleed photograph behind the header. */
    BANNER("_banner.img"),

    /**
     * The cut-out title logo drawn over it. Kept as `.img` because the file is
     * the picked picture byte for byte: whatever format it arrived in — PNG,
     * WebP, GIF — its transparency survives, since nothing is decoded or
     * re-encoded on the way in.
     */
    LOGO("_logo.img"),
}

/**
 * Artist artwork picked from the gallery, kept in the app's own files.
 *
 * The artist page's photograph normally arrives from the catalogue. This is the
 * user's own replacement for it — a photograph for the header and a cut-out
 * logo, in any image format the picker can hand over, transparency intact —
 * stored beside the page's other private state rather than in the shared
 * gallery, so it needs no storage permission and disappears with the app.
 *
 * [revision] is what the page watches: saving or resetting bumps it, and the
 * page rebuilds the two URLs it draws from. Each URL also carries the file's
 * modification time, so Coil treats a replaced picture as a new one rather than
 * handing back the previous decode.
 */
object LocalArtistArt {
    private const val DIRECTORY = "artist_art"

    /** Where logos were kept before they were stored verbatim; still read. */
    private const val LEGACY_LOGO_SUFFIX = "_logo.png"

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun bannerFile(context: Context, artist: String): File =
        file(context, artist, ArtistArtKind.BANNER)

    fun logoFile(context: Context, artist: String): File =
        file(context, artist, ArtistArtKind.LOGO)

    /** The saved banner, or null when there is none (or it is empty). */
    fun banner(context: Context, artist: String): File? = bannerFile(context, artist).existing()

    /** The saved logo, or null when there is none (or it is empty). */
    fun logo(context: Context, artist: String): File? =
        logoFile(context, artist).existing() ?: legacyLogoFile(context, artist).existing()

    /**
     * A model Coil can load, with the file's modification time in the URL.
     *
     * The query is ignored when the file is opened — only its path is read — but
     * it is part of the key Coil caches the decode under, so a picture written
     * over an older one at the same path is a different request.
     */
    fun model(file: File): String = "file://${file.absolutePath}?v=${file.lastModified()}"

    /**
     * Copies the picked image into internal storage, byte for byte. False when
     * nothing usable was read.
     *
     * Nothing is decoded, resized or re-encoded: the bytes the picker returns
     * are the bytes on disk, which is the only way an image's alpha — a
     * transparent PNG, an animated WebP, a GIF — comes through untouched.
     */
    suspend fun save(
        context: Context,
        artist: String,
        kind: ArtistArtKind,
        uri: Uri,
    ): Boolean = withContext(Dispatchers.IO) {
        val saved = runCatching {
            val target = file(context, artist, kind)
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            target.length() > 0L
        }.getOrDefault(false)
        if (saved) {
            // A logo saved under the old name would otherwise outlive the one
            // just picked wherever the new file is missing.
            if (kind == ArtistArtKind.LOGO) legacyLogoFile(context, artist).delete()
            _revision.value = System.currentTimeMillis()
        }
        saved
    }

    /** Deletes both pictures for [artist]; true when at least one was there. */
    fun reset(context: Context, artist: String): Boolean {
        var removed = false
        listOf(
            bannerFile(context, artist),
            logoFile(context, artist),
            legacyLogoFile(context, artist),
        ).forEach { file ->
            if (file.exists() && file.delete()) removed = true
        }
        _revision.value = System.currentTimeMillis()
        return removed
    }

    /**
     * The artist's name as a file name: letters, digits and a few safe
     * punctuation marks kept, every other run of characters folded to one
     * underscore. Names are matched case-sensitively, as the artist page's
     * title is.
     */
    fun sanitize(name: String): String = name
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "artist" }

    private fun file(context: Context, artist: String, kind: ArtistArtKind): File =
        File(File(context.filesDir, DIRECTORY), "${sanitize(artist)}${kind.suffix}")

    private fun legacyLogoFile(context: Context, artist: String): File =
        File(File(context.filesDir, DIRECTORY), "${sanitize(artist)}$LEGACY_LOGO_SUFFIX")

    private fun File.existing(): File? = takeIf { it.isFile && it.length() > 0L }
}
