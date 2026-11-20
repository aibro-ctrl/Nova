package ai.nova.core.skills

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Включи песню …»: разбор фразы, поиск в каталоге и выбор приложения. */
class MusicTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun p(text: String) = SkillPhrases.parse(text, today)

    // ------------------------------------------------------------------ Фразы

    @Test
    fun `песня без приложения играет встроенным плеером`() {
        val cmd = assertIs<SkillCommand.MusicPlay>(p("включи песню кино — группа крови"))
        assertEquals("кино — группа крови", cmd.query)
        assertNull(cmd.app)
    }

    @Test
    fun `трек и слово мне не мешают`() {
        val cmd = assertIs<SkillCommand.MusicPlay>(p("поставь мне трек земфира — ромашки"))
        assertEquals("земфира — ромашки", cmd.query)
    }

    @Test
    fun `названное приложение важнее встроенного плеера`() {
        val cmd = assertIs<SkillCommand.MusicPlay>(p("включи песню кино в яндекс музыке"))
        assertEquals("кино", cmd.query)
        assertEquals(MusicApp.YANDEX, cmd.app)

        assertEquals(MusicApp.SPOTIFY, assertIs<SkillCommand.MusicPlay>(p("включи песню кино в спотифае")).app)
        assertEquals(MusicApp.ZVUK, assertIs<SkillCommand.MusicPlay>(p("включи трек кино в звуке")).app)
        assertEquals(MusicApp.VK, assertIs<SkillCommand.MusicPlay>(p("включи песню кино в вк музыке")).app)
        assertEquals(MusicApp.YOUTUBE, assertIs<SkillCommand.MusicPlay>(p("включи песню кино в ютуб музыке")).app)
    }

    @Test
    fun `без названия песни просто открываем приложение`() {
        val cmd = assertIs<SkillCommand.MusicPlay>(p("включи в спотифае"))
        assertNull(cmd.query)
        assertEquals(MusicApp.SPOTIFY, cmd.app)
        assertEquals(MusicApp.YANDEX, assertIs<SkillCommand.MusicPlay>(p("включи музыку в яндекс музыке")).app)
    }

    @Test
    fun `радио и обычные команды не превращаются в музыку`() {
        assertIs<SkillCommand.RadioPlay>(p("включи радио европа плюс"))
        // «включи музыку» без названия — не поиск песни, а включение музыки в приложении.
        val music = assertIs<SkillCommand.MusicPlay>(p("включи музыку"))
        assertNull(music.query)
        assertNull(music.app)
        assertIs<SkillCommand.MusicPlay>(p("поставь что-нибудь"))
        assertIs<SkillCommand.MusicPlay>(p("включи музон"))
        assertNull(p("включи фонарик"))
    }

    @Test
    fun `сыграй - это про песню`() {
        val cmd = assertIs<SkillCommand.MusicPlay>(p("сыграй кино — звезда по имени солнце"))
        assertEquals("кино — звезда по имени солнце", cmd.query)
    }

    // ------------------------------------------------------------------ Каталог

    private val itunes = """
        {"resultCount":2,"results":[
          {"wrapperType":"track","kind":"song","artistName":"Кино","trackName":"Группа крови",
           "collectionName":"Группа крови","previewUrl":"https://audio-ssl.itunes.apple.com/preview.m4a",
           "artworkUrl100":"https://is1.mzstatic.com/100x100bb.jpg","trackViewUrl":"https://music.apple.com/ru/album/1"},
          {"wrapperType":"track","kind":"song","artistName":"Кино","trackName":"Звезда по имени Солнце",
           "previewUrl":"https://audio-ssl.itunes.apple.com/preview2.m4a"}
        ]}
    """.trimIndent()

    private val deezer = """
        {"data":[{"id":1,"readable":true,"title":"Instant Crush","duration":337,
          "preview":"https://cdnt-preview.dzcdn.net/api/1/1/preview.mp3","link":"https://www.deezer.com/track/1",
          "artist":{"id":27,"name":"Daft Punk"},"album":{"id":1,"title":"Random Access Memories","cover_medium":"https://e-cdns-images.dzcdn.net/cover.jpg"}}]}
    """.trimIndent()

    @Test
    fun `iTunes отдаёт песню с фрагментом`() {
        val track = assertNotNull(MusicSearch.parseItunes(itunes))
        assertEquals("Кино — Группа крови", track.label)
        assertEquals("Группа крови", track.album)
        assertEquals("https://audio-ssl.itunes.apple.com/preview.m4a", track.previewUrl)
    }

    @Test
    fun `Deezer - запасной источник`() {
        val track = assertNotNull(MusicSearch.parseDeezer(deezer))
        assertEquals("Daft Punk — Instant Crush", track.label)
        assertEquals("Random Access Memories", track.album)
    }

    @Test
    fun `пустой ответ и незашифрованные ссылки не играем`() {
        assertNull(MusicSearch.parseItunes("""{"resultCount":0,"results":[]}"""))
        assertNull(MusicSearch.parseDeezer("""{"error":{"type":"Exception","message":"no data","code":800}}"""))
        assertNull(
            MusicSearch.parseItunes(
                """{"results":[{"kind":"song","artistName":"X","trackName":"Y","previewUrl":"http://insecure/preview.m4a"}]}""",
            ),
        )
    }

    @Test
    fun `фильм из витрины пропускаем и берём песню`() {
        val json = """
            {"results":[
              {"wrapperType":"track","kind":"feature-movie","artistName":"Джефф Фаулер","trackName":"Соник в кино",
               "previewUrl":"https://video-ssl.itunes.apple.com/trailer.m3u8"},
              {"wrapperType":"track","kind":"song","artistName":"Кино","trackName":"Группа крови",
               "previewUrl":"https://audio-ssl.itunes.apple.com/preview.m4a"}
            ]}
        """.trimIndent()
        assertEquals("Кино — Группа крови", MusicSearch.parseItunes(json)?.label)
    }

    @Test
    fun `текст песни и аккорды`() {
        val text = SkillPhrases.parse("найди текст песни кино группа крови", LocalDate.of(2026, 9, 25), "Нова")
        assertIs<SkillCommand.MusicText>(text)
        assertEquals("кино группа крови", text.query)
        assertFalse(text.chords)

        val chords = SkillPhrases.parse("аккорды к песне кино группа крови", LocalDate.of(2026, 9, 25), "Нова")
        assertIs<SkillCommand.MusicText>(chords)
        assertTrue(chords.chords)
        assertEquals("кино группа крови", chords.query)

        // Включение песни не сломалось.
        val play = SkillPhrases.parse("включи песню кино группа крови", LocalDate.of(2026, 9, 25), "Нова")
        assertIs<SkillCommand.MusicPlay>(play)
        assertEquals("кино группа крови", play.query)
    }

    @Test
    fun `лайк на песню`() {
        val like = SkillPhrases.parse("поставь лайк на песню кино группа крови", LocalDate.of(2026, 9, 25), "Нова")
        assertIs<SkillCommand.MusicLike>(like)
        assertEquals("кино группа крови", like.query)
        // «поставь таймер» — не лайк.
        assertNull(SkillPhrases.parse("поставь таймер на 5 минут", LocalDate.of(2026, 9, 25), "Нова") as? SkillCommand.MusicLike)
    }
}
