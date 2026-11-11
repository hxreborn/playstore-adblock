package eu.hxreborn.gplayadblock

import android.app.Application
import eu.hxreborn.gplayadblock.discovery.ResolvedTargets
import eu.hxreborn.gplayadblock.discovery.TargetCache
import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.RemovalPolicy
import io.github.libxposed.api.XposedModule

object VariantConfiguration {
    @Suppress("UNUSED_PARAMETER")
    fun setUp(
        module: XposedModule,
        application: Application,
        classLoader: ClassLoader,
        targets: ResolvedTargets.Resolved,
        cacheKey: TargetCache.Key,
    ): VariantSetup = SponsoredOnly

    private object SponsoredOnly : VariantSetup {
        override fun removalPolicy(sponsored: PresentationClassifier): RemovalPolicy = sponsored

        override fun installAfterSponsored() = Unit
    }
}
