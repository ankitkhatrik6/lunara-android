package com.lunara.music.service.audio

import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache

/**
 * The single disk cache streamed audio is read through and written back to.
 *
 * Both reference apps — Blazify and InnerTune — keep one around: a `SimpleCache`
 * with a least-recently-used evictor, consulted by the resolving data source before
 * any network work. Without it every replay, every back-seek and every re-listen
 * re-downloads the whole song; with it those are disk reads. It also shrinks the
 * number of requests YouTube sees from this device, which is not nothing given how
 * readily the catalogue bot-gates a client.
 *
 * Exactly one instance per process, because `SimpleCache` refuses a second one on
 * the same directory — the singleton is what keeps the service's `onCreate` from
 * discovering that the hard way after a restart.
 *
 * 512 MB is roughly forty hours of 128 kbps audio: far more than anyone replays in
 * a session, small enough that it never competes with the rest of the phone.
 */
object StreamDiskCache {

    private const val TAG = "StreamDiskCache"

    /** LRU ceiling; the oldest bytes make room for the newest. */
    private const val CACHE_SIZE_BYTES = 512L * 1024L * 1024L

    private const val DIRECTORY = "stream_cache"

    @Volatile
    private var cache: SimpleCache? = null

    /** Returns the process-wide cache, creating it on first use — or null when the
     * cache directory is unusable (a lock left behind, a full disk). Null is a
     * normal value here, not an error: the resolving data source simply takes the
     * network path for every open, which is exactly how the app behaved before the
     * cache existed. */
    fun get(context: Context): Cache? {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val appContext = context.applicationContext
            val created = runCatching {
                SimpleCache(
                    appContext.getDir(DIRECTORY, Context.MODE_PRIVATE),
                    LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                    StandaloneDatabaseProvider(appContext),
                )
            }.getOrElse {
                // A lock left behind by a previous process, or a full disk. Playback
                // must not depend on the cache existing: with no cache the resolving
                // data source simply takes the network path for every open, which is
                // exactly how the app behaved before the cache existed.
                Log.w(TAG, "Stream disk cache unavailable; streaming without it", it)
                return null
            }
            cache = created
            return created
        }
    }
}
