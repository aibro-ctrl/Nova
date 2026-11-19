package ai.nova.core

import ai.nova.core.ai.AIProvider
import ai.nova.core.ai.AIProviderType
import ai.nova.core.ai.AIRequest
import ai.nova.core.ai.AIResponse
import ai.nova.core.ai.EmbeddingProvider
import ai.nova.core.agent.AgentCore
import ai.nova.core.assistant.ActionExecutor
import ai.nova.core.assistant.AssistantEngine
import ai.nova.core.assistant.AssistantSettings
import ai.nova.core.assistant.ReminderScheduler
import ai.nova.core.assistant.TargetResolver
import ai.nova.core.data.LocalStore
import ai.nova.core.health.Habits
import ai.nova.core.db.NovaDatabase
import ai.nova.core.model.Reminder
import ai.nova.core.search.SearchService
import ai.nova.core.util.FixedTimeSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId

/** Тестовое окружение: настоящая SQLite в памяти + фиксированное время (пятница, 25.09.2026, 12:00 МСК). */
class TestEnv(
    val time: FixedTimeSource = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), ZoneId.of("Europe/Moscow")),
    device: ai.nova.core.assistant.DeviceController = ai.nova.core.assistant.UnsupportedDevice,
    lockPolicy: () -> ai.nova.core.assistant.LockPolicy? = { null },
    private val skillsFactory: ((FixedTimeSource) -> ai.nova.core.skills.Skills)? = null,
    private val localChat: ai.nova.core.ai.LocalChat? = null,
    /** Обёртка над тратами (например, передача в Финансовый помощник). */
    wrapExpenses: (ai.nova.core.domain.ExpenseRepository) -> ai.nova.core.domain.ExpenseRepository = { it },
) {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NovaDatabase.Schema.create(it) }
    val store = LocalStore(driver, time, Dispatchers.Unconfined)
    val scheduler = RecordingScheduler()
    /** Напоминания, созданные как «настойчивые». */
    val persistent = mutableListOf<String>()
    /** Планы голосом, которые ещё не в календаре (для «в календарь»). */
    val plans = mutableListOf<ai.nova.core.assistant.PlanCalendar>()
    private var engineRef: AssistantEngine? = null
    var embeddings: EmbeddingProvider? = null
    val search = SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { embeddings }
    val resolver = TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories)
    val expenses = wrapExpenses(store.expenses)
    val executor = ActionExecutor(
        store.notes, expenses, store.tasks, store.reminders, store.memories, search, resolver, scheduler, time, device, lockPolicy,
        shopping = store.shopping, routines = store.routines, secrets = store.secrets,
        onPersistentReminder = { persistent += it },
        onPlanCreated = { plans += it; engineRef?.notePlan(it) },
    )
    var settings = AssistantSettings(useAI = true)
    var ai: AIProvider? = null

    /** Новый характер, выбранный голосом («будь деловой»). */
    var onPersona: (ai.nova.core.assistant.Persona) -> Unit = {}
    val engine = newEngine()

    /** Движок поверх той же базы: имитация перезапуска приложения. */
    fun restart(): AssistantEngine = newEngine()

    private fun newEngine() = AssistantEngine(
        store.notes, store.tasks, store.reminders, store.memories, store.conversations, search, executor, time,
        settings = { settings }, aiProvider = { ai },
        routines = { store.routines.all() }, shoppingItems = { store.shopping.all() },
        skills = skillsFactory?.invoke(time),
        localChat = localChat,
        habits = Habits(store.habits, time),
        daySpend = { d -> store.expenses.between(d, d).takeIf { it.isNotEmpty() }?.let { "Потрачено: ${it.sumOf { e -> e.amountMinor } / 100} ₽." } },
        onPersona = { onPersona(it) },
        expensesBetween = { a, b -> store.expenses.between(a, b) },
        agent = AgentCore(store.agent, time),
        cooking = store.cooking,
    ).also { engineRef = it }
}

class RecordingScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Reminder>()
    val cancelled = mutableListOf<String>()
    override fun schedule(reminder: Reminder) { scheduled += reminder }
    override fun cancel(reminderId: String) { cancelled += reminderId }
}

/** AI-провайдер со сценарием ответов: возвращает заранее заданный JSON и запоминает запросы. */
class ScriptedAI(private val responder: (AIRequest) -> String) : AIProvider {
    override val type = AIProviderType.OPENAI
    override val model = "test-model"
    val requests = mutableListOf<AIRequest>()
    override suspend fun complete(request: AIRequest): AIResponse {
        requests += request
        return AIResponse(responder(request), model, "stop")
    }
}

/** Достаёт из системного промпта метку кандидата по фрагменту заголовка. */
fun AIRequest.handleFor(titleFragment: String): String =
    Regex("""(#\d+) \[[^\]]+] «([^»]*)»""").findAll(system).firstOrNull { it.groupValues[2].contains(titleFragment, ignoreCase = true) }
        ?.groupValues?.get(1) ?: error("Кандидат «$titleFragment» не найден в промпте")
