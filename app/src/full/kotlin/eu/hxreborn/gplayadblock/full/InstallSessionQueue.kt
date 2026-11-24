package eu.hxreborn.gplayadblock.full

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import eu.hxreborn.gplayadblock.Logger
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal class InstallSessionQueue private constructor(
    private val application: Application,
    private val applicationRegistry: Field,
    private val registryProvider: Field,
    private val providerGet: Method,
    private val adminService: Field,
    private val filterDefault: Field,
    private val filterBuilder: Method,
    private val activeStates: Field,
    private val addStates: Method,
    private val buildFilter: Method,
    private val query: Method,
    private val taskPackage: Method,
    private val taskIsGroup: Method,
    private val taskState: Method,
    private val stateName: Method,
) {
    data class Snapshot(
        val packages: Set<String>,
        val hasRunningTask: Boolean,
    )

    data class Task(
        val packageName: String?,
        val state: String?,
        val grouped: Boolean,
    )

    fun snapshot(): Snapshot? =
        try {
            val registry = applicationRegistry.get(application)
            val provider = registryProvider.get(registry)
            val admin = providerGet.invoke(provider)
            val service = adminService.get(admin)
            val builder = filterBuilder.invoke(filterDefault.get(null))
            addStates.invoke(builder, activeStates.get(null))
            val filter = buildFilter.invoke(builder)
            val future = query.invoke(service, filter) as Future<*>
            val tasks = future.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS) as Iterable<*>
            val observed =
                tasks.map { task ->
                    val item = requireNotNull(task)
                    val grouped = taskIsGroup.invoke(item) as Boolean
                    if (grouped) {
                        Task(null, null, true)
                    } else {
                        Task(
                            taskPackage.invoke(item) as? String,
                            stateName.invoke(null, taskState.invoke(item)) as? String,
                            false,
                        )
                    }
                }
            summarize(observed)
        } catch (exception: Exception) {
            Logger.warn("install queue query failed", exception)
            null
        }

    fun isManaged(
        sessionId: Int,
        packageName: String,
    ): Boolean =
        try {
            val databaseFile =
                File(application.applicationInfo.dataDir, "databases/session_manager_datastore")
            SQLiteDatabase
                .openDatabase(
                    databaseFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database
                        .rawQuery(
                            "SELECT pk FROM session_context_id_table WHERE session_id = ?",
                            arrayOf(sessionId.toString()),
                        ).use { cursor ->
                            var found = false
                            while (cursor.moveToNext()) {
                                if (matchesSessionKey(cursor.getString(0), packageName)) {
                                    found = true
                                }
                            }
                            found
                        }
                }
        } catch (exception: Exception) {
            Logger.warn("install session lookup failed", exception)
            false
        }

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 2L
        private val IDLE_TASK_STATES = setOf("DOWNLOAD_PENDING", "SCHEDULED", "PAUSED")

        internal fun isRunningTask(state: String): Boolean = state !in IDLE_TASK_STATES

        internal fun summarize(tasks: List<Task>): Snapshot? {
            if (tasks.any { it.grouped || it.packageName.isNullOrBlank() || it.state == null }) {
                return null
            }
            return Snapshot(
                tasks.map { requireNotNull(it.packageName) }.toSet(),
                tasks.any { isRunningTask(requireNotNull(it.state)) },
            )
        }

        fun from(
            application: Application,
            classLoader: ClassLoader,
            targets: InstallSessionQueueTargets,
        ) = InstallSessionQueue(
            application,
            targets.applicationRegistry.resolve(classLoader),
            targets.registryProvider.resolve(classLoader),
            targets.providerGet.resolve(classLoader),
            targets.adminService.resolve(classLoader),
            targets.filterDefault.resolve(classLoader),
            targets.filterBuilder.resolve(classLoader),
            targets.activeStates.resolve(classLoader),
            targets.addStates.resolve(classLoader),
            targets.buildFilter.resolve(classLoader),
            targets.query.resolve(classLoader),
            targets.taskPackage.resolve(classLoader),
            targets.taskIsGroup.resolve(classLoader),
            targets.taskState.resolve(classLoader),
            targets.stateName.resolve(classLoader),
        )

        internal fun matchesSessionKey(
            key: String,
            packageName: String,
        ): Boolean {
            val suffix =
                when {
                    key.startsWith("${packageName}_") -> {
                        key.removePrefix("${packageName}_")
                    }

                    key.startsWith("parent_${packageName}_") -> {
                        key.removePrefix("parent_${packageName}_")
                    }

                    else -> {
                        return false
                    }
                }
            return suffix.isNotEmpty() && suffix.all(Char::isDigit)
        }
    }
}
