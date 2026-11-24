package ai.nova.core.assistant

/**
 * Один шаг выполнения действия: что сказать и что запомнить на будущее.
 * Живёт отдельно от [ActionExecutor], потому что тем же шагом отвечают и маршруты.
 */
internal data class Step(
    val outcomes: List<Outcome>,
    val record: RecordRef? = null,
    val confirm: PendingConfirmation? = null,
    val choice: PendingChoice? = null,
    /** Созданная запись, которая не должна становиться фокусом разговора (например, расход). */
    val created: RecordRef? = null,
)

/**
 * Видео и маршруты: «включи Кукла колдуна» и «построй маршрут до аптеки».
 *
 * Вынесено из [ActionExecutor]: там эти сценарии занимали четверть файла и держали собственное
 * состояние ожидания (какая платформа видео, посидеть или перекусить, что ещё нашлось рядом),
 * перемешанное с состоянием заметок, расходов и напоминаний.
 *
 * Правило ответа: пока ждём уточнения, следующий вопрос не задаём — иначе человек отвечает
 * на «посидеть или перекусить?», а слышит «где искать видео».
 */
internal class RouteFlows(
    private val device: DeviceController,
    /** Где искать видео: человек уже выбрал YouTube, VK Видео или Rutube (null — ещё не выбрал). */
    private val videoPlatform: () -> VideoPlatform? = { null },
    /** Выбор платформы запомнен: приложение сохраняет его в настройках. */
    private val onVideoPlatform: (VideoPlatform) -> Unit = {},
) {
    /** Вопрос «где искать видео» ждёт ответа: запрос, который выполним, как только платформа выбрана. */
    private var pendingVideo: String? = null

    /** Ждём ответ на «посидеть или перекусить?»: как только ответят — ищем и строим маршрут. */
    private var pendingFood: FoodAsk? = null

    /** Маршрут уже построен: помним, что ещё нашлось рядом, чтобы «ещё» вело к следующему месту. */
    private var nextPlace: PendingPlace? = null

    /** Новая команда отменяет все уточнения: человек сказал другое, ждать больше нечего. */
    fun reset() {
        pendingVideo = null
        pendingFood = null
        nextPlace = null
    }

    /** Ждём ответ про платформу видео — этот вопрос важнее остальных уточнений. */
    fun awaitingVideo(): Boolean = pendingVideo != null

    /** Ждём уточнения по маршруту: «посидеть или перекусить?» или ответа «ещё» вместо прошлого места. */
    fun awaitingPlace(): Boolean = pendingFood != null || nextPlace != null

    /**
     * Запрос видео, ожидающий выбора платформы. Нужен, чтобы [ActionExecutor] задал вопрос один раз:
     * платформу спрашиваем только если человек её ещё не выбрал.
     */
    fun expectVideo(query: String) {
        pendingVideo = query
    }

    /**
     * Ответ на вопрос «YouTube, VK Видео или Rutube?»: платформа запоминается, поиск выполняется сразу.
     * Не похоже на ответ — null, вопрос снимаем (человек передумал и говорит о другом).
     */
    suspend fun answerVideo(text: String): ExecutionResult? {
        val query = pendingVideo ?: return null
        val platform = VideoChoice.parse(text) ?: run { pendingVideo = null; return null }
        pendingVideo = null
        onVideoPlatform(platform)
        val r = device.perform(DeviceCommand.Play(query, youtube = true, platform = platform))
        return ExecutionResult(listOf(Outcome(if (r.ok) "${platform.title}. ${r.text}" else r.text, if (r.ok) Outcome.Kind.QUERY else Outcome.Kind.ERROR)))
    }

    /**
     * Ответ по маршруту: вид еды («фастфуд», «суши», «посидеть») или «ещё» / «другое» / «не то».
     * Не похоже на ответ — null, ожидание снимаем: человек передумал и говорит о другом.
     */
    suspend fun answerPlace(text: String): ExecutionResult? {
        if (Places.isRefusal(text)) {
            pendingFood = null
            nextPlace = null
            return ExecutionResult(listOf(Outcome("Хорошо, маршрут не строю.", Outcome.Kind.QUERY)))
        }
        pendingFood?.let { ask ->
            val query = Places.refineFood(text)
            if (query == null) {
                pendingFood = null
                return null
            }
            pendingFood = null
            return ExecutionResult(placesStep(query, Places.kind(query), ask.app).outcomes)
        }
        val state = nextPlace ?: return null
        if (!Places.isNext(text)) {
            nextPlace = null
            return null
        }
        val place = state.rest.firstOrNull()
        // Мест больше нет: предложение остаётся открытым, чтобы «нет» и «хватит» звучали как отказ, а не «не поняла».
        if (place == null) return ExecutionResult(listOf(Outcome("Больше мест рядом не нашла. Скажите иначе — например, «маршрут до аптеки на Ленина».", Outcome.Kind.QUERY)))
        nextPlace = state.copy(rest = state.rest.drop(1))
        return ExecutionResult(listOf(routeOutcome(place, state.kind, state.app, "Следующий вариант")))
    }

    suspend fun navigate(command: DeviceCommand.Navigate): Step {
        nextPlace = null
        pendingFood = null
        val kind = Places.kind(command.destination)
        // «Поесть» без уточнения: спрашиваем один раз, иначе можно привезти в фастфуд, когда человек хотел посидеть.
        if (command.vague && kind == PlaceKind.FOOD) {
            pendingFood = FoodAsk(command.app)
            return Step(listOf(Outcome(PlaceKind.FOOD.question ?: "Где поесть: кафе, ресторан или быстро перекусить?", Outcome.Kind.QUESTION)))
        }
        // Адрес с номером дома — это уже точка: спрашивать «где именно» и предлагать «ещё» не нужно.
        val address = Places.looksLikeAddress(command.destination)
        val query = when {
            address -> command.destination.trim()
            Places.vague(command.destination) -> kind?.search ?: command.destination
            else -> ai.nova.core.nlp.RuQuery.placeForm(command.destination)
        }
        return placesStep(query, kind, command.app, exact = address)
    }

    /** Ищем место рядом и строим маршрут до ближайшего; остальные запоминаем для «ещё». */
    suspend fun placesStep(query: String, kind: PlaceKind?, app: MapsApp?, exact: Boolean = false): Step {
        val found = runCatching { device.findPlaces(query) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            emptyList()
        }
        if (found.isEmpty()) {
            // Место не нашли (нет сети или места телефона) — пусть карты ищут сами: маршрут по названию.
            val r = device.perform(DeviceCommand.Navigate(query, app))
            return Step(listOf(Outcome(r.text, if (r.ok) Outcome.Kind.QUERY else Outcome.Kind.ERROR)))
        }
        val place = found.first()
        val rest = found.drop(1)
        // Для точного адреса «ещё» не предлагаем: человек назвал дом, а не «какое-нибудь место».
        nextPlace = if (exact) null else PendingPlace(kind, app, rest)
        // «Ближайший» — только если правда знаем, где телефон: иначе расстояние неизвестно.
        val lead = when {
            exact -> "Маршрут"
            place.meters != null -> "Ближайший вариант"
            else -> "Нашла вариант"
        }
        return Step(listOf(routeOutcome(place, kind, app, lead, hint = !exact && rest.isNotEmpty())))
    }

    fun routeOutcome(
        place: PlaceCandidate,
        kind: PlaceKind?,
        app: MapsApp?,
        lead: String,
        hint: Boolean = false,
    ): Outcome {
        val r = device.routeTo(place, app)
        val head = buildString {
            append(lead)
            append(" — «")
            append(place.title)
            append('»')
            val address = place.address.trim()
            if (address.isNotEmpty() && !address.equals(place.title, ignoreCase = true)) {
                append(", ")
                append(address)
            }
            val distance = Places.distance(place.meters)
            if (distance.isNotEmpty()) {
                append(", ")
                append(distance)
            }
            append(". ")
        }
        val tail = buildString {
            append(r.text.trimEnd('.'))
            // Место телефона неизвестно (геолокация выключена): карты попросят её включить — предупреждаем сразу.
            if (r.ok && place.meters == null) append(". Геолокация выключена — карты попросят включить её, иначе не поймут, откуда ехать")
            if (r.ok && hint) append(". Если не подходит — скажите «ещё»")
            append('.')
        }
        return Outcome((head + tail).trim(), if (r.ok) Outcome.Kind.QUERY else Outcome.Kind.ERROR)
    }

    /** Карты нужны и тогда, когда человек их не называл: поэтому ждём объект, а не «nullable app». */
    private data class FoodAsk(val app: MapsApp?)

    private data class PendingPlace(
        val kind: PlaceKind?,
        val app: MapsApp?,
        val rest: List<PlaceCandidate>,
    )
}
