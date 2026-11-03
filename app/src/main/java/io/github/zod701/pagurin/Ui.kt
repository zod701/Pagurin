package io.github.zod701.pagurin

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ground = Color(0xFF0B0D10)
private val RailBg = Color(0xFF101317)
private val Panel = Color(0xFF14181D)
private val Selected = Color(0xFF20252C)
private val Line = Color(0xFF23282F)
private val Line2 = Color(0xFF2F353E)
private val TextMain = Color(0xFFF1F3F5)
private val TextMid = Color(0xFFC5CBD3)
private val TextSub = Color(0xFFAEB6C0)
private val Accent = Color(0xFFF5B83D)
private val OnAccent = Color(0xFF15110A)
private val Mono = FontFamily.Monospace

private sealed class Screen { data object Library : Screen(); data object Drivers : Screen(); data object Settings : Screen(); data class Detail(val id: String) : Screen() }
// Kept over a recreated activity: a changed language must not leave the settings screen
private val ScreenSaver = Saver<Screen, String>(
    save = { when (it) { Screen.Library -> "library"; Screen.Drivers -> "drivers"; Screen.Settings -> "settings"; is Screen.Detail -> "detail:${it.id}" } },
    restore = { when { it == "drivers" -> Screen.Drivers; it == "settings" -> Screen.Settings; it.startsWith("detail:") -> Screen.Detail(it.removePrefix("detail:")); else -> Screen.Library } })
private data class Confirm(val text: String, val yes: String, val action: () -> Unit)

private fun size(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "%.1f MB".format(b / (1L shl 20).toDouble())
@Composable private fun driverLabel(n: String) = n.ifEmpty { stringResource(R.string.system_driver) }
@Composable private fun choiceLabel(c: Choice) = if (c.label != 0) stringResource(c.label) else c.text

// The one clickable surface: the focus ring is what a controller player steers by
@Composable
private fun Press(onClick: () -> Unit, modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(10.dp), background: Color = Color.Transparent, border: BorderStroke? = null,
                  requester: FocusRequester? = null, onFocus: ((Boolean) -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }; val focused by src.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocus?.invoke(focused) }
    val ring = if (focused) BorderStroke(2.dp, Accent) else border
    CompositionLocalProvider(LocalFocused provides focused) {
        Box(modifier.then(if (requester != null) Modifier.focusRequester(requester) else Modifier).clip(shape).background(background).then(if (ring != null) Modifier.border(ring, shape) else Modifier)
            .focusProperties { canFocus = true }.clickable(interactionSource = src, indication = null, onClick = onClick), content = content)   // canFocus: in touch mode a clickable takes no focus, and a controller's first press would land anywhere
    }
}

private val LocalFocused = compositionLocalOf { false }   // within a Press that has the focus

// One line cut short ends in "…"; in the focused Press it slides instead, to its end and round again, so a long name can be read whole
@Composable
private fun Label(text: String, modifier: Modifier = Modifier, size: Int = 13, color: Color = TextMain, weight: FontWeight = FontWeight.Normal, mono: Boolean = false, lines: Int = 1) =
    if (lines == 1 && LocalFocused.current)
        Text(text, modifier.basicMarquee(iterations = Int.MAX_VALUE), color, fontSize = size.sp, fontWeight = weight, fontFamily = if (mono) Mono else null, maxLines = 1, softWrap = false)
    else Text(text, modifier, color, fontSize = size.sp, fontWeight = weight, fontFamily = if (mono) Mono else null, maxLines = lines, overflow = TextOverflow.Ellipsis)

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, requester: FocusRequester? = null, onClick: () -> Unit) =
    Press(onClick, modifier.heightIn(min = 36.dp), background = Panel, border = BorderStroke(1.dp, Line2), requester = requester) { Label(text, Modifier.align(Alignment.Center).padding(horizontal = 12.dp), weight = FontWeight.Medium) }

@Composable
private fun Hints(vararg items: Pair<String, String>, start: String = "") = Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Label(start, Modifier.weight(1f), 12, TextMid)
    items.forEach { (k, v) -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Label(k, size = 12, mono = true, weight = FontWeight.Medium); Label(v, size = 12, color = TextMid) } }
}

@Composable
fun PagurinApp(store: Store, resumed: Int) = MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ground, surface = Panel)) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Library) }
    val ctx = LocalContext.current
    var games by remember { mutableStateOf<List<Game>?>(null) }
    var drivers by remember { mutableStateOf(emptyList<Driver>()) }
    var running by remember { mutableStateOf<String?>(null) }
    var free by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var notice by remember { mutableStateOf("") }
    val icons = remember { mutableStateMapOf<String, Bitmap?>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(resumed, refresh) {
        withContext(Dispatchers.IO) {
            val g = store.games(); g.filter { it.id !in icons }.forEach { icons[it.id] = store.icon(it) }
            games = g; drivers = store.drivers(); free = store.freeBytes(); running = store.runningId()
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(2000); running = withContext(Dispatchers.IO) { store.runningId() } } }

    fun attempt(block: () -> Unit) { try { block(); notice = "" } catch (e: Exception) { notice = ctx.getString(R.string.failed, e.message) } }
    // The game's code is checked before it runs: a change since its last start is asked about (Store.stageCode)
    fun start(g: Game) = scope.launch {
        if (g.tooNew) { notice = ctx.getString(R.string.game_too_new, g.name); return@launch }
        val s = try { withContext(Dispatchers.IO) { store.stageCode(g) } } catch (e: Exception) { notice = ctx.getString(R.string.failed, e.message); return@launch }
        if (s.changed) confirm = Confirm(ctx.getString(R.string.code_changed_confirm, g.name), ctx.getString(R.string.code_changed_run)) { attempt { store.play(g, s) } }
        else attempt { store.play(g, s) }
    }
    fun play(g: Game) {
        val r = store.runningId()
        when {
            r == g.id -> attempt { store.resume(g.id) }
            r != null -> confirm = Confirm(ctx.getString(R.string.start_game_confirm, g.name), ctx.getString(R.string.start)) { scope.launch { store.stopGame(); start(g).join(); refresh++ } }
            else -> start(g)
        }
    }
    // Ports installed as apps of their own, taken over through AYN's root service or Shizuku (asked for here the first time)
    var ports by remember { mutableStateOf<List<Store.InstalledPort>?>(null) }
    fun addInstalled() = scope.launch {
        if (running != null) { notice = ctx.getString(R.string.end_game_first); return@launch }
        if (withContext(Dispatchers.IO) { store.reach() } == null) {
            if (!ShizukuShell.running()) { notice = ctx.getString(R.string.shizuku_needed); return@launch }
            if (!ShizukuShell.ask()) { notice = ctx.getString(R.string.shizuku_denied); return@launch }
        }
        notice = ctx.getString(R.string.looking_for_ports)
        ports = runCatching { withContext(Dispatchers.IO) { store.installedPorts() } }.getOrElse { notice = ctx.getString(R.string.failed, it.message); return@launch }
        notice = ""
    }
    fun takeOver(p: Store.InstalledPort) = scope.launch {
        val r = runCatching { withContext(Dispatchers.IO) { store.takeOver(p) { s -> scope.launch { notice = s } } } }
        refresh++
        r.onFailure { notice = ctx.getString(R.string.failed, it.message); return@launch }
        val g = withContext(Dispatchers.IO) { store.games() }.firstOrNull { it.id.equals(p.id, ignoreCase = true) } ?: return@launch
        val save = r.getOrNull()
        if (save != null) { notice = ctx.getString(R.string.action_working, ctx.getString(R.string.action_import)); val kv = store.importSave(g, save)
            notice = if (kv["result"] == "ok") ctx.getString(R.string.takeover_done, p.name) else ctx.getString(R.string.action_failed, ctx.getString(R.string.action_import), kv["error"] ?: "") }
        else notice = ctx.getString(R.string.takeover_done, p.name)
        confirm = Confirm(ctx.getString(R.string.uninstall_app_confirm, p.pkg), ctx.getString(R.string.uninstall)) { scope.launch { notice = runCatching { withContext(Dispatchers.IO) { store.uninstallApp(p.pkg) }; ctx.getString(R.string.uninstalled, p.pkg) }.getOrElse { ctx.getString(R.string.failed, it.message) } } }
    }
    fun stop() { confirm = Confirm(ctx.getString(R.string.stop_game_confirm), ctx.getString(R.string.stop_game)) { scope.launch { store.stopGame(); refresh++ } } }

    Row(Modifier.fillMaxSize().background(Ground)) {
        Rail(when (screen) { Screen.Drivers -> 1; Screen.Settings -> 2; else -> 0 }, free, onLibrary = { screen = Screen.Library }, onDrivers = { screen = Screen.Drivers }, onSettings = { screen = Screen.Settings })
        Column(Modifier.weight(1f).padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
            val menus = listOf(Screen.Library, Screen.Drivers, Screen.Settings)
            val moveMenu: (Int) -> Unit = { by -> screen = menus[(menus.indexOf(screen) + by + menus.size) % menus.size] }
            when (val s = screen) {
                Screen.Library -> LibraryScreen(games, icons, running, ::play, ::stop, { id -> attempt { store.resume(id) } }, { screen = Screen.Detail(it.id) }, moveMenu, ::addInstalled)
                Screen.Drivers -> DriversScreen(store, drivers, games.orEmpty(), { refresh++ }, { confirm = it }, moveMenu) { screen = Screen.Library }
                Screen.Settings -> SettingsScreen(store, moveMenu, { refresh++ }) { screen = Screen.Library }
                is Screen.Detail -> games?.firstOrNull { it.id == s.id }?.let { g ->
                    DetailScreen(store, g, drivers, icons[g.id], running == g.id, running != null, ::play, ::stop, { refresh++ }, { confirm = it }) { screen = Screen.Library }
                }
            }
            if (notice.isNotEmpty()) Label(notice, size = 12, color = Accent, lines = 2)
        }
    }
    ports?.let { list ->
        AlertDialog(onDismissRequest = { ports = null }, containerColor = Panel, title = { Text(stringResource(R.string.installed_ports), color = TextMain) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) Text(stringResource(R.string.no_installed_ports), color = TextMid, fontSize = 14.sp)
                    list.forEach { p ->
                        Press({ ports = null; confirm = Confirm(ctx.getString(if (p.inLibrary) R.string.takeover_confirm_replace else R.string.takeover_confirm, p.name, p.pkg), ctx.getString(R.string.takeover)) { takeOver(p) } },
                            Modifier.fillMaxWidth(), background = Selected) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Label(p.name, Modifier.weight(1f, fill = false), size = 15, weight = FontWeight.SemiBold)
                                    if (p.inLibrary) Label(stringResource(R.string.already_in_library), size = 11, color = Accent)   // beside the name: the line below gets cut first
                                }
                                Label(listOf(p.display, size(p.bytes), p.pkg).filter { it.isNotEmpty() }.joinToString(" / "), size = 12, color = TextSub, mono = true)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ ports = null }) { Text(stringResource(R.string.close), color = Accent) } })
    }
    confirm?.let { c ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Panel, text = { Text(c.text, color = TextMain, fontSize = 15.sp) },
            confirmButton = { TextButton({ confirm = null; c.action() }) { Text(c.yes, color = Accent) } }, dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel), color = TextMid) } })
    }
}

@Composable
private fun Rail(active: Int, free: Long, onLibrary: () -> Unit, onDrivers: () -> Unit, onSettings: () -> Unit) = Column(Modifier.width(76.dp).fillMaxHeight().background(RailBg).padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Label("Pagurin", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), 12, Accent, FontWeight.Medium, mono = true)
    RailItem(stringResource(R.string.nav_library), active == 0, onLibrary) { c ->
        val w = size.width * 0.42f; val g = size.width * 0.16f
        listOf(Offset(0f, 0f), Offset(w + g, 0f), Offset(0f, w + g), Offset(w + g, w + g)).forEach { drawRoundRect(c, it, Size(w, w), CornerRadius(3f), style = Stroke(3.5f)) }
    }
    RailItem(stringResource(R.string.nav_drivers), active == 1, onDrivers) { c ->
        val m = size.width * 0.22f
        drawRoundRect(c, Offset(m, m), Size(size.width - 2 * m, size.width - 2 * m), CornerRadius(4f), style = Stroke(3.5f))
        listOf(0.36f, 0.64f).forEach { f ->
            val p = size.width * f
            drawLine(c, Offset(p, 0f), Offset(p, m), 3.5f); drawLine(c, Offset(p, size.height - m), Offset(p, size.height), 3.5f)
            drawLine(c, Offset(0f, p), Offset(m, p), 3.5f); drawLine(c, Offset(size.width - m, p), Offset(size.width, p), 3.5f)
        }
    }
    RailItem(stringResource(R.string.nav_settings), active == 2, onSettings) { c ->
        val mid = Offset(size.width / 2, size.height / 2); val r = size.width * 0.3f
        drawCircle(c, r, mid, style = Stroke(3.5f))
        for (i in 0 until 8) {
            val a = i * Math.PI / 4; val x = kotlin.math.cos(a).toFloat(); val y = kotlin.math.sin(a).toFloat()
            drawLine(c, mid + Offset(x, y) * r, mid + Offset(x, y) * (size.width * 0.48f), 3.5f)
        }
    }
    Spacer(Modifier.weight(1f))
    Label(stringResource(R.string.free_space), Modifier.align(Alignment.CenterHorizontally), 11, TextSub)
    Label(size(free), Modifier.align(Alignment.CenterHorizontally), 12, mono = true)
}

@Composable
private fun RailItem(text: String, active: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val c = if (active) Accent else TextSub
    Press(onClick, Modifier.fillMaxWidth().height(58.dp), background = if (active) Selected else Color.Transparent) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.size(20.dp)) { icon(c) }
            Label(text, size = 11, color = c, weight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

// ---- library ----
@Composable
private fun ColumnScope.LibraryScreen(games: List<Game>?, icons: Map<String, Bitmap?>, running: String?, play: (Game) -> Unit, stop: () -> Unit, resume: (String) -> Unit, open: (Game) -> Unit, moveMenu: (Int) -> Unit, addInstalled: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("pagurin", android.content.Context.MODE_PRIVATE)   // Language's file: the sort is kept across starts
    var sort by remember { mutableStateOf(prefs.getInt("sort", 0).coerceIn(0, 2)) }
    fun nextSort() { sort = (sort + 1) % 3; prefs.edit().putInt("sort", sort).apply() }
    var searching by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }
    var howTo by remember { mutableStateOf(false) }; var adding by remember { mutableStateOf(false) }; var focused by remember { mutableStateOf<Game?>(null) }
    val first = remember { FocusRequester() }
    val sortNames = listOf(stringResource(R.string.sort_name), stringResource(R.string.sort_recent), stringResource(R.string.sort_size))
    val shown = games.orEmpty().filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .let { l -> when (sort) { 1 -> l.sortedByDescending { it.lastPlayed }; 2 -> l.sortedByDescending { it.bytes }; else -> l } }
    SideEffect { Keys.handler = { k -> when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> { moveMenu(-1); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { moveMenu(1); true }
        KeyEvent.KEYCODE_BUTTON_Y -> { nextSort(); true }
        KeyEvent.KEYCODE_BUTTON_X -> { focused?.let(play); true }
        else -> false } } }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(stringResource(R.string.nav_library), size = 24, weight = FontWeight.Bold)
        Label(stringResource(R.string.games_count, games.orEmpty().size), size = 13, color = TextSub)
        Spacer(Modifier.weight(1f))
        if (searching) BasicTextField(query, { query = it }, Modifier.width(180.dp).background(Panel, RoundedCornerShape(10.dp)).border(1.dp, Line2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            singleLine = true, textStyle = TextStyle(color = TextMain, fontSize = 14.sp), cursorBrush = SolidColor(Accent))
        SmallButton(stringResource(if (searching) R.string.search_close else R.string.search)) { searching = !searching; query = "" }
        SmallButton(stringResource(R.string.sort_label, sortNames[sort])) { nextSort() }
        SmallButton(stringResource(R.string.add_game)) { adding = true }
    }
    running?.let { id ->   // above the cards, its buttons on the left: up from a card reaches them
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(R.string.resume_game)) { resume(id) }
            SmallButton(stringResource(R.string.stop_game), onClick = stop)
            Label(stringResource(R.string.running_game, games.orEmpty().firstOrNull { it.id == id }?.name ?: id), Modifier.weight(1f).padding(start = 4.dp), weight = FontWeight.Medium)
        }
    }
    if (games != null && games.isEmpty()) {
        Label(stringResource(R.string.no_games), Modifier.padding(top = 32.dp), 18, weight = FontWeight.SemiBold)
        Label(stringResource(R.string.no_games_hint), Modifier.padding(top = 8.dp), 14, TextMid, lines = 2)
    }
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(shown, key = { _, g -> g.id }) { i, g ->
            Press({ open(g) }, background = Panel, requester = if (i == 0) first else null, onFocus = { if (it) focused = g }) {
                Column {
                    Cover(g, icons[g.id], Modifier.fillMaxWidth().height(96.dp), 30)
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Label(g.name, weight = FontWeight.SemiBold)
                        Label(listOf(g.display, size(g.bytes)).filter { it.isNotEmpty() }.joinToString(" / "), Modifier.padding(top = 2.dp), 11, TextSub, mono = true)
                    }
                }
                g.badge?.let { b -> Label(stringResource(b), Modifier.padding(6.dp).background(Ground, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp), 11, weight = FontWeight.Medium) }
            }
        }
    }
    LaunchedEffect(shown.isNotEmpty()) { if (shown.isNotEmpty()) runCatching { first.requestFocus() } }
    Hints("A" to stringResource(R.string.hint_open), "X" to stringResource(R.string.hint_quick_start), "Y" to stringResource(R.string.hint_sort), "LB RB" to stringResource(R.string.hint_menu))
    if (adding) AlertDialog(onDismissRequest = { adding = false }, containerColor = Panel, title = { Text(stringResource(R.string.add_game), color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {   // the ways in: from an installed port app (AYN's root or Shizuku), from a PC
                listOf(R.string.add_installed to addInstalled, R.string.how_to_add to { howTo = true }).forEach { (t, go) ->
                    Press({ adding = false; go() }, Modifier.fillMaxWidth(), background = Selected) { Label(stringResource(t), Modifier.padding(horizontal = 12.dp, vertical = 12.dp), size = 15, weight = FontWeight.SemiBold) }
                }
            }
        },
        confirmButton = { TextButton({ adding = false }) { Text(stringResource(R.string.close), color = Accent) } })
    if (howTo) AlertDialog(onDismissRequest = { howTo = false }, containerColor = Panel, confirmButton = { TextButton({ howTo = false }) { Text(stringResource(R.string.close), color = Accent) } },
        text = { Text(stringResource(R.string.how_to_add_body), color = TextMain, fontSize = 14.sp) })
}

@Composable
private fun rememberSaveable0(v: Int) = remember { mutableIntStateOf(v) }.let { s -> object { operator fun getValue(a: Any?, b: Any?) = s.intValue; operator fun setValue(a: Any?, b: Any?, x: Int) { s.intValue = x } } }

@Composable
private fun Cover(g: Game, icon: Bitmap?, modifier: Modifier, monoSize: Int) = Box(modifier.background(Selected), contentAlignment = Alignment.Center) {
    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Label(g.name.split(' ', ':').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }, size = monoSize, weight = FontWeight.Bold)
}

// ---- game page ----
@Composable
private fun ColumnScope.DetailScreen(store: Store, g: Game, drivers: List<Driver>, icon: Bitmap?, isRunning: Boolean, anyRunning: Boolean, play: (Game) -> Unit, stop: () -> Unit, refresh: () -> Unit, ask: (Confirm) -> Unit, back: () -> Unit) {
    var tab by rememberSaveable0(0); var yAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val tabs = listOf(stringResource(R.string.tab_overview), stringResource(R.string.tab_settings), stringResource(R.string.tab_saves), stringResource(R.string.tab_log))
    val playFocus = remember { FocusRequester() }
    BackHandler(onBack = back)
    SideEffect { Keys.handler = { k -> when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> { tab = (tab + 3) % 4; true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { tab = (tab + 1) % 4; true }
        KeyEvent.KEYCODE_BUTTON_Y -> { yAction?.invoke(); true }
        else -> false } } }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallButton("‹", Modifier.width(36.dp), onClick = back)
        Cover(g, icon, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), 18)
        Column(Modifier.weight(1f)) {
            Label(g.name, size = 18, weight = FontWeight.Bold)
            Label(listOf(g.display, size(g.bytes), g.id).filter { it.isNotEmpty() }.joinToString(" / "), Modifier.padding(top = 2.dp), 11, TextSub, mono = true)
        }
        if (isRunning) SmallButton(stringResource(R.string.stop_game), onClick = stop)
        Press({ play(g) }, Modifier.height(42.dp), RoundedCornerShape(12.dp), Accent, requester = playFocus) { Label(stringResource(if (isRunning) R.string.resume_game else R.string.play), Modifier.align(Alignment.Center).padding(horizontal = 22.dp), 15, OnAccent, FontWeight.Bold) }
    }
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(38.dp).background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("LB", Modifier.padding(horizontal = 6.dp), 11, TextSub, mono = true)
        tabs.forEachIndexed { i, t ->
            Press({ tab = i }, Modifier.weight(1f).height(30.dp), RoundedCornerShape(8.dp), if (tab == i) Color(0xFF2A3038) else Color.Transparent) {
                Label(t, Modifier.align(Alignment.Center), color = if (tab == i) TextMain else TextMid, weight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Label("RB", Modifier.padding(horizontal = 6.dp), 11, TextSub, mono = true)
    }
    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
        when (tab) {
            0 -> OverviewTab(store, g)
            1 -> SettingsTab(store, g, drivers)
            2 -> SavesTab(store, g, isRunning, anyRunning, ask, refresh) { yAction = it }
            else -> LogTab(store, g) { yAction = it }
        }
    }
    when (tab) {
        1 -> Hints("A" to stringResource(R.string.hint_change), "B" to stringResource(R.string.hint_back), start = stringResource(R.string.hint_next_start))
        2 -> Hints("A" to stringResource(R.string.hint_import), "Y" to stringResource(R.string.hint_delete), "B" to stringResource(R.string.hint_back))
        3 -> Hints("Y" to stringResource(R.string.hint_save_txt), "LB RB" to stringResource(R.string.hint_tabs), "B" to stringResource(R.string.hint_back))
        else -> Hints("A" to stringResource(R.string.hint_select), "LB RB" to stringResource(R.string.hint_tabs), "B" to stringResource(R.string.hint_back))
    }
}

@Composable
private fun OverviewTab(store: Store, g: Game) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    val driver = store.driver(g)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (listOfNotNull(g.badge?.let { stringResource(R.string.info_status, g.adapter.kind) to stringResource(it) }) + listOf(stringResource(R.string.info_version) to g.display, stringResource(R.string.info_size) to size(g.bytes), stringResource(R.string.gpu_driver) to driverLabel(driver), stringResource(R.string.info_game_id) to g.id,
            stringResource(R.string.info_last_played) to if (g.lastPlayed > 0) fmt.format(Date(g.lastPlayed)) else stringResource(R.string.none),
            stringResource(R.string.info_host_features) to (g.features?.let { f -> f.sorted().joinToString(", ").ifEmpty { stringResource(R.string.none) } } ?: stringResource(R.string.not_checked)))).forEach { (k, v) ->
            Row { Label(k, Modifier.width(120.dp), 14, TextSub); Label(v, size = 14, mono = true) }
        }
    }
}

@Composable
private fun SettingsTab(store: Store, g: Game, drivers: List<Driver>) {
    // Every row holds the game's own value: there is no default behind it. A press moves to the next choice, round to the first after the last
    var values by remember(g.id) { mutableStateOf(store.values(g)) }
    var driver by remember(g.id) { mutableStateOf(store.driver(g)) }
    @Composable fun row(label: String, key: String, value: String, next: () -> Unit) {
        Press(next, Modifier.fillMaxWidth().height(44.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Label(label, weight = FontWeight.Medium); Label(key, size = 10, color = TextSub, mono = true) }
                Label(value, Modifier.widthIn(max = 220.dp), 12, TextMain, mono = true)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Line))
        }
    }
    @Composable fun setting(s: Setting) {
        val cur = values[s.key] ?: s.choices.first().value; val idx = s.choices.indexOfFirst { it.value == cur }
        row(stringResource(s.label), s.key, s.choices.getOrNull(idx)?.let { choiceLabel(it) } ?: cur) { store.setValue(g, s.key, s.choices[(idx + 1) % s.choices.size].value); values = store.values(g) }
    }
    @Composable fun heading(t: String) = Label(t, Modifier.padding(top = 6.dp, bottom = 2.dp, start = 10.dp), 11, TextSub, FontWeight.SemiBold)
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            if (g.settings.isEmpty() && !g.takesDriver) { Label(stringResource(R.string.no_settings), Modifier.padding(10.dp), 14, TextSub, lines = 3); return@Column }
            if (g.takesDriver || g.settings.any { it.group == R.string.group_graphics }) heading(stringResource(R.string.group_graphics))
            val names = listOf("") + drivers.map { it.name }
            if (g.takesDriver) row(stringResource(R.string.gpu_driver), "driver", driverLabel(driver)) { store.setDriver(g, names[(names.indexOf(driver) + 1) % names.size]); driver = store.driver(g) }
            g.settings.filter { it.group == R.string.group_graphics }.forEach { setting(it) }
            for (grp in listOf(R.string.group_input)) g.settings.filter { it.group == grp }.takeIf { it.isNotEmpty() }?.let { l -> heading(stringResource(grp)); l.forEach { setting(it) } }
        }
        Column(Modifier.weight(1f)) {
            for (grp in listOf(R.string.group_performance, R.string.group_display, R.string.group_system)) g.settings.filter { it.group == grp }.takeIf { it.isNotEmpty() }?.let { l -> heading(stringResource(grp)); l.forEach { setting(it) } }
        }
    }
}
@Composable
private fun SavesTab(store: Store, g: Game, isRunning: Boolean, anyRunning: Boolean, ask: (Confirm) -> Unit, refresh: () -> Unit, setY: ((() -> Unit)?) -> Unit) {
    var list by remember(g.id) { mutableStateOf(store.saves(g)) }; var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<String?>(null) }; var moveFocus by remember { mutableStateOf<String?>(null) }
    val exportFocus = remember { FocusRequester() }; val rowFocus = remember { mutableMapOf<String, FocusRequester>() }; val ctx = LocalContext.current
    val scope = rememberCoroutineScope(); val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    DisposableEffect(Unit) {
        setY {
            val n = focused
            if (n != null && !busy) ask(Confirm(ctx.getString(R.string.delete_save_confirm, n), ctx.getString(R.string.delete)) {
                val at = list.indexOfFirst { it.name == n }
                message = ctx.getString(if (store.deleteSave(g, n)) R.string.save_deleted else R.string.save_delete_failed, n)
                focused = null; list = store.saves(g)
                moveFocus = (list.getOrNull(at) ?: list.lastOrNull())?.name ?: ""   // the row that took its place, the new last one, or with none left the export button
            })
        }
        onDispose { setY(null) }
    }
    // After the dialog has gone: its window holds the focus until then
    LaunchedEffect(moveFocus) { moveFocus?.let { n -> delay(150); runCatching { (if (n.isEmpty()) exportFocus else rowFocus[n])?.requestFocus() }; moveFocus = null } }
    fun run(what: String, block: suspend () -> Map<String, String>) {
        if (anyRunning) { message = ctx.getString(R.string.end_game_first); return }
        busy = true; message = ctx.getString(R.string.action_working, what)
        scope.launch {
            val kv = block(); busy = false; list = withContext(Dispatchers.IO) { store.saves(g) }; refresh()
            message = if (kv["result"] == "ok") ctx.getString(R.string.action_done, what) + (kv["backup"]?.let { ctx.getString(R.string.action_backup, it) } ?: "") else ctx.getString(R.string.action_failed, what, kv["error"] ?: ctx.getString(R.string.unknown_reason))
        }
    }
    // A save from elsewhere: copied into saves/, then offered for import at once
    fun add(block: () -> String) {
        if (busy) return
        busy = true; message = ctx.getString(R.string.action_working, ctx.getString(R.string.action_add))
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }; busy = false; list = withContext(Dispatchers.IO) { store.saves(g) }
            r.onSuccess { n -> message = ctx.getString(R.string.save_added, n); ask(Confirm(ctx.getString(R.string.import_save_confirm, n), ctx.getString(R.string.action_import)) { run(ctx.getString(R.string.action_import)) { store.importSave(g, n) } }) }
                .onFailure { message = ctx.getString(R.string.action_failed, ctx.getString(R.string.action_add), it.message ?: ctx.getString(R.string.unknown_reason)) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { add { store.addSaveFromFolder(g, it) } } }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { add { store.addSaveFromZip(g, it) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!g.managesSaves) Label(stringResource(R.string.no_save_manager), size = 13, color = Accent, lines = 3)
            else {
                SmallButton(stringResource(R.string.export_current_save), requester = exportFocus) { if (!busy) run(ctx.getString(R.string.action_export)) { store.exportSave(g) } }
                SmallButton(stringResource(R.string.add_save_folder)) { if (!anyRunning) pickFolder.launch(null) else message = ctx.getString(R.string.end_game_first) }
                SmallButton(stringResource(R.string.add_save_zip)) { if (!anyRunning) pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed")) else message = ctx.getString(R.string.end_game_first) }
            }
            Label(message, Modifier.weight(1f), 12, TextMid, lines = 2)
        }
        if (list.isEmpty()) Label(stringResource(R.string.no_exports), Modifier.padding(top = 8.dp), 14, TextSub)
        list.forEach { s -> key(s.name) {   // by name, not place: a row reused for the next save stays focused and never reports it, and Y then has no target
            Press({ if (!busy && g.managesSaves) ask(Confirm(ctx.getString(R.string.import_save_confirm, s.name), ctx.getString(R.string.action_import)) { run(ctx.getString(R.string.action_import)) { store.importSave(g, s.name) } }) }, Modifier.fillMaxWidth().height(44.dp), background = Panel, requester = rowFocus.getOrPut(s.name) { FocusRequester() }, onFocus = { f -> if (f) focused = s.name else if (focused == s.name) focused = null }) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(s.name, Modifier.weight(1f), mono = true); Label("${size(s.bytes)} / ${fmt.format(Date(s.time))}", size = 12, color = TextSub)
                }
            }
        } }
    }
}

@Composable
private fun LogTab(store: Store, g: Game, setY: ((() -> Unit)?) -> Unit) {
    val log by produceState("", g.id) { value = withContext(Dispatchers.IO) { store.logTail(g) } }
    val state = rememberScrollState(); val scope = rememberCoroutineScope(); var message by remember { mutableStateOf("") }; val ctx = LocalContext.current
    fun save() { scope.launch { message = withContext(Dispatchers.IO) { runCatching { ctx.getString(R.string.log_saved, store.saveLog(g)) }.getOrElse { ctx.getString(R.string.log_save_failed, it.message) } } } }
    DisposableEffect(Unit) { setY { save() }; onDispose { setY(null) } }
    LaunchedEffect(log) { state.scrollTo(state.maxValue) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (message.isNotEmpty()) Label(message, Modifier.fillMaxWidth(), 12, TextMid, lines = 2)   // what Y's save did
    Text(log.ifEmpty { stringResource(R.string.no_log) }, Modifier.weight(1f).fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).focusable().onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.key.nativeKeyCode) { KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { state.scrollBy(240f) }; true }; KeyEvent.KEYCODE_DPAD_UP -> if (state.value > 0) { scope.launch { state.scrollBy(-240f) }; true } else false; else -> false }
    }.verticalScroll(state).padding(10.dp), TextMid, fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.sp)
    }
}

// ---- drivers ----
@Composable
private fun ColumnScope.DriversScreen(store: Store, drivers: List<Driver>, games: List<Game>, refresh: () -> Unit, ask: (Confirm) -> Unit, moveMenu: (Int) -> Unit, toLibrary: () -> Unit) {
    // The drivers there are, each with the games set to it; a game's driver is chosen in its own settings tab
    val context = LocalContext.current; var focused by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }; val rowFocus = remember { mutableMapOf<String, FocusRequester>() }; var moveFocus by remember { mutableStateOf<String?>(null) }; var deleted by remember { mutableStateOf("") }
    val usedBy = remember(games, drivers) { games.groupBy { store.driver(it) } }
    fun delete(n: String) = ask(Confirm(context.getString(R.string.delete_driver_confirm, n), context.getString(R.string.delete)) {
        val names = store.drivers().map { it.name }; val at = names.indexOf(n); val left = names - n   // read now: this closure can outlive the list it was composed with, and a row already gone can take no focus
        store.deleteDriver(n); focused = null; deleted = n; refresh()
        moveFocus = left.getOrNull(at) ?: left.lastOrNull() ?: ""   // the row that took its place, the new last one, or the system's driver, which can't be deleted
    })
    // Once the row has left the list, which comes back from another thread: removing the focused row moves the focus, over anything asked before that
    LaunchedEffect(moveFocus, drivers) { moveFocus?.let { n -> if (drivers.none { it.name == deleted }) { delay(150); runCatching { (rowFocus[n] ?: first).requestFocus() }; moveFocus = null } } }
    BackHandler(onBack = toLibrary)
    SideEffect { Keys.handler = { k -> when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> { moveMenu(-1); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { moveMenu(1); true }
        KeyEvent.KEYCODE_BUTTON_Y -> { focused?.takeIf { it.isNotEmpty() }?.let(::delete); true }
        else -> false } } }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(stringResource(R.string.gpu_driver), size = 24, weight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        SmallButton(stringResource(R.string.add_from_file)) { context.startActivity(Intent(context, DriverPickActivity::class.java)) }
    }
    Column(Modifier.weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(stringResource(R.string.drivers_note), size = 12, color = TextSub, lines = 2)
        (listOf(Driver("", 0)) + drivers).forEachIndexed { i, d -> key(d.name) {   // by name: see the saves tab
            val users = usedBy[d.name].orEmpty()
            Press({}, Modifier.fillMaxWidth().heightIn(min = 52.dp), background = Panel, border = BorderStroke(1.dp, Line),
                requester = if (i == 0) first else rowFocus.getOrPut(d.name) { FocusRequester() }, onFocus = { f -> if (f) focused = d.name else if (focused == d.name) focused = null }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) { Label(driverLabel(d.name), size = 13, weight = FontWeight.Medium, mono = d.name.isNotEmpty()); Label(if (d.name.isEmpty()) stringResource(R.string.system_driver_note) else size(d.bytes), size = 11, color = TextSub) }
                    Label(if (users.isEmpty()) stringResource(R.string.not_used) else stringResource(R.string.used_by, users.joinToString(", ") { it.name }), Modifier.weight(1f), 12, if (users.isEmpty()) TextSub else TextMain, lines = 2)
                }
            }
        } }
    }
    Hints("Y" to stringResource(R.string.hint_delete), "LB RB" to stringResource(R.string.hint_menu))
}
// ---- settings ----
@Composable
private fun ColumnScope.SettingsScreen(store: Store, moveMenu: (Int) -> Unit, changed: () -> Unit, back: () -> Unit) {
    val activity = LocalContext.current as Activity; val current = remember { Language.get(activity) }; val scope = rememberCoroutineScope()
    var gameLang by remember { mutableStateOf(store.gameLanguage()) }
    // A list of entries, each showing its value; an entry opens its own page, and B goes back to the list, onto the entry it came from
    var page by remember { mutableStateOf(0) }; var from by remember { mutableStateOf(0) }   // 0 the list, 1 the app's language, 2 the game language
    val focus = remember { FocusRequester() }
    // Each language under its own name, which reads the same whatever the app's language is
    val appLanguages = listOf("" to stringResource(R.string.language_system), "ko" to "한국어", "en" to "English")
    @Composable fun radio(name: String, on: Boolean, first: Boolean = false, pick: () -> Unit) =
        Press({ if (!on) pick() }, Modifier.fillMaxWidth().height(52.dp), background = Panel, border = BorderStroke(1.dp, Line), requester = if (on || first) focus else null) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(16.dp).border(if (on) 5.dp else 1.5.dp, if (on) Accent else Color(0xFF7C8591), CircleShape))
                Label(name, size = 14, weight = FontWeight.Medium)
            }
        }
    @Composable fun entry(n: Int, name: String, value: String) =
        Press({ from = n; page = n }, Modifier.fillMaxWidth().height(60.dp), background = Panel, border = BorderStroke(1.dp, Line), requester = if (from == n || (from == 0 && n == 1)) focus else null) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(name, Modifier.weight(1f), 15, weight = FontWeight.Medium)
                Label(value, size = 14, color = TextMid)
                Label("›", size = 18, color = TextSub)
            }
        }
    BackHandler { if (page != 0) page = 0 else back() }
    SideEffect { Keys.handler = { k -> when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> { moveMenu(-1); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { moveMenu(1); true }
        else -> false } } }
    LaunchedEffect(page) { runCatching { focus.requestFocus() } }
    Label(stringResource(when (page) { 1 -> R.string.language; 2 -> R.string.setting_language; else -> R.string.nav_settings }), Modifier.height(44.dp), 24, weight = FontWeight.Bold)
    Column(Modifier.weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (page) {
            1 -> appLanguages.forEach { (tag, name) -> radio(name, current == tag) { Language.set(activity, tag); activity.recreate() } }
            2 -> {   // the language every game runs in, where it has it (Store.setGameLanguage)
                Label(stringResource(R.string.game_language_note), Modifier.padding(bottom = 4.dp), size = 12, color = TextSub, lines = 2)
                GAME_LANGUAGES.forEachIndexed { i, c -> radio(c.text, gameLang == c.value, gameLang.isEmpty() && i == 0) { scope.launch { withContext(Dispatchers.IO) { store.setGameLanguage(c.value) }; gameLang = c.value; changed() } } }
            }
            else -> {
                entry(1, stringResource(R.string.language), appLanguages.first { it.first == current }.second)
                entry(2, stringResource(R.string.setting_language), GAME_LANGUAGES.firstOrNull { it.value == gameLang }?.text ?: stringResource(R.string.not_chosen))
            }
        }
    }
    Hints("A" to stringResource(R.string.hint_select), "LB RB" to stringResource(R.string.hint_menu), "B" to stringResource(R.string.hint_back))
}
