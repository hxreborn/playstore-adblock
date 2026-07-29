package eu.hxreborn.gplayadblock

import org.yaml.snakeyaml.Yaml
import java.io.File

class CompatibilityMetadata(
    val releases: List<Map<*, *>>,
    val majorReleases: Map<String, Any?>,
) {
    companion object {
        private const val PATH_PROPERTY = "gplayadblock.compatibilityMetadata"

        fun load(): CompatibilityMetadata {
            val path = System.getProperty(PATH_PROPERTY) ?: error("$PATH_PROPERTY is not set")
            val file = File(path)
            check(file.isFile) { "no compatibility metadata at $path" }
            val document = Yaml().load<Map<String, Any?>>(file.readText())
            val releases = document["releases"] as? List<*> ?: error("$path has no releases list")
            val majors = document["major_releases"] as? Map<*, *> ?: emptyMap<String, Any?>()
            return CompatibilityMetadata(
                releases = releases.map { release -> release as Map<*, *> },
                majorReleases =
                    majors.entries.associate { (key, value) -> key.toString() to value },
            )
        }
    }
}
