package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.full.ItemStatsIndex
import eu.hxreborn.gplayadblock.full.ItemStatsStore
import eu.hxreborn.gplayadblock.full.SearchOrderTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object ItemStatsRecorder {
    fun install(
        module: XposedModule,
        hooks: SearchSortInstallation,
        classLoader: ClassLoader,
        targets: SearchOrderTargets,
        protobufToByteArrayMethod: Method,
        itemStats: ItemStatsIndex,
        itemStatsStore: ItemStatsStore,
    ) {
        val methods = targets.itemPrefetchMethods.map { method -> method.resolve(classLoader) }
        val messageClass = protobufToByteArrayMethod.declaringClass
        val recorder =
            Recorder(
                hooks = hooks,
                messageClass = messageClass,
                protobufToByteArrayMethod = protobufToByteArrayMethod,
                itemStats = itemStats,
                itemStatsStore = itemStatsStore,
            )
        methods.forEach { method ->
            hooks.hook(module, method) { chain -> recorder.intercept(chain) }
        }
    }

    private class Recorder(
        private val hooks: SearchSortInstallation,
        private val messageClass: Class<*>,
        private val protobufToByteArrayMethod: Method,
        private val itemStats: ItemStatsIndex,
        private val itemStatsStore: ItemStatsStore,
    ) {
        fun intercept(chain: XposedInterface.Chain): Any? {
            var changed = false
            (chain.getArg(0) as? List<*>)?.forEach { item ->
                if (item != null && messageClass.isInstance(item)) {
                    try {
                        val bytes = protobufToByteArrayMethod.invoke(item) as? ByteArray
                        if (bytes != null && itemStats.record(bytes)) changed = true
                    } catch (exception: Exception) {
                        hooks.disable(exception)
                    }
                }
            }
            if (changed) {
                try {
                    itemStatsStore.scheduleWrite()
                } catch (exception: Exception) {
                    hooks.disable(exception)
                }
            }
            return chain.proceed()
        }
    }
}
