package eu.hxreborn.gplayadblock.full

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal object StreamCacheCleaner {
    fun clear(directory: File) {
        val path = directory.toPath()
        if (Files.isSymbolicLink(path)) throw IOException("$directory is a link")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        val root = directory.canonicalFile
        root.listFiles()?.forEach { entry -> delete(entry, root) }
            ?: throw IOException("cannot list $root")
    }

    private fun delete(
        entry: File,
        root: File,
    ) {
        val path = entry.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        val canonical = entry.canonicalFile
        if (!canonical.toPath().startsWith(root.toPath())) {
            throw IOException("$canonical is outside $root")
        }
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            entry.listFiles()?.forEach { child -> delete(child, root) }
                ?: throw IOException("cannot list $entry")
        }
        Files.deleteIfExists(path)
    }
}
