package eu.hxreborn.gplayadblock.full

import eu.hxreborn.gplayadblock.discovery.ReleaseFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OptionalTargetsFixtureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a missing account actions anchor keeps the badge target`() {
        val targets = accountTargetsWithout("AccountMenuFeatures{deactivatedAccountsFeature=")
        assertTrue(targets.actionRoleField == null)
        assertTrue(targets.notificationTopBarConstructor != null)
        assertTrue(targets.notificationBadgeTypeName != null)
        assertTrue(targets.unresolved.any { it.contains("AccountMenuFeatures") })
    }

    @Test
    fun `a missing badge anchor keeps account action targets`() {
        val targets =
            accountTargetsWithout(
                "NotificationTopBarItemUiContent(notificationIndicatorBadge=",
            )
        assertTrue(targets.actionRoleField != null)
        assertTrue(targets.notificationTopBarConstructor == null)
        assertTrue(targets.unresolved.any { it.contains("NotificationTopBarItemUiContent") })
    }

    private fun accountTargetsWithout(anchor: String): AccountMenuTargets {
        val checked = ReleaseFixtures.all().lastOrNull { it.release in CHECKED_RELEASES }
        assumeTrue("none of $CHECKED_RELEASES is archived", checked != null)
        val fixture = requireNotNull(checked)
        val apk = temporary.newFile("masked.apk")
        var changed = false
        ZipFile(fixture.apk).use { source ->
            ZipOutputStream(apk.outputStream()).use { destination ->
                source.entries().asSequence().forEach { entry ->
                    var bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (entry.name.matches(Regex("classes[0-9]*\\.dex"))) {
                        val text = bytes.toString(Charsets.ISO_8859_1)
                        if (anchor in text) {
                            changed = true
                            bytes =
                                text
                                    .replace(anchor, "x".repeat(anchor.length))
                                    .toByteArray(Charsets.ISO_8859_1)
                            MessageDigest
                                .getInstance(
                                    "SHA-1",
                                ).digest(bytes.copyOfRange(32, bytes.size))
                                .copyInto(bytes, 12)
                            val checksum =
                                Adler32()
                                    .apply {
                                        update(
                                            bytes,
                                            12,
                                            bytes.size - 12,
                                        )
                                    }.value
                            ByteBuffer
                                .wrap(bytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(8, checksum.toInt())
                        }
                    }
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(bytes)
                    destination.closeEntry()
                }
            }
        }
        assertTrue("fixture contains $anchor", changed)
        val resolved =
            OptionalDexKitResolver.resolve(
                listOf(apk.absolutePath),
                setOf(OptionalGroup.ACCOUNT_MENU),
            )
        assertEquals(emptyMap<OptionalGroup, String>(), resolved.failures)
        assertTrue(!resolved.transientFailure)
        return requireNotNull(resolved.accountMenu)
    }

    @Before
    fun requireReleaseFixtures() {
        val unavailable = ReleaseFixtures.unavailableReason()
        assumeTrue(unavailable, unavailable == null)
    }

    @Test
    fun `every release fixture resolves to its recorded optional target set`() {
        val actual =
            ReleaseFixtures.all().associate { fixture ->
                fixture.release to
                    OptionalDexKitResolver
                        .resolve(listOf(fixture.apk.absolutePath), OptionalGroup.entries.toSet())
                        .toString()
                        .replace(fixture.apk.absolutePath, "<apk>")
            }
        System.getProperty(RECORD_PROPERTY)?.let { path ->
            File(
                path,
            ).writeText(actual.entries.joinToString("") { (release, line) -> "$release\t$line\n" })
        }
        val recorded =
            javaClass
                .getResourceAsStream(RECORDED_RESOURCE)
                ?.bufferedReader()
                ?.readLines()
                .orEmpty()
                .filter(String::isNotBlank)
                .associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
        val drifted = actual.filter { (release, line) -> recorded[release] != line }.keys
        assertTrue(
            "optional targets differ from $RECORDED_RESOURCE on: ${drifted.joinToString()}",
            drifted.isEmpty(),
        )
    }

    @Test
    fun `every optional feature resolves on the builds checked against decompiled sources`() {
        val checked =
            ReleaseFixtures.all().filter { fixture ->
                fixture.release in CHECKED_RELEASES
            }
        assumeTrue("none of $CHECKED_RELEASES is archived", checked.isNotEmpty())
        for (fixture in checked) {
            val targets =
                OptionalDexKitResolver.resolve(
                    listOf(fixture.apk.absolutePath),
                    OptionalGroup.entries.toSet(),
                )
            assertEquals(fixture.release, emptyMap<OptionalGroup, String>(), targets.failures)
            assertEquals(fixture.release, emptyList<String>(), targets.manageApps?.unresolved)
            assertTrue(fixture.release, targets.accountMenu?.actionRoleField != null)
            assertTrue(fixture.release, targets.accountMenu?.notificationTopBarConstructor != null)
            val account = requireNotNull(targets.accountMenu)
            assertEquals(fixture.release, emptyList<String>(), account.unresolved)
            assertEquals(
                fixture.release,
                1,
                account.notificationTopBarConstructor?.paramTypeNames?.count {
                    it == account.notificationBadgeTypeName
                },
            )
            assertTrue(fixture.release, targets.selfUpdate?.autoUpdateMethod != null)
            assertTrue(fixture.release, targets.persistentNav?.navHiddenInstanceField != null)
            assertTrue(
                fixture.release,
                account.labeledActions
                    ?.labelIcons
                    .orEmpty()
                    .isNotEmpty(),
            )
            assertTrue(
                fixture.release,
                account.headerCards
                    ?.titleIcons
                    .orEmpty()
                    .isNotEmpty(),
            )
        }
    }

    @Test
    fun `account menu labels pair with their icons on 54_0_09`() {
        val fixture = ReleaseFixtures.all().singleOrNull { it.release == "54.0.09-34-85400940" }
        assumeTrue("54.0.09 is not archived", fixture != null)
        val account =
            requireNotNull(
                OptionalDexKitResolver
                    .resolve(
                        listOf(requireNotNull(fixture).apk.absolutePath),
                        setOf(OptionalGroup.ACCOUNT_MENU),
                    ).accountMenu,
            )
        val labelIcons = requireNotNull(account.labeledActions).labelIcons
        listOf(
            ResourcePair(labelId = 0x7f141156, iconId = 0x7f080404),
            ResourcePair(labelId = 0x7f141060, iconId = 0x7f0803a3),
            ResourcePair(labelId = 0x7f141176, iconId = 0x7f08052d),
            ResourcePair(labelId = 0x7f14113b, iconId = 0x7f080520),
            ResourcePair(labelId = 0x7f140bc5, iconId = 0x7f0803c6),
            ResourcePair(labelId = 0x7f14115a, iconId = 0x7f080418),
        ).forEach { pair -> assertTrue("$pair", pair in labelIcons) }
        assertTrue(
            ResourcePair(labelId = 0x7f140c02, iconId = 0x7f130162) in
                requireNotNull(account.headerCards).titleIcons,
        )
    }

    private companion object {
        const val RECORDED_RESOURCE = "/optional-fingerprints.txt"
        const val RECORD_PROPERTY = "gplayadblock.recordOptionalFingerprints"
        val CHECKED_RELEASES =
            setOf("53.6.15-34-85361540", "53.8.11-34-85381140", "54.0.09-34-85400940")
    }
}
