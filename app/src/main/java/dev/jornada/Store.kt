package dev.jornada

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.*
import java.time.*
import java.util.UUID

fun uid() = UUID.randomUUID().toString()
fun Entry.json() = JSONObject().put("id", id).put("start", start).put("end", end ?: JSONObject.NULL).put("zone", zone).put("note", note).put("reason", reason).put("voided", voided)
fun Schedule.json(): JSONObject {
    val daysJson = JSONObject()
    days.forEach { (d, slots) -> daysJson.put(d.toString(), JSONArray(slots.map { JSONObject().put("start", it.start).put("end", it.end) })) }
    return JSONObject().put("id", id).put("effective", effective).put("name", name).put("tolerance", tolerance).put("days", daysJson)
}
fun DayNote.json() = JSONObject().put("date", date).put("kind", kind).put("note", note)
fun Audit.json() = JSONObject().put("id", id).put("at", at).put("entity", entity).put("before", before).put("after", after).put("reason", reason)
fun entryFrom(o: JSONObject) = Entry(o.getString("id"), o.getLong("start"), if (o.isNull("end")) null else o.getLong("end"), o.getString("zone"), o.optString("note"), o.optString("reason"), o.optBoolean("voided"))
fun scheduleFrom(o: JSONObject): Schedule {
    val days = o.getJSONObject("days")
    return Schedule(o.getString("id"), o.getString("effective"), o.getString("name"), o.getInt("tolerance"), days.keys().asSequence().associate { k -> k.toInt() to days.getJSONArray(k).objects().map { Slot(it.getString("start"), it.getString("end")) } })
}
fun noteFrom(o: JSONObject) = DayNote(o.getString("date"), o.getString("kind"), o.getString("note"))
fun auditFrom(o: JSONObject) = Audit(o.getString("id"), o.getLong("at"), o.getString("entity"), o.getString("before"), o.getString("after"), o.getString("reason"))
fun describeAudit(raw: String, entity: String): String {
    if (raw.isBlank()) return "Sin registro anterior"
    return runCatching {
        val o = JSONObject(raw)
        when {
            entity.startsWith("entries/") -> {
                val e = entryFrom(o)
                val f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                "Entrada: ${e.local(e.start).format(f)} · Salida: ${e.end?.let { e.local(it).format(f) } ?: "Sin salida"}${if (e.voided) " · Anulado" else ""}${if (e.note.isNotBlank()) " · ${e.note}" else ""}"
            }
            entity.startsWith("schedules/") -> { val s = scheduleFrom(o); "${s.name}, desde ${s.effective}, tolerancia ${s.tolerance} min. " + s.days.toSortedMap().entries.joinToString("; ") { (d, slots) -> "${DayOfWeek.of(d).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag("es-ES"))}: ${slots.joinToString(", ") { "${it.start}–${it.end}" }.ifEmpty { "Libre" }}" } }
            entity.startsWith("notes/") -> { val n = noteFrom(o); "${n.kind}: ${n.note}" }
            else -> raw
        }
    }.getOrDefault("Detalle no disponible; conserva una copia completa para revisarlo.")
}
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun Snapshot.backup() = JSONObject().put("format", "jornada-1").put("exportedAt", Instant.now().toString()).put("entries", JSONArray(entries.map { it.json() })).put("schedules", JSONArray(schedules.map { it.json() })).put("notes", JSONArray(notes.map { it.json() })).put("audit", JSONArray(audit.map { it.json() })).toString(2)
fun readBackup(text: String): Snapshot {
    require(text.length <= 10_000_000) { "La copia supera 10 MB." }
    val o = JSONObject(text)
    require(o.getString("format") == "jornada-1") { "El archivo no es una copia de Jornada compatible." }
    val result = Snapshot(o.getJSONArray("entries").objects().map(::entryFrom), o.getJSONArray("schedules").objects().map(::scheduleFrom), o.getJSONArray("notes").objects().map(::noteFrom), o.getJSONArray("audit").objects().map(::auditFrom))
    require(result.entries.map { it.id }.distinct().size == result.entries.size && result.schedules.map { it.id }.distinct().size == result.schedules.size && result.notes.map { it.date }.distinct().size == result.notes.size && result.audit.map { it.id }.distinct().size == result.audit.size) { "La copia contiene identificadores duplicados." }
    require(result.schedules.map { it.effective }.distinct().size == result.schedules.size) { "La copia contiene horarios con la misma vigencia." }
    result.entries.forEach { validateEntry(it, result.entries) }
    result.schedules.forEach(::validateSchedule)
    result.notes.forEach { LocalDate.parse(it.date); require(it.kind in noteKinds && it.note.length <= 2000) { "Incidencia no válida." } }
    return result
}
val noteKinds = listOf("Justificación", "Vacaciones", "Festivo", "Baja", "Permiso", "Observación")

class Store(private val context: Context) : SQLiteOpenHelper(context, "jornada.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        listOf("entries", "schedules", "notes", "audit").forEach { db.execSQL("CREATE TABLE $it (id TEXT PRIMARY KEY, payload TEXT NOT NULL)") }
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("Versión de base de datos no compatible. No se borrará ningún registro.") }
    private fun rows(table: String): List<JSONObject> = readableDatabase.query(table, arrayOf("payload"), null, null, null, null, null).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    @Synchronized fun snapshot() = Snapshot(rows("entries").map(::entryFrom).sortedByDescending { it.start }, rows("schedules").map(::scheduleFrom).sortedByDescending { it.effective }, rows("notes").map(::noteFrom), rows("audit").map(::auditFrom).sortedByDescending { it.at })
    private fun put(db: SQLiteDatabase, table: String, id: String, value: JSONObject) {
        val values = ContentValues().apply { put("id", id); put("payload", value.toString()) }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "No se ha podido guardar. Comprueba el espacio libre." } }
    }
    private fun change(table: String, id: String, value: JSONObject, reason: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = db.query(table, arrayOf("payload"), "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.getString(0) else "" }
            put(db, table, id, value)
            val audit = Audit(uid(), System.currentTimeMillis(), "$table/$id", before, value.toString(), reason)
            put(db, "audit", audit.id, audit.json())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun save(entry: Entry, reason: String) {
        val data = snapshot()
        require(data.entries.none { it.id == entry.id } || reason.isNotBlank()) { "Indica el motivo de la corrección." }
        validateEntry(entry, data.entries)
        change("entries", entry.id, entry.json(), reason)
    }
    @Synchronized fun punch() {
        val active = snapshot().entries.firstOrNull { !it.voided && it.end == null }
        val now = System.currentTimeMillis()
        if (active == null) save(Entry(uid(), now, null, ZoneId.systemDefault().id), "Entrada registrada")
        else save(active.copy(end = now), "Salida registrada")
    }
    @Synchronized fun save(schedule: Schedule) {
        validateSchedule(schedule)
        val existing = snapshot().schedules
        require(existing.none { it.effective == schedule.effective && it.id != schedule.id }) { "Ya existe un horario desde esa fecha. Usa otra fecha de vigencia." }
        change("schedules", schedule.id, schedule.json(), "Horario desde ${schedule.effective}")
    }
    @Synchronized fun save(note: DayNote) {
        LocalDate.parse(note.date)
        require(note.kind in noteKinds && note.note.isNotBlank() && note.note.length <= 2000) { "Describe el caso puntual (máximo 2000 caracteres)." }
        change("notes", note.date, note.json(), "Incidencia: ${note.kind}")
    }
    @Synchronized fun restore(data: Snapshot) {
        val validated = readBackup(data.backup())
        val previous = snapshot()
        context.openFileOutput("antes-de-restaurar-${System.currentTimeMillis()}.json", Context.MODE_PRIVATE).bufferedWriter().use { it.write(previous.backup()) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("entries", "schedules", "notes", "audit").forEach { db.delete(it, null, null) }
            validated.entries.forEach { put(db, "entries", it.id, it.json()) }
            validated.schedules.forEach { put(db, "schedules", it.id, it.json()) }
            validated.notes.forEach { put(db, "notes", it.date, it.json()) }
            (previous.audit + validated.audit).distinctBy { it.id }.forEach { put(db, "audit", it.id, it.json()) }
            val audit = Audit(uid(), System.currentTimeMillis(), "restore", "${previous.entries.size} fichajes", "${validated.entries.size} fichajes", "Restauración confirmada; copia previa guardada en el dispositivo")
            put(db, "audit", audit.id, audit.json())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun previousBackups() = context.fileList().filter { it.startsWith("antes-de-restaurar-") }.sortedDescending()
    fun previousBackup(name: String) = context.openFileInput(name).bufferedReader().use { it.readText() }
}
