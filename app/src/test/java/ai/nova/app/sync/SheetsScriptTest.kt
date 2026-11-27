package ai.nova.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Код доступа вставляется в JavaScript-строку: любой пароль не должен ломать скрипт. */
class SheetsScriptTest {

    @Test
    fun plainTokenIsInsertedIntoScript() {
        val code = SheetsScript.text("  мой-код  ")
        assertTrue(code.contains("var TOKEN = 'мой-код';"))
        assertFalse(code.contains(SheetsScript.TOKEN_PLACEHOLDER))
    }

    @Test
    fun quotesBackslashesAndNewlinesAreEscaped() {
        val code = SheetsScript.text("pa's\\word\nnext")
        assertTrue(code.contains("var TOKEN = 'pa\\'s\\\\word\\nnext';"))
        assertFalse(code.contains("pa's\\word\nnext"))
    }
}
