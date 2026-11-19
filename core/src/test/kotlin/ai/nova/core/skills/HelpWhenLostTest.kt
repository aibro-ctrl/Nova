package ai.nova.core.skills

import ai.nova.core.TestEnv
import ai.nova.core.assistant.AssistantSettings
import ai.nova.core.assistant.DeviceCommand
import ai.nova.core.assistant.DeviceController
import ai.nova.core.assistant.DeviceResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * «Нова, где я?» → адрес и помощь: скорая 103 или близкий человек из контактов.
 * Проверяем весь путь: навык → фраза-ответ → команда телефону.
 */
class HelpWhenLostTest {
    private val nominatim =
        """{"display_name":"улица Баумана, 12, Казань, Россия","address":{"road":"улица Баумана","house_number":"12","city":"Казань"}}"""

    private fun http() = HttpClient(MockEngine { r ->
        if (r.url.toString().contains("nominatim")) {
            respond(nominatim, HttpStatusCode.OK, headersOf("Content-Type", "application/json; charset=utf-8"))
        } else {
            respond("", HttpStatusCode.NotFound)
        }
    })

    private class Host : SkillHost {
        override suspend fun location() = GeoPoint(55.79, 49.12)
        override suspend fun contacts(limit: Int) = listOf(
            ContactInfo("Мама", listOf("+79161234567")),
            ContactInfo("Дочь Аня", listOf("+79161234568")),
        )
    }

    private class Device : DeviceController {
        val performed = ArrayList<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult {
            performed += command
            return DeviceResult("ок")
        }
    }

    private fun env(): Pair<TestEnv, Device> {
        val device = Device()
        val host = Host()
        val e = TestEnv(device = device, skillsFactory = { t -> Skills(host, http(), t) })
        e.settings = AssistantSettings(useAI = false)
        return e to device
    }

    @Test fun speaksAddressAndOffersHelp() = runTest {
        val (e, _) = env()
        val reply = e.engine.handle("Нова, где я?")
        assertTrue(reply.text.contains("улица Баумана, 12, Казань"), reply.text)
        assertTrue(reply.text.contains("вызови скорую"), reply.text)
        assertTrue(reply.text.contains("Мама"), "близкие из контактов: ${reply.text}")
    }

    @Test fun ambulanceCallCarriesAddress() = runTest {
        val (e, device) = env()
        e.engine.handle("где я")
        val reply = e.engine.handle("вызови скорую")
        val call = assertIs<DeviceCommand.Call>(device.performed.single())
        assertTrue(call.who.all { it.isDigit() }, call.who)
        assertTrue(reply.text.contains("улица Баумана, 12"), reply.text)
    }

    @Test fun messageToRelativeWithAddress() = runTest {
        val (e, device) = env()
        e.engine.handle("где я")
        e.engine.handle("напиши маме")
        val msg = assertIs<DeviceCommand.Message>(device.performed.single())
        assertTrue(msg.who.startsWith("мам"), msg.who)
        assertTrue(msg.text.contains("улица Баумана, 12, Казань"), msg.text)
    }

    @Test fun callToRelativeAfterWhereAmI() = runTest {
        val (e, device) = env()
        e.engine.handle("где я")
        e.engine.handle("позвони дочери")
        val call = assertIs<DeviceCommand.Call>(device.performed.single())
        assertTrue(call.who.contains("дочер"), call.who)
    }

    @Test fun otherCommandInterruptsHelp() = runTest {
        val (e, device) = env()
        e.engine.handle("где я")
        // Таймер — новая тема: предложение помощи не перехватывает фразу.
        e.engine.handle("поставь таймер на 5 минут")
        assertIs<DeviceCommand.Timer>(device.performed.single())
        // После новой команды адрес забыт: «напиши маме» уже не дописывает его.
        e.engine.handle("напиши маме привет")
        val msg = device.performed.filterIsInstance<DeviceCommand.Message>().single()
        assertFalse(msg.text.contains("Баумана"), msg.text)
    }
}
