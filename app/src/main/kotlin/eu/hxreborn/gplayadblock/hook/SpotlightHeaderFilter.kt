package eu.hxreborn.gplayadblock.hook

import eu.hxreborn.gplayadblock.Logger
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object SpotlightHeaderFilter {
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        spotlight: ResolvedTargets.Spotlight,
    ) {
        val interceptor =
            SpotlightHeaderInterceptor(
                tabDefaultInstance =
                    requireNotNull(
                        spotlight.tabDefaultInstanceField.resolve(classLoader).get(null),
                    ),
                toByteArrayMethod = targets.protobufToByteArrayMethod.resolve(classLoader),
                editor = ProtoEditor.from(classLoader, targets),
            )
        module
            .hook(spotlight.tabAccessor.resolve(classLoader))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptor.intercept(chain) }
    }

    internal fun withoutHeaderFields(serializedTab: ByteArray): ByteArray? =
        ProtoWire.withoutFields(serializedTab, HEADER_FIELDS)

    private class SpotlightHeaderInterceptor(
        private val tabDefaultInstance: Any,
        private val toByteArrayMethod: Method,
        private val editor: ProtoEditor,
    ) {
        @Volatile
        private var lastRewrite: TabRewrite? = null

        fun intercept(chain: XposedInterface.Chain): Any? {
            val tab = chain.proceed() ?: return null
            lastRewrite?.takeIf { rewrite -> rewrite.original === tab }?.let { rewrite ->
                return rewrite.result
            }
            val result =
                try {
                    withoutHeader(tab)
                } catch (exception: Exception) {
                    Logger.error("spotlight header removal failed", exception)
                    return tab
                }
            lastRewrite = TabRewrite(tab, result)
            return result
        }

        private fun withoutHeader(tab: Any): Any {
            val bytes = toByteArrayMethod.invoke(tab) as ByteArray
            val retained = withoutHeaderFields(bytes) ?: return tab
            Logger.debug { "spotlight header removed bytes=${bytes.size - retained.size}" }
            return editor.parseBytes(tabDefaultInstance, retained)
        }
    }

    private class TabRewrite(
        val original: Any,
        val result: Any,
    )

    private const val INLINE_HEADER_FIELD = 12
    private const val HEADER_REQUEST_FIELD = 15
    private val HEADER_FIELDS = setOf(INLINE_HEADER_FIELD, HEADER_REQUEST_FIELD)
}
