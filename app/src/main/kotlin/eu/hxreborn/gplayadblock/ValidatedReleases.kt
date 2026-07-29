package eu.hxreborn.gplayadblock

object ValidatedReleases {
    val keys =
        setOf(
            843918L,
            844920L,
            845919L,
            846920L,
            847013L,
            848523L,
            849919L,
            850133L,
            852225L,
            852332L,
            852441L,
        )

    fun releaseKey(versionCode: Long): Long = versionCode / 100

    fun accepts(versionCode: Long): Boolean = releaseKey(versionCode) in keys
}
