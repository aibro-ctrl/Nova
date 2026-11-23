package ai.nova.core.assistant

import ai.nova.core.nlp.Gender
import ai.nova.core.nlp.RuGender
import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.ai.AIException
import ai.nova.core.ai.AIProvider
import ai.nova.core.ai.AIRequest
import ai.nova.core.ai.ChainAIProvider
import ai.nova.core.ai.ChatMessage
import ai.nova.core.domain.ConversationRepository
import ai.nova.core.domain.MemoryRepository
import ai.nova.core.domain.NoteRepository
import ai.nova.core.domain.ReminderRepository
import ai.nova.core.domain.TaskRepository
import ai.nova.core.model.MessageRole
import ai.nova.core.model.NoteKind
import ai.nova.core.model.RecordType
import ai.nova.core.nlp.TextAnalysis
import ai.nova.core.nlp.WakeWordMatcher
import ai.nova.core.search.SearchService
import ai.nova.core.util.Logger
import ai.nova.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Откуда пришла реплика — для статистики и поведения диалога. */
enum class InputSource { TEXT, VOICE, WAKE_WORD }

data class AssistantSettings(
    val assistantName: String = "Нова",
    /** Облачный AI необязателен: по умолчанию всё понимается локально, без интернета. */
    val useAI: Boolean = false,
    /** Разрешён ли диалоговый режим (продолжать разговор без обращения по имени). */
    val dialogMode: Boolean = true,
    /** Телефон заблокирован: облачному AI не передаются память и записи пользователя. */
    val locked: Boolean = false,
    /** Как зовут пользователя («называй меня …»). */
    val userName: String? = null,
    /** Город пользователя — для погоды, если геолокация недоступна. */
    val city: String? = null,
    /** Правила экрана блокировки (null — телефон разблокирован или правила не заданы). */
    val lockPolicy: LockPolicy? = null,
    /** Характер: заботливая, деловая, шутливая. */
    val persona: Persona = Persona.CARING,
)

/** Ответ ассистента для UI и голоса. */
data class AssistantReply(
    val text: String,
    val outcomes: List<Outcome> = emptyList(),
    /** Ждём «да/нет». */
    val awaitingConfirmation: Boolean = false,
    /** Ждём выбора из вариантов или ответа на уточняющий вопрос. */
    val awaitingAnswer: Boolean = false,
    /** Продолжать слушать без повторного обращения по имени. */
    val expectFollowUp: Boolean = false,
    val usedAI: Boolean = false,
    /** AI был недоступен, команда обработана офлайн. */
    val offline: Boolean = false,
    val changedData: Boolean = false,
    /** Пользователь закончил разговор («хватит», «спасибо, всё») — больше не слушать. */
    val endsDialog: Boolean = false,
    /** Какой AI-провайдер ответил (при нескольких подключённых). */
    val provider: String? = null,
    /** Почему AI не ответил и команда выполнена на устройстве (понятное человеку объяснение). */
    val aiError: String? = null,
    /** Язык озвучки ответа (перевод), ISO-код; null — русский. */
    val speakLanguage: String? = null,
    /** Начать длинную диктовку заметки: голос слушает с большими паузами до «готово». */
    val dictation: Boolean = false,
    /** Для ответа нужно разрешение — приложение предложит его выдать. */
    val permission: ai.nova.core.skills.Permission? = null,
    /** В ответе личное (сообщения, экран, контакты): в историю для AI не попадает. */
    val sensitive: Boolean = false,
    /** Найденное по ходу ответа (рецепт, статья, песня) — на это ссылается «запиши его в заметки». */
    val entity: ai.nova.core.agent.AgentEntity? = null,
)

/** Последний сбой AI — для экрана настроек: что именно не так с подключением. */
data class AiFailure(val message: String, val at: java.time.Instant)

/**
 * Главный оркестратор: реплика пользователя → намерение → проверенные действия → выполнение → ответ.
 *
 *  - Облачный AI (если настроен и доступен) определяет намерение и возвращает структурированные действия.
 *  - Без AI/интернета работает офлайн-парсер типовых команд — локальные операции не зависят от AI.
 *  - Разрушительные действия требуют подтверждения; неоднозначные ссылки — уточнения.
 */
class AssistantEngine(
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val memories: MemoryRepository,
    private val conversations: ConversationRepository,
    private val search: SearchService,
    private val executor: ActionExecutor,
    private val time: TimeSource,
    private val settings: () -> AssistantSettings,
    private val aiProvider: suspend () -> AIProvider?,
    private val localParser: LocalCommandParser = LocalCommandParser(),
    private val routines: suspend () -> List<ai.nova.core.model.Routine> = { emptyList() },
    private val shoppingItems: suspend () -> List<ai.nova.core.model.ShoppingItem> = { emptyList() },
    private val special: SpecialCommands = SpecialCommands(),
    /** Погода, курсы, новости, сообщения, радио, игры… */
    val skills: ai.nova.core.skills.Skills? = null,
    /** Фраза не понята ни на устройстве, ни AI — для журнала непонятых фраз (только на телефоне). */
    private val onNotUnderstood: (String) -> Unit = {},
    /** Офлайн-модель на телефоне: свободные вопросы без интернета и ключей. */
    private val localChat: ai.nova.core.ai.LocalChat? = null,
    /** Вода, лекарства, привычки, дыхание. */
    private val habits: ai.nova.core.health.Habits? = null,
    /** Строка про траты за день для «итогов дня» (null — не показывать). */
    private val daySpend: suspend (java.time.LocalDate) -> String? = { null },
    /** «Будь деловой» — приложение сохраняет новый характер. */
    private val onPersona: (Persona) -> Unit = {},
    /** Траты за период — для итогов недели и ленты дня. */
    private val expensesBetween: suspend (java.time.LocalDate, java.time.LocalDate) -> List<ai.nova.core.model.Expense> = { _, _ -> emptyList() },
    /** Ядро агента: реестр навыков, инструменты и постоянный контекст многошаговых задач. */
    private val agent: ai.nova.core.agent.AgentCore? = null,
    /** Сохранённые рецепты и идущая готовка: «продолжим готовить» после перезапуска. */
    private val cooking: ai.nova.core.data.CookingStore? = null,
    /** Выполненные действия — для журнала «что Нова сделала» (предсказуемость и контроль). */
    private val onExecuted: (List<AssistantAction>, AssistantReply) -> Unit = { _, _ -> },
) {
    private val review = ai.nova.core.review.Review(
        notes, tasks, reminders,
        habitLog = { a, b -> habits?.log(a, b).orEmpty() },
        expenses = expensesBetween, time = time,
    )

    /** Задача или напоминание с датой созданы — запоминаем план, чтобы «в календарь» сработало. */
    fun notePlan(plan: PlanCalendar) {
        pendingCalendar = plan
        pendingCalendarAt = time.now()
    }

    /** Итоги недели для воскресной сводки — тот же текст, что и по голосовой команде. */
    suspend fun weeklySummary(): String = review.week()

    val context = ConversationContext(time)
    private val mutex = Mutex()

    /** Сколько не пробовать облачный AI после сбоя: иначе каждый вопрос снова ждёт таймаут сети. */
    private val AI_COOLDOWN_MS = java.time.Duration.ofMinutes(2).toMillis()

    /** Куда отдавать куски ответа офлайн-модели, пока она пишет (озвучка начинает говорить сразу). */
    @Volatile private var chunkSink: ((String) -> Unit)? = null

    /** Последняя ошибка AI (null — последний запрос к AI прошёл успешно). */
    @Volatile var lastAiFailure: AiFailure? = null
        private set

    /** Живой диалог: последний ответ (для «повтори»), фраза, которую можно повторить «ещё», был ли это разговор с моделью. */
    private var lastReply: AssistantReply? = null
    private var lastReplyAt: java.time.Instant = java.time.Instant.EPOCH
    private var lastMoreable: String? = null
    private var lastWasChat = false
    private var repeating = false
    /** Уже прозвучавшие предложения помощи и время последнего — чтобы не навязываться. */
    private val shownSuggestions = LinkedHashSet<String>()
    private var lastSuggestionAt: java.time.Instant = java.time.Instant.EPOCH

    /** План голосом, который ещё не в календаре: «в календарь» добавит именно его. */
    private var pendingCalendar: PlanCalendar? = null
    private var pendingCalendarAt: java.time.Instant = java.time.Instant.EPOCH

    /** Откуда пришла текущая фраза: диктовка возможна только голосом. */
    @Volatile private var currentSource: InputSource = InputSource.TEXT

    suspend fun handle(
        input: String,
        source: InputSource = InputSource.TEXT,
        /** Куски ответа офлайн-модели по мере генерации: чтобы начать говорить, не дожидаясь всего текста. */
        onChunk: ((String) -> Unit)? = null,
    ): AssistantReply = mutex.withLock {
        currentSource = source
        chunkSink = onChunk
        val cfg = settings()
        val text = ai.nova.core.nlp.Slang.normalize(stripWakeWord(input, cfg.assistantName))
        if (text.isBlank()) return@withLock AssistantReply("Слушаю!", expectFollowUp = true, awaitingAnswer = true)
        context.touch()

        repeating = false
        val reply = try {
            decorate(process(text, cfg), cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Ошибка обработки команды", e)
            AssistantReply("Что-то пошло не так: ${e.message ?: "неизвестная ошибка"}. Попробуйте ещё раз.")
        }
        if (!repeating) {
            // Найденное (рецепт, статья) запоминаем: «запиши его в заметки» в следующей фразе.
            reply.entity?.let { runCatching { agent?.remember(it) } }
            record(text, reply.text, reply.sensitive)
            lastReply = reply; lastReplyAt = time.now()
            lastWasChat = reply.provider != null && reply.outcomes.isEmpty() && !reply.changedData
            if (MOREABLE.containsMatchIn(RuTokenizer.normalize(text))) lastMoreable = text
            else if (!MORE.containsMatchIn(RuTokenizer.normalize(text).trimEnd('?', '!', '.'))) lastMoreable = null
        }
        chunkSink = null
        continueDialog(reply, source, cfg)
    }

    /**
     * Голосовой разговор: при включённом диалоговом режиме ассистент продолжает слушать после любого ответа,
     * пока пользователь не скажет «хватит» или не замолчит. Без него — только если ждём ответа на вопрос.
     */
    private fun continueDialog(reply: AssistantReply, source: InputSource, cfg: AssistantSettings): AssistantReply {
        if (reply.endsDialog) {
            context.dialogMode = false
            context.appendMode = false
            return reply.copy(expectFollowUp = false)
        }
        if (source == InputSource.TEXT) return reply
        val waiting = reply.expectFollowUp || reply.awaitingAnswer || reply.awaitingConfirmation
        if (cfg.dialogMode) {
            context.dialogMode = true
        } else if (!waiting) {
            context.dialogMode = false
            context.appendMode = false
        }
        return reply.copy(expectFollowUp = cfg.dialogMode || waiting)
    }

    /**
     * Характер в ответе: шутливая иногда добавляет фразу к сделанному, деловая обходится без восклицаний,
     * заботливая — предлагает одно полезное действие по реальным данным дня (не чаще, чем раз в несколько минут).
     * Основная работа характера — в системном промпте; здесь только то, что модель не видит (данные приложения).
     */
    private suspend fun decorate(reply: AssistantReply, cfg: AssistantSettings): AssistantReply =
        agree(decoratePersona(reply, cfg), cfg)

    /**
     * Род ассистента: ответы написаны в женском роде, и для мужского имени («Джарвис», «Максим»)
     * «Записала» превращается в «Записал». Перевод и чужие цитаты не трогаем.
     */
    private fun agree(reply: AssistantReply, cfg: AssistantSettings): AssistantReply {
        if (reply.speakLanguage != null || reply.text.isEmpty()) return reply
        val gender = Gender.ofAssistant(cfg.assistantName)
        if (!gender.isMasculine) return reply
        return reply.copy(text = RuGender.masculine(reply.text))
    }

    private suspend fun decoratePersona(reply: AssistantReply, cfg: AssistantSettings): AssistantReply = when (cfg.persona) {
        Persona.PLAYFUL -> {
            val extra = if (reply.changedData && !reply.awaitingAnswer && !reply.awaitingConfirmation && !reply.sensitive) cfg.persona.flourish(time.now().epochSecond) else null
            if (extra != null) reply.copy(text = reply.text.trimEnd() + " " + extra) else reply
        }
        Persona.BUSINESS -> if (reply.speakLanguage == null) reply.copy(text = reply.text.replace("!", ".").replace("..", ".")) else reply
        Persona.CARING -> withSuggestion(reply)
    }

    /** Предложение помощи заботливой Новы — одно на ответ, с паузой и без повторов. */
    private suspend fun withSuggestion(reply: AssistantReply): AssistantReply {
        if (reply.sensitive || reply.awaitingAnswer || reply.awaitingConfirmation || reply.endsDialog) return reply
        val now = time.now()
        if (java.time.Duration.between(lastSuggestionAt, now) < SUGGESTION_COOLDOWN) return reply
        val data = runCatching { suggestionData() }.getOrNull() ?: return reply
        val suggestion = Suggestions.caring(data, shownSuggestions) ?: return reply
        shownSuggestions += suggestion.key
        while (shownSuggestions.size > 20) shownSuggestions.remove(shownSuggestions.first())
        lastSuggestionAt = now
        return reply.copy(text = reply.text.trimEnd() + " " + suggestion.text)
    }

    /** Что реально происходит у пользователя: задачи, ближайшее напоминание, траты. */
    private suspend fun suggestionData(): SuggestionData {
        val today = time.today()
        val nowTime = time.zonedNow().toLocalTime()
        val open = tasks.all().filter { !it.done }
        val reminder = reminders.active().minByOrNull { it.triggerAt }
        val minutes = reminder?.triggerAt?.let { java.time.Duration.between(time.now(), it).toMinutes() }
        val spentToday = expensesBetween(today, today).filter { it.amountMinor > 0 }.sumOf { it.amountMinor } / 100
        val week = expensesBetween(today.minusDays(6), today).filter { it.amountMinor > 0 }.sumOf { it.amountMinor } / 100
        return SuggestionData(
            tasksToday = open.count { it.dueDate == today },
            overdue = open.count { it.isOverdue(today, nowTime) },
            reminderInMinutes = minutes?.takeIf { it >= 0 },
            reminderText = reminder?.text,
            spentToday = spentToday,
            usualDaySpend = week / 7,
        )
    }

    /** «Будь деловой», «какой у тебя характер», а также приветствие и «как дела» в своём характере. */
    private suspend fun personaTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        Persona.switch(text)?.let { p ->
            runCatching { onPersona(p) }
            return AssistantReply(Persona.switched(p))
        }
        if (Persona.isAsk(text)) {
            return AssistantReply(
                "Сейчас я ${cfg.persona.title.lowercase()}: ${cfg.persona.hint}. Можно сменить: «будь заботливой», «будь деловой» или «будь шутливой».",
            )
        }
        return null
    }

    /**
     * «Начни разговор», «давай поговорим», «поболтаем» — приглашение к беседе.
     * Отвечаем мгновенно и переходим в диалог: раньше фраза уходила к модели, и на телефоне
     * человек ждал десятки секунд вместо ответа.
     */
    private fun dialogOpener(text: String, cfg: AssistantSettings): AssistantReply? {
        if (!ai.nova.core.ai.LocalChat.isDialogOpener(text)) return null
        context.dialogMode = true
        val hour = time.zonedNow().hour
        val text = when (cfg.persona) {
            Persona.BUSINESS -> "Слушаю. О чём поговорим?"
            Persona.PLAYFUL -> "Обожаю поболтать! О чём поговорим?"
            Persona.CARING -> when (hour) {
                in 5..11 -> "Доброе утро! Давайте поговорим — как вы?"
                in 12..17 -> "Добрый день! Давайте поговорим — как вы?"
                in 18..22 -> "Добрый вечер! Давайте поговорим — как прошёл день?"
                else -> "Давайте поговорим. Не поздно для вас?"
            }
        }
        return AssistantReply(text, expectFollowUp = true, awaitingAnswer = true)
    }

    private suspend fun smallTalk(text: String, cfg: AssistantSettings): AssistantReply? {
        val low = runCatching { habits?.moodLow() == true && !cfg.locked }.getOrDefault(false)
        return cfg.persona.smallTalk(text, time.zonedNow().hour, cfg.userName, low)?.let { AssistantReply(it) }
    }

    /** «У меня кот Барсик» — предлагаем запомнить, если такого в памяти ещё нет. */
    private suspend fun offerFact(text: String): AssistantReply? {
        val slot = FactOffer.offer(text) ?: return null
        val key = RuTokenizer.normalize(slot.content).trim()
        if (memories.all().any { RuTokenizer.normalize(it.content).trim() == key }) return null
        context.pendingSlot = slot
        return AssistantReply(slot.question, awaitingAnswer = true, expectFollowUp = true)
    }

    /** Голосовой разговор закончился (тишина, лимит, кнопка «стоп») — выходим из диалогового режима. */
    suspend fun endDialog() = mutex.withLock {
        context.dialogMode = false
        context.appendMode = false
        skills?.reset()
    }

    /** Экран выключили — забываем разговор: следующий человек с телефоном не продолжит его, а AI не увидит прошлого. */
    suspend fun forgetConversation() = mutex.withLock {
        context.reset()
        // Игру и «в каком городе?» не сбрасываем — экран гаснет посреди «Городов»; личное (ответ на сообщение) — забываем.
        skills?.forgetPrivate()
    }

    /** Понимает ли Нова фразу без AI — чтобы из нескольких вариантов распознавания выбрать осмысленный. */
    fun understandsLocally(text: String): Boolean = runCatching {
        val t = ai.nova.core.nlp.Slang.normalize(stripWakeWord(text, settings().assistantName))
        localParser.parse(t, time.now(), time.zone()) != null || skills?.recognizes(t, settings().assistantName) == true
    }.getOrDefault(false)

    /** Облачный AI для навыков (пересказ экрана, сказки); null — AI не подключён или недоступен. */
    private val skillAi: ai.nova.core.skills.SkillAi = { system, user, maxTokens ->
        val cloud = if (!settings().useAI) null else try {
            aiProvider()?.complete(AIRequest(system = system, messages = listOf(ChatMessage(ChatMessage.Role.USER, user)), jsonMode = false, maxTokens = maxTokens))
                ?.text?.trim()?.also { lastAiFailure = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AIException) {
            lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
            null
        }
        // Нет облачного AI — отвечает офлайн-модель (если скачана).
        cloud ?: localChat?.takeIf { it.available }?.let { lc ->
            runCatching { lc.reply(system, listOf(ChatMessage(ChatMessage.Role.USER, user.take(3000))), maxTokens.coerceAtMost(600)) }.getOrNull()
        }
    }

    /** Свободный вопрос без облачного AI — офлайн-модель. */
    private suspend fun localAnswer(text: String, cfg: AssistantSettings): AssistantReply? {
        val lc = localChat?.takeIf { it.available } ?: return null
        // Экран выключен и приложение не на виду — Android душит процесс, ответ модели идёт дольше минуты.
        // Честно говорим об этом вместо сорока секунд тишины.
        if (!lc.canAnswerNow) return AssistantReply(
            "Модель на телефоне без экрана отвечает слишком медленно. Включите экран или спросите в приложении — отвечу сразу.",
            offline = cfg.useAI,
        )
        val history = (if (cfg.locked) emptyList() else context.messages.takeLast(6)) + ChatMessage(ChatMessage.Role.USER, text)
        val out = try {
            lc.reply(
                ai.nova.core.ai.LocalChat.systemPrompt(cfg.assistantName, cfg.userName, cfg.persona.personality.skillStyle),
                history,
                onChunk = chunkSink,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Офлайн-модель не ответила", e)
            null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AssistantReply(out, offline = cfg.useAI, provider = "Офлайн-модель")
    }

    /**
     * Это реплика разговора, а не команда: вопрос, просьба к собеседнику, ответ в диалоге.
     * Нужно, чтобы «не поняла команду» от модели не съедало обычный разговор.
     */
    private fun isChatTurn(text: String, cfg: AssistantSettings): Boolean {
        if (ai.nova.core.ai.LocalChat.looksLikeChat(text)) return true
        if (context.dialogMode || lastWasChat) return true
        // Короткая реплика без команды: «всё хорошо», «спасибо», «скучно».
        return ai.nova.core.ai.LocalChat.isSmallTalk(text) && localParser.parse(text, time.now(), time.zone()) == null
    }

    /** Модель вместо ответа сказала, что не поняла фразу. */
    private fun looksLikeRefusal(reply: String): Boolean {
        if (reply.isBlank()) return true
        val r = RuTokenizer.normalize(reply).trim()
        return Regex(
            """^(?:не\s+понял\w*|не\s+понимаю|не\s+могу\s+(?:понять|ответить|выполнить)|не\s+удалось|извините[,\s]+не\s+понял\w*|я\s+не\s+понял\w*)""",
        ).containsMatchIn(r)
    }

    /** Ответ модели как собеседника — без инструкции про JSON и действия. */
    private suspend fun chatReply(provider: AIProvider, text: String, cfg: AssistantSettings): AssistantReply? {
        val system = ai.nova.core.ai.LocalChat.systemPrompt(cfg.assistantName, cfg.userName, cfg.persona.personality.skillStyle)
        val history = (if (cfg.locked) emptyList() else context.messages.takeLast(6)) + ChatMessage(ChatMessage.Role.USER, text)
        val answer = try {
            provider.complete(AIRequest(system = system, messages = history, maxTokens = 300)).text.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Разговорный ответ не получился", e)
            return null
        }
        if (answer.isEmpty() || looksLikeRefusal(answer)) return localAnswer(text, cfg)
        return AssistantReply(answer, usedAI = true)
    }

    /**
     * «В календарь» после созданной задачи или напоминания: событие уходит в календарь телефона.
     * Предложение живёт 10 минут — дальше фраза обрабатывается как обычная команда.
     */
    private suspend fun calendarTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val plan = pendingCalendar ?: return null
        if (java.time.Duration.between(pendingCalendarAt, time.now()).toMinutes() > 10) {
            pendingCalendar = null
            return null
        }
        val t = ai.nova.core.nlp.RuTokenizer.normalize(text)
        if (!IN_CALENDAR.containsMatchIn(t)) return null
        if (cfg.locked && cfg.lockPolicy?.edit == false) {
            return AssistantReply("Разблокируйте телефон — добавлять события в календарь без разблокировки не разрешено.")
        }
        pendingCalendar = null
        return execute(
            AssistantPlan("", listOf(AssistantAction.Device(DeviceCommand.CalendarEvent(plan.title, plan.at, allDay = plan.allDay)))),
            usedAI = false,
            offline = false,
            name = cfg.assistantName,
        )
    }

    /** Дневник дня: настроение, итоги, неделя. Личное — на заблокированном экране не показываем. */
    private suspend fun diaryTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val h = habits ?: return null
        val today = time.today()
        val zone = time.zone()
        // Задачи и траты считаем только для «итогов дня», а не на каждую фразу.
        val r = h.diary(
            text,
            tasksDone = { tasks.all().count { it.completedAt?.atZone(zone)?.toLocalDate() == today } },
            spend = { daySpend(today) },
            startsCommand = localParser.startsWithCommand(text),
        ) ?: return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.allowsSkill(SkillAccess.PRIVATE)) return AssistantReply("Разблокируйте телефон — дневник личный, без разблокировки не открываю.")
        return AssistantReply(r.text, changedData = r.changed, sensitive = true, expectFollowUp = r.text.endsWith("?") || r.text.contains("«нет»"))
    }

    /** Давление, вес, сон, показания счётчиков. Запись — как создание записей, просмотр — личное. */
    private suspend fun vitalsTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val v = habits?.vitals ?: return null
        // Сначала только разбор: на заблокированном экране не записываем и не показываем лишнего.
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        val r = v.handle(text) ?: return null
        r.monthlyMeterReminder?.let { day ->
            val rule = ai.nova.core.model.Recurrence(ai.nova.core.model.Recurrence.Frequency.MONTHLY, time = java.time.LocalTime.of(10, 0), dayOfMonth = day)
            val at = rule.nextAfter(time.now(), time.zone(), time.now())
            return execute(AssistantPlan("", listOf(AssistantAction.CreateReminder("Передать показания счётчиков", at, rule))), usedAI = false, offline = false, name = cfg.assistantName)
        }
        if (!r.write && policy != null && !policy.allowsSkill(SkillAccess.PRIVATE)) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не показываю.")
        return AssistantReply(r.text, changedData = r.changed, sensitive = !r.write)
    }

    /** Цели накоплений: «коплю на отпуск 100 тысяч», «отложила 5000», «сколько осталось до отпуска». */
    private suspend fun goalsTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val cmd = PersonalCommands.goal(text) ?: return null
        val book = ai.nova.core.personal.GoalBook(memories)
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        val writes = cmd is PersonalCommands.GoalCmd.Create || cmd is PersonalCommands.GoalCmd.Deposit
        if (policy != null && !(if (writes) policy.create else policy.view)) return AssistantReply("Разблокируйте телефон — накопления личные.")
        val g = ai.nova.core.personal.GoalBook
        return when (cmd) {
            is PersonalCommands.GoalCmd.Create -> {
                val goal = book.create(cmd.name, cmd.target)
                AssistantReply("Завела цель ${RuFormat.quote(goal.name)}: ${g.money(goal.target)}. Когда отложите, скажите: «отложила 5000 на ${goal.name.lowercase()}».", changedData = true)
            }
            is PersonalCommands.GoalCmd.Deposit -> {
                val all = book.all()
                val goal = cmd.name?.let { book.find(it) } ?: all.singleOrNull()
                    ?: return if (all.isEmpty()) null else AssistantReply(
                        if (cmd.name != null) "Цели ${RuFormat.quote(cmd.name)} нет. Есть: ${all.joinToString(", ") { it.name }}."
                        else "На какую цель? Скажите, например: «отложила ${ai.nova.core.nlp.Calculator.format(cmd.amount)} на ${all.first().name.lowercase()}».",
                    )
                val updated = book.add(goal, cmd.amount)
                AssistantReply("Отложила ${g.money(cmd.amount)}. " + g.describe(updated), changedData = true)
            }
            is PersonalCommands.GoalCmd.Withdraw -> {
                val goal = book.find(cmd.name) ?: return null
                val updated = book.add(goal, -cmd.amount)
                AssistantReply("Сняла ${g.money(cmd.amount)}. " + g.describe(updated), changedData = true)
            }
            is PersonalCommands.GoalCmd.Remove -> {
                val goal = book.find(cmd.name) ?: return AssistantReply("Цели ${RuFormat.quote(cmd.name)} нет.")
                book.remove(goal)
                AssistantReply("Убрала цель ${RuFormat.quote(goal.name)}.", changedData = true)
            }
            is PersonalCommands.GoalCmd.Query -> {
                val all = book.all()
                if (cmd.name != null) {
                    // «Сколько осталось до нового года» — не цель: отдаём фразу дальше.
                    val goal = book.find(cmd.name) ?: return null
                    AssistantReply(g.describe(goal), sensitive = true)
                } else AssistantReply(
                    if (all.isEmpty()) "Целей пока нет. Скажите, например: «коплю на отпуск 100 тысяч»."
                    else "Накопления:\n" + all.joinToString("\n") { "• " + g.describe(it) },
                    sensitive = true,
                )
            }
        }
    }

    /** «Где паспорт?» — по записям «положила паспорт в…». Неизвестную вещь отдаём дальше, если вопрос мог быть не о вещи. */
    private suspend fun whereTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val ask = PersonalCommands.where(text) ?: return null
        // Машину ищет «парковка» (по месту на карте), телефон — громкий сигнал, а не записи о вещах.
        if (Regex("""машин|авто|тачк|парковк|припарков|телефон|смартфон|мобильн|трубк""").containsMatchIn(RuTokenizer.normalize(text))) return null
        val hits = ai.nova.core.personal.ThingsBook(memories).find(ask.item)
        if (hits.isEmpty() && !ask.strong) return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.view) return AssistantReply("Разблокируйте телефон — где лежат вещи, без разблокировки не говорю.")
        // Раньше могли сказать «запомни, что паспорт в сейфе» — ищем и в памяти.
        val said = if (hits.isNotEmpty()) null else runCatching { search.search(ask.item, setOf(RecordType.MEMORY), limit = 1, minScore = 0.4) }
            .getOrDefault(emptyList()).firstOrNull()?.doc?.title
        return AssistantReply(
            when {
                said != null -> "Вы говорили: ${RuFormat.quote(said)}."
                hits.isEmpty() -> "Не знаю, где ${ask.item}. Когда положите, скажите: «положила ${ask.item} в …» — запомню."
                hits.size == 1 -> "${hits[0].item} — ${hits[0].place}."
                else -> hits.joinToString("\n") { "• ${it.item} — ${it.place}" }
            },
            sensitive = true,
        )
    }

    /** «Итоги недели», «что я делала 5 октября». Личное — на заблокированном экране не показываем. */
    private suspend fun reviewTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val ask = ai.nova.core.review.Review.parse(text, time.today()) ?: return null
        // «Что было завтра» не бывает: про будущее — план на день.
        if (ask is ai.nova.core.review.Review.Ask.Day && ask.date.isAfter(time.today())) return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.allowsSkill(SkillAccess.PRIVATE)) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не показываю.")
        val out = when (ask) {
            ai.nova.core.review.Review.Ask.Week -> review.week()
            is ai.nova.core.review.Review.Ask.Day -> review.day(ask.date)
        }
        return AssistantReply(out, sensitive = true)
    }

    /** «Что я говорила про ремонт» — поиск по истории разговора. Личное. */
    private suspend fun chatSearchTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val ask = ai.nova.core.review.ChatSearch.parse(text, time.today()) ?: return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.allowsSkill(SkillAccess.PRIVATE)) return AssistantReply("Разблокируйте телефон — разговоры личные.")
        return AssistantReply(ai.nova.core.review.ChatSearch(conversations, time).search(ask), sensitive = true)
    }

    private val cards = ai.nova.core.skills.Flashcards(memories)
    private var quiz: ai.nova.core.skills.QuizSession? = null
    private var quizAt = time.now()

    /** Карточки: добавить, сколько, проверка с повторением по Лейтнеру. */
    private suspend fun cardsTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        if (quiz != null && java.time.Duration.between(quizAt, time.now()) > java.time.Duration.ofMinutes(20)) quiz = null
        quiz?.let { q ->
            val card = q.current ?: run { quiz = null; return null }
            val n = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?')
            if (Regex("""^(?:хватит|стоп|закончим|закончили|достаточно|всё|все)$""").matches(n)) {
                quiz = null
                return AssistantReply("Закончили. " + q.score(), endsDialog = true)
            }
            // Новая команда посреди проверки — проверку оставляем, фразу отдаём дальше.
            val skip = Regex("""^(?:не\s+знаю|не\s+помню|сдаюсь|пропусти|подскажи|забыл[аи]?|дальше)$""").matches(n)
            if (!skip && (localParser.startsWithCommand(text) && text.trim().split(Regex("\\s+")).size > 3)) return null
            val ok = !skip && ai.nova.core.skills.Flashcards.correct(text, card.back)
            cards.grade(card, ok, time.today())
            q.answer(ok)
            quizAt = time.now()
            val verdict = if (ok) listOf("Верно!", "Правильно!", "Точно!")[q.index % 3] else "Правильно: ${RuFormat.quote(card.back)}."
            if (q.finished) { quiz = null; return AssistantReply("$verdict ${q.score()}", changedData = true, endsDialog = true) }
            return AssistantReply("$verdict ${q.question()}", changedData = true, expectFollowUp = true, awaitingAnswer = true)
        }
        val cmd = ai.nova.core.skills.Flashcards.parse(text) ?: return null
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !(if (cmd is ai.nova.core.skills.Flashcards.Command.Add) policy.create else policy.view)) return AssistantReply("Разблокируйте телефон, чтобы заниматься с карточками.")
        return when (cmd) {
            is ai.nova.core.skills.Flashcards.Command.Add -> {
                val replaced = cards.add(cmd.front, cmd.back, time.today())
                AssistantReply("${if (replaced) "Обновила" else "Добавила"} карточку: ${cmd.front} — ${cmd.back}. Проверить — «проверь меня по словам».", changedData = true)
            }
            ai.nova.core.skills.Flashcards.Command.Count -> {
                val all = cards.all()
                val due = all.count { !it.due.isAfter(time.today()) }
                val learned = all.count { it.box >= 4 }
                AssistantReply(if (all.isEmpty()) "Карточек пока нет. Скажите, например: «запомни слово apple — яблоко»." else "Карточек: ${all.size}, на сегодня к повторению: $due, выучено: $learned.")
            }
            is ai.nova.core.skills.Flashcards.Command.Remove -> {
                val n = cards.remove(cmd.front)
                AssistantReply(if (n == 0) "Не нашла карточку ${RuFormat.quote(cmd.front)}." else "Убрала карточку ${RuFormat.quote(cmd.front)}.", changedData = n > 0)
            }
            ai.nova.core.skills.Flashcards.Command.Quiz -> {
                val list = cards.dueToday(time.today())
                if (list.isEmpty()) return AssistantReply("Карточек пока нет. Скажите, например: «запомни слово apple — яблоко».")
                val q = ai.nova.core.skills.QuizSession(list)
                quiz = q; quizAt = time.now(); workout = null; cook = null
                AssistantReply("Поехали! Отвечайте; «не знаю» — подскажу, «хватит» — закончим. ${q.question()}", expectFollowUp = true, awaitingAnswer = true)
            }
        }
    }

    private var workout: ai.nova.core.skills.WorkoutSession? = null
    private var workoutAt = time.now()

    /** Тренировка: упражнения по очереди с таймером. Забывается через полчаса тишины. */
    private suspend fun workoutTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        if (workout != null && java.time.Duration.between(workoutAt, time.now()) > java.time.Duration.ofMinutes(30)) workout = null
        val session = workout
        if (session == null) {
            if (ai.nova.core.skills.Workout.isList(text)) return AssistantReply(ai.nova.core.skills.Workout.listText())
            val program = ai.nova.core.skills.Workout.start(text) ?: return null
            val s = ai.nova.core.skills.WorkoutSession(program)
            workout = s; workoutAt = time.now(); cook = null
            return exerciseReply(s.intro(), s, cfg)
        }
        val cmd = ai.nova.core.skills.Workout.command(text) ?: return null
        workoutAt = time.now()
        return when (cmd) {
            ai.nova.core.skills.Workout.Command.Stop -> {
                workout = null
                AssistantReply(finishWorkout(session, early = true), endsDialog = true, changedData = session.done > 0)
            }
            ai.nova.core.skills.Workout.Command.Repeat -> exerciseReply(session.step(), session, cfg)
            ai.nova.core.skills.Workout.Command.Next, ai.nova.core.skills.Workout.Command.Skip -> {
                session.advance(skipped = cmd == ai.nova.core.skills.Workout.Command.Skip)
                if (session.finished) {
                    workout = null
                    AssistantReply(finishWorkout(session, early = false), endsDialog = true, changedData = session.done > 0)
                } else exerciseReply(session.step(), session, cfg)
            }
        }
    }

    /** Текст упражнения + таймер на него (сигнал скажет, когда пора дальше). */
    private suspend fun exerciseReply(text: String, s: ai.nova.core.skills.WorkoutSession, cfg: AssistantSettings): AssistantReply {
        val e = s.current ?: return AssistantReply(text, expectFollowUp = true)
        val timer = AssistantAction.Device(DeviceCommand.Timer(e.seconds, "${s.program.name}: ${e.name.lowercase()}"))
        val r = execute(AssistantPlan("", listOf(timer), preface = text), usedAI = false, offline = false, name = cfg.assistantName)
        // Таймер не поставился (нет разрешения) — ведём без него.
        return r.copy(text = if (r.outcomes.any { it.kind == Outcome.Kind.ERROR }) text + " Засеките время сами, потом скажите «дальше»." else text, expectFollowUp = true)
    }

    /** Конец тренировки: отмечаем привычку («зарядка»), чтобы считались дни подряд. */
    private suspend fun finishWorkout(s: ai.nova.core.skills.WorkoutSession, early: Boolean): String {
        if (s.done == 0) return "Хорошо, без тренировки. В другой раз!"
        val streak = runCatching { habits?.run(ai.nova.core.health.HabitCommand.Mark(s.program.habit))?.text }.getOrNull()
        val head = if (early) "Закончили: ${RuFormat.count(s.done, "упражнение", "упражнения", "упражнений")} из ${s.program.exercises.size}." else "Тренировка окончена: ${s.program.name.lowercase()}, ${RuFormat.count(s.done, "упражнение", "упражнения", "упражнений")}. Молодец!"
        return head + (streak?.let { " $it" } ?: "")
    }

    private var cook: ai.nova.core.skills.CookingSession? = null
    /** Идущая готовка в базе — чтобы «продолжим» работало после перезапуска приложения. */
    private var cookRecord: ai.nova.core.data.CookingSessionRecord? = null
    private var cookAskedAt: java.time.Instant? = null
    /** Ждём текст идеи после «Нова, у меня идея». */
    private var ideaAskedAt: java.time.Instant? = null
    private var cookAt = time.now()
    /** Рецепт показан и предложен («добавить в покупки и провести по шагам?») — ждём ответ. */
    private var recipeOffer: ai.nova.core.skills.KitchenRecipe? = null
    private var recipeOfferAt = time.now()

    /** Готовка забывается после часа молчания. */
    private suspend fun cookingTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val active = cook
        if (active != null && java.time.Duration.between(cookAt, time.now()) > java.time.Duration.ofMinutes(60)) cook = null
        val session = cook
        if (session == null) {
            // Ответ на «Добавить всё в покупки и провести по шагам?»
            val offer = recipeOffer?.takeIf { java.time.Duration.between(recipeOfferAt, time.now()) <= java.time.Duration.ofMinutes(30) }
            if (recipeOffer != null && offer == null) recipeOffer = null
            if (offer != null) {
                when {
                    ai.nova.core.skills.Cuisine.wantsShopping(text) -> { recipeOffer = null; return offerDish(offer, cfg, shopping = true, steps = false) }
                    ai.nova.core.skills.Cuisine.wantsSteps(text) -> { recipeOffer = null; return offerDish(offer, cfg, shopping = false, steps = true) }
                    ai.nova.core.skills.Cuisine.agrees(text) -> { recipeOffer = null; return offerDish(offer, cfg, shopping = true, steps = true) }
                    LocalCommandParser.isNo(text) -> {
                        recipeOffer = null
                        return AssistantReply("Хорошо. Скажите «помоги приготовить ${offer.title}», когда будете готовы.")
                    }
                }
            }
            // «Пересчитай на 6 порций» — к показанному рецепту.
            ai.nova.core.skills.Cuisine.servingsRequest(text)?.let { servings ->
                val target = recipeOffer ?: rememberedRecipe()?.takeIf { it.servings > 0 }
                if (target != null) return recalcRecipe(target, servings, cfg)
            }
            // «Продолжим готовить» после перезапуска приложения: поднимаем сохранённую сессию.
            val continues = ai.nova.core.skills.Cuisine.wantsSteps(text) || CONTINUE_COOKING.containsMatchIn(text)
            val namesDish = ai.nova.core.skills.Cuisine.ask(text) != null ||
                !ai.nova.core.skills.Cooking.startDish(text).isNullOrBlank()
            if (continues && !namesDish) restoreCooking()?.let { return it }
            // «Как приготовить плов», «что нужно для теста на хлеб», «из чего готовят борщ»
            ai.nova.core.skills.Cuisine.ask(text)?.let { ask ->
                val found = findRecipe(ask.dish)
                if (found != null) return recipeCard(found, cfg)
                if (ask.strong) {
                    return AssistantReply(
                        "Рецепта «${ask.dish}» нет ни в заметках, ни в моей книге рецептов. Скажите «найди рецепт ${ask.dish}» — поищу в интернете, " +
                            "или продиктуйте его заметкой: «запиши заметку рецепт ${ask.dish}: ингредиенты — …, сначала …, потом …».",
                    )
                }
            }
            // Спросили «Что готовим?» — следующая короткая фраза и есть название блюда.
            val asked = cookAskedAt?.let { java.time.Duration.between(it, time.now()) <= java.time.Duration.ofMinutes(3) } == true
            cookAskedAt = null
            val dish = ai.nova.core.skills.Cooking.startDish(text)
                ?: (if (asked && !localParser.startsWithCommand(text) && text.trim().split(Regex("\\s+")).size <= 4 && !LocalCommandParser.isNo(text)) text.trim().trimEnd('.', '!', '?') else null)
                ?: return null
            val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
            if (policy != null && !policy.view) return AssistantReply("Разблокируйте телефон — рецепты без разблокировки не открываю.")
            if (dish.isEmpty()) { cookAskedAt = time.now(); return AssistantReply("Что готовим? Скажите название блюда — рецепт должен быть в заметках.", awaitingAnswer = true) }
            val recipe = findRecipe(dish)
                ?: return AssistantReply("Рецепта «$dish» нет ни в заметках, ни в моей книге рецептов. Скажите «найди рецепт $dish» — поищу в интернете, или продиктуйте его заметкой.")
            if (recipe.steps.isEmpty()) return AssistantReply("Для «$dish» я нашла ингредиенты, но без шагов. Продиктуйте шаги заметкой — тогда проведу по ним.")
            val s = ai.nova.core.skills.CookingSession(recipe.toRecipe())
            cook = s; cookAt = time.now()
            saveCooking(recipe, s, ai.nova.core.data.CookingState.WAITING_FOR_USER)
            return AssistantReply(s.intro(), expectFollowUp = true)
        }
        // «Пересчитай на 2 порции» прямо во время готовки: количества новые, шаги те же.
        ai.nova.core.skills.Cuisine.servingsRequest(text)?.let { servings ->
            val current = rememberedRecipe() ?: return@let
            val scaled = current.scaled(servings)
            rememberRecipe(scaled)
            val moved = ai.nova.core.skills.CookingSession(scaled.toRecipe(), startIndex = session.index)
            cook = moved; cookAt = time.now()
            saveCooking(scaled, moved, ai.nova.core.data.CookingState.WAITING_FOR_USER)
            return AssistantReply(
                "Пересчитала на ${RuFormat.count(servings, "порцию", "порции", "порций")}. Продолжаем. " +
                    moved.current() + " Скажите «дальше», когда сделаете.",
                expectFollowUp = true,
            )
        }
        val cmd = ai.nova.core.skills.Cooking.command(text) ?: return null
        cookAt = time.now()
        return when (cmd) {
            ai.nova.core.skills.CookingCommand.Stop -> {
                finishCooking(ai.nova.core.data.CookingState.CANCELLED)
                cook = null
                AssistantReply("Хорошо, закончили готовить.", endsDialog = true)
            }
            ai.nova.core.skills.CookingCommand.Next -> AssistantReply(session.next(), expectFollowUp = !session.finished, endsDialog = session.finished).also {
                if (session.finished) finishCooking(ai.nova.core.data.CookingState.COMPLETED) else touchCooking(session)
                if (session.finished) cook = null
            }
            ai.nova.core.skills.CookingCommand.Repeat -> AssistantReply(if (session.finished) "Блюдо готово!" else session.current(), expectFollowUp = true)
            ai.nova.core.skills.CookingCommand.Back -> AssistantReply(session.back(), expectFollowUp = true).also { touchCooking(session) }
            ai.nova.core.skills.CookingCommand.Ingredients -> AssistantReply(session.ingredients(), expectFollowUp = true)
            is ai.nova.core.skills.CookingCommand.Go -> AssistantReply(session.go(cmd.step), expectFollowUp = true).also { touchCooking(session) }
            ai.nova.core.skills.CookingCommand.Timer -> {
                val sec = session.timerSeconds() ?: return AssistantReply("В этом шаге времени нет. Скажите, например: «таймер на 10 минут».", expectFollowUp = true)
                execute(AssistantPlan("", listOf(AssistantAction.Device(DeviceCommand.Timer(sec, session.recipe.title)))), usedAI = false, offline = false, name = cfg.assistantName)
                    .copy(expectFollowUp = true)
            }
        }
    }

    /** Рецепт блюда: заметки → своя книга рецептов → интернет (Википедия, Викиучебник). */
    private suspend fun findRecipe(dish: String): ai.nova.core.skills.KitchenRecipe? {
        val name = dish.trim()
        if (name.isEmpty()) return null
        ai.nova.core.skills.Cooking.find(notes.all(), name)?.let { note ->
            val r = ai.nova.core.skills.Cooking.parse(note)
            if (r.steps.isNotEmpty()) {
                val ings = r.ingredients?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                return rememberRecipe(ai.nova.core.skills.KitchenRecipe.of(r.title, ings, r.steps, source = "заметка"))
            }
        }
        ai.nova.core.skills.Cuisine.book(name)?.let { return rememberRecipe(it) }
        // Уже находили раньше — берём из базы, не ходим в интернет повторно.
        runCatching { cooking?.recipeByTitle(name) }.getOrNull()?.let { return it }
        return skills?.recipe(name)?.let { rememberRecipe(it) }
    }

    /** Рецепт сохраняем: пересчёт порций и «продолжим готовить» работают без повторного поиска. */
    private suspend fun rememberRecipe(recipe: ai.nova.core.skills.KitchenRecipe): ai.nova.core.skills.KitchenRecipe {
        runCatching { cooking?.saveRecipe(recipe) }
        return recipe
    }

    /** Последний показанный рецепт: из предложения или из сохранённой сессии. */
    private suspend fun rememberedRecipe(): ai.nova.core.skills.KitchenRecipe? {
        recipeOffer?.let { return it }
        val record = cookRecord ?: runCatching { cooking?.activeSession() }.getOrNull() ?: return null
        return runCatching { cooking?.recipe(record.recipeId) }.getOrNull()
    }

    /** «Пересчитай на 6 порций»: количества умножаются, рецепт заменяется пересчитанным. */
    private suspend fun recalcRecipe(
        recipe: ai.nova.core.skills.KitchenRecipe,
        servings: Int,
        cfg: AssistantSettings,
    ): AssistantReply {
        val scaled = recipe.scaled(servings)
        rememberRecipe(scaled)
        recipeOffer = scaled; recipeOfferAt = time.now()
        val porций = RuFormat.count(servings, "порцию", "порции", "порций")
        val list = if (scaled.ingredients.isEmpty()) "" else " Вам понадобится: " + scaled.ingredientTexts.joinToString(", ") + "."
        return AssistantReply(
            "Пересчитала на $porций. ${scaled.title.replaceFirstChar { it.uppercase() }}.$list " +
                "Добавить в покупки и провести по шагам?",
            awaitingAnswer = true, expectFollowUp = true,
            entity = ai.nova.core.agent.AgentEntity.recipe(scaled.title, scaled.ingredientTexts, scaled.steps),
        )
    }

    /** Сохранить начало готовки: после перезапуска «продолжим» поднимет этот шаг. */
    private suspend fun saveCooking(
        recipe: ai.nova.core.skills.KitchenRecipe,
        session: ai.nova.core.skills.CookingSession,
        state: ai.nova.core.data.CookingState,
    ) {
        val store = cooking ?: return
        val recipeId = runCatching { store.saveRecipe(recipe) }.getOrNull() ?: return
        val record = ai.nova.core.data.CookingSessionRecord(
            id = cookRecord?.id ?: ai.nova.core.util.Ids.newId(),
            recipeId = recipeId,
            title = recipe.title,
            stepIndex = session.index,
            state = state,
            updatedAt = time.now().toEpochMilli(),
        )
        cookRecord = record
        runCatching { store.saveSession(record) }
    }

    /** Шаг сменился — запоминаем, на чём остановились. */
    private suspend fun touchCooking(session: ai.nova.core.skills.CookingSession) {
        val record = cookRecord ?: return
        val updated = record.copy(
            stepIndex = session.index,
            state = ai.nova.core.data.CookingState.WAITING_FOR_USER,
            updatedAt = time.now().toEpochMilli(),
        )
        cookRecord = updated
        runCatching { cooking?.saveSession(updated) }
    }

    private suspend fun finishCooking(state: ai.nova.core.data.CookingState) {
        val record = cookRecord ?: return
        cookRecord = null
        runCatching { cooking?.closeSession(record.id, state) }
    }

    /** «Продолжим готовить» после перезапуска: поднимаем рецепт и шаг из базы. */
    private suspend fun restoreCooking(): AssistantReply? {
        val store = cooking ?: return null
        val record = cookRecord ?: runCatching { store.activeSession() }.getOrNull() ?: return null
        if (!record.state.active) return null
        // Брошенная вчера готовка не должна перехватывать «дальше» в других темах.
        val age = time.now().toEpochMilli() - record.updatedAt
        if (age > COOKING_RESUME_TTL) {
            runCatching { store.closeSession(record.id, ai.nova.core.data.CookingState.CANCELLED) }
            return null
        }
        val recipe = runCatching { store.recipe(record.recipeId) }.getOrNull() ?: return null
        if (recipe.steps.isEmpty()) return null
        val session = ai.nova.core.skills.CookingSession(recipe.toRecipe(), startIndex = record.stepIndex)
        cook = session; cookAt = time.now(); cookRecord = record
        recipeOffer = null
        return AssistantReply("Продолжаем: ${recipe.title}. ${session.current()}", expectFollowUp = true)
    }

    /** «Вам понадобится …» и предложение: добавить в покупки и провести по шагам. */
    /**
     * «Нова, у меня идея» — сама фраза идеи не названа, поэтому спрашиваем и записываем следующую.
     * Без этого команда уходила в «не поняла»: разбор идеи требовал текст сразу.
     */
    private suspend fun ideaTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val asked = ideaAskedAt?.let { java.time.Duration.between(it, time.now()) <= java.time.Duration.ofMinutes(3) } == true
        val bare = BARE_IDEA.matches(RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?', ','))
        if (!bare) {
            if (!asked) return null
            // Вопрос или команда — это не идея: уходим в обычную обработку.
            if (text.trim().endsWith("?") || localParser.startsWithCommand(text) || NOT_IDEA.containsMatchIn(text.trim()) ||
                ai.nova.core.skills.Cuisine.ask(text) != null
            ) { ideaAskedAt = null; return null }
            val idea = text.trim().trim('«', '»', '"', '.', ',').replaceFirstChar { it.uppercase() }
            if (idea.length < 3) return null
            ideaAskedAt = null
            val plan = AssistantPlan("", listOf(AssistantAction.CreateNote(NoteKind.IDEA, idea.take(120), if (idea.length > 120) idea else "")))
            return execute(plan, usedAI = false, offline = false, name = cfg.assistantName)
        }
        ideaAskedAt = time.now()
        return AssistantReply(
            "Записываю. Расскажите идею одной фразой — например «у меня идея: приложение для холодильника».",
            awaitingAnswer = true, expectFollowUp = true,
        )
    }

    private suspend fun recipeCard(recipe: ai.nova.core.skills.KitchenRecipe, cfg: AssistantSettings): AssistantReply {
        recipeOffer = recipe; recipeOfferAt = time.now()
        rememberRecipe(recipe)
        val title = recipe.title.replaceFirstChar { it.uppercase() }
        val list = if (recipe.ingredients.isEmpty()) "" else " Вам понадобится: " + recipe.ingredientTexts.joinToString(", ") + "."
        val steps = RuFormat.count(recipe.steps.size, "шаг", "шага", "шагов")
        val portions = " На ${RuFormat.count(recipe.servings, "порцию", "порции", "порций")}."
        return AssistantReply(
            "$title.$list$portions Шагов: $steps. Добавить всё в список покупок и провести вас по шагам? " +
                "Скажите «на 6 порций» — пересчитаю.",
            awaitingAnswer = true, expectFollowUp = true,
            entity = ai.nova.core.agent.AgentEntity.recipe(recipe.title, recipe.ingredientTexts, recipe.steps),
        )
    }

    /** Ответ на предложение рецепта: покупки и (или) пошаговая готовка. */
    private suspend fun offerDish(
        recipe: ai.nova.core.skills.KitchenRecipe,
        cfg: AssistantSettings,
        shopping: Boolean,
        steps: Boolean,
    ): AssistantReply {
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        if (policy != null && !policy.view) return AssistantReply("Разблокируйте телефон — рецепты без разблокировки не открываю.")
        val parts = ArrayList<String>()
        var changed = false
        if (shopping && recipe.ingredients.isNotEmpty()) {
            // Количества уходят в список покупок вместе с ингредиентами: «рис — 500 г».
            val added = execute(
                AssistantPlan(
                    "",
                    listOf(AssistantAction.AddToList(ai.nova.core.model.ShoppingItem.DEFAULT_LIST, recipe.ingredientTexts, source = recipe.title)),
                ),
                usedAI = false, offline = false, name = cfg.assistantName,
            )
            parts += added.text
            changed = added.changedData
        }
        if (!steps) {
            parts += if (recipe.ingredients.isEmpty()) "Для «${recipe.title}» отдельного списка ингредиентов нет."
            else "Ингредиенты: " + recipe.ingredientTexts.joinToString(", ") + "."
            return AssistantReply(
                parts.joinToString(" ") + " Скажите «помоги приготовить ${recipe.title}», когда начнёте.",
                expectFollowUp = true, changedData = changed,
            )
        }
        val session = ai.nova.core.skills.CookingSession(recipe.toRecipe())
        cook = session; cookAt = time.now()
        saveCooking(recipe, session, ai.nova.core.data.CookingState.WAITING_FOR_USER)
        // Ингредиенты уже назвали — не повторяем их в шагах.
        val total = RuFormat.count(recipe.steps.size, "шаг", "шага", "шагов")
        val timer = session.timerSeconds()?.let { " Скажите «таймер» — поставлю на ${DevicePhrases.describeDuration(it)}." }.orEmpty()
        parts += "Готовим: ${recipe.title}. Всего $total. Говорите «дальше», «повтори» или «назад». Шаг 1. ${recipe.steps.first()}$timer"
        return AssistantReply(parts.joinToString(" "), expectFollowUp = true, changedData = changed)
    }

    // ---------------------------------------------------------------- Составные фразы

    /** Защита от повторного входа: шаги задачи обрабатываются обычным путём, без новых планов. */
    private var insideAgent = false

    /**
     * Несколько намерений в одной фразе: «найди рецепт борща и запиши его в заметки»,
     * «как приготовить хлеб и добавь продукты в покупки».
     *
     * Намерения выполняются по шагам, найденное («это», «его») подставляется из контекста,
     * ход задачи сохраняется в БД. Если фраза не составная или контекста не хватает —
     * возвращаем null и работаем как раньше.
     */
    private suspend fun agentTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        val core = agent ?: return null
        if (insideAgent) return null
        // Ждём короткого ответа («да/нет», «какую запись?», название блюда) — это не составная команда.
        if (context.pendingConfirmation != null || context.pendingChoice != null || context.pendingSlot != null) return null
        val plan = core.plan(text) ?: return null
        val known = core.lastEntity()
        // «его/её» разрешимо, если сущность уже есть или её найдёт предыдущий шаг этой же фразы.
        val resolvable = plan.intents.withIndex().all { (i, intent) ->
            !intent.needsEntity || known != null ||
                plan.intents.take(i).any { core.registry.byId(it.skill)?.producesEntity != null }
        }
        if (!resolvable) return null
        val clauses = plan.intents.map { it.text }
        if (clauses.size < 2) return null
        val task = core.beginTask(plan, plan.intents.map { it.skill })
        val parts = ArrayList<String>()
        var changed = false
        var awaiting = false
        var followUp = false
        var sensitive = false
        var ends = false
        var permission: ai.nova.core.skills.Permission? = null
        var found = known
        insideAgent = true
        try {
            for ((i, original) in clauses.withIndex()) {
                val intent = plan.intents[i]
                // Ссылку «запиши его в заметки» превращаем в конкретную команду по найденному рецепту,
                // а «найди рецепт борща» — в вопрос «дай рецепт борща», который понимает кухня.
                val clause = when {
                    intent.needsEntity -> found?.let { rewriteIntent(intent, it) } ?: original
                    intent.skill == ai.nova.core.agent.SkillRegistry.RECIPE -> canonicalRecipe(original)
                    else -> original
                }
                val started = time.now().toEpochMilli()
                val step = try {
                    process(clause, cfg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Шаг задачи не выполнен", e)
                    AssistantReply("Не получилось: ${e.message ?: "ошибка"}. Скажите ещё раз.")
                }
                val spent = time.now().toEpochMilli() - started
                core.context.recordStep(
                    task, i, intent.skill, clause,
                    ai.nova.core.agent.ToolResult(step.text.isNotBlank(), step.text), spent,
                )
                if (step.text.isNotBlank()) parts += step.text.trim()
                changed = changed || step.changedData || step.outcomes.isNotEmpty()
                sensitive = sensitive || step.sensitive
                permission = step.permission ?: permission
                if (i == clauses.lastIndex) {
                    awaiting = step.awaitingAnswer || step.awaitingConfirmation
                    followUp = step.expectFollowUp
                    ends = step.endsDialog
                }
                step.entity?.let { found = it }
            }
            val waiting = awaiting || followUp
            core.context.transition(task, if (waiting) ai.nova.core.agent.TaskState.WAITING_FOR_USER else ai.nova.core.agent.TaskState.COMPLETED, clauses.size - 1)
            if (!waiting) core.context.close()
        } finally {
            insideAgent = false
        }
        found?.let { runCatching { core.remember(it) } }
        return AssistantReply(
            parts.joinToString(" "), changedData = changed, awaitingAnswer = awaiting, expectFollowUp = followUp || awaiting,
            sensitive = sensitive, endsDialog = ends, permission = permission, entity = found,
        )
    }

    /** «найди рецепт борща» → «дай рецепт борща»: так фразу понимает кухня, а не поиск по записям. */
    private fun canonicalRecipe(text: String): String = FIND_RECIPE.replace(text.trim()) { "дай рецепт " + it.groupValues[1] }

    private val FIND_RECIPE = Regex(
        """^(?:нова[,!]?\s*)?(?:найди|поищи|покажи|подбери|подскажи|дай)\s+(?:мне\s+)?рецепт\w*\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * «запиши его в заметки» по найденному рецепту: собираем обычную команду, которую понимает
     * локальный разбор, — так работает тот же проверенный путь создания заметки.
     */
    private fun rewriteIntent(intent: ai.nova.core.agent.AgentIntent, entity: ai.nova.core.agent.AgentEntity): String? {
        val recipe = entity.kind == ai.nova.core.agent.AgentEntity.RECIPE
        val (ingredients, steps) = if (recipe) ai.nova.core.agent.AgentEntity.decodeRecipe(entity.payload) else emptyList<String>() to emptyList()
        val title = entity.title.replaceFirstChar { it.uppercase() }
        return when {
            recipe && intent.skill == "note.save" -> {
                val body = (ingredients + steps.mapIndexed { i, s -> "${i + 1}) $s" }).joinToString("; ")
                if (body.isBlank()) null else "запиши рецепт $title: $body"
            }
            recipe && intent.skill == "shopping.add" && ingredients.isNotEmpty() -> "добавь в покупки " + ingredients.joinToString(", ")
            recipe && intent.skill == "cooking.steps" -> "помоги приготовить " + entity.title
            entity.kind == ai.nova.core.agent.AgentEntity.ARTICLE && intent.skill == "note.save" && entity.payload.isNotBlank() ->
                "запиши $title: " + entity.payload.take(400)
            else -> null
        }
    }

    private suspend fun habitReply(text: String, cfg: AssistantSettings): AssistantReply? {
        val h = habits ?: return null
        val cmd = h.parse(text) ?: return null
        // Здоровье — личное: на экране блокировки только дыхание/медитация и отметки (если разрешено создавать записи).
        val policy = cfg.lockPolicy ?: if (cfg.locked) LockPolicy.SAFE else null
        val access = when (cmd) {
            is ai.nova.core.health.HabitCommand.Relax -> SkillAccess.PUBLIC
            is ai.nova.core.health.HabitCommand.EventSave -> SkillAccess.CREATE
            is ai.nova.core.health.HabitCommand.Countdown -> SkillAccess.VIEW
            is ai.nova.core.health.HabitCommand.Water, is ai.nova.core.health.HabitCommand.Pill, is ai.nova.core.health.HabitCommand.Mark -> SkillAccess.CREATE
            else -> SkillAccess.PRIVATE
        }
        if (policy != null && !policy.allowsSkill(access)) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не показываю.")
        val r = h.run(cmd) ?: return null
        if (r.device != null) {
            // Во время упражнения микрофон не слушает: иначе он услышит подсказки «вдох», «выдох» как команды.
            val relaxing = r.device is DeviceCommand.Relax && r.device.kind != RelaxKind.STOP
            return execute(AssistantPlan("", listOf(AssistantAction.Device(r.device)), preface = r.text), usedAI = false, offline = false, name = cfg.assistantName)
                .copy(sensitive = false, endsDialog = relaxing)
        }
        return AssistantReply(r.text, changedData = r.changed, sensitive = r.private)
    }

    private suspend fun dialogTurn(text: String, cfg: AssistantSettings): AssistantReply? {
        if (context.pendingConfirmation != null || context.pendingChoice != null || context.pendingSlot != null) return null
        val n = RuTokenizer.normalize(text).trim().trimEnd('?', '!', '.', ',')
        val fresh = java.time.Duration.between(lastReplyAt, time.now()) <= DIALOG_TTL
        if (fresh && REPEAT.containsMatchIn(n)) lastReply?.let { r ->
            if (cfg.locked && r.sensitive) return AssistantReply("Разблокируйте телефон — это личное, без разблокировки не повторяю.")
            repeating = true
            return AssistantReply(r.text, sensitive = r.sensitive, speakLanguage = r.speakLanguage, provider = r.provider)
        }
        skills?.let { sk ->
            skillReply(sk.more(text, cfg, skillAi), cfg)?.let { return it }
            skillReply(sk.followUp(text, cfg, skillAi), cfg)?.let { return it }
        }
        if (fresh && MORE.containsMatchIn(n)) lastMoreable?.let { return process(it, cfg) }
        // «Отмени последнее» сразу после «выпила стакан воды» — отменяем отметку.
        if (UNDO.containsMatchIn(n)) habits?.undoRecent()?.let { return AssistantReply(it.text, changedData = true, sensitive = true) }
        // «Продолжай» после ответа модели — продолжение рассказа, а не музыка.
        if (fresh && lastWasChat && CONTINUE.containsMatchIn(n)) {
            if (!cfg.useAI) localAnswer("Продолжай.", cfg)?.let { return it }
        }
        return null
    }

    /** Фраза — команда (расход, задача, телефон, список)? Тогда она прерывает игру или ожидание навыка. */
    private fun isCommand(text: String): Boolean = runCatching {
        localParser.parse(text, time.now(), time.zone())?.actions?.isNotEmpty() == true || special.parse(text, time.today()) != null
    }.getOrDefault(false)

    private suspend fun runSkills(text: String, cfg: AssistantSettings): AssistantReply? = skillReply(skills?.handle(text, cfg, skillAi), cfg)

    private suspend fun skillReply(out: ai.nova.core.skills.SkillOutcome?, cfg: AssistantSettings): AssistantReply? =
        when (out) {
            is ai.nova.core.skills.SkillOutcome.Say -> AssistantReply(
                out.text, expectFollowUp = out.followUp, awaitingAnswer = out.followUp, permission = out.permission, speakLanguage = out.speakLanguage,
                sensitive = out.sensitive,
            )
            is ai.nova.core.skills.SkillOutcome.Run -> execute(out.plan, usedAI = false, offline = false, name = cfg.assistantName)
            null -> null
        }

    /** Подтверждение/отмена кнопкой в UI. */
    suspend fun respondToConfirmation(confirm: Boolean): AssistantReply = handle(if (confirm) "да" else "нет")

    private suspend fun process(text: String, cfg: AssistantSettings): AssistantReply {
        // «Работать без разблокировки» выключено — на заблокированном экране Нова ничего не выполняет
        // (в том числе по слову-активатору) и ничего не отправляет AI.
        if (cfg.locked && cfg.lockPolicy?.any == false) {
            return AssistantReply("Разблокируйте телефон — без разблокировки я не отвечаю. Это можно разрешить в настройках Нова.")
        }
        // 0. Ждём ответ «YouTube, VK Видео или Rutube?» — выбор запоминается, поиск идёт сразу.
        if (executor.awaitingVideo()) {
            executor.answerVideo(text)?.let { result ->
                return AssistantReply(result.outcomes.joinToString("\n") { it.text }, outcomes = result.outcomes)
            }
        }
        // 0. Идёт игра или навык ждёт ответа («В каком городе?») — фраза для него, если это не новая команда.
        var skillsTried = false
        if (skills?.busy == true) {
            if (skills.interruptedBy(text, cfg, ::isCommand)) skills.reset()
            else { skillsTried = true; runSkills(text, cfg)?.let { return it } }
        }
        // 0.3. Составная фраза: «найди рецепт борща и запиши его в заметки».
        agentTurn(text, cfg)?.let { return it }
        // 0.4. Тренировка и режим готовки: «дальше», «повтори», «назад», «таймер».
        cardsTurn(text, cfg)?.let { return it }
        workoutTurn(text, cfg)?.let { return it }
        cookingTurn(text, cfg)?.let { return it }
        ideaTurn(text, cfg)?.let { return it }
        reviewTurn(text, cfg)?.let { return it }
        chatSearchTurn(text, cfg)?.let { return it }
        diaryTurn(text, cfg)?.let { return it }
        calendarTurn(text, cfg)?.let { return it }
        // 0.5. Живой диалог: «повтори», «ещё», «а завтра?», «продолжай».
        dialogTurn(text, cfg)?.let { return it }
        // 1. Ожидаем подтверждение опасного действия.
        context.pendingConfirmation?.let { pending ->
            when {
                LocalCommandParser.isYes(text) -> {
                    context.pendingConfirmation = null
                    // Пока ждали «да», телефон могли заблокировать: удаление — это изменение записей.
                    if (cfg.lockPolicy?.edit == false) return AssistantReply("Разблокируйте телефон — удалять записи без разблокировки не разрешено.")
                    val result = executor.applyConfirmed(pending, context)
                    return AssistantReply(result.outcomes.joinToString(" ") { it.text }, result.outcomes, changedData = true)
                }
                LocalCommandParser.isNo(text) -> {
                    context.pendingConfirmation = null
                    context.pendingChoice = null
                    return AssistantReply("Хорошо, ничего не удаляю.")
                }
                context.pendingChoice != null -> Unit // сначала ответ на «какую запись?», подтверждение ждёт
                else -> context.pendingConfirmation = null // новая команда отменяет ожидание
            }
        }
        // 2. Ожидаем выбор варианта.
        context.pendingChoice?.let { choice ->
            context.pendingChoice = null
            if (LocalCommandParser.isNo(text)) { context.pendingConfirmation = null; return AssistantReply("Хорошо, отменила.") }
            // Ответ на «какую?» — короткая фраза без новой команды: «вторую», «про склад».
            val wordCount = text.trim().split(Regex("\\s+")).size
            val looksLikeCommand = localParser.startsWithCommand(text)
            val index = if (looksLikeCommand) null else {
                (if (wordCount <= 3) LocalCommandParser.ordinal(text, choice.options.size) else null)
                    ?: (if (wordCount <= 6) matchOptionByTitle(text, choice.options) else null)
            }
            if (index != null) {
                val chosen = choice.options[index]
                context.touchRecord(chosen)
                val kept = context.pendingConfirmation
                val actions = listOf(withTarget(choice.action, chosen.id)) + choice.remaining
                return execute(AssistantPlan("", actions), usedAI = false, offline = false, carriedConfirmation = kept, name = cfg.assistantName, dialogAllowed = cfg.dialogMode)
            }
            context.pendingConfirmation = null
        }
        // 3. Дозаполнение недостающих данных («Сколько потратили?» → «500»).
        context.pendingSlot?.let { slot ->
            context.pendingSlot = null
            if (LocalCommandParser.isNo(text) && slot !is SlotRequest.SaveAsNote && slot !is SlotRequest.RememberFact) return AssistantReply("Хорошо, отменила.")
            localParser.fillSlot(slot, text, time.now(), time.zone())?.let { filled ->
                return execute(filled, usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // Уточнение к вопросу о расходах: «а на транспорт?»
        context.lastExpenseQuery?.let { prevQuery ->
            localParser.followUpExpenseQuery(text, prevQuery, time.now(), time.zone())?.let { q ->
                return execute(AssistantPlan("", listOf(q)), usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // 4. Завершение диалогового режима.
        if ((context.dialogMode || context.appendMode) && LocalCommandParser.isDialogEnd(text)) {
            return AssistantReply("Хорошо! Если что — зовите.", endsDialog = true)
        }
        // 4. Сценарии, перевод, списки, дни рождения, секретные заметки — точные команды, выполняются на устройстве.
        runRoutine(text, cfg)?.let { return it }
        if (currentSource != InputSource.TEXT && SpecialCommands.isDictationStart(text)) {
            return AssistantReply("Диктуйте — я записываю. Можно делать паузы. Когда закончите, скажите «готово».", dictation = true, expectFollowUp = false)
        }
        special.translation(text)?.let { return translate(it, cfg) }
        personaTurn(text, cfg)?.let { return it }
        whereTurn(text, cfg)?.let { return it }
        vitalsTurn(text, cfg)?.let { return it }
        goalsTurn(text, cfg)?.let { return it }
        special.parse(text, time.today())?.let { return execute(it, usedAI = false, offline = false, name = cfg.assistantName) }
        habitReply(text, cfg)?.let { return it }
        // Погода, курсы, новости, справка, сообщения, радио, игры, сказки…
        if (!skillsTried) runSkills(text, cfg)?.let { return it }
        special.boughtItems(text)?.let { bought ->
            val open = shoppingItems().filter { !it.done }
            val matched = bought.filter { b ->
                val stems = ai.nova.core.nlp.TextAnalysis.stems(b).toSet()
                open.any { i -> i.text.equals(b, true) || ai.nova.core.nlp.TextAnalysis.stems(i.text).any { it in stems } }
            }
            if (matched.isNotEmpty()) {
                // Каждую покупку отмечаем в том списке, где она есть.
                val actions = matched.map { b ->
                    val stems = ai.nova.core.nlp.TextAnalysis.stems(b).toSet()
                    val item = open.firstOrNull { i -> i.text.equals(b, true) } ?: open.first { i -> ai.nova.core.nlp.TextAnalysis.stems(i.text).any { it in stems } }
                    AssistantAction.CheckListItem(item.listName, b)
                }
                return execute(AssistantPlan("", actions), usedAI = false, offline = false, name = cfg.assistantName)
            }
        }
        // Приветствие, «как дела», «спасибо» — в своём характере.
        smallTalk(text, cfg)?.let { return it }
        // «Начни разговор» — сразу отвечаем и продолжаем слушать, не дожидаясь модели.
        dialogOpener(text, cfg)?.let { return it }
        // 5. Облачный AI → при недоступности офлайн-парсер.
        var aiError: AIException? = null
        // Облако только что не ответило (сеть, лимит, сервер лежит): не ждём тот же таймаут ещё раз —
        // отвечаем на телефоне сразу. Через несколько минут снова пробуем облако.
        val cloudCooldown = lastAiFailure?.let { java.time.Duration.between(it.at, time.now()).toMillis() < AI_COOLDOWN_MS } == true
        if (cfg.useAI && cloudCooldown) {
            Logger.w(TAG, "Облачный AI недавно не ответил — отвечаю на устройстве")
            aiError = AIException.Network(null)
        }
        if (cfg.useAI && !cloudCooldown) {
            val provider = try { aiProvider() } catch (e: AIException) { aiError = e; null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logger.w(TAG, "AI-провайдер не создан: ${e::class.simpleName}"); aiError = AIException.Network(e); null }
            if (provider != null) {
                try {
                    val plan = guardDeviceActions(planWithAI(provider, text, cfg), text)
                    lastAiFailure = null
                    val used = (provider as? ChainAIProvider)?.lastUsed ?: provider
                    // Разговор, а не команда: модель ответила «не поняла команду» (или ничего не ответила).
                    // Спрашиваем её же как собеседника, без инструкции про JSON — на этом «Начни разговор» не ломался.
                    if (plan.actions.isEmpty() && plan.slot == null && isChatTurn(text, cfg) && looksLikeRefusal(plan.reply)) {
                        chatReply(provider, text, cfg)?.let { answer ->
                            return answer.copy(usedAI = true, provider = "${used.type.title} · ${used.model}")
                        }
                    }
                    return execute(plan, usedAI = true, offline = false, name = cfg.assistantName)
                        .copy(provider = "${used.type.title} · ${used.model}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AIException) {
                    Logger.w(TAG, "AI недоступен: ${e::class.simpleName}")
                    aiError = e
                    lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
                } catch (e: Exception) {
                    // Любой другой сбой AI-ветки (разбор ответа, сеть под капотом) не должен ломать команду: работаем на устройстве.
                    Logger.w(TAG, "AI-ветка не сработала: ${e::class.simpleName}")
                    aiError = AIException.Network(e)
                    lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
                }
            }
        }
        // «Посоветуй, чем заняться в выходные» — просьба, а не задача на выходные.
        if (ai.nova.core.ai.LocalChat.isStrongChat(text)) localAnswer(text, cfg)?.let { return it }
        // «Мой размер обуви 38» — факт о себе, а не расход на 38 ₽: спрашиваем раньше разбора команд.
        offerFact(text)?.let { return it }
        val parsed = localParser.parse(text, time.now(), time.zone())
        // Вопрос или просьба, которую не понял разбор команд, — отвечает офлайн-модель, а не «сохранить заметкой?».
        if (parsed == null && ai.nova.core.ai.LocalChat.looksLikeChat(text)) localAnswer(text, cfg)?.let { return it }
        val local = parsed ?: localFallback(text, cfg)
        if (local != null) {
            val reply = execute(local, usedAI = false, offline = cfg.useAI, name = cfg.assistantName)
            // «Как приготовить плов» — не поиск по записям: если в записях пусто, отвечает офлайн-модель.
            // Вопрос о себе («что я люблю…», «где мой паспорт») модели не отдаём — она не знает ваших записей.
            if (local.actions.isNotEmpty() && local.actions.all { it is AssistantAction.Search } && reply.text.startsWith("Ничего не нашла") &&
                ai.nova.core.ai.LocalChat.looksLikeChat(text) && !Regex("""(?:^|\s)(?:я|мой|моя|моё|мое|мои|моего|моей|моих)(?:\s|$)""").containsMatchIn(text.lowercase())
            ) localAnswer(text, cfg)?.let { return it }
            val note = when (aiError) {
                is AIException.Unauthorized -> " (AI: ключ не принят — выполнено на устройстве)"
                else -> ""
            }
            // Разбор команд не узнал фразу — записываем её для «Непонятных фраз» (даже если ответила модель).
            if (parsed == null) runCatching { onNotUnderstood(text) }
            return reply.copy(text = reply.text + note, aiError = aiError?.let { shortReason(it) })
        }
        localAnswer(text, cfg)?.let { return it }
        if (parsed != null) runCatching { onNotUnderstood(text) }
        val reason = when {
            !cfg.useAI -> "Не совсем поняла."
            aiError is AIException.NotConfigured || aiError == null -> "Не поняла, а облачный AI не настроен."
            else -> "Не поняла, а облачный AI недоступен: ${aiError?.message}"
        }
        return AssistantReply(
            "$reason Попробуйте сказать иначе, например: «расход 500 рублей на продукты», «добавь задачу…», " +
                "«напомни завтра в 10 утра…», «что у меня на сегодня». Скажите «что ты умеешь» — расскажу подробнее.",
            offline = cfg.useAI,
        )
    }

    /** Сохраняет надиктованный текст заметкой: расставляет точки, заглавные буквы и абзацы. */
    suspend fun saveDictation(raw: String): AssistantReply = mutex.withLock {
        val text = ai.nova.core.nlp.DictationText.format(SpecialCommands.cleanDictation(raw))
        if (text.isBlank()) return@withLock AssistantReply("Ничего не услышала — заметку не сохраняю.")
        val title = ai.nova.core.nlp.DictationText.title(text)
        val note = notes.create(ai.nova.core.model.NoteKind.NOTE, title, text)
        context.touchRecord(RecordRef(RecordType.NOTE, note.id, note.title))
        runCatching { conversations.add(context.conversationId, MessageRole.USER, text) }
        val words = text.split(Regex("""\s+""")).size
        AssistantReply("Сохранила заметку ${RuFormat.quote(note.title)} — ${RuFormat.count(words, "слово", "слова", "слов")}.", changedData = true)
    }

    /** Фраза совпала со сценарием («спокойной ночи») — выполняем его команды по очереди. */
    private var routineDepth = 0

    private suspend fun runRoutine(text: String, cfg: AssistantSettings): AssistantReply? {
        // Сценарий внутри сценария не запускается — иначе фраза, ссылающаяся на себя, зациклится.
        if (routineDepth > 0) return null
        val list = runCatching { routines() }.getOrDefault(emptyList())
        if (list.isEmpty()) return null
        val key = ai.nova.core.model.Routine.normalize(text)
            .replace(Regex("""^(?:запусти|включи|выполни)\s+(?:сценарий\s+)?"""), "")
        val routine = list.firstOrNull { ai.nova.core.model.Routine.normalize(it.trigger) == key } ?: return null
        return runCommands(routine, cfg)
    }

    /**
     * Сработало напоминание «Сценарий: по будням в 7:30» — выполняем сценарий сами. Телефон в это время обычно
     * заблокирован, но сценарий создан владельцем на разблокированном телефоне, поэтому выполняется без ограничений блокировки.
     * null — такого сценария уже нет.
     */
    suspend fun runScheduledRoutine(reminderText: String): AssistantReply? = mutex.withLock {
        if (!reminderText.startsWith(ai.nova.core.model.Routine.SCHEDULE_PREFIX)) return@withLock null
        val key = ai.nova.core.model.Routine.normalize(reminderText.removePrefix(ai.nova.core.model.Routine.SCHEDULE_PREFIX))
        val routine = runCatching { routines() }.getOrDefault(emptyList()).firstOrNull { ai.nova.core.model.Routine.normalize(it.trigger) == key }
            ?: return@withLock null
        val cfg = settings().copy(locked = false, lockPolicy = null)
        executor.trusted = true
        try {
            runCommands(routine, cfg).also { context.dialogMode = false; context.appendMode = false }
        } finally {
            executor.trusted = false
        }
    }

    /**
     * Сводка по расписанию (утро, вечер, итоги недели, сценарий): полный текст пишется в историю общения.
     * Уведомление обрезает длинный текст, а история — нет, поэтому сводку всегда можно перечитать целиком;
     * заодно она становится последним ответом, и «повтори» прочитает её ещё раз.
     */
    suspend fun recordScheduledReport(text: String): String {
        val full = text.trim()
        if (full.isEmpty()) return full
        runCatching { conversations.add(context.conversationId, MessageRole.ASSISTANT, full) }
            .onFailure { Logger.w(TAG, "Не удалось сохранить сводку в историю", it) }
        lastReply = AssistantReply(full)
        lastReplyAt = time.now()
        return full
    }

    private suspend fun runCommands(routine: ai.nova.core.model.Routine, cfg: AssistantSettings): AssistantReply {
        val replies = ArrayList<String>()
        var changed = false
        for (cmd in routine.commands.take(10)) {
            routineDepth++
            val r = try {
                process(cmd, cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantReply("Не получилось: $cmd")
            } finally {
                routineDepth--
            }
            // Сценарий не ведёт диалог: вопросы и подтверждения внутри него не ждём.
            context.pendingConfirmation = null; context.pendingChoice = null; context.pendingSlot = null
            if (r.text.isNotBlank()) replies += r.text
            changed = changed || r.changedData
        }
        return AssistantReply("Сценарий ${RuFormat.quote(routine.trigger)}:\n" + replies.joinToString("\n") { "• $it" }, changedData = changed)
    }

    /** Перевод: с AI — любой текст, без AI — небольшой словарь частых фраз. */
    private suspend fun translate(t: SpecialCommands.Translation, cfg: AssistantSettings): AssistantReply {
        val offline = SpecialCommands.OFFLINE[t.language]?.get(ai.nova.core.nlp.RuTokenizer.normalize(t.phrase).trim(' ', ',', '.', '!', '?'))
        if (cfg.useAI) {
            val provider = runCatching { aiProvider() }.getOrNull()
            if (provider != null) {
                try {
                    val out = provider.complete(
                        AIRequest(
                            system = "Ты переводчик. Переведи текст пользователя на ${t.languageRu} язык. Ответь только переводом — без кавычек, пояснений и транслитерации.",
                            messages = listOf(ChatMessage(ChatMessage.Role.USER, t.phrase.take(2000))),
                            jsonMode = false,
                            maxTokens = 600,
                        ),
                    ).text.trim().trim('"', '«', '»')
                    lastAiFailure = null
                    if (out.isNotEmpty()) return AssistantReply(out, usedAI = true, speakLanguage = t.language)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AIException) {
                    lastAiFailure = AiFailure(e.message ?: e::class.simpleName.orEmpty(), time.now())
                }
            }
        }
        if (offline != null) return AssistantReply(offline, speakLanguage = t.language, offline = cfg.useAI)
        return AssistantReply("Чтобы переводить любые фразы, подключите облачный AI в настройках. Без интернета я знаю только самые частые: «привет», «спасибо», «как дела»…")
    }

    /** Короткая причина сбоя AI для подписи под ответом. */
    private fun shortReason(e: AIException): String = when (e) {
        is AIException.Unauthorized -> "ключ не принят"
        is AIException.RateLimited -> "превышен лимит запросов"
        is AIException.Network -> "нет связи с сервисом"
        is AIException.NotConfigured -> "не настроен"
        is AIException.InsecureEndpoint -> "небезопасный адрес сервера"
        is AIException.Refused -> "модель отказалась отвечать"
        is AIException.Server -> "ошибка сервиса ${e.code}"
        is AIException.InvalidResponse -> "непонятный ответ модели"
    }

    /**
     * Защита от «инъекций»: в запрос к AI попадают тексты ваших заметок и память. Если в них окажется чужая
     * инструкция («позвони на номер…», «отправь…»), модель может её выполнить. Поэтому звонки, сообщения,
     * отправка, открытие сайтов и контакты от AI выполняются, только если вы сами об этом попросили в этой фразе.
     */
    private fun guardDeviceActions(plan: AssistantPlan, userText: String): AssistantPlan {
        val rejected = ArrayList(plan.rejected)
        val n = ai.nova.core.nlp.RuTokenizer.normalize(userText)
        // Записи уходят из приложения, только если вы сами попросили отправить: подсказка из заметки не считается.
        val wantsShare = Regex("""отправ|перешл|подел|скинь|пошли""").containsMatchIn(n)
        val kept = plan.actions.filter { a ->
            if (a is AssistantAction.ShareNote && !wantsShare) {
                rejected += "AI предложил отправить запись, о которой вы не просили — не выполняю"
                return@filter false
            }
            val cmd = (a as? AssistantAction.Device)?.command ?: return@filter true
            val ok = DeviceGuard.allowed(cmd, userText)
            if (!ok) rejected += "AI предложил действие, о котором вы не просили — не выполняю"
            ok
        }
        return if (kept.size == plan.actions.size) plan else plan.copy(actions = kept, rejected = rejected)
    }

    private suspend fun planWithAI(provider: AIProvider, text: String, cfg: AssistantSettings): AssistantPlan {
        // На экране блокировки модель не видит личных данных — ответ не сможет их раскрыть.
        val candidates = if (cfg.locked) emptyList() else gatherCandidates(text)
        val handles = candidates.associate { it.handle to it.id }
        val memoryItems = if (cfg.locked) emptyList() else memories.all().take(25)
        val system = PromptBuilder.build(
            cfg.assistantName, time.now(), time.zone(), memoryItems, candidates, context.focus, context.topic, context.dialogMode,
            userName = cfg.userName, persona = cfg.persona,
        )
        // На блокировке прошлый разговор модели не показываем: в нём могли быть личные данные.
        val history = if (cfg.locked) emptyList() else context.messages
        val request = AIRequest(system = system, messages = history + ChatMessage(ChatMessage.Role.USER, text))
        val response = provider.complete(request)
        val plan = ActionParser(time.now(), time.zone(), handles).parse(response.text)
        plan.topic?.let { context.topic = it }
        if (plan.rejected.isNotEmpty()) Logger.w(TAG, "Отклонено действий: ${plan.rejected.size}")
        return plan
    }

    /** Записи, на которые AI может сослаться: найденные по смыслу + фокус + недавние + активные задачи/напоминания. */
    private suspend fun gatherCandidates(text: String): List<Candidate> {
        val seen = LinkedHashMap<String, Candidate>()
        fun add(type: RecordType, id: String, title: String, snippet: String, extra: String = "") {
            if (seen.containsKey(id) || seen.size >= MAX_CANDIDATES) return
            seen[id] = Candidate("#${seen.size + 1}", type, id, title, snippet, extra)
        }
        context.focus?.let { f -> executorLookup(f)?.let { add(it.type, it.id, it.title, it.snippet) } }
        val query = listOfNotNull(text, context.topic).joinToString(" ")
        runCatching { search.search(query, limit = 8, minScore = 0.25) }.getOrDefault(emptyList())
            .forEach { add(it.doc.type, it.doc.id, it.doc.title, it.doc.body) }
        context.recent.forEach { r -> executorLookup(r)?.let { add(it.type, it.id, it.title, it.snippet) } }
        notes.all().take(5).forEach { add(if (it.kind == NoteKind.IDEA) RecordType.IDEA else RecordType.NOTE, it.id, it.title, it.content) }
        tasks.all().filter { !it.done }.take(8).forEach { add(RecordType.TASK, it.id, it.title, it.details, it.dueDate?.let { d -> "срок $d" } ?: "") }
        reminders.active().take(6).forEach { add(RecordType.REMINDER, it.id, it.text, "", "сработает ${it.triggerAt.atZone(time.zone()).toLocalDateTime()}") }
        return seen.values.toList()
    }

    private suspend fun executorLookup(ref: RecordRef): Candidate? = when (ref.type) {
        RecordType.NOTE, RecordType.IDEA -> notes.get(ref.id)?.let { Candidate("", ref.type, it.id, it.title, it.content) }
        RecordType.TASK -> tasks.get(ref.id)?.let { Candidate("", ref.type, it.id, it.title, it.details) }
        RecordType.REMINDER -> reminders.get(ref.id)?.let { Candidate("", ref.type, it.id, it.text, "") }
        RecordType.MEMORY -> memories.get(ref.id)?.let { Candidate("", ref.type, it.id, it.content, "") }
        RecordType.EXPENSE -> null
    }

    private suspend fun execute(
        plan: AssistantPlan,
        usedAI: Boolean,
        offline: Boolean,
        carriedConfirmation: PendingConfirmation? = null,
        name: String = "Нова",
        dialogAllowed: Boolean = settings().dialogMode,
    ): AssistantReply {
        val executed = executor.execute(plan.actions, context)
        // Подтверждение, заданное до уточнения, не теряется — объединяем с новыми.
        val result = if (carriedConfirmation == null) executed else executed.copy(
            pendingConfirmation = executed.pendingConfirmation?.let {
                PendingConfirmation(carriedConfirmation.question + " " + it.question, carriedConfirmation.operations + it.operations)
            } ?: carriedConfirmation,
        )
        context.pendingConfirmation = result.pendingConfirmation
        context.pendingChoice = result.pendingChoice
        val changed = result.outcomes.any { it.kind == Outcome.Kind.CHANGED }
        val onlyMutations = result.outcomes.all { it.kind == Outcome.Kind.CHANGED }
        val parts = ArrayList<String>()
        when {
            plan.actions.isEmpty() -> parts += plan.reply.ifBlank { "Не совсем поняла. Повторите, пожалуйста, иначе." }
            // Для изменений AI-ответ звучит естественнее, но только если всё действительно выполнено.
            onlyMutations && plan.reply.isNotBlank() && !result.hasErrors && result.outcomes.isNotEmpty() -> parts += plan.reply
            else -> parts += result.outcomes.map { it.text }
        }
        result.pendingConfirmation?.let { parts += it.question }
        if (plan.rejected.isNotEmpty()) parts += "Часть команды не выполнила: ${plan.rejected.joinToString("; ")}."
        if (plan.preface.isNotBlank()) parts.add(0, plan.preface)
        // Недостающие данные спрашиваем после выполненного (если уточнений по записям не требуется).
        plan.slot?.takeIf { result.pendingChoice == null }?.let { slot ->
            context.pendingSlot = slot
            if (plan.actions.isEmpty()) parts.clear()
            parts += slot.question
        }
        val awaitingAnswer = result.pendingChoice != null || plan.slot != null || result.outcomes.any { it.kind == Outcome.Kind.QUESTION }
        if (plan.expectFollowUp && dialogAllowed) {
            context.dialogMode = true
            context.appendMode = true
        }
        // В диалоге продолжаем слушать, пока пользователь не скажет «хватит» (или не замолчит).
        val followUp = plan.expectFollowUp || context.dialogMode || awaitingAnswer || result.pendingConfirmation != null
        // Запись голосовой заметки займёт микрофон: разговор на этом заканчиваем.
        val recording = plan.actions.any { (it as? AssistantAction.Device)?.command == DeviceCommand.VoiceMemo }
        val reply = AssistantReply(
            endsDialog = recording,
            text = parts.filter { it.isNotBlank() }.joinToString("\n").trim().replace("{name}", name),
            outcomes = result.outcomes,
            awaitingConfirmation = result.pendingConfirmation != null,
            awaitingAnswer = awaitingAnswer,
            expectFollowUp = followUp,
            usedAI = usedAI,
            offline = offline,
            changedData = changed,
        )
        // Журнал: только реально выполненные действия (не уточнения и не разговор).
        if (plan.actions.isNotEmpty() && result.outcomes.any { it.kind != Outcome.Kind.ERROR }) {
            runCatching { onExecuted(plan.actions, reply) }
        }
        return reply
    }

    /**
     * Фраза не распознана локальными правилами:
     *  - в диалоге о записи (мозговой штурм) — дописываем сказанное в неё;
     *  - повествовательная фраза — предлагаем сохранить заметкой (не теряем мысль);
     *  - иначе — null (подсказка, что умею).
     */
    private fun localFallback(text: String, cfg: AssistantSettings): AssistantPlan? {
        val focus = context.focus
        if (context.appendMode && focus != null && (focus.type == RecordType.NOTE || focus.type == RecordType.IDEA)) {
            return AssistantPlan(
                "", listOf(AssistantAction.AppendNote(TargetRef(focus.id, null, setOf(focus.type)), text.trim().replaceFirstChar { it.uppercase() })),
                expectFollowUp = true,
            )
        }
        val words = text.trim().split(Regex("\\s+")).size
        val question = text.trim().endsWith("?")
        if (!cfg.useAI && words >= 3 && !question) {
            return AssistantPlan("", emptyList(), slot = SlotRequest.SaveAsNote(text.trim(), "Не совсем поняла команду. Сохранить это как заметку?"))
        }
        return null
    }

    private fun matchOptionByTitle(text: String, options: List<RecordRef>): Int? {
        val q = TextAnalysis.stems(text)
        if (q.isEmpty()) return null
        val scored = options.mapIndexed { i, o ->
            val t = TextAnalysis.stems(o.title)
            i to q.count { s -> t.any { TextAnalysis.stemSimilarity(s, it) >= 0.7 } }.toDouble() / q.size
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        return if (best.second >= 0.5 && (second == null || best.second > second.second)) best.first else null
    }

    private fun withTarget(action: AssistantAction, id: String): AssistantAction = when (action) {
        is AssistantAction.AppendNote -> action.copy(target = action.target.withId(id), splitQueryFromContent = false)
        is AssistantAction.UpdateNote -> action.copy(target = action.target.withId(id))
        is AssistantAction.DeleteNote -> action.copy(target = action.target.withId(id))
        is AssistantAction.ShareNote -> action.copy(target = action.target.withId(id), last = null)
        is AssistantAction.CompleteTask -> action.copy(target = action.target.withId(id))
        is AssistantAction.DeleteTask -> action.copy(target = action.target.withId(id))
        is AssistantAction.CancelReminder -> action.copy(target = action.target.withId(id))
        is AssistantAction.RescheduleReminder -> action.copy(target = action.target.withId(id))
        is AssistantAction.RescheduleTask -> action.copy(target = action.target.withId(id))
        is AssistantAction.ForgetMemory -> action.copy(target = action.target.withId(id))
        else -> action
    }

    private suspend fun record(userText: String, reply: String, sensitive: Boolean = false) {
        // Личное (сообщения, экран, контакты) остаётся в истории на телефоне, но не уходит AI в следующих запросах.
        context.addTurn(userText, if (sensitive) "(личные данные — показаны пользователю, не пересказываю)" else reply)
        runCatching {
            conversations.add(context.conversationId, MessageRole.USER, userText)
            if (reply.isNotBlank()) conversations.add(context.conversationId, MessageRole.ASSISTANT, reply)
        }.onFailure { Logger.w(TAG, "Не удалось сохранить историю", it) }
    }

    companion object {
        private const val TAG = "Assistant"
        private val DIALOG_TTL: java.time.Duration = java.time.Duration.ofMinutes(3)
        private val REPEAT = Regex("""^(?:повтори|повтори пожалуйста|повтори еще раз|повтори ещё раз|повтори последнее|повтори ответ|что ты сказала|что ты сказал|что ты говоришь|что ты там сказала|еще раз|ещё раз|скажи еще раз|скажи ещё раз|не расслышал|не расслышала|я не расслышал|я не расслышала|не поняла повтори|не понял повтори|что-что|что что|чего|а)$""")
        private val MORE = Regex("""^(?:а\s+)?(?:давай\s+)?(?:ещ[её]|еще)(?:\s+(?:одну|один|одно|разок|пожалуйста|давай|анекдот|шутку|факт|комплимент|цитату|тост|скороговорку|загадку))?$""")
        private val MOREABLE = Regex("""анекдот|шутк|пошути|смешное|факт|комплимент|цитат|тост|скороговорк|стих|монетк|кубик|случайное число|совет дня|мотивац""")
        private val UNDO = Regex("""^(?:отмени|удали|убери)\s+(?:последнее|это|то что (?:я )?(?:только что )?(?:сказала|сказал|добавила|добавил))$|^(?:отмени|отмена последнего)$""")
        private val CONTINUE = Regex("""^(?:продолжай|продолжи|дальше|и что дальше|а дальше|что было дальше|рассказывай дальше|продолжай рассказ|и\?)$""")
        private const val MAX_CANDIDATES = 20
        /** Заботливая Нова предлагает помощь не чаще, чем раз в столько времени. */
        private val SUGGESTION_COOLDOWN: java.time.Duration = java.time.Duration.ofMinutes(4)
        /** «В календарь» — согласие на событие в календаре телефона после созданного плана. */
        private val IN_CALENDAR = Regex("""(?:(?:добавь|добавить|внеси|запиши|записать|поставь|помести|сохрани|кинь)\s+(?:это\s+|его\s+|ее\s+|её\s+|план\s+)?)?(?:в|во)\s+календарь(?![а-яе])""")
        /** Сколько времени после последнего шага готовку можно продолжить («продолжим готовить»). */
        private const val COOKING_RESUME_TTL = 6 * 60 * 60 * 1000L
        /** С таких слов начинается вопрос или команда, а не идея. */
        private val NOT_IDEA = Regex(
            """^(?:как(?:ая|ой|ие|их|ое|им)?|что|кто|где|когда|сколько|почему|зачем|какой|чем|чего|найди|найти|включи|выключи|поставь|напомни|запиши|добавь|открой|позвони|напиши|удали|покажи|расскажи|переведи|посчитай|приготовь|сделай|давай|расход|потратил)""",
            RegexOption.IGNORE_CASE,
        )
        /** «У меня идея» без текста — спросим, что за идея. */
        private val BARE_IDEA = Regex(
            """^(?:нова[,!]?\s*)?(?:у\s+меня\s+|есть\s+|появилась\s+|новая\s+|возникла\s+|родилась\s+)?(?:идея|мысль|задумка|идейка)$""",
        )
        /** Фразы, которыми просят продолжить прерванную готовку. */
        private val CONTINUE_COOKING = Regex(
            """^(?:продолжим|продолжаем|продолжи|дальше|далее|что\s+дальше|на\s+ч[её]м\s+(?:мы\s+)?остановились|где\s+мы\s+остановились|верн[её]мся\s+к\s+готовке|продолжим\s+готовить|продолжаем\s+готовить)(?:\s|$)""",
        )

        /** Убирает обращение «Нова, …» из начала текстовой команды. */
        fun stripWakeWord(input: String, name: String): String {
            val trimmed = misheardName(input.trim(), name)
            WakeWordMatcher(name, threshold = 0.85).match(trimmed)?.let { return it.command }
            // Распознаватель пишет имя по-разному («Ноль», «Нава»). Мягче сравниваем, если дальше идёт команда.
            WakeWordMatcher(name, threshold = 0.7).match(trimmed)?.let { m ->
                val first = ai.nova.core.nlp.RuTokenizer.normalize(m.command.substringBefore(' ').trim(',', '.'))
                if (first in COMMAND_WORDS || first.endsWith("ть") && first.length >= 5) return m.command
            }
            // «поставь, Нова, задачу…», «напомни мне, Нова, …», «…, Нова»
            val strict = WakeWordMatcher(name, threshold = 0.85)
            val cut = Regex(""",\s*([\p{L}-]+)\s*(?:,|$)""").findAll(trimmed).firstOrNull { strict.similarity(ai.nova.core.nlp.RuTokenizer.normalize(it.groupValues[1])) >= 0.85 }
            if (cut != null) {
                val joined = trimmed.substring(0, cut.range.first) + " " + trimmed.substring(cut.range.last + 1)
                return joined.replace(Regex("""\s{2,}"""), " ").trim().trim(',').trim()
            }
            return trimmed
        }

        /**
         * Офлайн-распознаватель не знает слова «Нова» и пишет вместо него похожее настоящее слово:
         * «ноль», «ну ли», «нова». В начале фразы перед командой это обращение — убираем,
         * иначе в задаче или напоминании появляется «Ноль …» или «00 …».
         */
        private fun misheardName(text: String, name: String): String {
            val novaLike = WakeWordMatcher(name, threshold = 0.85).similarity("нова") >= 0.85
            if (!novaLike) return text
            val words = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
            fun norm(i: Int) = words.getOrNull(i)?.let { ai.nova.core.nlp.RuTokenizer.normalize(it.trim(',', '.', '!', ':', '-', '—')) }
            var skip = 0
            while (skip < 2) {
                val w = norm(skip) ?: break
                val pair = norm(skip + 1)?.let { "$w $it" }
                when {
                    pair != null && pair in MISHEARD_PAIRS -> skip += 2
                    w in MISHEARD_NOVA -> skip += 1
                    else -> break
                }
            }
            if (skip == 0) return text
            val next = norm(skip) ?: return text
            val command = next in COMMAND_WORDS || next in EXTRA_COMMAND_WORDS || (next.endsWith("ть") && next.length >= 5)
            return if (command) words.drop(skip).joinToString(" ") else text
        }

        private val MISHEARD_NOVA = setOf(
            "ноль", "ноли", "ноле", "нолик", "нолли", "нова", "ново", "нава", "нову", "нуль",
        )
        private val MISHEARD_PAIRS = setOf("ну ли", "но ли", "ноль ноль", "ну ль", "но ль")
        private val EXTRA_COMMAND_WORDS = setOf(
            "купи", "засеки", "посчитай", "давай", "привет", "как", "отмени", "перенеси", "убери", "кто", "почему", "зачем", "можешь",
            "помоги", "сделай", "спой", "переключи", "поставить", "пожалуйста", "слушай", "скажи", "покажи", "надо", "нужно",
            "потратила", "потратил", "заплатила", "заплатил", "купила", "купил", "доброе", "спокойной", "итоги", "погода", "курс", "новости",
        )

        private val COMMAND_WORDS = setOf(
            "добавь", "поставь", "заведи", "создай", "запиши", "напомни", "найди", "покажи", "удали", "отметь", "запомни",
            "сохрани", "внеси", "запланируй", "позвони", "напиши", "открой", "включи", "выключи", "сделай", "скажи", "расскажи",
            "сколько", "какая", "какой", "какие", "что", "когда", "где", "мне", "у", "разбуди", "переведи", "стоп", "задача", "задачу",
        )
    }
}
