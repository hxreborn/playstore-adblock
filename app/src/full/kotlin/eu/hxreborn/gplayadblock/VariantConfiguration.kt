package eu.hxreborn.gplayadblock

import android.app.Application
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.full.FullModuleConfiguration
import io.github.libxposed.api.XposedModule

object VariantConfiguration {
    fun setUp(
        module: XposedModule,
        application: Application,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        cacheKey: TargetCache.Key,
    ): VariantSetup =
        FullModuleConfiguration(
            module = module,
            application = application,
            classLoader = classLoader,
            targets = targets,
            cacheKey = cacheKey,
            requestedSettings = FullModuleConfiguration.readSettings(module),
        )
}
