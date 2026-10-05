package com.lunara.music.service.innertube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Pure-Kotlin player.js cipher (Blazify CipherDeobfuscator equivalent). */
object PlayerCipher {
    private const val TAG = "PlayerCipher"
    private const val IFRAME_API = "https://www.youtube.com/iframe_api"
    private const val PLAYER_URL_TPL =
        "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var cacheDir: File? = null
    @Volatile private var cachedSts: Int? = null
    @Volatile private var cachedOps: List<DecipherOp>? = null

    fun initCache(dir: File) {
        if (cacheDir == null) {
            cacheDir = File(dir, "cipher_cache").apply { mkdirs() }
        }
    }

    suspend fun signatureTimestamp(): Int? = withContext(Dispatchers.IO) {
        cachedSts?.let { return@withContext it }
        val js = getPlayerJs()?.first ?: return@withContext null
        extractSts(js)?.also { cachedSts = it }
    }

    sealed interface DecipherOp {
        data object Reverse : DecipherOp
        data class Splice(val n: Int) : DecipherOp
        data class Swap(val n: Int) : DecipherOp
        data class Slice(val n: Int) : DecipherOp
    }

    suspend fun decipherSignatureCipher(cipher: String): String? = withContext(Dispatchers.IO) {
        try {
            val params = cipher.split("&").associate {
                val i = it.indexOf('=')
                if (i > 0) android.net.Uri.decode(it.substring(0, i)) to
                    android.net.Uri.decode(it.substring(i + 1)) else "" to ""
            }
            val baseUrl = params["url"] ?: return@withContext null
            val ciphered = params["s"] ?: return@withContext baseUrl
            val sigParam = params["sp"] ?: "sig"
            val ops = decipherOps() ?: return@withContext null
            val sig = applyOps(ciphered, ops)
            val sep = if (baseUrl.contains("?")) "&" else "?"
            "$baseUrl$sep$sigParam=$sig"
        } catch (e: Exception) {
            Log.w(TAG, "decipher failed: ${e.message}")
            null
        }
    }

    private suspend fun decipherOps(): List<DecipherOp>? {
        cachedOps?.let { return it }
        val js = getPlayerJs()?.first ?: return null
        val ops = extractOps(js)
        if (ops != null) cachedOps = ops
        return ops
    }

    private fun getPlayerJs(): Pair<String, String>? {
        cacheDir?.let { dir ->
            try {
                val hashFile = File(dir, "current_hash.txt")
                if (hashFile.exists()) {
                    val lines = hashFile.readText().split("\n")
                    val hash = lines.getOrNull(0).orEmpty()
                    val ts = lines.getOrNull(1)?.toLongOrNull() ?: 0L
                    if (hash.isNotBlank() && System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                        val f = File(dir, "player_$hash.js")
                        if (f.exists() && f.length() > 100_000) return f.readText() to hash
                    }
                }
            } catch (_: Exception) { }
        }
        val hash = fetchHash() ?: return null
        val js = downloadJs(hash) ?: return null
        cacheDir?.let { dir ->
            try {
                File(dir, "player_$hash.js").writeText(js)
                File(dir, "current_hash.txt").writeText("$hash\n${System.currentTimeMillis()}")
            } catch (_: Exception) { }
        }
        return js to hash
    }

    private fun fetchHash(): String? {
        val body = runCatching {
            http.newCall(Request.Builder().url(IFRAME_API)
                .header("User-Agent", InnerTubeClients.USER_AGENT_WEB).build())
                .execute().use { it.body?.string() }
        }.getOrNull() ?: return null
        return Regex("""/s/player/([a-zA-Z0-9_-]+)/""").find(body ?: return null)
            ?.groupValues?.get(1)
    }

    private fun downloadJs(hash: String): String? = runCatching {
        http.newCall(Request.Builder().url(PLAYER_URL_TPL.format(hash))
            .header("User-Agent", InnerTubeClients.USER_AGENT_WEB).build())
            .execute().use { r -> if (!r.isSuccessful) null else r.body?.string() }
    }.getOrNull()

    private fun extractSts(js: String): Int? {
        Regex("""signatureTimestamp['":\s]+(\d+)""").find(js)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { return it }
        return Regex("""sts['":\s]+(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun applyOps(sig: String, ops: List<DecipherOp>): String {
        val arr = sig.toMutableList()
        for (op in ops) {
            when (op) {
                is DecipherOp.Reverse -> arr.reverse()
                is DecipherOp.Splice -> repeat(op.n.coerceAtMost(arr.size)) { if (arr.isNotEmpty()) arr.removeAt(0) }
                is DecipherOp.Slice -> repeat(op.n.coerceAtMost(arr.size)) { if (arr.isNotEmpty()) arr.removeAt(0) }
                is DecipherOp.Swap -> {
                    if (arr.isNotEmpty()) {
                        val i = op.n % arr.size
                        val tmp = arr[0]; arr[0] = arr[i]; arr[i] = tmp
                    }
                }
            }
        }
        return arr.joinToString("")
    }

    private fun extractOps(js: String): List<DecipherOp>? {
        val splitIdx = js.indexOf("split(\"\")")
        if (splitIdx == -1) return null
        val body = js.substring((splitIdx - 1500).coerceAtLeast(0), (splitIdx + 2500).coerceAtMost(js.length))
        parseHelperObject(js, body)?.takeIf { it.isNotEmpty() }?.let { return it }
        val ops = mutableListOf<DecipherOp>()
        for (m in Regex("""\.\s*(reverse|splice|slice)\s*\(\s*([^)]*)\)""").findAll(body)) {
            when (m.groupValues[1]) {
                "reverse" -> ops.add(DecipherOp.Reverse)
                "splice" -> {
                    val n = Regex("""\d+""").find(m.groupValues[2])?.value?.toIntOrNull() ?: 1
                    ops.add(DecipherOp.Splice(if (n == 0) 1 else n))
                }
                "slice" -> ops.add(DecipherOp.Slice(Regex("""\d+""").find(m.groupValues[2])?.value?.toIntOrNull() ?: 1))
            }
        }
        return ops.takeIf { it.isNotEmpty() }
    }

    private fun parseHelperObject(js: String, callerBody: String): List<DecipherOp>? {
        val objName = Regex("""([a-zA-Z0-9_$]+)\.[a-zA-Z0-9_$]+\s*\(\s*[a-zA-Z]+\s*(?:,\s*\d+)?\s*\)""")
            .find(callerBody)?.groupValues?.get(1) ?: return null
        val objDef = Regex("""var\s+${Regex.escape(objName)}\s*=\s*\{(.{0,3000}?)\};""")
            .find(js)?.groupValues?.get(1) ?: return null
        val methodOp = mutableMapOf<String, DecipherOp>()
        for (entry in objDef.split("},")) {
            val name = Regex("""([a-zA-Z0-9_$]+)\s*:\s*function""").find(entry)?.groupValues?.get(1) ?: continue
            val op: DecipherOp = when {
                entry.contains(".reverse(") -> DecipherOp.Reverse
                entry.contains(".splice(") -> DecipherOp.Splice(1)
                entry.contains(".slice(") -> DecipherOp.Slice(1)
                entry.contains("%") -> DecipherOp.Swap(1)
                else -> continue
            }
            methodOp[name] = op
        }
        val ops = mutableListOf<DecipherOp>()
        val callRegex = Regex("""${Regex.escape(objName)}\.([a-zA-Z0-9_$]+)\s*\(\s*[a-zA-Z]+\s*(?:,\s*(\d+))?\s*\)""")
        for (m in callRegex.findAll(callerBody)) {
            val base = methodOp[m.groupValues[1]] ?: continue
            val n = m.groupValues[2].toIntOrNull()
            ops.add(when (base) {
                is DecipherOp.Swap -> DecipherOp.Swap(n ?: 1)
                is DecipherOp.Splice -> if (n != null && m.value.contains(",")) DecipherOp.Splice(n) else base
                is DecipherOp.Slice -> if (n != null) DecipherOp.Slice(n) else base
                else -> base
            })
        }
        return ops.takeIf { it.isNotEmpty() }
    }
}
