package eu.hxreborn.gplayadblock.discovery

import java.io.File

fun main() {
    val unavailable = ReleaseFixtures.unavailableReason()
    check(unavailable == null) { "cannot record fingerprints: $unavailable" }

    val target =
        System.getProperty("gplayadblock.fingerprints")?.let(::File)
            ?: error("gplayadblock.fingerprints is not set")
    target.mkdirs()

    for (fixture in ReleaseFixtures.all()) {
        val file = File(target, "${fixture.release}.txt")
        val recorded = fixture.recordedFingerprint()?.trim()
        val actual = fixture.fingerprint().trim()
        val verdict =
            when (recorded) {
                null -> "new"
                actual -> "unchanged"
                else -> "CHANGED"
            }
        file.writeText("$actual\n")
        println("%-10s %s".format(verdict, fixture.release))
    }
    println("\nReview the diff before committing. A CHANGED line means the resolver now selects")
    println("a different member on a build that already worked.")
}
