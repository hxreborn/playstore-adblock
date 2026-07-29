package eu.hxreborn.gplayadblock.discovery

import java.io.File

class ReleaseFixture(
    val release: String,
    private val apk: File,
) {
    fun resolveTargets(): ResolvedTargets = DexKitResolver.resolve(listOf(apk.absolutePath))

    fun fingerprint(): String =
        when (val targets = resolveTargets()) {
            is ResolvedTargets.Missing -> {
                "MISSING\n${targets.reason.replace(apk.absolutePath, "<apk>")}"
            }

            is ResolvedTargets.Resolved -> {
                targets.toString()
            }
        }

    fun recordedFingerprint(): String? =
        javaClass
            .getResourceAsStream("$FINGERPRINT_RESOURCES/$release.txt")
            ?.bufferedReader()
            ?.use { reader -> reader.readText() }

    override fun toString(): String = release

    private companion object {
        const val FINGERPRINT_RESOURCES = "/fingerprints"
    }
}

object ReleaseFixtures {
    private const val ROOT_PROPERTY = "gplayadblock.releaseFixtures"
    private const val DIRECTORY_PREFIX = "com.android.vending-"
    private const val HOST_RESOLVER_LIBRARY = "libdexkit.so"

    fun all(): List<ReleaseFixture> {
        val root = System.getProperty(ROOT_PROPERTY)?.let(::File) ?: return emptyList()
        return root
            .listFiles { file -> file.isDirectory && file.name.startsWith(DIRECTORY_PREFIX) }
            .orEmpty()
            .map { directory ->
                directory.name.removePrefix(DIRECTORY_PREFIX) to File(directory, "base.apk")
            }.filter { (_, apk) -> apk.isFile }
            .map { (release, apk) -> ReleaseFixture(release, apk) }
            .sortedBy(ReleaseFixture::release)
    }

    fun unavailableReason(): String? {
        val root = System.getProperty(ROOT_PROPERTY) ?: return "$ROOT_PROPERTY is not set"
        return when {
            all().isEmpty() -> {
                "no release fixtures under $root"
            }

            !hostResolverLibraryPresent() -> {
                "$HOST_RESOLVER_LIBRARY is not on java.library.path"
            }

            else -> {
                null
            }
        }
    }

    private fun hostResolverLibraryPresent(): Boolean =
        System
            .getProperty("java.library.path")
            .orEmpty()
            .split(File.pathSeparator)
            .any { entry -> File(entry, HOST_RESOLVER_LIBRARY).isFile }
}
