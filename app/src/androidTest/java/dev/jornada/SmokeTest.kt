package dev.jornada

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.time.*

/** Uses the dedicated debug app sandbox; never seeds the release app. */
class SmokeTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName == "dev.jornada.debug")
            targetContext.deleteDatabase("jornada.db")
            var store = Store(targetContext)
            val now = System.currentTimeMillis()
            val entry = Entry(uid(), now - 3_600_000, now - 600_000, ZoneId.systemDefault().id, "Prueba de persistencia")
            store.save(entry, "Alta manual de prueba")
            store.close(); store = Store(targetContext)
            check(store.snapshot().entries.single() == entry)
            check(store.snapshot().audit.size == 1)
            check(describeAudit(entry.json().toString(), "entries/${entry.id}").startsWith("Entrada:"))
            store.save(entry.copy(end = now - 300_000, reason = "Salida corregida"), "Salida corregida")
            check(store.snapshot().audit.size == 2)
            check(store.snapshot().audit.any { it.before.contains("Prueba de persistencia") })
            val original = store.snapshot()
            check(readBackup(original.backup()).entries == original.entries)
            val bad = original.backup().replace("jornada-1", "broken")
            check(runCatching { readBackup(bad) }.isFailure)
            check(store.snapshot().entries == original.entries)
            store.save(Entry(uid(), now - 120_000, null, ZoneId.systemDefault().id), "Entrada")
            check(runCatching { store.save(Entry(uid(), now - 60_000, null, ZoneId.systemDefault().id), "Duplicado") }.isFailure)
            check(store.snapshot().entries.count { it.end == null } == 1)
            store.restore(original)
            check(store.snapshot().entries == original.entries)
            check(store.previousBackups().isNotEmpty())
            val recovery = readBackup(store.previousBackup(store.previousBackups().first()))
            check(recovery.entries.size == 2)
            store.close()
            result.putString("stream", "OK: SQLite persistence, audit, JSON validation, overlapping entries, restore transaction and automatic recovery backup. Production app data untouched.\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) { result.putString("stream", "FAIL: ${e.stackTraceToString()}\n"); finish(Activity.RESULT_CANCELED, result) }
    }
}
