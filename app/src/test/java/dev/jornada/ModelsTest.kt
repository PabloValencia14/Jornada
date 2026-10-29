package dev.jornada

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ModelsTest {
    private val day = LocalDate.of(2026, 10, 26)
    private fun stamp(date: LocalDate, time: String) = date.atTime(LocalTime.parse(time)).atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
    private fun entry(start: String, end: String, endDay: LocalDate = day) = Entry("e", stamp(day, start), stamp(endDay, end), "Europe/Madrid")
    private fun plan(vararg slots: Slot) = Schedule("s", "2026-01-01", "General", 5, mapOf(1 to slots.toList()))
    @Test fun latenessAndEarlyExitRemainVisible() {
        val data = Snapshot(listOf(entry("08:12", "14:50")), listOf(plan(Slot("08:00", "15:00"))))
        val s = summarize(day, data)
        assertEquals(12L, s.late); assertEquals(10L, s.early); assertEquals(398L, s.worked); assertEquals(-22L, s.difference); assertTrue(s.incident)
    }
    @Test fun toleranceDoesNotEraseTimestamps() {
        val s = summarize(day, Snapshot(listOf(entry("08:03", "15:00")), listOf(plan(Slot("08:00", "15:00")))))
        assertEquals(3L, s.late); assertFalse(s.incident)
    }
    @Test fun versionedSchedulesDoNotChangeEarlierDays() {
        val old = plan(Slot("08:00", "15:00"))
        val new = old.copy(id = "new", effective = "2026-11-01", days = mapOf(1 to listOf(Slot("09:00", "16:00"))))
        assertEquals(0L, summarize(day, Snapshot(listOf(entry("08:00", "15:00")), listOf(new, old))).late)
    }
    @Test fun overnightAndDstUseElapsedTime() {
        val s = summarize(day, Snapshot(listOf(entry("22:00", "06:00", day.plusDays(1))), listOf(plan(Slot("22:00", "06:00")))))
        assertEquals(480L, s.worked); assertEquals(480L, s.planned); assertEquals(0L, s.late); assertEquals(0L, s.early)
        val saturday = LocalDate.of(2026, 10, 24)
        val dst = Entry("dst", stamp(saturday, "22:00"), stamp(saturday.plusDays(1), "06:00"), "Europe/Madrid")
        assertEquals(540L, summarize(saturday, Snapshot(listOf(dst))).worked)
    }
    @Test fun splitShiftAndBreaksDoNotAddPhantomLateness() {
        val e1 = entry("08:00", "10:00")
        val e2 = entry("10:15", "13:00").copy(id = "e2")
        val e3 = entry("16:07", "19:10").copy(id = "e3")
        val s = summarize(day, Snapshot(listOf(e3, e1, e2), listOf(plan(Slot("08:00", "13:00"), Slot("16:00", "19:00")))))
        assertEquals(7L, s.late); assertEquals(10L, s.after); assertEquals(0L, s.early); assertEquals(468L, s.worked)
    }
    @Test fun dayExemptionAndJustificationAreDifferent() {
        val data = Snapshot(listOf(entry("08:12", "15:00")), listOf(plan(Slot("08:00", "15:00"))))
        val justified = summarize(day, data.copy(notes = listOf(DayNote(day.toString(), "Justificación", "Cita médica"))))
        assertEquals(12L, justified.late); assertEquals(420L, justified.planned)
        assertEquals(0L, summarize(day, data.copy(notes = listOf(DayNote(day.toString(), "Festivo", "Local")))).planned)
    }
    @Test fun overlappingAndDoubleOpenEntriesAreRejected() {
        val existing = entry("08:00", "15:00")
        assertThrows(IllegalArgumentException::class.java) { validateEntry(existing.copy(id = "other"), listOf(existing)) }
        assertThrows(IllegalArgumentException::class.java) { validateEntry(existing.copy(id = "other", start = stamp(day, "16:00"), end = null), listOf(existing.copy(end = null))) }
        validateEntry(existing.copy(id = "other", start = stamp(day, "16:00"), end = stamp(day, "17:00")), listOf(existing), now = stamp(day, "23:59"))
    }
    @Test fun invalidAndCrossDaySchedulesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateSchedule(plan(Slot("08:00", "12:00"), Slot("11:00", "15:00"))) }
        assertThrows(IllegalArgumentException::class.java) { validateSchedule(plan(Slot("08:00", "08:00"))) }
        assertThrows(IllegalArgumentException::class.java) { validateSchedule(plan(Slot("22:00", "06:00")).copy(days = mapOf(1 to listOf(Slot("22:00", "06:00")), 2 to listOf(Slot("05:00", "10:00"))))) }
    }
    @Test fun voidedEntriesAreExcludedFromTotalsButRemainExportable() {
        val e = entry("08:00", "15:00").copy(voided = true)
        assertEquals(0L, summarize(day, Snapshot(listOf(e))).worked)
        val out = ByteArrayOutputStream(); exportWorkbook(out, Snapshot(listOf(e)), day, day)
        val parts = unzip(out.toByteArray())
        assertTrue(parts.getValue("xl/worksheets/sheet1.xml").contains("Anulado"))
    }
    @Test fun xlsxContainsFiveWorksheetsAndSafeTextNotFormulas() {
        val e = entry("08:00", "15:00").copy(note = "=HYPERLINK(\"evil\") & <dato>")
        val out = ByteArrayOutputStream()
        exportWorkbook(out, Snapshot(listOf(e), listOf(plan(Slot("08:00", "15:00")))) , day, day)
        val parts = unzip(out.toByteArray())
        assertEquals(5L, parts.keys.count { it.startsWith("xl/worksheets/") }.toLong())
        val xml = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("&lt;dato&gt;")); assertFalse(xml.contains("<f>")); assertTrue(xml.contains("inlineStr"))
        assertTrue(parts.getValue("xl/worksheets/sheet2.xml").contains("420"))
    }
    @Test fun dateFilterIsInclusiveAndUsesStartOfOvernightShift() {
        val e = entry("22:00", "06:00", day.plusDays(1))
        val out = ByteArrayOutputStream(); exportWorkbook(out, Snapshot(listOf(e)), day.plusDays(1), day.plusDays(1))
        assertFalse(unzip(out.toByteArray()).getValue("xl/worksheets/sheet1.xml").contains("22:00"))
        assertThrows(IllegalArgumentException::class.java) { exportWorkbook(ByteArrayOutputStream(), Snapshot(), day.plusDays(1), day) }
    }
    @Test fun exportFixtureForIndependentReader() {
        val directory = java.io.File("build/test-artifacts").apply { mkdirs() }
        java.io.File(directory, "Jornada-test.xlsx").outputStream().use { exportWorkbook(it, Snapshot(listOf(entry("08:12", "15:10")), listOf(plan(Slot("08:00", "15:00")))), day, day) }
    }
    @Test fun editingOnlyNotePreservesOriginalSeconds() {
        val original = stamp(day, "08:12") + 42123
        val zone = ZoneId.of("Europe/Madrid")
        assertEquals(original, editedTimestamp(day.toString(), "08:12", zone, original))
        assertEquals(stamp(day, "08:15"), editedTimestamp(day.toString(), "08:15", zone, original))
        assertThrows(IllegalArgumentException::class.java) { editedTimestamp("2026-10-25", "02:30", zone) }
        assertThrows(IllegalArgumentException::class.java) { editedTimestamp("2026-03-29", "02:30", zone) }
    }
    @Test fun missingSplitShiftIsVisibleWithoutInventingTime() {
        val data = Snapshot(listOf(entry("08:00", "13:00")), listOf(plan(Slot("08:00", "13:00"), Slot("16:00", "19:00"))))
        val s = summarize(day, data, stamp(day, "20:00"))
        assertEquals(1, s.missing); assertEquals(300L, s.worked); assertTrue(s.incident)
    }
    private fun unzip(bytes: ByteArray): Map<String, String> = ZipInputStream(bytes.inputStream()).use { zip -> buildMap { while (true) { val part = zip.nextEntry ?: break; put(part.name, zip.readBytes().toString(Charsets.UTF_8)) } } }
}
