package dev.jornada

import java.time.*
import kotlin.math.*

data class Slot(val start: String, val end: String) {
    fun bounds(day: LocalDate): Pair<LocalDateTime, LocalDateTime> {
        val a = day.atTime(LocalTime.parse(start))
        var b = day.atTime(LocalTime.parse(end))
        if (!b.isAfter(a)) b = b.plusDays(1)
        return a to b
    }
}
data class Schedule(val id: String, val effective: String, val name: String, val tolerance: Int, val days: Map<Int, List<Slot>>)
data class Entry(val id: String, val start: Long, val end: Long?, val zone: String, val note: String = "", val reason: String = "", val voided: Boolean = false) {
    val date: LocalDate get() = Instant.ofEpochMilli(start).atZone(ZoneId.of(zone)).toLocalDate()
    fun local(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.of(zone)).toLocalDateTime()
}
data class DayNote(val date: String, val kind: String, val note: String)
data class Audit(val id: String, val at: Long, val entity: String, val before: String, val after: String, val reason: String)
data class Snapshot(val entries: List<Entry> = emptyList(), val schedules: List<Schedule> = emptyList(), val notes: List<DayNote> = emptyList(), val audit: List<Audit> = emptyList())
data class DaySummary(val planned: Long, val worked: Long, val late: Long, val early: Long, val after: Long, val open: Boolean, val slots: List<Slot>, val note: DayNote?, val entries: List<Entry>, val tolerance: Int, val missing: Int) {
    val difference get() = worked - planned
    val incident get() = late > tolerance || early > tolerance || after > tolerance || missing > 0
}

fun summarize(day: LocalDate, data: Snapshot, now: Long = System.currentTimeMillis()): DaySummary {
    val plan = data.schedules.filter { it.effective <= day.toString() }.maxByOrNull { it.effective }
    val note = data.notes.find { it.date == day.toString() }
    val slots = if (note?.kind in listOf("Vacaciones", "Festivo", "Baja", "Permiso")) emptyList() else plan?.days?.get(day.dayOfWeek.value).orEmpty()
    val entries = data.entries.filter { !it.voided && it.date == day }.sortedBy { it.start }
    val worked = entries.sumOf { max(0, (it.end ?: now) - it.start) } / 60000
    val planned = slots.sumOf { (a, b) -> val p = Slot(a, b).bounds(day); Duration.between(p.first, p.second).toMinutes() }
    var late = 0L; var early = 0L; var after = 0L
    val groups = entries.groupBy { entry ->
        val time = entry.local(entry.start)
        slots.indices.minByOrNull { i ->
            val (a, b) = slots[i].bounds(day)
            when { time < a -> Duration.between(time, a).seconds; time > b -> Duration.between(b, time).seconds; else -> 0L }
        }
    }
    slots.forEachIndexed { i, slot ->
        val group = groups[i].orEmpty()
        if (group.isNotEmpty()) {
            val (a, b) = slot.bounds(day)
            late += max(0, Duration.between(a, group.first().local(group.first().start)).toMinutes())
            val last = group.last()
            if (last.end != null) {
                val end = last.local(last.end)
                early += max(0, Duration.between(end, b).toMinutes())
                after += max(0, Duration.between(b, end).toMinutes())
            }
        }
    }
    val localNow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val missing = slots.indices.count { groups[it].isNullOrEmpty() && slots[it].bounds(day).second < localNow }
    return DaySummary(planned, worked, late, early, after, entries.any { it.end == null }, slots, note, entries, plan?.tolerance ?: 0, missing)
}

fun validateSchedule(s: Schedule) {
    LocalDate.parse(s.effective)
    require(s.name.isNotBlank() && s.name.length <= 80) { "Pon un nombre al horario (máximo 80 caracteres)." }
    require(s.tolerance in 0..120) { "La tolerancia debe estar entre 0 y 120 minutos." }
    require(s.days.keys.all { it in 1..7 }) { "Día de la semana no válido." }
    s.days.values.forEach { slots ->
        require(slots.size <= 3) { "Máximo tres tramos por día." }
        val ranges = slots.map { it.bounds(LocalDate.of(2026, 1, 5)) }.sortedBy { it.first }
        require(ranges.all { Duration.between(it.first, it.second).toMinutes() in 1..1439 }) { "Un turno debe durar entre 1 minuto y menos de 24 horas." }
        require(ranges.zipWithNext().all { (a, b) -> a.second <= b.first }) { "Los tramos del horario se solapan." }
    }
    // Cross-day overlap matters when an overnight shift meets the next morning.
    for (d in 1..7) {
        val base = LocalDate.of(2026, 1, 4).plusDays(d.toLong())
        val last = s.days[d].orEmpty().maxOfOrNull { it.bounds(base).second }
        val next = s.days[d % 7 + 1].orEmpty().minOfOrNull { it.bounds(base.plusDays(1)).first }
        require(last == null || next == null || last <= next) { "El turno nocturno se solapa con el día siguiente." }
    }
}
fun validateEntry(entry: Entry, others: List<Entry>, now: Long = System.currentTimeMillis()) {
    ZoneId.of(entry.zone)
    require(entry.start > 0 && entry.start <= now + 60000) { "La entrada no puede estar en el futuro." }
    require(entry.end == null || entry.end > entry.start) { "La salida debe ser posterior a la entrada." }
    require(entry.end == null || entry.end <= now + 60000) { "La salida no puede estar en el futuro." }
    require(entry.note.length <= 2000 && entry.reason.length <= 2000) { "El texto supera 2000 caracteres." }
    if (entry.voided) return
    val active = others.filter { it.id != entry.id && !it.voided }
    require(entry.end != null || active.none { it.end == null }) { "Ya hay una entrada sin salida. Ciérrala primero." }
    require(active.none { entry.start < (it.end ?: Long.MAX_VALUE) && (entry.end ?: Long.MAX_VALUE) > it.start }) { "Este fichaje se solapa con otro. Revisa las horas." }
}
fun minutes(value: Long): String = "${if (value < 0) "−" else ""}${abs(value) / 60} h ${abs(value) % 60} min"
fun editedTimestamp(date: String, time: String, zone: ZoneId, original: Long? = null): Long {
    val local = LocalDate.parse(date).atTime(LocalTime.parse(time))
    if (original != null) {
        val previous = Instant.ofEpochMilli(original).atZone(zone).toLocalDateTime()
        if (previous.withSecond(0).withNano(0) == local) return original
    }
    val offsets = zone.rules.getValidOffsets(local)
    require(offsets.size == 1) { "La hora coincide con un cambio de horario y es ambigua o no existe. Elige otra hora." }
    return local.atOffset(offsets.first()).toInstant().toEpochMilli()
}
fun parseSlots(text: String): List<Slot> = text.trim().takeIf { it.isNotEmpty() }?.split(",")?.map {
    val pair = it.trim().split("-")
    require(pair.size == 2) { "Usa HH:mm-HH:mm; separa los tramos con comas." }
    Slot(LocalTime.parse(pair[0].trim()).toString(), LocalTime.parse(pair[1].trim()).toString())
} ?: emptyList()
