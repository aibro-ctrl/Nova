package ai.nova.app

import ai.nova.app.ui.Changelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Что нового» хранит историю последних пяти версий: после хотфикса предыдущие релизы не исчезают,
 * а самые старые записи отсекаются.
 */
class ChangelogTest {

    @Test
    fun historyIsCappedAtFiveReleases() {
        assertTrue("история не пустая", Changelog.releases.isNotEmpty())
        assertTrue(
            "показываем не больше ${Changelog.HISTORY} версий, иначе окно превратится в летопись",
            Changelog.releases.size <= Changelog.HISTORY,
        )
    }

    @Test
    fun releasesAreNewestFirst() {
        val numbers = Changelog.releases.map { version ->
            version.version.split('.').map { it.toIntOrNull() ?: 0 }
        }
        numbers.zipWithNext().forEach { (newer, older) ->
            assertTrue("версии идут от новой к старой: $newer перед $older", compare(newer, older) > 0)
        }
        assertEquals("первая запись — самая свежая версия", Changelog.releases.first(), Changelog.latest)
    }

    @Test
    fun installedVersionHasReleaseNotes() {
        assertEquals(
            "для установленной версии есть запись в «Что нового»",
            BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            Changelog.latest.version,
        )
    }

    @Test
    fun everyReleaseIsReadable() {
        Changelog.releases.forEach { release ->
            assertTrue("у ${release.version} есть название", release.title.isNotBlank())
            assertTrue("у ${release.version} есть изменения", release.changes.isNotEmpty())
            release.changes.forEach { change ->
                assertTrue("пункт «$change» дописан до конца, без «…»", !change.trimEnd().endsWith("…"))
            }
        }
    }

    @Test
    fun sinceSkipsVersionsAlreadySeen() {
        val newest = Changelog.releases.first()
        val second = Changelog.releases[1]

        assertEquals("без прошлой версии показываем только свежую", listOf(newest), Changelog.since(null))
        assertEquals("прошлую версию уже видели — остаётся одна", listOf(newest), Changelog.since(second.version))
        assertEquals("неизвестная версия — показываем только свежую", listOf(newest), Changelog.since("0.0.1"))
        assertEquals(
            "из истории показываем всё, что новее просмотренного",
            2,
            Changelog.since(Changelog.releases[2].version).size,
        )
    }

    private fun compare(a: List<Int>, b: List<Int>): Int =
        (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { i ->
            (a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }).takeIf { it != 0 }
        } ?: 0
}
