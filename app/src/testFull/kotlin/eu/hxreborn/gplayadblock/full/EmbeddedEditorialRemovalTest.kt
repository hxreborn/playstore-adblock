package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.hook.PresentationClassifier
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.AdMetadata
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.ByteString
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Card
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Cluster
import eu.hxreborn.gplayadblock.hook.PresentationClassifierTest.Presentation
import eu.hxreborn.gplayadblock.hook.ProtoEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

class EmbeddedEditorialRemovalTest {
    open class Message {
        open fun toByteArray(): ByteArray = byteArrayOf()
    }

    class SchemaCard : Message() {
        fun schema(
            operation: Int,
            ignored: Any?,
        ): Any {
            require(operation == 3)
            return Schema(arrayOf(Payload::class.java, OneofPayload::class.java))
        }

        companion object {
            @JvmField val DEFAULT = SchemaCard()
        }
    }

    class Schema(
        @JvmField val objects: Array<Any>,
    )

    class Payload(
        @JvmField var details: Details,
    ) : Message() {
        override fun toByteArray(): ByteArray {
            val bytes = details.toByteArray()
            return byteArrayOf(18, bytes.size.toByte()) + bytes
        }
    }

    class OneofPayload(
        @JvmField var details: Any,
    ) : Message() {
        override fun toByteArray(): ByteArray {
            val bytes = (details as Details).toByteArray()
            return byteArrayOf(18, bytes.size.toByte()) + bytes
        }

        fun schema(
            operation: Int,
            ignored: Any?,
        ): Any {
            require(operation == 3)
            return Schema(arrayOf(Details::class.java))
        }

        companion object {
            @JvmField val DEFAULT = OneofPayload(Details(Media(ProtoList(emptyList()))))
        }
    }

    class Details(
        @JvmField var media: Media,
    ) : Message() {
        override fun toByteArray(): ByteArray = media.toByteArray()
    }

    class Media(
        @JvmField var items: ProtoList,
    ) : Message() {
        override fun toByteArray(): ByteArray =
            items.flatMap { it.toByteArray().toList() }.toByteArray()
    }

    class Item(
        val link: String,
    ) : Message() {
        override fun toByteArray(): ByteArray = link.toByteArray()
    }

    class ProtoList(
        values: Collection<Item>,
    ) : ArrayList<Item>(values) {
        fun mutableCopy(capacity: Int): ProtoList = ProtoList(this)
    }

    class Builder {
        @JvmField var message: Any? = null

        fun merge(original: Any) {
            message =
                when (original) {
                    is Presentation -> Presentation(original.kind, original.payload)
                    is Card -> Card(original.kind, original.payload)
                    is Payload -> Payload(original.details)
                    is OneofPayload -> OneofPayload(original.details)
                    is Details -> Details(original.media)
                    is Media -> Media(original.items)
                    else -> error("unexpected message ${original.javaClass}")
                }
        }

        fun build(): Any = requireNotNull(message)

        companion object {
            @JvmStatic fun create(): Builder = Builder()
        }
    }

    private val bytesMethod = Message::class.java.getDeclaredMethod("toByteArray")
    private val sponsored =
        PresentationClassifier(
            presentationKindField = field<Presentation>("kind"),
            presentationPayloadField = field<Presentation>("payload"),
            clusterCaseField = field<Cluster>("case"),
            clusterPayloadField = field<Cluster>("payload"),
            clusterServerLogsField = field<Cluster>("serverLogs"),
            byteStringToByteArrayMethod = ByteString::class.java.getDeclaredMethod("toByteArray"),
            protobufToByteArrayMethod = bytesMethod,
            cardKindField = field<Card>("kind"),
            cardPayloadField = field<Card>("payload"),
            cardAdMetadataFields = emptyMap(),
            adPresenceField = field<AdMetadata>("bitField"),
        )
    private val policy =
        FullRemovalPolicy(
            sponsored = sponsored,
            presentationKindField = field<Presentation>("kind"),
            presentationPayloadField = field<Presentation>("payload"),
            clusterCaseField = field<Cluster>("case"),
            clusterServerLogsField = field<Cluster>("serverLogs"),
            byteStringToByteArrayMethod = ByteString::class.java.getDeclaredMethod("toByteArray"),
            cardKindField = field<Card>("kind"),
            cardPayloadField = field<Card>("payload"),
            protobufToByteArrayMethod = bytesMethod,
            hideLiveOps = true,
            hidePreRegistrationGames = false,
            hideSearchPromotions = false,
            embeddedFields =
                EmbeddedEditorialFields.resolve(
                    SchemaCard::class.java,
                    Message::class.java,
                ),
            reportFailure = { throw AssertionError(it) },
        )
    private val editor =
        ProtoEditor(
            newBuilderMethod = Builder::class.java.getDeclaredMethod("create"),
            mergeMethod = Builder::class.java.getDeclaredMethod("merge", Any::class.java),
            buildMethod = Builder::class.java.getDeclaredMethod("build"),
            builderMessageField = field<Builder>("message"),
            parseMethod = Any::class.java.getDeclaredMethod("toString"),
            registry = Any(),
            toByteArrayMethod = bytesMethod,
            repeatedListCopyMethod =
                ProtoList::class.java.getDeclaredMethod(
                    "mutableCopy",
                    Int::class.javaPrimitiveType,
                ),
        )

    @Test
    fun `embedded editorial media is copied out while organic media keeps its order`() {
        val first = Item("apps/details/first")
        val editorial = Item("apps/liveOpDetails/event")
        val last = Item("apps/details/last")
        val media = Media(ProtoList(listOf(first, editorial, last)))
        val payload = Payload(Details(media))
        val card = Card(3, payload)
        val presentation = Presentation(PresentationClassifier.CARD_PRESENTATION, card)
        assertFalse(policy.isRemovable(policy.classify(presentation)))

        val rewritten = policy.strippedPresentation(presentation, editor) as Presentation
        val rewrittenCard = rewritten.payload as Card
        val rewrittenPayload = rewrittenCard.payload as Payload
        assertNotSame(presentation, rewritten)
        assertNotSame(card, rewrittenCard)
        assertNotSame(payload, rewrittenPayload)
        assertNotSame(payload.details, rewrittenPayload.details)
        assertNotSame(media, rewrittenPayload.details.media)
        assertEquals(listOf(first, last), rewrittenPayload.details.media.items)
        assertSame(
            first,
            rewrittenPayload.details.media.items
                .first(),
        )
        assertEquals(listOf(first, editorial, last), media.items)
        assertTrue(payload.toByteArray().toString(Charsets.UTF_8).contains("apps/liveOpDetails"))
        assertFalse(
            rewrittenPayload.toByteArray().toString(Charsets.UTF_8).contains("liveOpDetails"),
        )
    }

    @Test
    fun `an embedded promotion under an Object oneof uses schema fields resolved at setup`() {
        val organic = Item("apps/details/organic")
        val editorial = Item("apps/liveOpDetails/event")
        val media = Media(ProtoList(listOf(organic, editorial)))
        val payload = OneofPayload(Details(media))
        val presentation = Presentation(PresentationClassifier.CARD_PRESENTATION, Card(3, payload))
        val rewritten = policy.strippedPresentation(presentation, editor) as Presentation
        val rewrittenPayload = (rewritten.payload as Card).payload as OneofPayload
        assertEquals(listOf(organic), (rewrittenPayload.details as Details).media.items)
        assertEquals(listOf(organic, editorial), media.items)
    }

    private inline fun <reified T> field(name: String): Field =
        T::class.java.getDeclaredField(name).apply { isAccessible = true }
}
