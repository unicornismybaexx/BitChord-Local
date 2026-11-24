package com.music.bitchord.data.animatedcover

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * A looping picture the listener picked for one track — an animated GIF or
 * WebP, or a short MP4 / WebM clip — kept in the app's own files and shown in
 * the player in place of the still cover.
 *
 * The file lives at `filesDir/animated_covers/<id>`, where `<id>` is the
 * track's own id (or, when the pick was made for a release, the album's id
 * under [ALBUM_PREFIX]). It needs no storage permission and disappears with
 * the app.
 *
 * The extension is kept because it is how the picture is played back: an
 * animated image goes through Coil, a video clip through the player's own
 * muted, looping video surface. Everything that draws the track watches
 * [revision], which is bumped on save and reset so the new file is picked up
 * in place.
 */
object AnimatedCover {
    private const val DIRECTORY = "animated_covers"
    private const val ALBUM_PREFIX = "album_"

    private val EXTENSIONS = listOf(
        "gif", "webp", "apng", "png", "avif",
        "mp4", "webm", "mkv", "mov", "m4v", "3gp",
    )

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp")

    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever a cover is set or removed, so every surface redraws. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** The file for [song], if one was picked: the track's own first, then its album's. */
    fun file(context: Context, song: Song?): File? {
        song ?: return null
        return find(context, trackKey(song)) ?: albumKey(song)?.let { find(context, it) }
    }

    /** The animated image for [song], or null when there is none (or it is a video). */
    fun imageFile(context: Context, song: Song?): File? =
        file(context, song)?.takeUnless { isVideo(it) }

    /** The video clip for [song], or null when there is none (or it is an image). */
    fun videoFile(context: Context, song: Song?): File? =
        file(context, song)?.takeIf { isVideo(it) }

    /** Whether anything has been picked for [song]. */
    fun has(context: Context, song: Song?): Boolean = file(context, song) != null

    /**
     * A model Coil can load, with the file's modification time in the URL so a
     * picture written over an older one at the same path is a new request.
     */
    fun model(file: File): String = "file://${file.absolutePath}?v=${file.lastModified()}"

    /** A model the platform's video decoder can open, for the player's clip surface. */
    fun videoUrl(file: File): String = Uri.fromFile(file).toString()

    /** The looping clip [song] was given by hand, for the player's canvas slot. */
    fun videoCanvas(context: Context, song: Song?): CanvasArtwork? {
        val file = videoFile(context, song) ?: return null
        return CanvasArtwork(url = videoUrl(file), source = CanvasSource.OTHER)
    }

    /** Copies the picked picture into internal storage. False when nothing usable was read. */
    suspend fun save(context: Context, song: Song?, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (song == null) return@withContext false
        val key = trackKey(song)
        val extension = extensionOf(context, uri)
        val saved = runCatching {
            val directory = directory(context).apply { mkdirs() }
            removeFiles(directory, key)
            val target = File(directory, "$key.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            target.length() > 0L
        }.getOrDefault(false)
        if (saved) _revision.value = System.currentTimeMillis()
        saved
    }

    /** Deletes the cover picked for [song]; true when there was one to delete. */
    fun reset(context: Context, song: Song?): Boolean {
        song ?: return false
        val directory = directory(context)
        var removed = removeFiles(directory, trackKey(song))
        albumKey(song)?.let { removed = removeFiles(directory, it) || removed }
        _revision.value = System.currentTimeMillis()
        return removed
    }

    /** Whether [file] is a video clip rather than an animated image. */
    fun isVideo(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS

    /**
     * The track's id as a file name: letters, digits and a few safe punctuation
     * marks kept, every other run of characters folded to one underscore.
     */
    fun sanitize(id: String): String = id
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "cover" }

    fun trackKey(song: Song): String = sanitize(song.videoId)

    fun albumKey(song: Song): String? =
        song.albumName?.takeIf { it.isNotBlank() }?.let { ALBUM_PREFIX + sanitize(it) }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    private fun find(context: Context, key: String): File? {
        val directory = directory(context)
        if (!directory.isDirectory) return null
        return directory.listFiles()
            ?.firstOrNull { file ->
                file.isFile && file.length() > 0L &&
                    file.name.substringBeforeLast('.').equals(key, ignoreCase = true) &&
                    file.extension.lowercase(Locale.ROOT) in EXTENSIONS
            }
    }

    private fun removeFiles(directory: File, key: String): Boolean {
        val stale = directory.listFiles()?.filter { file ->
            file.isFile && file.name.substringBeforeLast('.').equals(key, ignoreCase = true)
        }.orEmpty()
        var removed = false
        stale.forEach { file -> if (file.delete()) removed = true }
        return removed
    }

    private fun extensionOf(context: Context, uri: Uri): String {
        val mime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()?.lowercase(Locale.ROOT).orEmpty()
        when {
            mime.contains("gif") -> return "gif"
            mime.contains("webp") -> return "webp"
            mime.contains("apng") -> return "apng"
            mime.contains("avif") -> return "avif"
            mime.contains("webm") -> return "webm"
            mime.contains("quicktime") -> return "mov"
            mime.contains("matroska") -> return "mkv"
            mime.contains("3gpp") -> return "3gp"
            mime.contains("mp4") -> return "mp4"
            mime == "image/png" -> return "png"
        }
        val named = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return named.takeIf { it in EXTENSIONS } ?: if (mime.startsWith("video/")) "mp4" else "gif"
    }
}
