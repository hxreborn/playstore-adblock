package eu.hxreborn.gplayadblock.settings.contributors

import org.json.JSONArray

const val DIFF_BLOCK_COUNT = 5

data class ContributorEntry(
    val login: String,
    val added: Int,
    val removed: Int,
    val url: String,
    val avatar: String,
    val owner: Boolean,
)

data class DiffBlocks(
    val added: Int,
    val removed: Int,
)

fun parseContributorEntries(json: String): List<ContributorEntry> {
    val array = JSONArray(json)
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        ContributorEntry(
            login = item.getString("login"),
            added = item.getInt("added"),
            removed = item.getInt("removed"),
            url = item.getString("url"),
            avatar = item.getString("avatar"),
            owner = item.optBoolean("owner", false),
        )
    }
}

fun diffBlocks(
    added: Int,
    removed: Int,
): DiffBlocks {
    val total = added.toLong() + removed.toLong()
    if (total == 0L) return DiffBlocks(added = 0, removed = 0)
    return DiffBlocks(
        added = (DIFF_BLOCK_COUNT * added.toLong() / total).toInt(),
        removed = (DIFF_BLOCK_COUNT * removed.toLong() / total).toInt(),
    )
}
