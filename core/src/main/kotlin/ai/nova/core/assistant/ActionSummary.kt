package ai.nova.core.assistant

/**
 * Короткое описание действия для журнала «что Нова сделала» — человек должен видеть, что именно произошло,
 * без чтения кода и без догадок. Только факты: вид действия и ключевой текст.
 */
object ActionSummary {
    fun describe(action: AssistantAction): String = when (action) {
        is AssistantAction.CreateNote -> "заметка «${short(action.title)}»"
        is AssistantAction.AppendNote -> "дописала в заметку «${short(action.content)}»"
        is AssistantAction.CreateTask -> "задача «${short(action.title)}»"
        is AssistantAction.CreateReminder -> "напоминание «${short(action.text)}»"
        is AssistantAction.CreateExpense -> "трата «${short(action.description.ifBlank { action.category })}»"
        is AssistantAction.Remember -> "в память: «${short(action.content)}»"
        is AssistantAction.Search -> "поиск «${short(action.query)}»"
        is AssistantAction.QueryNotes -> "список заметок"
        is AssistantAction.QueryTasks -> "список задач"
        is AssistantAction.QueryReminders -> "список напоминаний"
        is AssistantAction.Agenda -> "план на день"
        is AssistantAction.DeleteTask, is AssistantAction.DeleteNote, is AssistantAction.DeleteExpenses,
        is AssistantAction.CancelReminder, is AssistantAction.ForgetMemory -> "удаление записи"
        is AssistantAction.ShareNote -> "отправила заметку"
        is AssistantAction.Device -> "команда телефону: ${describe(action.command)}"
        else -> action::class.simpleName?.let { name -> name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase() } ?: "действие"
    }

    private fun describe(command: DeviceCommand): String = when (command) {
        is DeviceCommand.Flashlight -> if (command.on) "фонарик включён" else "фонарик выключен"
        is DeviceCommand.Play -> if (command.youtube) "видео «${short(command.query)}»" else "музыка «${short(command.query)}»"
        is DeviceCommand.WebSearch -> "поиск в интернете «${short(command.query)}»"
        is DeviceCommand.Navigate -> "маршрут «${short(command.destination)}»"
        is DeviceCommand.DoNotDisturb -> if (command.on) "«не беспокоить» включён" else "«не беспокоить» выключен"
        is DeviceCommand.Volume -> "громкость"
        is DeviceCommand.Brightness -> "яркость"
        is DeviceCommand.OpenApp -> "открыла «${short(command.name)}»"
        is DeviceCommand.Call -> "звонок: ${short(command.who)}"
        is DeviceCommand.Message -> "сообщение: ${short(command.who)}"
        is DeviceCommand.Timer -> "таймер на ${command.seconds / 60} мин"
        is DeviceCommand.Alarm -> "будильник на ${command.time}"
        is DeviceCommand.OpenUrl -> "открыла ссылку"
        is DeviceCommand.Share -> "поделилась в ${short(command.app)}"
        is DeviceCommand.UndoDevice -> "отмена последнего действия"
        else -> command::class.simpleName ?: "команда"
    }

    private fun short(text: String, limit: Int = 60): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= limit) one else one.take(limit - 1).trimEnd() + "…"
    }
}
