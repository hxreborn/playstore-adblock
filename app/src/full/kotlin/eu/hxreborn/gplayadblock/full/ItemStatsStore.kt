package eu.hxreborn.gplayadblock.full

import android.util.AtomicFile
import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.hook.SearchSortInstallation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ItemStatsStore(
    private val cacheFile: File,
    private val index: ItemStatsIndex,
    private val hooks: SearchSortInstallation,
) {
    private val file = AtomicFile(cacheFile)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val restored = RestoreSignal()
    private var generation = 0L
    private var scheduled = false
    private var cacheRestored = false

    fun start() {
        executor.execute(
            hooks.guarded {
                try {
                    val cached =
                        readStatsCache(cacheFile, ::readSnapshot) { error ->
                            Logger.error("item statistics cache read failed", error)
                            scheduleWrite()
                        }
                    index.restore(cached)
                    enableWrites()
                } finally {
                    restored.complete()
                }
            },
        )
    }

    private fun readSnapshot(): Map<String, ItemStats> {
        val rows = JSONArray(file.readFully().toString(Charsets.UTF_8))
        return buildMap {
            for (position in 0 until rows.length()) {
                val row = rows.getJSONObject(position)
                val rating = row.optDouble("rating", Double.NaN)
                val downloads = row.optLong("downloads", 0)
                val count = row.optLong("ratingCount", 0)
                val stats =
                    ItemStats(
                        rating = rating.takeIf { it > 0 && it <= 5 }?.toFloat(),
                        downloads = downloads.takeIf { it > 0 },
                        ratingCount = count.takeIf { it > 0 },
                    )
                put(row.getString("id"), stats)
            }
        }
    }

    fun stop() {
        executor.shutdownNow()
    }

    fun onRestored(action: () -> Unit) = restored.listen(action)

    @Synchronized
    fun scheduleWrite() {
        generation++
        queueWrite()
    }

    @Synchronized
    private fun enableWrites() {
        cacheRestored = true
        if (generation != 0L) queueWrite()
    }

    private fun queueWrite() {
        if (!cacheRestored || scheduled) return
        scheduled = true
        executor.execute(hooks.guarded { writeChanges() })
    }

    private fun writeChanges(
        retryGeneration: Long? = null,
        previousRetries: Int = 0,
    ) {
        val current = synchronized(this) { generation }
        val retries = if (current == retryGeneration) previousRetries else 0
        val written =
            try {
                writeSnapshot()
                true
            } catch (exception: Exception) {
                Logger.error("item statistics cache write failed", exception)
                false
            }
        synchronized(this) {
            when {
                current != generation -> {
                    executor.execute(hooks.guarded { writeChanges() })
                }

                !written && retries < WRITE_RETRIES -> {
                    executor.schedule(
                        hooks.guarded { writeChanges(current, retries + 1) },
                        500L shl retries,
                        TimeUnit.MILLISECONDS,
                    )
                }

                else -> {
                    scheduled = false
                }
            }
        }
    }

    private fun writeSnapshot() {
        val rows = JSONArray()
        index.snapshot().forEach { (id, stats) ->
            rows.put(
                JSONObject()
                    .put("id", id)
                    .putOpt("rating", stats.rating)
                    .putOpt("downloads", stats.downloads)
                    .putOpt("ratingCount", stats.ratingCount),
            )
        }
        replaceStatsCache(cacheFile, rows.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val WRITE_RETRIES = 3
    }
}

internal class RestoreSignal {
    private var completed = false
    private val listeners = mutableListOf<() -> Unit>()

    fun listen(action: () -> Unit): () -> Unit {
        val runNow =
            synchronized(this) {
                if (completed) {
                    true
                } else {
                    listeners.add(action)
                    false
                }
            }
        if (runNow) action()
        return { synchronized(this) { listeners.remove(action) } }
    }

    fun complete() {
        val actions =
            synchronized(this) {
                completed = true
                listeners.toList().also { listeners.clear() }
            }
        actions.forEach { it() }
    }
}

internal fun readStatsCache(
    file: File,
    read: () -> Map<String, ItemStats>,
    recover: (Exception) -> Unit,
): Map<String, ItemStats> =
    try {
        read()
    } catch (exception: FileNotFoundException) {
        if (!Files.notExists(file.toPath())) recover(exception)
        emptyMap()
    } catch (exception: IOException) {
        recover(exception)
        emptyMap()
    } catch (exception: JSONException) {
        recover(exception)
        emptyMap()
    } catch (exception: SecurityException) {
        recover(exception)
        emptyMap()
    }

internal fun replaceStatsCache(
    destination: File,
    bytes: ByteArray,
    move: (File, File) -> Unit = { from, to ->
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
) {
    val staged = File.createTempFile(destination.name, ".tmp", destination.parentFile)
    try {
        FileOutputStream(staged).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        move(staged, destination)
    } finally {
        staged.delete()
    }
}
