package ai.nova.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Каталог офлайн-моделей Hugging Face: коллекция и файлы репозитория (ответы API сняты с настоящих). */
class LocalModelsTest {
    private val collection = """{"title":"Qwen3.5","items":[
        {"type":"model","itemId":"Qwen/Qwen3.5-4B-Instruct"},
        {"type":"model","itemId":"Qwen/Qwen3.5-1.7B-Instruct"},
        {"type":"dataset","itemId":"Qwen/some-data"},
        {"type":"model","itemId":"Qwen/Qwen3.5-4B-Instruct"}]}"""

    private val repo = """{"siblings":[
        {"rfilename":"README.md"},
        {"rfilename":"qwen3.5-4b-q8_0.gguf","size":4200000000},
        {"rfilename":"qwen3.5-4b-q4_k_m.gguf","size":2500000000,"lfs":{"size":2500000000,"oid":"a3f1c9d2b7e8f4a5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9"}},
        {"rfilename":"qwen3.5-4b-q4_0.gguf","size":2400000000,"lfs":{"size":2400000000,"oid":"b3f1c9d2b7e8f4a5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9"}},
        {"rfilename":"mmproj-qwen3.5-4b-f16.gguf","size":1200000000},
        {"rfilename":"qwen3.5-4b-q4_k_m-00001-of-00002.gguf","size":1800000000}]}"""

    @Test fun collectionKeepsOnlyModels() {
        assertEquals(listOf("Qwen/Qwen3.5-4B-Instruct", "Qwen/Qwen3.5-1.7B-Instruct"), LocalModels.parseCollection(collection))
    }

    /** Настоящий ответ API отдаёт репозиторий в поле id, а не itemId. */
    @Test fun collectionReadsRepoFromIdField() {
        val real = """{"items":[{"type":"model","id":"unsloth/Qwen3.5-2B-GGUF"},{"type":"model","id":"unsloth/Qwen3.5-4B-GGUF"}]}"""
        assertEquals(listOf("unsloth/Qwen3.5-2B-GGUF", "unsloth/Qwen3.5-4B-GGUF"), LocalModels.parseCollection(real))
    }

    @Test fun recommendedStartsWithBigModelOnStrongPhone() {
        // 12 ГБ ОЗУ: телефон тянет 4B.
        assertEquals("unsloth/Qwen3.5-4B-GGUF", LocalModels.recommended(12_000_000_000L).first())
    }

    @Test fun recommendedUsesTwoBOnEightGigPhone() {
        assertEquals("unsloth/Qwen3.5-2B-GGUF", LocalModels.recommended(8_000_000_000L).first())
    }

    @Test fun recommendedSkipsBigModelOnWeakPhone() {
        // 4 ГБ ОЗУ: 2,7 ГБ модель не поднять — сразу 2B.
        assertEquals("unsloth/Qwen3.5-2B-GGUF", LocalModels.recommended(4_000_000_000L).first())
        assertEquals(2, LocalModels.recommended(4_000_000_000L).size)
    }

    @Test fun thinkingIsStrippedFromAnswer() {
        assertEquals("Борщ — суп из свёклы.", LocalModels.stripThinking("<think>надо объяснить просто</think>Борщ — суп из свёклы."))
        // Размышления без закрывающего тега: показываем то, что после последней метки.
        assertEquals("Ответ.", LocalModels.stripThinking("думаю</think>Ответ."))
        assertEquals("Просто ответ", LocalModels.stripThinking("Просто ответ"))
    }

    @Test fun collectionToleratesBrokenJson() {
        assertTrue(LocalModels.parseCollection("не json").isEmpty())
    }

    @Test fun repoFilesSkipProjectorAndShards() {
        val files = LocalModels.parseRepoFiles("Qwen/Qwen3.5-4B-Instruct", repo)
        assertEquals(3, files.size)
        assertTrue(files.none { it.path.contains("mmproj") || it.path.contains("of-00002") })
    }

    @Test fun quantAndUrlAreReadable() {
        val f = LocalModels.parseRepoFiles("Qwen/Qwen3.5-4B-Instruct", repo).first { it.quant == "Q4_K_M" }
        assertEquals("qwen3.5-4b-q4_k_m.gguf", f.fileName)
        assertEquals("Q4_K_M", f.quant)
        assertEquals("https://huggingface.co/Qwen/Qwen3.5-4B-Instruct/resolve/main/qwen3.5-4b-q4_k_m.gguf", f.url)
        assertEquals(2500000000L, f.size)
        assertNotNull(f.sha256)
        assertEquals("qwen3.5 4b q4_k_m", f.title)
    }

    @Test fun pickPrefersBalancedQuantThatFits() {
        val files = LocalModels.parseRepoFiles("Qwen/Qwen3.5-4B-Instruct", repo)
        // Q8_0 не влезает в 3 ГБ — берём Q4_K_M, он в порядке предпочтения выше Q4_0.
        assertEquals("Q4_K_M", LocalModels.pick(files)?.quant)
    }

    @Test fun pickFallsBackToSmallest() {
        val files = LocalModels.parseRepoFiles("Qwen/Qwen3.5-4B-Instruct", repo)
        assertEquals("Q4_0", LocalModels.pick(files, maxBytes = 1)?.quant)
    }

    @Test fun pickNullWhenNothing() {
        assertNull(LocalModels.pick(emptyList()))
    }

    @Test fun sizeTextIsHuman() {
        assertEquals("2,5 ГБ", LocalModels.sizeText(2_500_000_000L))
        assertEquals("986 МБ", LocalModels.sizeText(986_048_768L))
        assertEquals("размер неизвестен", LocalModels.sizeText(0))
    }

    @Test fun labelHasRepoAndQuant() {
        val f = LocalModels.parseRepoFiles("Qwen/Qwen3.5-4B-Instruct", repo).first()
        assertEquals("Qwen3.5-4B-Instruct · Q4_0", LocalModels.label(f))
    }

    @Test fun friendlyNameHidesRepoDetails() {
        assertEquals("Qwen 3.5 2B", LocalModels.friendlyName("unsloth/Qwen3.5-2B-GGUF"))
        assertEquals("Qwen 3.5 4B", LocalModels.friendlyName("unsloth/Qwen3.5-4B-GGUF"))
        assertEquals("Qwen 2.5 3B Instruct", LocalModels.friendlyName("Qwen/Qwen2.5-3B-Instruct-GGUF"))
    }

    @Test fun quantNoteExplainsQuant() {
        assertTrue(LocalModels.quantNote("Q4_K_M").contains("баланс"))
        assertTrue(LocalModels.quantNote("Q8_0").contains("качество"))
        assertTrue(LocalModels.quantNote("IQ2_XXS").contains("компактн"))
        assertEquals("", LocalModels.quantNote(""))
    }
}
