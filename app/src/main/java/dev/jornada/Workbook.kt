package dev.jornada

import java.io.OutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.*

private fun xml(s: String) = s.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
fun exportWorkbook(out: OutputStream, data: Snapshot, from: LocalDate, to: LocalDate) {
    require(!from.isAfter(to)) { "La fecha inicial debe ser anterior a la final." }
    require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 36600) { "Exporta tramos de hasta 100 años." }
    val entries = data.entries.filter { it.date >= from && it.date <= to }.sortedBy { it.start }
    val dayList = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
    val sheets = listOf(
        "Fichajes" to sequence {
            yield(listOf("ID", "Fecha de jornada", "Entrada", "Salida", "Zona horaria", "Minutos trabajados", "Estado", "Nota", "Motivo de corrección"))
            entries.forEach { e -> yield(listOf(e.id, e.date.toString(), e.local(e.start).format(dateTimeFormat), e.end?.let { e.local(it).format(dateTimeFormat) }.orEmpty(), e.zone, e.end?.let { (it - e.start) / 60000 } ?: "", if (e.voided) "Anulado" else if (e.end == null) "Abierto" else "Cerrado", e.note, e.reason)) }
        },
        "Resumen diario" to sequence {
            yield(listOf("Fecha", "Horario previsto", "Minutos previstos", "Minutos trabajados", "Balance (min)", "Retraso entrada (min)", "Salida anticipada (min)", "Salida posterior (min)", "Tolerancia (min)", "Estado", "Caso puntual", "Justificación", "Tramos sin fichajes"))
            dayList.forEach { day ->
                val s = summarize(day, data)
                if (s.planned > 0 || s.entries.isNotEmpty() || s.note != null) yield(listOf(day.toString(), s.slots.joinToString(" / ") { "${it.start}–${it.end}" }, s.planned, if (s.open) "En curso" else s.worked, if (s.open) "" else s.difference, s.late, s.early, s.after, s.tolerance, if (s.open) "Abierto" else if (s.entries.isEmpty() && s.planned > 0) "Sin fichajes" else if (s.incident) "Con diferencias" else "Registrado", s.note?.kind.orEmpty(), s.note?.note.orEmpty(), s.missing))
            }
        },
        "Incidencias" to sequence {
            yield(listOf("Fecha", "Tipo", "Motivo"))
            data.notes.filter { it.date >= from.toString() && it.date <= to.toString() }.sortedBy { it.date }.forEach { yield(listOf(it.date, it.kind, it.note)) }
        },
        "Horarios" to sequence {
            yield(listOf("Vigente desde", "Nombre", "Tolerancia (min)", "Día (1=lunes)", "Tramos"))
            data.schedules.filter { it.effective <= to.toString() }.sortedBy { it.effective }.forEach { s -> (1..7).forEach { d -> yield(listOf(s.effective, s.name, s.tolerance, d, s.days[d].orEmpty().joinToString(" / ") { "${it.start}–${it.end}" })) } }
        },
        "Historial de fichajes" to sequence {
            yield(listOf("Fecha de modificación", "Registro", "Antes", "Después", "Motivo"))
            val ids = entries.map { "entries/${it.id}" }.toSet() + data.notes.filter { it.date in from.toString()..to.toString() }.map { "notes/${it.date}" }
            data.audit.filter { it.entity in ids }.sortedBy { it.at }.forEach { yield(listOf(Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).format(dateTimeFormat), it.entity, it.before, it.after, it.reason)) }
        }
    )
    ZipOutputStream(out).use { zip ->
        fun part(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        part("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" + sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
        part("_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        part("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" + sheets.mapIndexed { i, s -> "<sheet name=\"${xml(s.first)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
        part("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } + "</Relationships>")
        sheets.forEachIndexed { i, (_, rows) ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
            fun write(s: String) { zip.write(s.toByteArray(Charsets.UTF_8)) }
            write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"15\" width=\"24\" customWidth=\"1\"/></cols><sheetData>")
            var count = 0; var columns = 1
            rows.forEach { row ->
                count++; columns = maxOf(columns, row.size)
                write("<row r=\"$count\">")
                row.forEachIndexed { index, value ->
                    val cell = "${('A'.code + index).toChar()}$count"
                    if (value is Number) write("<c r=\"$cell\"><v>$value</v></c>")
                    else write("<c r=\"$cell\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>")
                }
                write("</row>")
            }
            write("</sheetData><autoFilter ref=\"A1:${('A'.code + columns - 1).toChar()}$count\"/></worksheet>")
            zip.closeEntry()
        }
    }
}
