package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException

internal class SearchSortInstallation(
    private val reportRollbackFailure: (Throwable) -> Unit,
) {
    @Volatile private var active = false

    @Volatile var activated = false
        private set
    private var release: (() -> Unit)? = null
    private val undo = mutableListOf<() -> Unit>()

    @Synchronized
    fun install(
        startAfterActivation: () -> Unit = {},
        register: () -> Boolean,
    ): Boolean {
        try {
            activated = register()
            active = activated
            if (active) startAfterActivation()
            return active
        } catch (error: Throwable) {
            active = false
            activated = false
            throw error
        } finally {
            if (!active) {
                undo.asReversed().forEach { unhook ->
                    try {
                        unhook()
                    } catch (error: Throwable) {
                        report(error)
                    }
                }
            }
            undo.clear()
        }
    }

    fun onRollback(action: () -> Unit) {
        undo.add(action)
    }

    private fun report(error: Throwable) {
        runCatching { reportRollbackFailure(error) }
    }

    fun onDisable(action: () -> Unit) {
        release = action
    }

    fun disable(error: Throwable) {
        if (error is InvocationTargetException) {
            if (isActive()) runCatching { Logger.error("native search sort call failed", error) }
            return
        }
        val cleanup =
            synchronized(this) {
                if (!active) return
                active = false
                release.also { release = null }
            }
        report(error)
        runCatching { cleanup?.invoke() }.onFailure(::report)
    }

    fun run(action: () -> Unit) {
        if (!active) return
        try {
            action()
        } catch (error: Throwable) {
            disable(error)
        }
    }

    fun isActive(): Boolean = active

    fun guarded(
        shouldDisable: () -> Boolean = { true },
        action: () -> Unit,
    ): Runnable =
        Runnable {
            if (!isActive()) return@Runnable
            try {
                action()
            } catch (error: Throwable) {
                if (runCatching(shouldDisable).getOrDefault(false)) disable(error)
            }
        }

    fun <T> proceedRewrite(
        native: () -> T,
        rewritten: () -> T,
    ): T = if (active) rewritten() else native()

    fun <T> dispatch(
        native: () -> T,
        unchangedFailure: () -> Throwable? = { null },
        sort: (() -> Unit) -> T,
    ): T {
        if (!active) return native()
        var proceeded = false
        return try {
            sort { proceeded = true }
        } catch (error: Throwable) {
            if (error === unchangedFailure()) throw error
            disable(error)
            if (proceeded) throw error
            native()
        }
    }

    fun <T> intercept(
        proceed: () -> T,
        intercept: () -> T,
    ): T = if (active) intercept() else proceed()
}

internal fun SearchSortInstallation.hook(
    module: XposedModule,
    executable: Executable,
    fallback: (XposedInterface.Chain) -> Any? = { it.proceed() },
    hooker: XposedInterface.Hooker,
) {
    val parameterTypes = executable.parameterTypes
    val handle =
        module
            .hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                var nativeFailure: Throwable? = null
                val native =
                    object : XposedInterface.Chain by chain {
                        override fun proceed(): Any? =
                            try {
                                chain.proceed()
                            } catch (error: Throwable) {
                                nativeFailure = error
                                throw error
                            }
                    }
                dispatch({
                    if (activated) fallback(native) else native.proceed()
                }, { nativeFailure }) { proceeded ->
                    val tracked =
                        object : XposedInterface.Chain by chain {
                            override fun proceed(): Any? {
                                proceeded()
                                return fallback(native)
                            }

                            override fun proceed(args: Array<Any?>): Any? {
                                require(args.size == parameterTypes.size)
                                parameterTypes.forEachIndexed { index, type ->
                                    if (!type.isPrimitive &&
                                        args[index] != null
                                    ) {
                                        require(type.isInstance(args[index]))
                                    }
                                }
                                proceeded()
                                return proceedRewrite({ fallback(native) }) { chain.proceed(args) }
                            }
                        }
                    hooker.intercept(tracked)
                }
            }
    onRollback(handle::unhook)
}
