package ai.nova.app.sync

/**
 * Код веб-приложения Google Apps Script. Пользователь вставляет его в таблицу, разворачивает
 * и присылает Нова адрес — тогда Нова пишет записи прямо в лист, без OAuth и без Google Play.
 *
 * Скрипт хранит только то, что прислало приложение: напоминания, заметки, задачи, покупки, траты,
 * сценарии и память. Ключи AI, пароль базы и история чата (если она выключена) сюда не попадают.
 */
object SheetsScript {
    const val TOKEN_PLACEHOLDER = "ВСТАВЬТЕ_КОД_ДОСТУПА"

    /** Готовый скрипт с уже подставленным кодом доступа. */
    fun text(token: String): String = TEMPLATE.replace(TOKEN_PLACEHOLDER, jsString(token.trim()))

    /**
     * Код доступа вставляется в строковый литерал JavaScript, поэтому кавычка, обратный слэш или
     * перевод строки в нём не должны ломать скрипт: пользователь мог придумать любой пароль.
     */
    private fun jsString(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private val TEMPLATE = """
// Нова: синхронизация напоминаний, заметок и других записей с этой таблицей.
// 1. Расширения -> Apps Script. Удалите всё и вставьте этот код.
// 2. Сохраните (Ctrl+S).
// 3. Развернуть -> Новое развёртывание -> тип «Веб-приложение».
//    «Запуск от имени»: Я. «Доступ»: Все. Скопируйте адрес и вставьте его в Нова.
var TOKEN = 'ВСТАВЬТЕ_КОД_ДОСТУПА';
var SHEET_NAME = 'Нова';
var HEADER = ['table', 'id', 'updated_at', 'deleted', 'payload'];
var MAX_CELL = 45000;

function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    if (String(body.token) !== String(TOKEN)) {
      return reply({ ok: false, error: 'Неверный код доступа' });
    }
    var ss = SpreadsheetApp.openById(body.spreadsheetId);
    var sheet = ss.getSheetByName(SHEET_NAME);
    if (!sheet) {
      sheet = ss.insertSheet(SHEET_NAME);
      sheet.appendRow(HEADER);
      sheet.setFrozenRows(1);
    }
    if (body.action === 'ping') {
      return reply({ ok: true, rows: [] });
    }

    var existing = readRows(sheet);
    var incoming = body.rows || [];
    for (var i = 0; i < incoming.length; i++) {
      var r = incoming[i];
      var key = r.table + '\u0001' + r.id;
      var old = existing[key];
      if (!old || Number(r.updatedAt) > Number(old.updatedAt)) {
        existing[key] = r;
      }
    }

    var rows = [];
    var tooLong = 0;
    for (var k in existing) {
      var row = existing[k];
      var payload = String(row.payload || '');
      if (payload.length > MAX_CELL) {
        tooLong++;
        continue;
      }
      rows.push(row);
    }
    writeRows(sheet, rows);
    return reply({ ok: true, rows: rows, tooLong: tooLong });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  }
}

function readRows(sheet) {
  var values = sheet.getDataRange().getValues();
  var map = {};
  for (var i = 1; i < values.length; i++) {
    var v = values[i];
    if (!v[0] || !v[1]) continue;
    map[v[0] + '\u0001' + v[1]] = {
      table: String(v[0]),
      id: String(v[1]),
      updatedAt: Number(v[2]),
      deleted: v[3] === true || String(v[3]) === 'true',
      payload: String(v[4])
    };
  }
  return map;
}

function writeRows(sheet, rows) {
  var out = [HEADER];
  for (var i = 0; i < rows.length; i++) {
    var r = rows[i];
    out.push([r.table, r.id, r.updatedAt, r.deleted, r.payload]);
  }
  sheet.clearContents();
  sheet.getRange(1, 1, out.length, HEADER.length).setValues(out);
}

function reply(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
""".trimIndent()
}
