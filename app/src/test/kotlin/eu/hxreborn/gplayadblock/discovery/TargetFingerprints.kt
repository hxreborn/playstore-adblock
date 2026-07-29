package eu.hxreborn.gplayadblock.discovery

object TargetFingerprints {
    private const val EXCERPT = 60
    private val ROLE_BOUNDARY = charArrayOf(' ', '(', ',')

    fun firstDifference(
        recorded: String,
        actual: String,
    ): String? {
        val expected = recorded.trim()
        val resolved = actual.trim()
        if (expected == resolved) return null
        val shared = expected.commonPrefixWith(resolved).length
        val role = lastRole(expected.take(shared))
        return buildString {
            append(role?.let { name -> "$name " }.orEmpty())
            append(
                "recorded ${excerpt(expected, shared)} but resolved ${excerpt(resolved, shared)}",
            )
        }
    }

    private fun lastRole(prefix: String): String? {
        var depth = 0
        var role: String? = null
        prefix.forEachIndexed { index, character ->
            when (character) {
                '(', '[' -> {
                    depth++
                }

                ')', ']' -> {
                    depth--
                }

                '=' -> {
                    if (depth == 1) {
                        val start = prefix.lastIndexOfAny(ROLE_BOUNDARY, index) + 1
                        role = prefix.substring(start, index)
                    }
                }
            }
        }
        return role
    }

    private fun excerpt(
        fingerprint: String,
        from: Int,
    ): String {
        val text = fingerprint.substring(from.coerceAtMost(fingerprint.length))
        return if (text.length <= EXCERPT) "'$text'" else "'${text.take(EXCERPT)}...'"
    }
}
