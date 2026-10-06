package com.lunara.extractor.cipher

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Owns the player-config table at runtime: bundled asset as the offline default,
 * overlaid by the same JSON fetched from remote sources so rotated players are
 * fixed without an APK update. Parsing/validation is delegated to
 * [PlayerConfigParser]; only validated payloads ever replace the in-memory map
 * or touch the disk cache.
 *
 * Read path is lock-free: lookups hit an immutable map behind a @Volatile
 * reference that refreshes swap wholesale. Each swap bumps [configEpoch] so the
 * cipher stack can detect that a WebView built under an older table may be stale.
 *
 * The table schema is shared with the player-config registry this file follows:
 * same schema, same entries, refreshed whenever a new player build appears.
 * Neither remote source is fatal — the bundled table alone still plays today.
 */
object PlayerConfigStore {
    private const val TAG = "Lunara_ConfigStore"
    private const val ASSET_NAME = "player_configs.json"

    /**
     * Where remote tables come from, in the order they are asked.
     *
     * Each source keeps its own cached body and ETag, and the table is the
     * bundled asset with every cached source laid over it, so a source that is
     * behind can never take away a player another one already taught.
     */
    internal enum class Source(private val encodedUrl: String, val cacheName: String) {
        OWN(
            "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL3JhamVuZHJhNzE2OS9ibGF6aWZ5L3BsYXllci1jb25maWdzL3BsYXllcl9jb25maWdzLmpzb24=",
            "configs_own",
        ),
        MIRROR(
            "aHR0cHM6Ly9jZG4uanNkZWxpdnIubmV0L2doL3JhamVuZHJhNzE2OS9ibGF6aWZ5QHBsYXllci1jb25maWdzL3BsYXllcl9jb25maWdzLmpzb24=",
            "configs_mirror",
        ),

        // Keeps the file names from when this was the only source, so a copy cached back then still loads.
        UPSTREAM(
            "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL01ldHJvbGlzdEdyb3VwL2ZhcmFkYXkvbWFzdGVyL3JlZ2lzdHJ5L3BsYXllcl9jb25maWdzLmpzb24=",
            "configs_remote",
        ),
        ;

        val url: String by lazy { String(Base64.decode(encodedUrl, Base64.DEFAULT), StandardCharsets.UTF_8) }
    }

    // Lowest first. Our copy goes on top: when it disagrees with upstream it is either a fix made
    // on purpose or a few hours behind, while upstream's cache may be all that is left of a file
    // that has since moved. The mirror trails our copy, so it sits just under it.
    private val OVERLAY_ORDER = listOf(Source.UPSTREAM, Source.MIRROR, Source.OWN)

    private const val REFRESH_TTL_MS = 6 * 60 * 60 * 1000L

    // Failure-triggered refreshes are rate-limited so a player that is unknown both locally
    // and remotely doesn't turn every song into a remote request.
    private const val FORCE_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var appContext: Context? = null

    /** Current table. Replaced wholesale on every load/refresh; never mutated. */
    @Volatile
    private var configs: Map<String, PlayerConfigParser.PlayerConfig> = emptyMap()

    /**
     * Bumped on every wholesale table swap. The cipher stack records the epoch a
     * WebView was built under and rebuilds it when the epoch has moved on.
     */
    @Volatile
    var configEpoch: Long = 0L
        private set

    @Volatile
    private var lastForceRefreshMs: Long = 0L

    @Volatile
    private var lastRejectionRefreshMs: Long = 0L

    /** Cooldown for the CDN-refusal-triggered refresh; independent of the miss-triggered one. */
    private const val REJECTION_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

    private val initMutex = Mutex()
    private val fetchMutex = Mutex()

    @Volatile
    private var initialized = false

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Test seam: unit tests point this at a temp dir; production resolves from appContext.
    internal var cacheDirForTest: File? = null

    // Test seam: unit tests point the sources at a local server.
    internal var sourceUrlsForTest: Map<Source, String>? = null
    /**
     * Loads the bundled table plus any previously cached remote overlay, synchronously.
     * Must be called once from app start before any lookup; afterwards [scheduleStartupRefresh]
     * brings the table up to date without blocking playback.
     */
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val bundled = parseSource("bundled asset") {
            runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: emptyMap()
        val cached = loadCachedOverlay()
        swap(bundled + cachedOverlayInOrder(cached))
        initialized = true
        Log.d(TAG, "Loaded ${configs.size} player configs (bundled=${bundled.size})")
    }

    /** Lock-free lookup on the hot path: never blocks, never does I/O. */
    fun configForPlayer(hash: String): PlayerConfigParser.PlayerConfig? = configs[hash]

    fun signatureTimestampFor(hash: String): Int? = configs[hash]?.signatureTimestamp

    fun knownHashes(): Set<String> = configs.keys

    /** Swap the whole table and bump the epoch so cipher WebViews can detect staleness. */
    private fun swap(next: Map<String, PlayerConfigParser.PlayerConfig>) {
        configs = next
        configEpoch++
    }

    /** TTL-gated background refresh, fired once at startup. Never throws. */
    fun scheduleStartupRefresh() {
        refreshScope.launch {
            runCatching { refreshIfStale() }.onFailure {
                Log.d(TAG, "Startup config refresh failed: ${it.message}")
            }
        }
    }

    /**
     * Ensures [hash] is known, triggering a rate-limited remote refresh when it is not.
     * Returns true when the hash is known afterwards.
     */
    suspend fun ensurePlayerKnown(hash: String): Boolean {
        if (configs.containsKey(hash)) return true
        val now = System.currentTimeMillis()
        if (now - lastForceRefreshMs < FORCE_REFRESH_COOLDOWN_MS) return false
        lastForceRefreshMs = now
        refresh(force = true)
        return configs.containsKey(hash)
    }

    /**
     * Synchronous self-heal entry: bring [missingHash] into the table if it is not
     * there, rate-limited so a player the table genuinely does not cover costs one
     * fetch per window rather than one per song. Returns true when the hash is known
     * afterwards — i.e. only when a re-extraction can actually succeed.
     */
    suspend fun forceRefresh(missingHash: String): Boolean = ensurePlayerKnown(missingHash)

    /**
     * Fingerprint of the current table's key set. Stored beside an "undecipherable
     * player" verdict so that a table update invalidates the verdict automatically —
     * a new table may exist precisely because that player was taught.
     */
    fun tableFingerprint(): String =
        "${configs.size}:${configs.keys.sorted().joinToString(",").hashCode()}"

    /**
     * Teaches the table about [missingHash] off the hot path. The caller has already
     * decided it cannot proceed without the hash; this is the non-blocking second
     * chance, so the *next* attempt can.
     */
    fun refreshInBackground(missingHash: String) {
        if (configs.containsKey(missingHash)) return
        refreshScope.launch {
            runCatching { ensurePlayerKnown(missingHash) }.onFailure {
                Log.d(TAG, "Background refresh for $missingHash failed: ${it.message}")
            }
        }
    }

    /**
     * Full refresh after the CDN refused a stream this table produced: every entry can
     * be individually valid while the table as a whole is behind a player rotation.
     * Rate-limited so a run of rejections cannot become a fetch per song.
     *
     * Returns true when a refresh actually ran.
     */
    suspend fun refreshAfterStreamRejection(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRejectionRefreshMs < REJECTION_REFRESH_COOLDOWN_MS) {
            Log.d(TAG, "Post-rejection refresh skipped (cooldown)")
            return false
        }
        lastRejectionRefreshMs = now
        refresh(force = true)
        return true
    }

    /** True while [stampMs] lies in the half-open window `[stampMs, stampMs + windowMs)`. */
    internal fun withinWindow(now: Long, stampMs: Long, windowMs: Long): Boolean =
        (now - stampMs) in 0 until windowMs


    private suspend fun refreshIfStale() {
        var stale = false
        for (source in Source.values()) {
            val meta = readMeta(source) ?: run { stale = true; break }
            if (System.currentTimeMillis() - meta.second > REFRESH_TTL_MS) {
                stale = true
                break
            }
        }
        if (stale) refresh(force = false) else applyCachedOverlay()
    }

    private suspend fun refresh(force: Boolean) = fetchMutex.withLock {
        withContext(Dispatchers.IO) {
            var changed = false
            for (source in Source.values()) {
                if (!force) {
                    val meta = readMeta(source)
                    if (meta != null && System.currentTimeMillis() - meta.second <= REFRESH_TTL_MS) {
                        continue
                    }
                }
                if (fetchSource(source)) changed = true
            }
            if (changed) applyCachedOverlay()
        }
    }
    /**
     * Re-reads every cached source body off disk and overlays them onto the bundled
     * asset. A corrupt cache beside a valid ETag must not wedge refreshes forever, so
     * a body that no longer parses is deleted and skipped rather than trusted.
     */
    private fun applyCachedOverlay() {
        val bundled = parseSource("bundled asset", ::loadBundledJson) ?: return
        val cached = mutableMapOf<Source, Map<String, PlayerConfigParser.PlayerConfig>>()
        for (source in Source.values()) {
            val parsed = parseSource("${source.name} cache") {
                cacheFile(source)?.takeIf { it.exists() }?.readText()
            }
            if (parsed != null) {
                cached[source] = parsed
                continue
            }
            if (cacheFile(source)?.exists() == true) {
                cacheFile(source)?.delete()
                metaFile(source)?.delete()
            }
        }
        val next = bundled + cachedOverlayInOrder(cached)
        if (next.keys != configs.keys) {
            swap(next)
            Log.d(TAG, "Config overlay applied: ${next.size} entries (epoch=$configEpoch)")
        }
    }

    private fun cachedOverlayInOrder(
        cached: Map<Source, Map<String, PlayerConfigParser.PlayerConfig>>,
    ): Map<String, PlayerConfigParser.PlayerConfig> {
        var merged = emptyMap<String, PlayerConfigParser.PlayerConfig>()
        for (source in OVERLAY_ORDER) {
            cached[source]?.let { merged = PlayerConfigParser.merge(merged, it) }
        }
        return merged
    }

    private fun loadCachedOverlay(): Map<Source, Map<String, PlayerConfigParser.PlayerConfig>> =
        buildMap {
            for (source in Source.values()) {
                parseSource("${source.name} cache") {
                    cacheFile(source)?.takeIf { it.exists() }?.readText()
                }?.let { put(source, it) }
            }
        }

    private fun fetchSource(source: Source): Boolean {
        val url = sourceUrlsForTest?.get(source) ?: source.url
        val (etag, _) = readMeta(source) ?: ("" to 0L)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Lunara/1.0")
            .apply { if (etag.isNotEmpty()) header("If-None-Match", etag) }
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> {
                        writeMeta(source, etag, System.currentTimeMillis())
                        false
                    }
                    !response.isSuccessful -> {
                        Log.d(TAG, "${source.name} config HTTP ${response.code}")
                        false
                    }
                    else -> {
                        val body = response.body?.string() ?: return false
                        when (val result = PlayerConfigParser.parse(body)) {
                            is PlayerConfigParser.ParseResult.Failure -> {
                                Log.w(TAG, "Rejected ${source.name}: ${result.reason}")
                                false
                            }
                            is PlayerConfigParser.ParseResult.Success -> {
                                if (result.skippedEntries.isNotEmpty()) {
                                    Log.w(TAG, "${source.name} skipped ${result.skippedEntries}")
                                }
                                cacheFile(source)?.let { writeAtomic(it, body) }
                                writeMeta(source, response.header("ETag").orEmpty(), now())
                                true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not fetch ${source.name}: ${e.message}")
            false
        }
    }

    private fun parseSource(
        label: String,
        read: () -> String?,
    ): Map<String, PlayerConfigParser.PlayerConfig>? {
        val text = try {
            read() ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $label: ${e.message}")
            return null
        }
        return when (val result = PlayerConfigParser.parse(text)) {
            is PlayerConfigParser.ParseResult.Failure -> {
                Log.w(TAG, "Rejected $label: ${result.reason}")
                null
            }
            is PlayerConfigParser.ParseResult.Success -> {
                if (result.skippedEntries.isNotEmpty()) {
                    Log.w(TAG, "$label skipped ${result.skippedEntries}")
                }
                result.configs
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun loadBundledJson(): String? {
        val context = appContext ?: return null
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun cacheDir(): File? {
        cacheDirForTest?.let { return it.apply { if (!exists()) mkdirs() } }
        val context = appContext ?: return null
        return File(context.filesDir, "cipher_cache").apply { if (!exists()) mkdirs() }
    }

    private fun cacheFile(source: Source): File? =
        cacheDir()?.let { File(it, "${source.cacheName}.json") }

    private fun metaFile(source: Source): File? =
        cacheDir()?.let { File(it, "${source.cacheName}.meta") }

    /** Meta file: line 1 = ETag (may be empty), line 2 = lastFetchMs. */
    private fun readMeta(source: Source): Pair<String, Long>? {
        return try {
            val file = metaFile(source)?.takeIf { it.exists() } ?: return null
            val lines = file.readText().split("\n")
            if (lines.size < 2) return null
            val lastFetchMs = lines[1].toLongOrNull() ?: return null
            lines[0] to lastFetchMs
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(source: Source, etag: String, lastFetchMs: Long) {
        try {
            metaFile(source)?.let { writeAtomic(it, "$etag\n$lastFetchMs") }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write ${source.name} config meta: ${e.message}")
        }
    }

    /**
     * Temp-file + rename so a process death mid-write can't leave a truncated file (a
     * corrupt cache body beside a valid ETag is exactly the 304-lock state
     * [applyCachedOverlay] defends against).
     */
    internal fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            // renameTo won't overwrite an existing target on some filesystems — retry after
            // deleting it (two cheap metadata ops, still atomic) before the last-resort direct
            // write, which is both non-atomic and a second full write of the content.
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(content)
                tmp.delete()
            }
        }
    }
}
