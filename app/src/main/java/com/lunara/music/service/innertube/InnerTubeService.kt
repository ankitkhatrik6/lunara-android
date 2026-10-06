package com.lunara.music.service.innertube

import android.util.Log
import com.lunara.music.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object InnerTubeService {
    private const val TAG = "InnerTubeService"
    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_HOST = "music.youtube.com"

    // Public YouTube Music (WEB_REMIX) client identity, as used by Blazify.
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_ID = "67"
    private const val CLIENT_VERSION = "1.20260213.01.00"
    private const val ORIGIN = "https://music.youtube.com"
    private const val REFERER = "$ORIGIN/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder().header("User-Agent", USER_AGENT)
            if (original.url.host == MUSIC_HOST) {
                val url = original.url.newBuilder()
                    .setQueryParameter("key", API_KEY)
                    .setQueryParameter("prettyPrint", "false")
                    .build()
                builder
                    .url(url)
                    .header("X-Goog-Api-Key", API_KEY)
                    .header("X-YouTube-Client-Name", CLIENT_ID)
                    .header("X-YouTube-Client-Version", CLIENT_VERSION)
                    .header("Origin", ORIGIN)
                    .header("Referer", REFERER)
                attachSession(builder)
            }
            chain.proceed(builder.build())
        }
        .build()

    private fun attachSession(builder: okhttp3.Request.Builder) {
        YouTubeSession.cookie.value?.let { cookie ->
            builder.header("Cookie", cookie)
            YouTubeSession.authorizationHeader(ORIGIN)?.let { auth ->
                builder.header("Authorization", auth)
            }
        }
        YouTubeSession.visitorData?.takeIf { it.isNotBlank() }?.let { visitor ->
            builder.header("X-Goog-Visitor-Id", visitor)
        }
    }

    private fun getClientContext(): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", CLIENT_NAME)
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
                put("gl", "US")
            })
        }
    }

    private fun extractRunsText(obj: JSONObject?, field: String = "runs"): String {
        if (obj == null) return ""
        val runs = obj.optJSONArray(field) ?: return obj.optString("simpleText", "")
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            sb.append(r.optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun extractThumbnail(obj: JSONObject?): String? {
        if (obj == null) return null
        val thumbs = obj.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: obj.optJSONArray("thumbnails")
            ?: return null

        if (thumbs.length() == 0) return null
        val bestUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url")
        // Improve resolution from low default thumbnails if needed
        return bestUrl.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
    }

    private fun parseDuration(durationStr: String): Long {
        if (durationStr.isBlank()) return 0L
        val parts = durationStr.split(":")
        return when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
            3 -> (parts[0].toLongOrNull() ?: 0L) * 3600 + (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
            else -> 0L
        }
    }

    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                // Format: window.google.ac.h(["query",[["sug1",0,[...]],["sug2",0,...]],...])
                val start = body.indexOf("(")
                val end = body.lastIndexOf(")")
                if (start != -1 && end != -1 && end > start) {
                    val jsonStr = body.substring(start + 1, end)
                    val arr = JSONArray(jsonStr)
                    val suggestionsArr = arr.optJSONArray(1) ?: return@withContext emptyList()
                    val result = mutableListOf<String>()
                    for (i in 0 until suggestionsArr.length()) {
                        val item = suggestionsArr.optJSONArray(i)
                        val text = item?.optString(0)
                        if (!text.isNullOrBlank()) {
                            result.add(text)
                        }
                    }
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search suggestions failed: ${e.message}")
        }
        emptyList()
    }

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val artists = mutableListOf<Artist>()
        val albums = mutableListOf<Album>()
        val playlists = mutableListOf<Playlist>()

        try {
            val reqBody = JSONObject().apply {
                put("context", getClientContext())
                put("query", query)
            }

            val req = Request.Builder()
                .url("$BASE_URL/search")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext SearchResult()
                val body = resp.body?.string() ?: return@withContext SearchResult()
                val json = JSONObject(body)

                val tabs = json.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?: return@withContext SearchResult()

                val sections = tabs.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?: return@withContext SearchResult()

                for (sIdx in 0 until sections.length()) {
                    val sectionObj = sections.optJSONObject(sIdx) ?: continue

                    // Parse itemSectionRenderer
                    val isr = sectionObj.optJSONObject("itemSectionRenderer")
                    if (isr != null) {
                        val contents = isr.optJSONArray("contents") ?: JSONArray()
                        for (cIdx in 0 until contents.length()) {
                            val cObj = contents.optJSONObject(cIdx) ?: continue
                            val listItem = cObj.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            parseListItem(listItem, songs, artists, albums, playlists)
                        }
                    }

                    // Parse musicCardShelfRenderer (Top result)
                    val cardShelf = sectionObj.optJSONObject("musicCardShelfRenderer")
                    if (cardShelf != null) {
                        val title = extractRunsText(cardShelf.optJSONObject("title"))
                        val subtitle = extractRunsText(cardShelf.optJSONObject("subtitle"))
                        val thumb = extractThumbnail(cardShelf.optJSONObject("thumbnail"))
                        val nav = cardShelf.optJSONObject("onTap") ?: cardShelf.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                        val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId")
                        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId")

                        if (!videoId.isNullOrBlank()) {
                            songs.add(
                                Song(
                                    id = videoId,
                                    title = title,
                                    artist = subtitle.split("•").getOrNull(1)?.trim() ?: subtitle,
                                    thumbnailUrl = thumb
                                )
                            )
                        } else if (!browseId.isNullOrBlank()) {
                            if (browseId.startsWith("UC")) {
                                artists.add(Artist(id = browseId, name = title, thumbnailUrl = thumb))
                            } else if (browseId.startsWith("MPREb_")) {
                                albums.add(Album(id = browseId, title = title, artist = subtitle, thumbnailUrl = thumb))
                            } else if (browseId.startsWith("VL")) {
                                playlists.add(Playlist(id = browseId, title = title, description = subtitle, thumbnailUrl = thumb))
                            }
                        }
                    }

                    // Parse musicShelfRenderer
                    val shelf = sectionObj.optJSONObject("musicShelfRenderer")
                    if (shelf != null) {
                        val contents = shelf.optJSONArray("contents") ?: JSONArray()
                        for (cIdx in 0 until contents.length()) {
                            val cObj = contents.optJSONObject(cIdx) ?: continue
                            val listItem = cObj.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            parseListItem(listItem, songs, artists, albums, playlists)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
        }

        SearchResult(
            songs = songs.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id }
        )
    }

    private fun parseListItem(
        item: JSONObject,
        songs: MutableList<Song>,
        artists: MutableList<Artist>,
        albums: MutableList<Album>,
        playlists: MutableList<Playlist>
    ) {
        val flexColumns = item.optJSONArray("flexColumns") ?: return
        if (flexColumns.length() == 0) return

        val col1 = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val title = extractRunsText(col1)

        val col2 = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val subtitleRuns = col2?.optJSONArray("runs") ?: JSONArray()

        var artistName = ""
        var albumName: String? = null
        var duration = 0L

        for (i in 0 until subtitleRuns.length()) {
            val run = subtitleRuns.optJSONObject(i) ?: continue
            val text = run.optString("text", "").trim()
            if (text == "•" || text.isBlank()) continue

            if (text.contains(":") && text.length <= 8) {
                duration = parseDuration(text)
            } else if (artistName.isEmpty()) {
                artistName = text
            } else if (albumName == null) {
                albumName = text
            }
        }

        val thumb = extractThumbnail(item.optJSONObject("thumbnail"))

        val nav = item.optJSONObject("navigationEndpoint")
            ?: col1?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?: item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")

        // Album/playlist rows often carry the playable id only in
        // playlistItemData (or the menu's play command), not in any navigation
        // endpoint — without this fallback every such row is silently dropped
        // and detail screens show "0 songs".
        val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("playlistItemData")?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: item.optString("videoId").takeIf { it.isNotBlank() }
            ?: menuVideoId(item)
        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId")

        if (!videoId.isNullOrBlank()) {
            songs.add(
                Song(
                    id = videoId,
                    title = title.ifBlank { "Unknown Title" },
                    artist = artistName.ifBlank { "Unknown Artist" },
                    album = albumName,
                    durationSeconds = duration,
                    thumbnailUrl = thumb
                )
            )
        } else if (!browseId.isNullOrBlank()) {
            when {
                browseId.startsWith("UC") -> {
                    artists.add(Artist(id = browseId, name = title, thumbnailUrl = thumb))
                }
                browseId.startsWith("MPREb_") -> {
                    albums.add(Album(id = browseId, title = title, artist = artistName, thumbnailUrl = thumb))
                }
                browseId.startsWith("VL") || browseId.startsWith("RDAMPL") -> {
                    playlists.add(Playlist(id = browseId, title = title, description = artistName, thumbnailUrl = thumb))
                }
            }
        }
    }

    /**
     * Pulls a video id out of the row's overflow-menu play commands.
     * Some album/playlist responses only embed the id there.
     */
    private fun menuVideoId(item: JSONObject): String? {
        val items = item.optJSONObject("menu")?.optJSONObject("menuRenderer")
            ?.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val id = items.optJSONObject(i)
                ?.optJSONObject("menuNavigationItemRenderer")
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")?.takeIf { it.isNotBlank() }
            if (id != null) return id
        }
        return null
    }

    suspend fun getHome(): Map<String, List<Any>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, MutableList<Any>>()
        val trendingSongs = mutableListOf<Song>()
        val popularAlbums = mutableListOf<Album>()
        val featuredPlaylists = mutableListOf<Playlist>()

        try {
            val reqBody = JSONObject().apply {
                put("context", getClientContext())
                put("browseId", "FEmusic_home")
            }

            val req = Request.Builder()
                .url("$BASE_URL/browse")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyMap()
                val body = resp.body?.string() ?: return@withContext emptyMap()
                val json = JSONObject(body)

                val tabs = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")

                val sectionList = tabs?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: JSONArray()

                for (i in 0 until sectionList.length()) {
                    val sec = sectionList.optJSONObject(i) ?: continue
                    val carousel = sec.optJSONObject("musicCarouselShelfRenderer") ?: continue
                    val headerTitle = extractRunsText(carousel.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))

                    val contents = carousel.optJSONArray("contents") ?: JSONArray()
                    for (cIdx in 0 until contents.length()) {
                        val cObj = contents.optJSONObject(cIdx) ?: continue

                        // Two row item (Album or Playlist)
                        val twoRow = cObj.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            val title = extractRunsText(twoRow.optJSONObject("title"))
                            val subtitle = extractRunsText(twoRow.optJSONObject("subtitle"))
                            val thumb = extractThumbnail(twoRow.optJSONObject("thumbnailRenderer"))
                            val nav = twoRow.optJSONObject("navigationEndpoint")
                            val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                            val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""

                            if (browseId.startsWith("MPREb_")) {
                                popularAlbums.add(Album(id = browseId, title = title, artist = subtitle, thumbnailUrl = thumb))
                            } else if (browseId.startsWith("VL") || browseId.startsWith("RDAMPL")) {
                                featuredPlaylists.add(Playlist(id = browseId, title = title, description = subtitle, thumbnailUrl = thumb))
                            } else if (videoId.isNotBlank()) {
                                trendingSongs.add(Song(id = videoId, title = title, artist = subtitle, thumbnailUrl = thumb))
                            }
                        }

                        // Responsive list item (Song)
                        val listItem = cObj.optJSONObject("musicResponsiveListItemRenderer")
                        if (listItem != null) {
                            val songsTemp = mutableListOf<Song>()
                            val emptyList1 = mutableListOf<Artist>()
                            val emptyList2 = mutableListOf<Album>()
                            val emptyList3 = mutableListOf<Playlist>()
                            parseListItem(listItem, songsTemp, emptyList1, emptyList2, emptyList3)
                            trendingSongs.addAll(songsTemp)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHome failed: ${e.message}", e)
        }

        result["trending"] = trendingSongs.distinctBy { it.id }.take(20).toMutableList()
        result["albums"] = popularAlbums.distinctBy { it.id }.take(15).toMutableList()
        result["playlists"] = featuredPlaylists.distinctBy { it.id }.take(15).toMutableList()
        result
    }

    /**
     * Where the detail page's header lives has moved between response layouts, so every
     * shape is read rather than one: the legacy top-level `header`, and the current
     * two-column layout where it sits as the first section of the tab content. With no
     * header the screen shows its placeholder title and a blank byline.
     */
    private fun detailHeader(json: JSONObject): JSONObject? {
        val legacy = json.optJSONObject("header")
        legacy?.optJSONObject("musicDetailHeaderRenderer")?.let { return it }
        legacy?.optJSONObject("musicResponsiveHeaderRenderer")?.let { return it }

        val sections = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return null
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            section.optJSONObject("musicResponsiveHeaderRenderer")?.let { return it }
            section.optJSONObject("musicDetailHeaderRenderer")?.let { return it }
        }
        return null
    }

    /** Reads a `nextContinuationData` token out of a shelf or section list. */
    private fun collectContinuations(from: JSONObject, into: MutableList<String>) {
        val continuations = from.optJSONArray("continuations") ?: return
        for (i in 0 until continuations.length()) {
            continuations.optJSONObject(i)
                ?.optJSONObject("nextContinuationData")
                ?.optString("continuation")
                ?.takeIf { it.isNotBlank() }
                ?.let { into.add(it) }
        }
    }

    /** The track shelves of a browse response, plus the tokens that page past the first. */
    private data class BrowseShelves(
        val shelves: List<JSONObject>,
        val continuations: List<String>,
    )

    /**
     * Finds every track shelf the response carries, in every layout YouTube serves.
     *
     * Today's two-column layout puts the tracks in `secondaryContents`, beside the tab
     * that holds only the header — walking the tab alone finds nothing, which is how a
     * detail screen ends up showing "0 tracks" against a response that has them all.
     * The legacy single-column layout keeps them in the tab's section list, so both are
     * read, along with the shelf-level and section-level continuation tokens.
     */
    private fun browseShelves(json: JSONObject): BrowseShelves {
        val shelves = mutableListOf<JSONObject>()
        val continuations = mutableListOf<String>()

        fun consumeSections(sections: JSONArray?) {
            for (i in 0 until (sections?.length() ?: 0)) {
                val section = sections?.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("musicPlaylistShelfRenderer")
                    ?: continue
                shelves.add(shelf)
                collectContinuations(shelf, continuations)
            }
        }

        val contents = json.optJSONObject("contents")
        val twoColumn = contents?.optJSONObject("twoColumnBrowseResultsRenderer")

        twoColumn?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.let { sectionList ->
                consumeSections(sectionList.optJSONArray("contents"))
                collectContinuations(sectionList, continuations)
            }

        val tabs = twoColumn?.optJSONArray("tabs")
            ?: contents?.optJSONObject("singleColumnBrowseResultsRenderer")?.optJSONArray("tabs")
        for (t in 0 until (tabs?.length() ?: 0)) {
            val sectionList = tabs?.optJSONObject(t)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer") ?: continue
            consumeSections(sectionList.optJSONArray("contents"))
            collectContinuations(sectionList, continuations)
        }

        return BrowseShelves(shelves, continuations.distinct())
    }

    /**
     * Pulls a four-digit year out of a header subtitle ("Single • 2026"), because the
     * subtitle is the only place the year is still carried.
     */
    private fun extractYear(header: JSONObject?): String? {
        val runs = header?.optJSONObject("subtitle")?.optJSONArray("runs") ?: return null
        for (i in 0 until runs.length()) {
            val text = runs.optJSONObject(i)?.optString("text", "")?.trim() ?: continue
            if (text.matches(Regex("""\d{4}"""))) return text
        }
        return null
    }


    suspend fun getAlbum(browseId: String): Album? = withContext(Dispatchers.IO) {
        try {
            val reqBody = JSONObject().apply {
                put("context", getClientContext())
                put("browseId", browseId)
            }

            val req = Request.Builder()
                .url("$BASE_URL/browse")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val header = detailHeader(json)
                val title = extractRunsText(header?.optJSONObject("title"))
                val subtitle = extractRunsText(header?.optJSONObject("subtitle"))
                val thumb = extractThumbnail(header?.optJSONObject("thumbnail"))
                val year = extractYear(header)

                val tracks = mutableListOf<Song>()
                for (shelf in browseShelves(json).shelves) {
                    val contents = shelf.optJSONArray("contents") ?: continue
                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val before = tracks.size
                        val tmpAlbums = mutableListOf<Album>()
                        val tmpPlaylists = mutableListOf<Playlist>()
                        // Reuse the hardened item parser (playlistItemData/menu
                        // fallbacks) instead of hand-rolling nav extraction that
                        // drops rows and yields "0 songs".
                        parseListItem(item, tracks, mutableListOf(), tmpAlbums, tmpPlaylists)
                        // Stamp album context on freshly added rows.
                        for (k in before until tracks.size) {
                            val t = tracks[k]
                            if (t.album.isNullOrBlank() || t.thumbnailUrl.isNullOrBlank()) {
                                tracks[k] = t.copy(
                                    album = t.album?.takeIf { it.isNotBlank() } ?: title,
                                    artist = t.artist.takeIf { it.isNotBlank() && it != "Unknown Artist" } ?: subtitle,
                                    thumbnailUrl = t.thumbnailUrl?.takeIf { it.isNotBlank() } ?: thumb,
                                )
                            }
                        }
                    }
                }

                return@withContext Album(
                    id = browseId,
                    title = title,
                    artist = subtitle,
                    year = year,
                    thumbnailUrl = thumb,
                    trackCount = tracks.size,
                    tracks = tracks
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAlbum failed: ${e.message}", e)
        }
        null
    }

    suspend fun getPlaylist(browseId: String): Playlist? = withContext(Dispatchers.IO) {
        try {
            // Blazify parity: browse ids arrive as PL/VL/RDAMPL. The browse
            // endpoint wants the VL form for plain playlists, but radio/mix ids
            // must pass through untouched.
            val idParam = when {
                browseId.startsWith("VL") || browseId.startsWith("MP") ||
                    browseId.startsWith("RD") || browseId.startsWith("OLAK5uy_") -> browseId
                browseId.startsWith("PL") -> "VL" + browseId.substring(2)
                else -> browseId
            }
            val reqBody = JSONObject().apply {
                put("context", getClientContext())
                put("browseId", idParam)
            }

            val req = Request.Builder()
                .url("$BASE_URL/browse")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val header = detailHeader(json)
                val title = extractRunsText(header?.optJSONObject("title"))
                val desc = extractRunsText(header?.optJSONObject("description"))
                    .ifBlank { extractRunsText(header?.optJSONObject("subtitle")) }
                val thumb = extractThumbnail(header?.optJSONObject("thumbnail"))

                val tracks = mutableListOf<Song>()
                val continuations = mutableListOf<String>()

                // Every shelf the response carries, wherever it lives. Today's layout
                // keeps them in secondaryContents beside the tab — a tab-only walk
                // sees only the header, which is how a playlist showed "0 tracks".
                val shelfData = browseShelves(json)
                continuations.addAll(shelfData.continuations)
                for (shelf in shelfData.shelves) {
                    val contents = shelf.optJSONArray("contents") ?: continue
                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val songsTemp = mutableListOf<Song>()
                        parseListItem(item, songsTemp, mutableListOf(), mutableListOf(), mutableListOf())
                        tracks.addAll(songsTemp)
                    }
                }

                // Carousel shelves (radio/mix listings) live in the tab content and
                // carry their playable ids only in the two-row items.
                val tabs = json.optJSONObject("contents")
                    ?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?: json.optJSONObject("contents")
                        ?.optJSONObject("singleColumnBrowseResultsRenderer")
                        ?.optJSONArray("tabs") ?: JSONArray()

                for (t in 0 until tabs.length()) {
                    val sectionList = tabs.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents") ?: continue

                    for (s in 0 until sectionList.length()) {
                        val carousel = sectionList.optJSONObject(s)
                            ?.optJSONObject("musicCarouselShelfRenderer") ?: continue
                        val contents = carousel.optJSONArray("contents") ?: JSONArray()
                        for (i in 0 until contents.length()) {
                            val twoRow = contents.optJSONObject(i)
                                ?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                            val st = extractRunsText(twoRow.optJSONObject("title"))
                            val sa = extractRunsText(twoRow.optJSONObject("subtitle"))
                            val sh = extractThumbnail(twoRow.optJSONObject("thumbnailRenderer"))
                            val vid = twoRow.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
                            if (vid.isNotBlank()) {
                                tracks.add(Song(id = vid, title = st, artist = sa, thumbnailUrl = sh))
                            }
                        }
                    }
                }

                // Page long playlists via continuations (up to ~500 tracks).
                var depth = 0
                while (depth < 5 && continuations.isNotEmpty() && tracks.size < 500) {
                    tracks.addAll(getPlaylistContinuation(continuations.removeAt(0)))
                    depth += 1
                }

                return@withContext Playlist(
                    id = browseId,
                    title = title,
                    description = desc,
                    thumbnailUrl = thumb,
                    trackCount = tracks.size,
                    tracks = tracks
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylist failed: ${e.message}", e)
        }
        null
    }

    suspend fun getPlaylistContinuation(continuation: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val reqBody = JSONObject().apply {
                put("context", getClientContext())
                put("continuation", continuation)
            }

            val req = Request.Builder()
                .url("$BASE_URL/browse")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)

                val tracks = mutableListOf<Song>()
                // Continuations arrive as sectionListContinuation or
                // musicPlaylistShelfContinuation depending on the shelf.
                val shelves = mutableListOf<JSONObject>()
                json.optJSONObject("continuationContents")
                    ?.optJSONObject("musicPlaylistShelfContinuation")?.let { shelves.add(it) }
                json.optJSONObject("contents")
                    ?.optJSONObject("sectionListContinuation")
                    ?.optJSONArray("contents")?.let { sectionList ->
                        for (s in 0 until sectionList.length()) {
                            sectionList.optJSONObject(s)
                                ?.optJSONObject("musicPlaylistShelfRenderer")?.let { shelves.add(it) }
                        }
                    }

                for (shelf in shelves) {
                    val contents = shelf.optJSONArray("contents") ?: continue
                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val songsTemp = mutableListOf<Song>()
                        parseListItem(item, songsTemp, mutableListOf(), mutableListOf(), mutableListOf())
                        tracks.addAll(songsTemp)
                    }
                    // Keep paging: each shelf can hand us the token for the next
                    // page. Without this a long playlist stops after ~100 tracks.
                    shelf.optJSONArray("continuations")?.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")?.optString("continuation")
                        ?.takeIf { it.isNotBlank() }?.let { next ->
                            if (tracks.size < 500) {
                                runCatching { tracks.addAll(getPlaylistContinuation(next)) }
                            }
                        }
                }

                return@withContext tracks
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylistContinuation failed: ${e.message}", e)
        }
        emptyList()
    }

    suspend fun getArtist(browseId: String): Artist? = withContext(Dispatchers.IO) {
        try {
            val reqBody = JSONObject().apply {
                put("context", getClientContext())
                put("browseId", browseId)
            }

            val req = Request.Builder()
                .url("$BASE_URL/browse")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val header = json.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
                    ?: json.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")
                val name = extractRunsText(header?.optJSONObject("title"))
                val thumb = extractThumbnail(header?.optJSONObject("thumbnail"))
                val subs = extractRunsText(header?.optJSONObject("subscriptionButton")?.optJSONObject("subscribeButtonRenderer")?.optJSONObject("subscriberCountText"))

                val topSongs = mutableListOf<Song>()
                val albums = mutableListOf<Album>()

                val sectionList = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: JSONArray()

                for (s in 0 until sectionList.length()) {
                    val sec = sectionList.optJSONObject(s) ?: continue
                    val shelf = sec.optJSONObject("musicShelfRenderer")
                    if (shelf != null) {
                        val contents = shelf.optJSONArray("contents") ?: JSONArray()
                        for (i in 0 until contents.length()) {
                            val item = contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val songsTemp = mutableListOf<Song>()
                            parseListItem(item, songsTemp, mutableListOf(), mutableListOf(), mutableListOf())
                            topSongs.addAll(songsTemp)
                        }
                    }

                    val carousel = sec.optJSONObject("musicCarouselShelfRenderer")
                    if (carousel != null) {
                        val contents = carousel.optJSONArray("contents") ?: JSONArray()
                        for (i in 0 until contents.length()) {
                            val twoRow = contents.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                            val albumTitle = extractRunsText(twoRow.optJSONObject("title"))
                            val albumSubtitle = extractRunsText(twoRow.optJSONObject("subtitle"))
                            val albumThumb = extractThumbnail(twoRow.optJSONObject("thumbnailRenderer"))
                            val bId = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                            if (bId.startsWith("MPREb_")) {
                                albums.add(Album(id = bId, title = albumTitle, artist = name, thumbnailUrl = albumThumb))
                            }
                        }
                    }
                }

                return@withContext Artist(
                    id = browseId,
                    name = name,
                    thumbnailUrl = thumb,
                    subscriberCount = subs.ifBlank { null },
                    topSongs = topSongs.distinctBy { it.id }.take(10),
                    albums = albums.distinctBy { it.id }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getArtist failed: ${e.message}", e)
        }
        null
    }
}
