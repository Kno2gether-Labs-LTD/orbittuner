package dev.avijit.orbittuner.net

/** The service address: a bare `hostname[:port]`, turned into a base URL. */
object ServiceHost {
    private val LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    private val HOST_RE = Regex("^$LABEL(?:\\.$LABEL)*(?::([0-9]{1,5}))?$")
    private val LOCAL_RE = Regex("^(?:10\\.0\\.2\\.2|localhost):[0-9]{1,5}$", RegexOption.IGNORE_CASE)
    private const val OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
    /** Private IPv4 (10/8, 172.16/12, 192.168/16) with an optional port: a dev server on the LAN. */
    private val PRIVATE_V4_RE = Regex(
        "^(?:10\\.$OCTET|172\\.(?:1[6-9]|2[0-9]|3[01])|192\\.168)\\.$OCTET\\.$OCTET(?::[0-9]{1,5})?$",
    )

    /** True for a bare `hostname[:port]` (no scheme, userinfo, path, query or fragment). */
    fun isValid(host: String): Boolean {
        val m = HOST_RE.matchEntire(host) ?: return false
        val port = m.groupValues[1]
        return port.isEmpty() || port.toInt() in 1..65535
    }

    /** Base URL for a host: https, except debug builds talking to the emulator host, localhost or a private LAN IPv4. */
    fun baseUrl(host: String, debug: Boolean): String? {
        if (!isValid(host)) return null
        val scheme = if (debug && (LOCAL_RE.matches(host) || PRIVATE_V4_RE.matches(host))) "http" else "https"
        return "$scheme://$host"
    }
}
