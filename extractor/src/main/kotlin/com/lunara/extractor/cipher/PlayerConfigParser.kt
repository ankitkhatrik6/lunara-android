package com.lunara.extractor.cipher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The per-player facts the cipher needs, and the reader for the table that carries them.
 *
 * Working out YouTube's signature means running its own JavaScript, and the table says
 * *how* to call it for a given player build: which function deciphers a signature, which
 * class transforms the throttle parameter, and the `signatureTimestamp` the player was
 * stamped with. Every value read here ends up evaluated as JavaScript inside the cipher
 * WebView, so entries are regex-locked to shapes that cannot carry arbitrary code —
 * `sig` must be a single `NAME(int,int,INPUT)` call and `nClass` a bare identifier.
 *
 * Pure JVM on purpose: no Android imports, so the validation surface is coverable by
 * plain unit tests. Fetching and caching live in [PlayerConfigStore].
 */
object PlayerConfigParser {

    const val SUPPORTED_SCHEMA_VERSION = 1

    private val SIG_RE = Regex("""^[A-Za-z0-9${'$'}_]{1,8}\(\d+,\d+,INPUT\)$""")
    private val NCLASS_RE = Regex("""^[A-Za-z0-9${'$'}_]{1,8}$""")
    private val HASH_RE = Regex("""^[a-f0-9]{8}$""")

    /** One player build's cipher recipe. */
    data class PlayerConfig(
        /** `Qn(65,5918,INPUT)` — substituted with the obfuscated signature at call time. */
        val sigExpression: String,
        /** The n-transform class, used as `new g.<nClass>(url, true).get('n')`. */
        val nClass: String,
        /** The `sts` this player expects in playback requests. */
        val signatureTimestamp: Int,
    )

    sealed class ParseResult {
        data class Success(
            val configs: Map<String, PlayerConfig>,
            val skippedEntries: List<String> = emptyList(),
        ) : ParseResult()
        data class Failure(val reason: String) : ParseResult()
    }

    /** Builds the n-transform snippet; the URL shape is fixed here, never taken from the file. */
    fun buildNJsExpression(nClass: String): String =
        "(function(n){try{var u=new g.$nClass('https://x.googlevideo.com/videoplayback?n='+n,true);" +
            "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)"

    /**
     * Parses [jsonText] into a hash→config table (aliases included as extra keys).
     *
     * File-level problems (malformed JSON, missing/unsupported `schemaVersion`, missing
     * `players`) fail the whole file so callers keep their previous table. Invalid
     * individual entries are skipped rather than trusted.
     */
    fun parse(jsonText: String): ParseResult {
        val root = try {
            Json.parseToJsonElement(jsonText) as? JsonObject
                ?: return ParseResult.Failure("root is not a JSON object")
        } catch (e: Exception) {
            return ParseResult.Failure("malformed JSON: ${e.message}")
        }

        val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: return ParseResult.Failure("schemaVersion missing or not an int")
        if (schemaVersion <= 0) return ParseResult.Failure("schemaVersion must be positive")
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return ParseResult.Failure("unsupported schemaVersion $schemaVersion")
        }

        val players = root["players"] as? JsonObject
            ?: return ParseResult.Failure("players missing or not an object")

        val configs = mutableMapOf<String, PlayerConfig>()
        val skipped = mutableListOf<String>()
        for ((hash, entryElement) in players) {
            if (!HASH_RE.matches(hash)) {
                skipped += hash
                continue
            }
            val entry = parseEntry(entryElement as? JsonObject) ?: run {
                skipped += hash
                continue
            }
            val aliases = entry.second
            val keys = listOf(hash) + aliases
            // A duplicate key makes the table ambiguous — which entry wins would depend
            // on iteration order, so the whole file is rejected.
            val duplicate = keys.firstOrNull { it in configs }
                ?: keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
            if (duplicate != null) {
                return ParseResult.Failure("duplicate hash/alias '$duplicate' (entry $hash)")
            }
            configs[hash] = entry.first
            for (alias in aliases) configs[alias] = entry.first
        }

        return ParseResult.Success(configs, skipped)
    }

    /** Returns the config and its aliases, or null when the entry is malformed. */
    private fun parseEntry(obj: JsonObject?): Pair<PlayerConfig, List<String>>? {
        if (obj == null) return null
        val sig = (obj["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!SIG_RE.matches(sig)) return null
        val nClass = (obj["nClass"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!NCLASS_RE.matches(nClass)) return null
        val sts = (obj["sts"] as? JsonPrimitive)?.takeIf { !it.isString }
            ?.content?.toIntOrNull()?.takeIf { it > 0 } ?: return null

        val aliases = when (val element = obj["aliases"]) {
            null -> emptyList()
            else -> {
                val array = try {
                    element.jsonArray
                } catch (e: Exception) {
                    return null
                }
                array.map { alias ->
                    val value = (alias as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    if (!HASH_RE.matches(value)) return null
                    value
                }
            }
        }

        return PlayerConfig(sig, nClass, sts) to aliases
    }

    /** Overlay [remote] onto [bundled]: remote wins per key; bundled-only keys survive. */
    fun merge(
        bundled: Map<String, PlayerConfig>,
        remote: Map<String, PlayerConfig>,
    ): Map<String, PlayerConfig> = bundled + remote
}
