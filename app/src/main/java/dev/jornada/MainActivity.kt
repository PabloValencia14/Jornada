package dev.jornada

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")
private val humanDate = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", es)
private val shortTime = DateTimeFormatter.ofPattern("HH:mm")
private val LightColors = lightColorScheme(primary = Color(0xFF245C48), onPrimary = Color.White, primaryContainer = Color(0xFFD9EBDD), onPrimaryContainer = Color(0xFF123B2B), secondaryContainer = Color(0xFFD9EBDD), onSecondaryContainer = Color(0xFF123B2B), background = Color(0xFFFAF8F1), onBackground = Color(0xFF202B24), surface = Color(0xFFFAF8F1), onSurface = Color(0xFF202B24), surfaceVariant = Color(0xFFECEEE5), onSurfaceVariant = Color(0xFF505C52), outline = Color(0xFF737D72), error = Color(0xFFA52E2E))
private val DarkColors = darkColorScheme(primary = Color(0xFFA4D4B8), onPrimary = Color(0xFF063824), primaryContainer = Color(0xFF24533C), onPrimaryContainer = Color(0xFFCFEDD8), secondaryContainer = Color(0xFF24533C), onSecondaryContainer = Color(0xFFCFEDD8), background = Color(0xFF141C17), onBackground = Color(0xFFE2E8DF), surface = Color(0xFF141C17), onSurface = Color(0xFFE2E8DF), surfaceVariant = Color(0xFF29352D), onSurfaceVariant = Color(0xFFBCC9BD), outline = Color(0xFF8E9D90), error = Color(0xFFFFB4AB))

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(applicationContext)
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) { Jornada(store) } }
    }
    override fun onDestroy() { super.onDestroy(); store.close() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Jornada(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(Snapshot()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var foreground by remember { mutableStateOf(true) }
    val lifecycle = (context as ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(foreground) { if (foreground) while (true) { now = System.currentTimeMillis(); delay(60_000 - now % 60_000) } }
    val snack = remember { SnackbarHostState() }
    fun perform(message: String, onSuccess: () -> Unit = {}, action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { data = withContext(Dispatchers.IO) { action(); store.snapshot() }; now = System.currentTimeMillis(); busy = false; onSuccess(); snack.showSnackbar(message) }
            catch (e: Exception) { busy = false; snack.showSnackbar(e.message ?: "No se ha podido guardar. Inténtalo de nuevo.") }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { data = withContext(Dispatchers.IO) { store.snapshot() }; loading = false }
        catch (e: Exception) { snack.showSnackbar("No se pudo abrir la base de datos. Reinicia la app. ${e.message}") }
    }
    var editor by remember { mutableStateOf<String?>(null) }
    var editingEntry by remember { mutableStateOf<Entry?>(null) }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var restorePending by remember { mutableStateOf<Snapshot?>(null) }
    var exportFrom by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var exportTo by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var exportAll by rememberSaveable { mutableStateOf(false) }
    var rangeFrom by rememberSaveable { mutableStateOf("") }
    var rangeTo by rememberSaveable { mutableStateOf("") }
    val excelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) perform("Excel guardado") {
            context.contentResolver.openOutputStream(uri, "w").let { requireNotNull(it) { "No se pudo abrir el archivo de destino." } }.use { exportWorkbook(it, store.snapshot(), LocalDate.parse(rangeFrom), LocalDate.parse(rangeTo)) }
        }
    }
    var backupSource by rememberSaveable { mutableStateOf<String?>(null) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform("Copia guardada. Consérvala fuera de la app.") { val text = backupSource?.let { store.previousBackup(it) } ?: store.snapshot().backup(); requireNotNull(context.contentResolver.openOutputStream(uri, "w")).bufferedWriter().use { it.write(text) } }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    restorePending = withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { reader ->
                            val text = StringBuilder(); val buffer = CharArray(8192)
                            while (true) { val n = reader.read(buffer); if (n < 0) break; text.append(buffer, 0, n); require(text.length <= 10_000_000) { "La copia supera 10 MB." } }
                            readBackup(text.toString())
                        }
                    }
                } catch (e: Exception) { snack.showSnackbar("Copia no válida: ${e.message}") }
            }
        }
    }
    val labels = listOf("Hoy", "Calendario", "Horario", "Informe")
    val icons = listOf(Icons.Default.Home, Icons.Default.DateRange, Icons.Default.Settings, Icons.Default.List)
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val expanded = maxWidth >= 700.dp
        Row(Modifier.fillMaxSize()) {
            if (expanded) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Spacer(Modifier.height(20.dp))
                Icon(painterResource(R.drawable.ic_launcher), "Jornada", Modifier.size(44.dp), tint = Color.Unspecified)
                Spacer(Modifier.height(32.dp))
                labels.forEachIndexed { i, label -> NavigationRailItem(selected = page == i, onClick = { page = i }, icon = { Icon(icons[i], null) }, label = { Text(label) }) }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = { TopAppBar(title = { Text(if (page == 0) "Jornada" else labels[page], fontWeight = FontWeight.SemiBold) }, actions = { if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp) }) },
                bottomBar = {
                    if (!expanded) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) { labels.forEachIndexed { i, label -> NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Icon(icons[i], null) }, label = { Text(label) }) } }
                },
                snackbarHost = { SnackbarHost(snack) }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    if (loading) Column(Modifier.padding(32.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Abriendo tus registros…", Modifier.padding(top = 20.dp)) }
                    else {
                        val contentModifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()
                        when (page) {
                            0 -> TodayScreen(data, now, busy, contentModifier, onPunch = { perform("Fichaje guardado") { store.punch() } }, onAdd = { editingEntry = null; selectedDay = LocalDate.now().toString(); editor = "entry" }, onEdit = { editingEntry = it; editor = "entry" }, onSchedule = { page = 2 }, onNote = { selectedDay = LocalDate.now().toString(); editor = "note" })
                            1 -> CalendarScreen(data, selectedDay, { selectedDay = it }, contentModifier, onAdd = { editingEntry = null; editor = "entry" }, onEdit = { editingEntry = it; editor = "entry" }, onNote = { editor = "note" })
                            2 -> ScheduleScreen(data, contentModifier, onAdd = { editingSchedule = null; editor = "schedule" }, onEdit = { editingSchedule = it; editor = "schedule" })
                            3 -> ReportScreen(data, exportFrom, exportTo, exportAll, { exportFrom = it }, { exportTo = it }, { exportAll = it }, busy, contentModifier,
                                onExcel = {
                                    try {
                                        val recorded = data.entries.map { it.date } + data.notes.map { LocalDate.parse(it.date) }
                                        val from = if (exportAll) recorded.minOrNull() ?: LocalDate.now() else LocalDate.parse(exportFrom)
                                        val to = if (exportAll) recorded.maxOrNull() ?: LocalDate.now() else LocalDate.parse(exportTo)
                                        require(from <= to) { "La fecha inicial debe ser anterior a la final." }
                                        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 36600) { "Elige un intervalo de hasta 100 años." }
                                        rangeFrom = from.toString(); rangeTo = to.toString()
                                        excelLauncher.launch("Jornada_${from}_${to}.xlsx")
                                    } catch (e: Exception) { scope.launch { snack.showSnackbar(e.message ?: "Revisa las fechas.") } }
                                }, onBackup = { backupSource = null; backupLauncher.launch("Jornada_copia_${LocalDate.now()}.json") }, onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, onAudit = { editor = "audit" }, onRecovery = { editor = "recovery" })
                        }
                    }
                }
            }
        }
    }
    if (editor != null) {
        ModalBottomSheet(onDismissRequest = { if (!busy) editor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (editor) {
                "entry" -> EntryEditor(editingEntry, LocalDate.parse(selectedDay), busy) { entry, reason -> perform("Registro guardado", { editor = null }) { store.save(entry, reason) } }
                "schedule" -> ScheduleEditor(editingSchedule, busy) { schedule -> perform("Horario guardado", { editor = null }) { store.save(schedule) } }
                "note" -> NoteEditor(selectedDay, data.notes.find { it.date == selectedDay }, busy) { note -> perform("Caso puntual guardado", { editor = null }) { store.save(note) } }
                "audit" -> AuditScreen(data.audit)
                "recovery" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Copias antes de restaurar", style = MaterialTheme.typography.headlineSmall)
                    Text("Puedes exportar el estado anterior a cada restauración.")
                    val backups = remember { store.previousBackups() }
                    if (backups.isEmpty()) Text("Aún no se ha restaurado ninguna copia.")
                    backups.forEach { name -> OutlinedButton(onClick = { backupSource = name; backupLauncher.launch(name) }, modifier = Modifier.fillMaxWidth()) { Text(name.removePrefix("antes-de-restaurar-").removeSuffix(".json").toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) } ?: name) } }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (restorePending != null) AlertDialog(onDismissRequest = { restorePending = null }, title = { Text("¿Restaurar esta copia?") }, text = { Text("Se reemplazarán los registros y horarios actuales por ${restorePending!!.entries.size} fichajes y ${restorePending!!.schedules.size} horarios. Guardaremos una copia del estado anterior en este dispositivo, accesible desde Informe.") }, confirmButton = { TextButton(onClick = { val pending = restorePending!!; restorePending = null; perform("Copia restaurada") { store.restore(pending) } }) { Text("Restaurar") } }, dismissButton = { TextButton(onClick = { restorePending = null }) { Text("Cancelar") } })
}

@Composable private fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); if (action != null) TextButton(onClick = onAction) { Text(action) } }
}
@Composable private fun Muted(text: String, modifier: Modifier = Modifier) { Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun EmptyState(title: String, detail: String) { Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Muted(detail) } }
@Composable private fun Status(text: String, warning: Boolean = false) { Surface(color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) } }

@Composable private fun TodayScreen(data: Snapshot, now: Long, busy: Boolean, modifier: Modifier, onPunch: () -> Unit, onAdd: () -> Unit, onEdit: (Entry) -> Unit, onSchedule: () -> Unit, onNote: () -> Unit) {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val open = data.entries.find { !it.voided && it.end == null }
    val s = summarize(today, data, now)
    LazyColumn(modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)) {
        item { Text(today.format(humanDate).replaceFirstChar { it.titlecase(es) }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (open == null) "Hoy" else "Jornada en curso", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                Text(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(shortTime), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                if (open != null) {
                    Status("Entrada a las ${open.local(open.start).format(shortTime)} · ${minutes((now - open.start).coerceAtLeast(0) / 60000)}")
                    if (open.date != today) Status("Hay una entrada abierta del ${open.date}. Revisa la salida.", true)
                } else Muted(if (s.entries.isEmpty()) "Cuando empieces, registra tu entrada." else "Fuera de jornada. Puedes iniciar otro tramo.")
                Button(onClick = onPunch, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(if (open == null) Icons.Default.PlayArrow else Icons.Default.Check, null)
                    Spacer(Modifier.width(12.dp)); Text(if (open == null) "Fichar entrada" else "Fichar salida", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            SectionTitle("Tu horario de hoy", if (data.schedules.isEmpty()) "Configurar" else "Ver horario", onSchedule)
            if (data.schedules.isEmpty()) EmptyState("Empieza por tu horario", "Añade los días y las horas de trabajo para calcular diferencias.")
            else {
                Text(if (s.slots.isEmpty()) s.note?.kind?.takeIf { it != "Observación" && it != "Justificación" } ?: "Día sin horario previsto" else s.slots.joinToString("   /   ") { "${it.start} – ${it.end}" }, style = MaterialTheme.typography.titleMedium)
                if (s.planned > 0) Muted("${minutes(s.planned)} previstas · ${s.tolerance} min de tolerancia")
            }
        }
        item { HorizontalDivider(); SectionTitle("Actividad de hoy", "Añadir", onAdd) }
        if (s.entries.isEmpty()) item { EmptyState("Todavía no hay fichajes", "Aquí verás tus entradas y salidas de hoy.") }
        s.entries.forEach { entry -> item(key = entry.id) { EntryRow(entry, onEdit) } }
        if (s.entries.isNotEmpty()) item { Summary(s) }
        item { OutlinedButton(onClick = onNote, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Anotar un caso puntual") }; s.note?.let { Muted("${it.kind}: ${it.note}", Modifier.padding(top = 12.dp)) } }
    }
}

@Composable private fun Summary(s: DaySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (s.open) "Trabajado hasta ahora" else "Tiempo trabajado"); Text(minutes(s.worked), fontWeight = FontWeight.SemiBold) }
        if (!s.open && s.planned > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted("Balance con lo previsto"); Text("${if (s.difference > 0) "+" else ""}${minutes(s.difference)}") }
        if (s.late > 0) Status("Entrada: ${s.late} min después${if (s.late <= s.tolerance) " · dentro de tolerancia" else ""}", s.late > s.tolerance)
        if (s.early > 0) Status("Salida: ${s.early} min antes", s.early > s.tolerance)
        if (s.after > 0) Status("Salida: ${s.after} min después", s.after > s.tolerance)
        if (s.missing > 0) Status("${s.missing} tramo(s) sin fichajes", true)
        if (s.note?.kind == "Justificación") Muted("Diferencias justificadas: ${s.note.note}")
    }
}
@Composable private fun EntryRow(entry: Entry, onEdit: (Entry) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onEdit(entry) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${entry.local(entry.start).format(shortTime)}  →  ${entry.end?.let { entry.local(it).format(shortTime) } ?: "En curso"}", style = MaterialTheme.typography.titleMedium)
            Muted(if (entry.voided) "Anulado · ${entry.reason}" else if (entry.end == null) "Entrada registrada" else "${minutes((entry.end - entry.start) / 60000)}${if (entry.local(entry.end).toLocalDate() != entry.date) " · salida ${entry.local(entry.end).toLocalDate()}" else ""}")
            if (entry.note.isNotBlank()) Muted(entry.note)
        }
        IconButton(onClick = { onEdit(entry) }) { Icon(Icons.Default.Edit, "Editar fichaje") }
    }
}

@Composable private fun CalendarScreen(data: Snapshot, selected: String, onSelect: (String) -> Unit, modifier: Modifier, onAdd: () -> Unit, onEdit: (Entry) -> Unit, onNote: () -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    val day = LocalDate.parse(selected)
    val s = summarize(day, data)
    LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthText = month.minusMonths(1).toString() }) { Icon(Icons.Default.KeyboardArrowLeft, "Mes anterior") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", es)).replaceFirstChar { it.titlecase(es) }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { monthText = YearMonth.now().toString(); onSelect(LocalDate.now().toString()) }) { Text("Hoy") }
                IconButton(onClick = { monthText = month.plusMonths(1).toString() }) { Icon(Icons.Default.KeyboardArrowRight, "Mes siguiente") }
            }
            Row { listOf("L", "M", "X", "J", "V", "S", "D").forEach { Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) { Muted(it) } } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val cells = (offset + month.lengthOfMonth() + 6) / 7 * 7
            (0 until cells / 7).forEach { week -> Row {
                (0..6).forEach { weekday ->
                    val number = week * 7 + weekday - offset + 1
                    if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).heightIn(min = 54.dp))
                    else {
                        val date = month.atDay(number); val summary = summarize(date, data)
                        val isSelected = date == day
                        Surface(onClick = { onSelect(date.toString()) }, modifier = Modifier.weight(1f).heightIn(min = 54.dp).semantics { contentDescription = "${date.format(humanDate)}, ${if (summary.entries.isNotEmpty()) "con fichajes" else "sin fichajes"}${if (summary.incident) ", con diferencias" else ""}${if (summary.note != null) ", ${summary.note.kind}" else ""}" }, color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(number.toString(), fontWeight = if (date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal)
                                Text(if (summary.note != null) "•" else if (summary.entries.isNotEmpty()) "—" else if (summary.planned > 0 && date < LocalDate.now()) "·" else "", color = if (summary.incident && summary.note?.kind != "Justificación") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } }
            Muted("— Fichajes   • Caso puntual   · Sin fichar", Modifier.padding(top = 12.dp))
        }
        item { HorizontalDivider(); SectionTitle(day.format(DateTimeFormatter.ofPattern("d 'de' MMMM", es)), "Añadir", onAdd); Muted(if (s.slots.isEmpty()) "Sin horario previsto" else s.slots.joinToString(" / ") { "${it.start}–${it.end}" }) }
        if (s.entries.isEmpty()) item { EmptyState(if (s.planned > 0 && day < LocalDate.now()) "Sin fichajes registrados" else "No hay fichajes este día", "Puedes añadir uno olvidado indicando el motivo.") }
        s.entries.forEach { e -> item(key = e.id) { EntryRow(e, onEdit) } }
        if (s.entries.isNotEmpty()) item { Summary(s) }
        item { s.note?.let { Status(it.kind); Text(it.note, Modifier.padding(top = 8.dp, bottom = 12.dp)) }; OutlinedButton(onClick = onNote, modifier = Modifier.fillMaxWidth()) { Text(if (s.note == null) "Anotar un caso puntual" else "Editar caso puntual") } }
        val voided = data.entries.filter { it.voided && it.date == day }
        if (voided.isNotEmpty()) item { Muted("${voided.size} fichaje(s) anulado(s). Toca uno para revisarlo o recuperarlo.") }
        voided.forEach { e -> item(key = e.id) { EntryRow(e, onEdit) } }
    }
}

@Composable private fun ScheduleScreen(data: Snapshot, modifier: Modifier, onAdd: () -> Unit, onEdit: (Schedule) -> Unit) {
    val current = data.schedules.filter { it.effective <= LocalDate.now().toString() }.maxByOrNull { it.effective }
    LazyColumn(modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)) {
        item { Text("Horario de trabajo", style = MaterialTheme.typography.headlineMedium); Muted("Cada horario tiene una fecha de inicio. Al añadir uno nuevo, los días anteriores mantienen el que les corresponde.", Modifier.padding(top = 12.dp)); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Añadir horario") } }
        if (data.schedules.isEmpty()) item { EmptyState("Define tu primera semana", "Puedes configurar días libres, jornada partida y turnos que terminan al día siguiente.") }
        data.schedules.forEach { s -> item(key = s.id) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(); Spacer(Modifier.height(6.dp))
                Status(if (s.id == current?.id) "Horario vigente" else if (s.effective > LocalDate.now().toString()) "Próximo horario" else "Horario anterior")
                SectionTitle(s.name, "Corregir") { onEdit(s) }
                Muted("Desde ${s.effective} · Tolerancia: ${s.tolerance} min")
                (1..7).forEach { d -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, es).replaceFirstChar { it.titlecase(es) }, Modifier.width(52.dp)); Text(s.days[d].orEmpty().joinToString(" / ") { "${it.start}–${it.end}" }.ifEmpty { "Libre" }, Modifier.weight(1f)) } }
            }
        } }
    }
}

@Composable private fun ReportScreen(data: Snapshot, from: String, to: String, all: Boolean, onFrom: (String) -> Unit, onTo: (String) -> Unit, onAll: (Boolean) -> Unit, busy: Boolean, modifier: Modifier, onExcel: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onAudit: () -> Unit, onRecovery: () -> Unit) {
    val selected = data.entries.filter { !it.voided && (all || it.date.toString() in from..to) }
    LazyColumn(modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)) {
        item { Text("Exportar registros", style = MaterialTheme.typography.headlineMedium); Muted("Excel con fichajes, resumen diario, incidencias, horarios e historial de correcciones.", Modifier.padding(top = 12.dp)) }
        item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onAll(!all) }) { Text("Todo el historial", Modifier.weight(1f)); Switch(checked = all, onCheckedChange = onAll) } }
        if (!all) { item { DateField("Desde", from, onFrom) }; item { DateField("Hasta", to, onTo) } }
        item {
            Text("${selected.size} fichajes · ${minutes(selected.filter { it.end != null }.sumOf { (it.end!! - it.start) / 60000 })}", style = MaterialTheme.typography.titleMedium)
            Muted("El intervalo incluye ambas fechas. Cada turno pertenece al día de su entrada. Los fichajes abiertos se identifican sin inventar una salida.", Modifier.padding(top = 8.dp))
            Button(onClick = onExcel, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 56.dp)) { Text("Exportar a Excel (.xlsx)") }
        }
        item { HorizontalDivider(); SectionTitle("Copias y seguridad"); Muted("Los datos están solo en este dispositivo. Exporta una copia para conservarlos al cambiar de móvil o desinstalar."); OutlinedButton(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Guardar copia completa") }; OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restaurar una copia") }; TextButton(onClick = onRecovery, modifier = Modifier.fillMaxWidth()) { Text("Copias anteriores a restauraciones") } }
        item { SectionTitle("Historial de cambios"); Muted("Las correcciones y anulaciones conservan sus valores anteriores y el motivo. Las horas dependen del reloj del dispositivo."); OutlinedButton(onClick = onAudit, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Ver historial de cambios") }; Muted("Jornada 1.0.0", Modifier.padding(top = 16.dp)) }
    }
}

@Composable private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(value), onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { val d = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now()); DatePickerDialog(context, { _, y, m, day -> onChange(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show() }) { Icon(Icons.Default.DateRange, "Elegir $label") } })
}
@Composable private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { val t = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.now()); TimePickerDialog(context, { _, h, m -> onChange(LocalTime.of(h, m).toString()) }, t.hour, t.minute, true).show() }) { Icon(Icons.Default.Edit, "Elegir $label") } })
}

@Composable private fun EntryEditor(existing: Entry?, day: LocalDate, busy: Boolean, onSave: (Entry, String) -> Unit) {
    val initialStart = existing?.local(existing.start) ?: day.atTime(LocalTime.now().withSecond(0).withNano(0))
    var date by rememberSaveable { mutableStateOf(initialStart.toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(initialStart.toLocalTime().format(shortTime)) }
    var closed by rememberSaveable { mutableStateOf(existing?.end != null) }
    var endDate by rememberSaveable { mutableStateOf(existing?.end?.let { existing.local(it).toLocalDate().toString() } ?: day.toString()) }
    var endTime by rememberSaveable { mutableStateOf(existing?.end?.let { existing.local(it).format(shortTime) } ?: LocalTime.now().format(shortTime)) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var voided by rememberSaveable { mutableStateOf(existing?.voided ?: false) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (existing == null) "Añadir fichaje" else "Corregir fichaje", style = MaterialTheme.typography.headlineSmall)
        Muted("Indica el motivo. Los valores anteriores se conservan en el historial.")
        DateField("Fecha de entrada", date) { date = it }; TimeField("Hora de entrada", time) { time = it }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Tiene salida", Modifier.weight(1f)); Switch(closed, { closed = it }) }
        if (closed) { DateField("Fecha de salida", endDate) { endDate = it }; TimeField("Hora de salida", endTime) { endTime = it } }
        OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Nota (opcional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(reason, { reason = it.take(2000) }, label = { Text("Motivo de la corrección") }, placeholder = { Text("Por ejemplo: olvidé fichar la salida") }, modifier = Modifier.fillMaxWidth(), isError = error.isNotBlank())
        if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Anular este registro", Modifier.weight(1f)); Switch(voided, { voided = it }) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            try {
                require(reason.isNotBlank()) { "Indica el motivo para dejar constancia." }
                val zone = ZoneId.of(existing?.zone ?: ZoneId.systemDefault().id)
                val entry = Entry(existing?.id ?: uid(), editedTimestamp(date, time, zone, existing?.start), if (closed) editedTimestamp(endDate, endTime, zone, existing?.end) else null, zone.id, note.trim(), reason.trim(), voided)
                validateEntry(entry, emptyList()); onSave(entry, reason.trim())
            } catch (e: Exception) { error = e.message ?: "Revisa las horas y las fechas." }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Guardar registro") }
    }
}

@Composable private fun ScheduleEditor(existing: Schedule?, busy: Boolean, onSave: (Schedule) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "Mi horario") }
    var effective by rememberSaveable { mutableStateOf(existing?.effective ?: LocalDate.now().toString()) }
    var tolerance by rememberSaveable { mutableStateOf((existing?.tolerance ?: 0).toString()) }
    val days = remember { mutableStateListOf<String>().apply { (1..7).forEach { d -> add(existing?.days?.get(d)?.joinToString(", ") { "${it.start}-${it.end}" }.orEmpty()) } } }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (existing == null) "Nuevo horario" else "Corregir horario", style = MaterialTheme.typography.headlineSmall)
        Muted(if (existing == null) "Se aplicará desde la fecha indicada hasta el siguiente horario. Los días anteriores mantienen su horario." else "Esta corrección recalculará las diferencias de los días cubiertos por este horario. Quedará registrada en el historial.")
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
        DateField("Vigente desde", effective) { effective = it }
        OutlinedTextField(tolerance, { tolerance = it.filter(Char::isDigit).take(3) }, label = { Text("Tolerancia diaria (minutos)") }, supportingText = { Text("Las diferencias se conservan; la tolerancia solo cambia el aviso.") }, modifier = Modifier.fillMaxWidth())
        Muted("Usa 08:00-15:00. Para jornada partida: 08:00-13:00, 16:00-19:00. Deja vacío un día libre. Si la salida es anterior a la entrada, termina al día siguiente.")
        TextButton(onClick = { (1..4).forEach { days[it] = days[0] } }) { Text("Copiar lunes a martes–viernes") }
        days.forEachIndexed { i, text -> OutlinedTextField(text, { days[i] = it }, label = { Text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL, es).replaceFirstChar { it.titlecase(es) }) }, placeholder = { Text("Libre") }, singleLine = false, modifier = Modifier.fillMaxWidth()) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            try { val s = Schedule(existing?.id ?: uid(), effective, name.trim(), tolerance.toIntOrNull() ?: -1, (1..7).associateWith { parseSlots(days[it - 1]) }); validateSchedule(s); onSave(s) }
            catch (e: Exception) { error = e.message ?: "Revisa el formato del horario: HH:mm-HH:mm." }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Guardar horario") }
    }
}

@Composable private fun NoteEditor(date: String, existing: DayNote?, busy: Boolean, onSave: (DayNote) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: "Justificación") }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Caso puntual · $date", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { noteKinds.forEach { k -> item { FilterChip(kind == k, { kind = k }, label = { Text(k) }) } } }
        Muted(if (kind in listOf("Vacaciones", "Festivo", "Baja", "Permiso")) "Este día no tendrá horas previstas. Los fichajes existentes se conservan." else "La justificación explica las diferencias sin modificar las horas reales ni el horario previsto.")
        OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Qué ocurrió") }, placeholder = { Text("Por ejemplo: cita médica autorizada") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { if (note.isBlank()) error = "Describe el caso puntual." else onSave(DayNote(date, kind, note.trim())) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Guardar caso puntual") }
    }
}

@Composable private fun AuditScreen(audit: List<Audit>) {
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Historial de cambios", style = MaterialTheme.typography.headlineSmall); Muted("Cada guardado conserva el antes, el después y su motivo. El Excel incluye las correcciones de los fichajes seleccionados.", Modifier.padding(top = 8.dp)) }
        if (audit.isEmpty()) item { EmptyState("Sin cambios registrados", "Los fichajes y cambios de horario aparecerán aquí.") }
        audit.forEach { a -> item(key = a.id) {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.reason, fontWeight = FontWeight.Medium)
                Muted(Instant.ofEpochMilli(a.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")))
                if (expanded) { Muted("Antes: ${describeAudit(a.before, a.entity)}"); Muted("Después: ${describeAudit(a.after, a.entity)}") } else Muted("Toca para ver los valores")
            }
            HorizontalDivider()
        } }
    }
}
