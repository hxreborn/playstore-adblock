package eu.hxreborn.gplayadblock.settings.prefs

object SearchSortAvailability {
    fun supports(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp in 1 until 600
}
