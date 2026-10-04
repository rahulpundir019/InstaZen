package com.example.instazen

object InstagramUrls {
    const val HOME = "https://www.instagram.com/"
    const val LOGIN = "https://www.instagram.com/accounts/login/"
    const val DM_INBOX = "https://www.instagram.com/direct/inbox/"

    fun isInstagramHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return normalized == "instagram.com" ||
            normalized.endsWith(".instagram.com")
    }

    fun isMetaLoginHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return normalized == "facebook.com" ||
            normalized.endsWith(".facebook.com")
    }

    fun isDirectPath(path: String): Boolean {
        return path.startsWith("/direct/")
    }

    fun isAuthPath(path: String): Boolean {
        return path.startsWith("/accounts/") ||
            path.startsWith("/challenge/") ||
            path.startsWith("/consent/") ||
            path.startsWith("/two_factor/")
    }
}
