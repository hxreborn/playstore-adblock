package eu.hxreborn.gplayadblock.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TargetCacheTest {
    @get:Rule
    val dataDir = TemporaryFolder()

    private val targetVersion = 85244130L
    private val moduleVersion = 10200L

    @Test
    fun `a stored target set survives the round trip unchanged`() {
        TargetCache.store(root(), targetVersion, moduleVersion, RESOLVED)

        assertEquals(RESOLVED, TargetCache.load(root(), targetVersion, moduleVersion))
    }

    @Test
    fun `a terminal failure is remembered so it is not rediscovered every launch`() {
        TargetCache.store(
            root(),
            targetVersion,
            moduleVersion,
            ResolvedTargets.Missing("no anchor"),
        )

        val loaded = TargetCache.load(root(), targetVersion, moduleVersion)

        assertEquals(ResolvedTargets.Missing("no anchor"), loaded)
    }

    @Test
    fun `a retryable failure is never written so the next launch resolves again`() {
        TargetCache.store(
            root(),
            targetVersion,
            moduleVersion,
            ResolvedTargets.Missing("native library missing", retryable = true),
        )

        assertNull(TargetCache.load(root(), targetVersion, moduleVersion))
    }

    @Test
    fun `a target set stored for another Play Store build is not reused`() {
        TargetCache.store(root(), targetVersion, moduleVersion, RESOLVED)

        assertNull(TargetCache.load(root(), targetVersion + 100, moduleVersion))
    }

    @Test
    fun `a target set stored by another module build is not reused`() {
        TargetCache.store(root(), targetVersion, moduleVersion, RESOLVED)

        assertNull(TargetCache.load(root(), targetVersion, moduleVersion + 1))
    }

    @Test
    fun `storing a new target set drops the entry it supersedes`() {
        TargetCache.store(root(), targetVersion, moduleVersion, RESOLVED)
        TargetCache.store(root(), targetVersion + 100, moduleVersion, RESOLVED)

        val remaining = cacheDirectory().list().orEmpty().toList()
        assertEquals(listOf("targets-${targetVersion + 100}-$moduleVersion.json"), remaining)
    }

    @Test
    fun `a corrupt entry is ignored rather than crashing the module`() {
        TargetCache.store(root(), targetVersion, moduleVersion, RESOLVED)
        File(cacheDirectory(), "targets-$targetVersion-$moduleVersion.json").writeText("{ not json")

        assertNull(TargetCache.load(root(), targetVersion, moduleVersion))
    }

    @Test
    fun `loading before anything was stored reports no entry`() {
        assertNull(TargetCache.load(root(), targetVersion, moduleVersion))
    }

    @Test
    fun `every resolved role is written, so a new target cannot be dropped silently`() {
        val everyRolePresent = RESOLVED.copy(suggestionFailure = "recorded")
        TargetCache.store(root(), targetVersion, moduleVersion, everyRolePresent)
        val stored = File(cacheDirectory(), "targets-$targetVersion-$moduleVersion.json").readText()

        val unwritten =
            ResolvedTargets.Resolved::class.java.declaredFields
                .map { field -> field.name }
                .filterNot { name -> name == "\$stable" || name.contains("$") }
                .filterNot { name -> stored.contains("\"$name\"") }
        assertTrue("roles missing from the cache entry: $unwritten", unwritten.isEmpty())
    }

    @Test
    fun `an optional suggestion target is preserved when absent`() {
        val withoutSuggestion = RESOLVED.copy(suggestion = null, suggestionFailure = "no candidate")
        TargetCache.store(root(), targetVersion, moduleVersion, withoutSuggestion)

        assertEquals(withoutSuggestion, TargetCache.load(root(), targetVersion, moduleVersion))
    }

    private fun root(): String = dataDir.root.absolutePath

    private fun cacheDirectory(): File = File(dataDir.root, "files/playstore-adblock")

    private companion object {
        fun method(name: String) =
            MethodRef(
                className = "a$name",
                methodName = name,
                returnTypeName = "b$name",
                paramTypeNames = listOf("java.lang.String", "int"),
            )

        fun field(name: String) = FieldRef(className = "c$name", fieldName = name)

        val RESOLVED =
            ResolvedTargets.Resolved(
                streamDataMethod = method("streamData"),
                streamChildrenField = field("streamChildren"),
                childHandlersField = field("childHandlers"),
                childIdField = field("childId"),
                nodeField = field("node"),
                presentationAccessor = method("presentationAccessor"),
                presentationKindField = field("presentationKind"),
                presentationPayloadField = field("presentationPayload"),
                clusterCaseField = field("clusterCase"),
                clusterPayloadField = field("clusterPayload"),
                clusterServerLogsField = field("clusterServerLogs"),
                cardKindField = field("cardKind"),
                cardPayloadField = field("cardPayload"),
                cardAdMetadataFields = listOf(field("cardAdOne"), field("cardAdTwo")),
                adPresenceField = field("adPresence"),
                responseMethods = listOf(method("responseOne"), method("responseTwo")),
                responseListFields = listOf(field("responseListOne")),
                nodeWrapperKindField = field("nodeWrapperKind"),
                nodeWrapperPayloadField = field("nodeWrapperPayload"),
                nodeIdField = field("nodeId"),
                nodePresentationField = field("nodePresentation"),
                nodeChildrenField = field("nodeChildren"),
                rootWrapperKindField = field("rootWrapperKind"),
                rootWrapperPayloadField = field("rootWrapperPayload"),
                rootChildrenField = field("rootChildren"),
                childIdsField = field("childIds"),
                childPresenceField = field("childPresence"),
                childContinuationField = field("childContinuation"),
                nodeDefaultInstanceField = field("nodeDefaultInstance"),
                rootDefaultInstanceField = field("rootDefaultInstance"),
                cacheAssemblyMethod = method("cacheAssembly"),
                cacheNodeChildrenField = field("cacheNodeChildren"),
                cachePageBoundariesField = field("cachePageBoundaries"),
                cachePageBoundariesCopyMethod = method("cachePageBoundariesCopy"),
                childKeyMethod = method("childKey"),
                protobufNewBuilderMethod = method("protobufNewBuilder"),
                protobufMergeMethod = method("protobufMerge"),
                protobufBuildMethod = method("protobufBuild"),
                protobufBuilderMessageField = field("protobufBuilderMessage"),
                protobufParseMethod = method("protobufParse"),
                protobufRegistryFactory = method("protobufRegistryFactory"),
                byteStringToByteArrayMethod = method("byteStringToByteArray"),
                protobufToByteArrayMethod = method("protobufToByteArray"),
                repeatedListCopyMethod = method("repeatedListCopy"),
                suggestion =
                    ResolvedTargets.Suggestion(
                        constructor =
                            ConstructorRef(
                                className = "suggestionOwner",
                                paramTypeNames = listOf("java.util.List", "int", "int"),
                            ),
                        adInfoField = field("adInfo"),
                    ),
                suggestionFailure = null,
            )
    }
}
