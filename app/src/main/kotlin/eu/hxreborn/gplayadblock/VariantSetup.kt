package eu.hxreborn.gplayadblock

import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.RemovalPolicy

interface VariantSetup {
    fun removalPolicy(sponsored: PresentationClassifier): RemovalPolicy

    fun installAfterSponsored()
}
