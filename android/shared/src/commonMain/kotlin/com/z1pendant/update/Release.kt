package com.z1pendant.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One file attached to a GitHub release. [sha256] is GitHub's own hash of it (lower-case hex). */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** A published release of the app, from GitHub's "latest release" API. */
data class Release(
    val tag: String,
    /** [tag] without the leading "v": "0.1.12". */
    val version: String,
    val title: String,
    /** The release notes (GitHub Markdown, as written). */
    val notes: String,
    /** The release's web page. */
    val page: String,
    val apk: ReleaseAsset?,
    val ipa: ReleaseAsset?,
) {
    companion object {
        /** Public API, no sign-in: 60 requests an hour per IP, and the app asks at most every few hours. */
        const val LATEST_URL = "https://api.github.com/repos/jimmyvegas29/makera-z1-pendant/releases/latest"

        private val json = Json { ignoreUnknownKeys = true }

        /** GitHub's release JSON → [Release]; null if it isn't one (no tag, or a draft/pre-release). */
        fun parse(text: String): Release? {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            fun JsonObject.bool(k: String) = str(k) == "true"
            val tag = o.str("tag_name")?.takeIf { it.isNotBlank() } ?: return null
            if (o.bool("draft") || o.bool("prerelease")) return null
            val assets = runCatching { o["assets"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                val name = a.str("name") ?: return@mapNotNull null
                val url = a.str("browser_download_url") ?: return@mapNotNull null
                ReleaseAsset(name, url, a["size"]?.jsonPrimitive?.longOrNull ?: -1,
                    a.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase())
            }
            return Release(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                title = o.str("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = o.str("body").orEmpty(),
                page = o.str("html_url") ?: "https://github.com/jimmyvegas29/makera-z1-pendant/releases/latest",
                apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) },
                ipa = assets.firstOrNull { it.name.endsWith(".ipa", ignoreCase = true) },
            )
        }

        /**
         * Compares dotted versions number by number ("0.1.10" > "0.1.9"); missing parts count as 0.
         * A suffix after "-" ("0.2.0-test") sorts before the plain version.
         */
        fun compareVersions(a: String, b: String): Int {
            fun split(v: String): Pair<List<Int>, String?> {
                val s = v.trim().removePrefix("v").removePrefix("V")
                val core = s.substringBefore('-')
                val suffix = s.substringAfter('-', "").ifEmpty { null }
                return core.split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } to suffix
            }
            val (pa, sa) = split(a)
            val (pb, sb) = split(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return when {
                sa == sb -> 0
                sa == null -> 1
                sb == null -> -1
                else -> sa.compareTo(sb)
            }
        }

        /**
         * The notes as plain text for a dialog: Markdown emphasis and code marks dropped, links shown
         * as their text, headings and bullets kept readable.
         */
        fun plainNotes(md: String): String = md
            .replace("\r\n", "\n")
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace("**", "").replace("__", "").replace("`", "")
            .lines().joinToString("\n") { l ->
                val t = l.trimStart()
                when {
                    t.startsWith("#") -> t.trimStart('#').trim()
                    t.startsWith("- ") || t.startsWith("* ") -> l.substring(0, l.length - t.length) + "• " + t.drop(2)
                    else -> l
                }
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
