package com.music.bitchord.data.local

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.data.LocalBuild
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The Local edition's feeds, built from the phone's own files.
 *
 * Home and Search keep their layouts but have no catalogue behind them: every
 * row here is a track, album, artist or playlist read off the device. Nothing
 * in this file touches the network.
 */

/** Every track on the device, read once per [refresh] (and whenever access changes). */
@Composable
fun rememberLocalSongs(refresh: Int): UiState<List<Song>> {
    val context = LocalContext.current
    val hasAccess = LocalMediaRepository.hasStoragePermission(context)
    return produceState<UiState<List<Song>>>(UiState.Loading, refresh, hasAccess) {
        value = if (!hasAccess) {
            UiState.Error(context.getString(R.string.storage_required_read))
        } else {
            withContext(Dispatchers.IO) {
                runCatching { LocalMediaRepository.getLocalMusic(context) }.fold(
                    onSuccess = { songs ->
                        if (songs.isEmpty()) {
                            UiState.Error(context.getString(R.string.no_local_audio_found))
                        } else {
                            UiState.Success(songs)
                        }
                    },
                    onFailure = { UiState.Error(context.getString(R.string.no_local_audio_found)) },
                )
            }
        }
    }.value
}

/** A track card for one of Home's shelves. */
fun localSongItem(song: Song): ShelfItem = ShelfItem(
    title = song.title,
    subtitle = song.artist,
    thumbnailUrl = song.thumbnailUrl,
    videoId = song.videoId,
    browseId = null,
)

/** The page id a local artist's name opens — the folder's own Artist page. */
fun localArtistBrowseId(name: String): String =
    LocalGroups.artistPageId(LocalBuild.LIBRARY_BROWSE_ID, name)

/** The page id a local album's name opens. */
fun localAlbumBrowseId(name: String): String =
    LocalGroups.albumPageId(LocalBuild.LIBRARY_BROWSE_ID, name)

/** What a local page id is, for opening it from a feed card. */
fun localBrowseType(browseId: String): BrowseType =
    // An artist is its own page, not the generic list LocalGroups.typeOf maps
    // every unknown kind to.
    if (LocalGroups.isArtist(browseId)) {
        BrowseType.ARTIST
    } else {
        LocalGroups.typeOf(browseId) ?: when {
            OfflinePlaylists.isOfflinePlaylist(browseId) -> BrowseType.PLAYLIST
            LocalPlaylistStore.getPlaylist(browseId) != null -> BrowseType.PLAYLIST
            else -> BrowseType.OTHER
        }
    }

/**
 * The device played most, first — read off Replay's all-time chart and matched
 * back to the files that are still on it. Empty until something has been played.
 */
suspend fun localMostPlayed(songs: List<Song>): List<Song> {
    if (songs.isEmpty()) return emptyList()
    val byId = songs.associateBy { it.videoId }
    val summary = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull()
        ?: return emptyList()
    return summary.songs
        .mapNotNull { ranked -> byId[ranked.song.videoId] }
        .distinctBy { it.videoId }
}

/** A day-stable shuffle, so Quick Picks turns over each day but not each redraw. */
fun localQuickPicks(songs: List<Song>): List<Song> {
    val seed = LocalDate.now().toEpochDay()
    return songs.sortedBy { song ->
        ((song.videoId.hashCode().toLong() * 31L + seed) and 0x7fffffffL)
    }
}

/**
 * Home's carousels, in the order the page draws them: what arrived recently,
 * what gets played most, the device's albums and artists, then Quick Picks.
 */
fun localHomeShelves(
    context: Context,
    songs: List<Song>,
    mostPlayed: List<Song>,
): List<HomeShelf> {
    if (songs.isEmpty()) return emptyList()
    val recentlyAdded = songs
        .sortedByDescending { it.localDateAddedSeconds ?: it.localDateModifiedSeconds ?: 0L }
        .take(HOME_SHELF_SIZE)
    val albums = LocalGroups
        .albumsOf(LocalBuild.LIBRARY_BROWSE_ID, songs, emptyList())
        .take(HOME_SHELF_SIZE)
    val artists = LocalGroups
        .artistsOf(LocalBuild.LIBRARY_BROWSE_ID, songs)
        .take(HOME_SHELF_SIZE)
    val quickPicks = localQuickPicks(songs).take(HOME_SHELF_SIZE)

    return buildList {
        if (recentlyAdded.isNotEmpty()) {
            add(
                HomeShelf(
                    title = context.getString(R.string.local_recently_added),
                    items = recentlyAdded.map(::localSongItem),
                ),
            )
        }
        if (mostPlayed.isNotEmpty()) {
            add(
                HomeShelf(
                    title = context.getString(R.string.local_most_played),
                    items = mostPlayed.take(HOME_SHELF_SIZE).map(::localSongItem),
                    layout = ShelfLayout.LIST,
                ),
            )
        }
        if (albums.isNotEmpty()) {
            add(
                HomeShelf(
                    title = context.getString(R.string.local_albums),
                    items = albums.map { album ->
                        ShelfItem(
                            title = album.title,
                            subtitle = album.subtitle,
                            thumbnailUrl = album.thumbnailUrl,
                            videoId = null,
                            browseId = album.browseId,
                        )
                    },
                ),
            )
        }
        if (artists.isNotEmpty()) {
            add(
                HomeShelf(
                    title = context.getString(R.string.local_artists),
                    // No picture: an album cover is not the artist, and this
                    // build never borrows one. The card draws its placeholder
                    // until a banner or logo is picked on the artist's page.
                    items = artists.map { artist ->
                        ShelfItem(
                            title = artist.title,
                            subtitle = "",
                            thumbnailUrl = null,
                            videoId = null,
                            browseId = artist.browseId,
                        )
                    },
                ),
            )
        }
        if (quickPicks.isNotEmpty()) {
            add(
                HomeShelf(
                    title = context.getString(R.string.local_quick_picks),
                    items = quickPicks.map(::localSongItem),
                    layout = ShelfLayout.LIST,
                ),
            )
        }
    }
}

/** A playlist made on this device, as a name and the page it opens. */
data class LocalPlaylistRef(val browseId: String, val title: String)

/** The playlists made on this device, as searchable cards. */
fun localPlaylistRefs(): List<LocalPlaylistRef> = buildList {
    OfflinePlaylists.playlists.value.forEach { playlist ->
        add(LocalPlaylistRef(playlist.browseId, playlist.title))
    }
    LocalPlaylistStore.playlists.value.forEach { playlist ->
        if (none { it.browseId == playlist.browseId }) {
            add(LocalPlaylistRef(playlist.browseId, playlist.title))
        }
    }
}

/**
 * The Search tab's answer, read off the device: matching tracks, albums,
 * artists and playlists, narrowed by the page's own filter chips.
 */
fun localSearchResults(
    context: Context,
    songs: List<Song>,
    playlists: List<LocalPlaylistRef>,
    query: String,
    filter: SearchFilter,
): List<SearchResult> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()

    fun matches(vararg fields: String?): Boolean =
        fields.any { it?.contains(term, ignoreCase = true) == true }

    val all = filter == SearchFilter.ALL
    val results = mutableListOf<SearchResult>()

    if (all || filter == SearchFilter.ALBUMS) {
        LocalGroups.albumsOf(LocalBuild.LIBRARY_BROWSE_ID, songs, emptyList())
            .filter { matches(it.title, it.subtitle) }
            .take(SEARCH_BROWSE_SIZE)
            .forEach { album ->
                results += SearchResult.Browse(
                    BrowseItem(
                        browseId = album.browseId,
                        title = album.title,
                        subtitle = album.subtitle.ifBlank { context.getString(R.string.album) },
                        thumbnailUrl = album.thumbnailUrl,
                        type = BrowseType.ALBUM,
                    ),
                )
            }
    }
    if (all || filter == SearchFilter.ARTISTS) {
        LocalGroups.artistsOf(LocalBuild.LIBRARY_BROWSE_ID, songs)
            .filter { matches(it.title) }
            .take(SEARCH_BROWSE_SIZE)
            .forEach { artist ->
                results += SearchResult.Browse(
                    BrowseItem(
                        browseId = artist.browseId,
                        title = artist.title,
                        subtitle = context.getString(R.string.artist),
                        thumbnailUrl = null,
                        type = BrowseType.ARTIST,
                    ),
                )
            }
    }
    if (all || filter == SearchFilter.PLAYLISTS) {
        playlists
            .filter { matches(it.title) }
            .take(SEARCH_BROWSE_SIZE)
            .forEach { playlist ->
                results += SearchResult.Browse(
                    BrowseItem(
                        browseId = playlist.browseId,
                        title = playlist.title,
                        subtitle = context.getString(R.string.playlist),
                        thumbnailUrl = null,
                        type = BrowseType.PLAYLIST,
                    ),
                )
            }
    }
    if (all || filter == SearchFilter.SONGS) {
        songs
            .filter { matches(it.title, it.artist, it.albumName) }
            .take(SEARCH_TRACK_SIZE)
            .forEach { song -> results += SearchResult.Track(song) }
    }
    return results
}

private const val HOME_SHELF_SIZE = 25
private const val SEARCH_BROWSE_SIZE = 20
private const val SEARCH_TRACK_SIZE = 80
