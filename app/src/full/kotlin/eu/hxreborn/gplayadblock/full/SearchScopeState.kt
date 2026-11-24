package eu.hxreborn.gplayadblock.full

import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.CancellationException

internal class SearchScopeState {
    private data class Binding(
        val scope: WeakReference<Any>,
        var job: WeakReference<Any>? = null,
        var cancellation: Method? = null,
    )

    private val bindings = WeakHashMap<Any, Binding>()
    private var scopeMethod: Method? = null
    private var foldMethod: Method? = null
    private var cancellationMethod: Method? = null

    fun configure(
        scopeType: Class<*>,
        cancellation: Method?,
    ): Boolean {
        if (cancellation == null) return false
        val context =
            scopeType.methods.singleOrNull { method ->
                method.parameterCount == 0 && method.returnType.isInterface &&
                    method.returnType.methods.any { it.name == "fold" && it.parameterCount == 2 }
            } ?: return false
        val fold =
            context.returnType.methods.singleOrNull { it.name == "fold" && it.parameterCount == 2 }
                ?: return false
        scopeMethod = context
        foldMethod = fold
        cancellationMethod = cancellation
        return true
    }

    @Synchronized
    fun bind(
        owner: Any,
        scope: Any,
    ) {
        bindings[owner] = Binding(WeakReference(scope))
    }

    fun resolveJob(owner: Any): Boolean {
        val binding = synchronized(this) { bindings[owner] } ?: return false
        val scope = binding.scope.get() ?: return false
        val pair = findJob(scope) ?: return false
        synchronized(this) {
            if (bindings[owner] !== binding) return false
            binding.job = WeakReference(pair.first)
            binding.cancellation = pair.second
        }
        return true
    }

    @Synchronized
    fun token(owner: Any): Any? = bindings[owner]

    @Synchronized
    fun isCurrent(
        owner: Any,
        token: Any?,
    ): Boolean = token != null && bindings[owner] === token && isActive(owner)

    @Synchronized
    fun isActive(owner: Any): Boolean {
        val binding = bindings[owner] ?: return false
        val job = binding.job?.get() ?: return false
        val method = binding.cancellation ?: return false
        return try {
            method.invoke(job)
            false
        } catch (exception: InvocationTargetException) {
            exception.cause is IllegalStateException
        } catch (_: Exception) {
            false
        }
    }

    private fun findJob(scope: Any): Pair<Any, Method>? =
        try {
            val scopeMethod = scopeMethod ?: return null
            val fold = foldMethod ?: return null
            val cancellation = cancellationMethod ?: return null
            val context = scopeMethod.invoke(scope) ?: return null
            val operation = fold.parameterTypes[1]
            val jobs = mutableListOf<Any>()
            val collector =
                Proxy.newProxyInstance(
                    operation.classLoader,
                    arrayOf(operation),
                ) { _, method, args ->
                    if (method.parameterCount == 2 && args != null) {
                        val element = args[1]
                        if (element != null && cancellation.declaringClass.isInstance(element)) {
                            jobs.add(element)
                        }
                        args[0]
                    } else {
                        null
                    }
                }
            fold.invoke(context, Unit, collector)
            jobs.singleOrNull()?.let { it to cancellation }
        } catch (_: Exception) {
            null
        }
}
