package com.ali.downloader.core

/**
 * دسته‌بندی خطاهای yt-dlp/FFmpeg/aria2c به کدهای ثابت. متن فارسی هر کد (با راه‌حل) در لایهٔ رابط کاربری است.
 */
object ErrorClassifier {
    const val DRM = "DRM"
    const val NETWORK = "NETWORK"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val FORBIDDEN = "FORBIDDEN"
    const val UNSUPPORTED_URL = "UNSUPPORTED_URL"
    const val LOGIN_REQUIRED = "LOGIN_REQUIRED"
    const val GEO_BLOCKED = "GEO_BLOCKED"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val NO_SPACE = "NO_SPACE"
    const val FORMAT_UNAVAILABLE = "FORMAT_UNAVAILABLE"
    const val ENGINE_OUTDATED = "ENGINE_OUTDATED"
    const val SUBTITLES = "SUBTITLES"
    const val ARIA2C = "ARIA2C"
    const val MERGE_FAILED = "MERGE_FAILED"
    const val UPCOMING_LIVE = "UPCOMING_LIVE"
    const val STALLED = "STALLED"
    const val STORAGE_PERMISSION = "STORAGE_PERMISSION"
    const val PUBLISH_FAILED = "PUBLISH_FAILED"
    const val UNKNOWN = "UNKNOWN"

    data class Classified(val code: String, val retryable: Boolean, val detail: String)

    private val rules: List<Triple<String, Boolean, List<String>>> = listOf(
        Triple(DRM, false, listOf("drm protected", "drm-protected", "is drm", "widevine", "playready")),
        Triple(NO_SPACE, false, listOf("no space left on device", "[errno 28]", "enospc")),
        Triple(SUBTITLES, true, listOf("unable to download video subtitles", "unable to download subtitles")),
        Triple(ARIA2C, true, listOf("aria2c exited with code", "libaria2c")),
        Triple(MERGE_FAILED, true, listOf("ffmpeg exited with code", "postprocessing:", "conversion failed", "error merging", "could not write header")),
        Triple(UPCOMING_LIVE, false, listOf("live event will begin", "premieres in", "this live event", "is not live yet")),
        Triple(GEO_BLOCKED, false, listOf("available in your country", "geo restrict", "geo-restrict", "geoblock", "not available from your location")),
        Triple(LOGIN_REQUIRED, false, listOf(
            "private video", "sign in to confirm", "login required", "log in to", "--cookies",
            "only available for registered users", "members-only", "join this channel", "age-restricted",
            "requires authentication", "this account is private", "login_required",
        )),
        Triple(UNAVAILABLE, false, listOf(
            "video unavailable", "has been removed", "http error 404", "does not exist", "no video formats found",
            "this content isn't available", "this post is unavailable", "account has been terminated",
        )),
        Triple(UNSUPPORTED_URL, false, listOf("unsupported url", "is not a valid url", "invalid url")),
        Triple(FORMAT_UNAVAILABLE, false, listOf("requested format is not available", "format is not available")),
        Triple(RATE_LIMITED, true, listOf("http error 429", "too many requests", "rate-limit", "rate limit")),
        Triple(FORBIDDEN, true, listOf("http error 403", "forbidden")),
        Triple(NETWORK, true, listOf(
            "unable to download webpage", "urlopen error", "connection reset", "timed out", "timeout",
            "temporary failure in name resolution", "network is unreachable", "no address associated",
            "getaddrinfo", "connection refused", "connection aborted", "remote end closed", "incompleteread",
            "ssl:", "[errno 101]", "[errno 7]", "[errno -2]", "[errno -3]", "[errno 104]", "[errno 110]",
            "failed to resolve", "unable to connect", "connection broken", "giving up after",
        )),
        Triple(ENGINE_OUTDATED, false, listOf(
            "unable to extract", "please report this issue", "nsig extraction failed",
            "signature extraction failed", "extractorerror", "unable to parse", "keyerror", "typeerror",
        )),
    )

    fun classify(stderr: String?): Classified {
        val text = stderr.orEmpty()
        val lower = text.lowercase()
        val detail = lastErrorLine(text)
        for ((code, retry, needles) in rules) {
            if (needles.any { lower.contains(it) }) return Classified(code, retry, detail)
        }
        return Classified(UNKNOWN, true, detail)
    }

    /** آخرین خط ERROR برای نمایش در جزئیات؛ اگر نبود، آخرین خط غیرخالی. */
    fun lastErrorLine(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull().orEmpty()
        return if (err.length > 600) err.take(600) else err
    }

    fun isNetworkRelated(code: String) = code == NETWORK || code == STALLED
}
