package com.abeernch.civicobd.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Car
import com.abeernch.civicobd.core.Diary
import com.abeernch.civicobd.core.DiaryEntry
import com.abeernch.civicobd.core.DiaryText
import com.abeernch.civicobd.core.Maintenance
import com.abeernch.civicobd.core.Part
import com.abeernch.civicobd.core.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.US)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

private fun whenText(e: DiaryEntry) = dayFmt.format(Date(e.timeMs)) + if (e.hasTime) ", " + timeFmt.format(Date(e.timeMs)) else ""

// ---------------------------------------------------------------- photos

/** Small decoded photos, kept so a list scrolls without decoding them again. */
private val thumbs = android.util.LruCache<String, Bitmap>(48)

private fun decode(file: File, px: Int): Bitmap? {
    val key = "${file.path}@$px"
    thumbs.get(key)?.let { return it }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { thumbs.put(key, it) }
}

/** A photo decoded off the main thread, cropped to fill its box. */
@Composable
private fun Photo(file: File, modifier: Modifier, px: Int = 300, fit: Boolean = false) {
    val bmp by produceState<ImageBitmap?>(null, file.path, px) {
        value = withContext(Dispatchers.IO) { runCatching { decode(file, px) }.getOrNull()?.asImageBitmap() }
    }
    Box(modifier.background(Palette.Hairline.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(it, contentDescription = "Photo", contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The photos full screen, swiped through; a tap anywhere closes them. */
@Composable
private fun PhotoViewer(files: List<File>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black).pressable(onClick = onClose)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i -> Photo(files[i], Modifier.fillMaxSize(), px = 1400, fit = true) }
            if (files.size > 1) {
                Text(
                    "${pager.currentPage + 1} of ${files.size}",
                    style = Type.label.copy(color = Color.White),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- the card on the Car tab

/**
 * The diary on the Car tab: how many entries and the latest, with its first photo, and a quick way to add
 * one. A tap opens the diary.
 */
@Composable
internal fun DiaryCard(entries: List<DiaryEntry>, diary: Diary, onOpen: () -> Unit, onNew: () -> Unit) {
    val latest = entries.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(16.dp))
            .semantics { contentDescription = "Maintenance diary" }
            .pressable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Maintenance diary", style = Type.section.copy(color = Palette.Value), modifier = Modifier.weight(1f))
            Text(
                if (entries.isEmpty()) "" else "${entries.size} entr${if (entries.size == 1) "y" else "ies"}",
                style = Type.label,
            )
            Spacer(Modifier.width(6.dp))
            IconChevron(Palette.Label, left = false, size = 18.dp)
        }
        Spacer(Modifier.height(10.dp))
        if (latest == null) {
            Text("What was done, the parts and the receipts, kept as a dated record for when you sell.", style = Type.label)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                latest.photos.firstOrNull()?.let {
                    Photo(diary.photo(it), Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), px = 160)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(latest.title, style = Type.body, maxLines = 1)
                    Text(
                        dayFmt.format(Date(latest.timeMs)) + (latest.cost?.let { "  ·  ${rupees(it)}" } ?: ""),
                        style = Type.label,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "+  New entry",
            style = Type.body.copy(color = Palette.Accent),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onNew).padding(vertical = 6.dp),
        )
    }
}

// ---------------------------------------------------------------- the diary

/**
 * The diary page: its entries newest first, one entry opened to read, or the form for a new or changed
 * one. [startNew] opens straight on the form. [onChanged] runs after a save or delete (service jobs may
 * have been logged); [onExport] shares the PDF record.
 */
@Composable
internal fun DiaryScreen(
    diary: Diary,
    store: TripStore,
    settings: AppSettings,
    startNew: Boolean,
    onChanged: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    var version by remember { mutableIntStateOf(0) }
    val entries by produceState(emptyList<DiaryEntry>(), version) { value = withContext(Dispatchers.IO) { diary.all() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { diary.tidy() } } }
    var reading by remember { mutableStateOf<DiaryEntry?>(null) }
    var editing by remember { mutableStateOf(startNew) }
    var editEntry by remember { mutableStateOf<DiaryEntry?>(null) }

    if (editing) {
        EntryForm(diary, store, settings, editEntry, onDone = { saved, deleted ->
            editing = false
            editEntry = null
            if (saved != null || deleted) {
                version++
                onChanged()
            }
            reading = if (deleted) null else saved ?: reading
        })
        return
    }
    reading?.let { e ->
        // The entry as it is now, after any change.
        val current = entries.firstOrNull { it.id == e.id } ?: e
        EntryPage(current, diary, settings, onBack = { reading = null }, onEdit = { editEntry = current; editing = true })
        return
    }
    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Back", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                Text("Diary", style = Type.title, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    Text(
                        "Share PDF", style = Type.body.copy(color = Palette.Accent),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onExport).padding(8.dp),
                    )
                }
            }
        }
        item { OutlineButton("New entry", color = Palette.Accent) { editEntry = null; editing = true } }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Add what was done, how, the parts that went in and photos of the receipts. " +
                        "Shared as a PDF, it is a clear service record for a buyer.",
                    style = Type.label,
                )
            }
        } else {
            val spent = entries.mapNotNull { it.cost }.sum()
            item {
                Text(
                    "${entries.size} entr${if (entries.size == 1) "y" else "ies"}" + (if (spent > 0) "  ·  ${rupees(spent)} recorded" else ""),
                    style = Type.label,
                )
            }
            items(entries, key = { it.id }) { e -> EntryCard(e, diary) { reading = e } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One entry in the list: its day, title, odometer and place, cost, and its first photos. */
@Composable
private fun EntryCard(e: DiaryEntry, diary: Diary, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
            .pressable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row {
            Text(whenText(e), style = Type.label, modifier = Modifier.weight(1f))
            e.cost?.let { Text(rupees(it), style = Type.label.copy(color = Palette.Value)) }
        }
        Text(e.title, style = Type.body.copy(color = Palette.Value), modifier = Modifier.padding(top = 2.dp))
        val line = listOfNotNull(e.odometerKm?.let { "%,d km".format(it.toLong()) }, e.shop.takeIf { it.isNotBlank() },
            e.parts.size.takeIf { it > 0 }?.let { "$it part${if (it == 1) "" else "s"}" })
        if (line.isNotEmpty()) Text(line.joinToString("  ·  "), style = Type.label)
        if (e.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.photos.take(4).forEachIndexed { i, p ->
                    Box {
                        Photo(diary.photo(p), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), px = 160)
                        if (i == 3 && e.photos.size > 4) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                                Text("+${e.photos.size - 4}", style = Type.body.copy(color = Color.White))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One entry to read: everything in it, its photos large; tap a photo to see it full screen. */
@Composable
private fun EntryPage(e: DiaryEntry, diary: Diary, settings: AppSettings, onBack: () -> Unit, onEdit: () -> Unit) {
    BackHandler(onBack = onBack)
    var viewing by remember { mutableStateOf<Int?>(null) }
    val names = remember { Maintenance.load(settings).associate { it.key to it.name } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Back", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Edit", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onEdit).padding(8.dp),
                )
            }
        }
        item {
            Column {
                Text(whenText(e), style = Type.label)
                Text(e.title, style = Type.title, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                Tile("Odometer", e.odometerKm?.let { "%,d".format(it.toLong()) } ?: "--", "km", Modifier.weight(1f))
                Tile("Cost", e.cost?.let { "%,d".format(it.toLong()) } ?: "--", "PKR", Modifier.weight(1f))
            }
        }
        if (e.shop.isNotBlank()) item { Text("Done at  ${e.shop}", style = Type.body) }
        if (e.parts.isNotEmpty()) {
            item { Text("Parts", style = Type.section.copy(color = Palette.Value), modifier = Modifier.padding(top = 6.dp)) }
            items(e.parts) { p ->
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(p.name, style = Type.body)
                    DiaryText.partDetail(p).takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.label) }
                }
            }
        }
        if (e.jobs.isNotEmpty()) {
            item { Text("Service jobs: " + e.jobs.joinToString(", ") { names[it] ?: it }, style = Type.label, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (e.notes.isNotBlank()) item { Text(e.notes, style = Type.body, modifier = Modifier.padding(top = 6.dp)) }
        if (e.photos.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)) }
            items(e.photos.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { p ->
                        val i = e.photos.indexOf(p)
                        Photo(
                            diary.photo(p),
                            Modifier.weight(1f).aspectRatio(0.8f).clip(RoundedCornerShape(10.dp)).pressable { viewing = i },
                            px = 600,
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    viewing?.let { PhotoViewer(e.photos.map(diary::photo), it) { viewing = null } }
}

// ---------------------------------------------------------------- the form

/** A text box in the app's look: a hairline frame, the hint inside until something is typed. */
@Composable
private fun TextBox(value: String, hint: String, description: String, minLines: Int = 1, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.Hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = Type.label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = minLines == 1,
            minLines = minLines,
            textStyle = Type.body.copy(color = Palette.Value),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        )
    }
}

/** Several choices as chips that wrap; each tap turns one on or off. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ToggleChips(options: List<T>, chosen: Set<T>, label: (T) -> String, onToggle: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val on = o in chosen
            Text(
                label(o),
                style = Type.body.copy(color = if (on) Palette.Value else Palette.Label, fontSize = Type.sz(14f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Palette.AccentSoft else Palette.Background)
                    .border(1.dp, if (on) Palette.Accent else Palette.Hairline, RoundedCornerShape(16.dp))
                    .pressable { onToggle(o) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = Type.label, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

/**
 * A new entry, or [initial] to change. Its day is today unless stepped back, its time optional; its
 * odometer is filled in for the day from the app's reading and drives, and can be typed over. The service
 * jobs ticked are logged as done on that day. Photos come from the camera or the gallery.
 */
@Composable
private fun EntryForm(diary: Diary, store: TripStore, settings: AppSettings, initial: DiaryEntry?, onDone: (DiaryEntry?, Boolean) -> Unit) {
    BackHandler { onDone(null, false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opened = remember { System.currentTimeMillis() }
    val today = remember { midnightOf(opened) }
    val cal = remember { Calendar.getInstance().apply { timeInMillis = initial?.timeMs ?: opened } }

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var dayOffset by remember { mutableIntStateOf(daysBetween(today, midnightOf(cal.timeInMillis)).coerceAtMost(0)) }
    var withTime by remember { mutableStateOf(initial?.hasTime ?: false) }
    var hour by remember { mutableStateOf("%02d".format(cal.get(Calendar.HOUR_OF_DAY))) }
    var minute by remember { mutableStateOf("%02d".format(cal.get(Calendar.MINUTE))) }
    var odometer by remember { mutableStateOf(initial?.odometerKm?.let { fmt(it, 0) } ?: "") }
    var odoTyped by remember { mutableStateOf(initial?.odometerKm != null) }
    var cost by remember { mutableStateOf(initial?.cost?.let { fmt(it, 0) } ?: "") }
    var shop by remember { mutableStateOf(initial?.shop ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    val parts = remember { mutableStateListOf<Part>().apply { addAll(initial?.parts ?: emptyList()) } }
    var jobs by remember { mutableStateOf(initial?.jobs?.toSet() ?: emptySet()) }
    val photos = remember { mutableStateListOf<String>().apply { addAll(initial?.photos ?: emptyList()) } }
    var photoProblem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val dayStart = remember(dayOffset) { Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, dayOffset) }.timeInMillis }
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    // With no time, the entry sits at noon of its day, so it never slips a day either way.
    val timeMs = if (withTime && h != null && m != null) dayStart + h * 3_600_000L + m * 60_000L else dayStart + 12 * 3_600_000L
    val timeBad = withTime && (h == null || m == null || timeMs > System.currentTimeMillis())

    // The odometer for the day, until one is typed.
    LaunchedEffect(dayStart, withTime, h, m) {
        if (!odoTyped) {
            val at = if (dayOffset == 0 && !withTime) System.currentTimeMillis() else timeMs
            withContext(Dispatchers.IO) { Car.odometerAt(settings, store, at) }?.let { odometer = fmt(it, 0) }
        }
    }

    // Photos: the camera writes to a file of ours; the gallery hands back pictures to copy in.
    val shot = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    fun keep(open: () -> java.io.InputStream) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { runCatching { diary.addPhoto(open) } }
            name.onSuccess { photos += it; photoProblem = null }.onFailure { photoProblem = it.message ?: "That photo could not be added" }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && shot.length() > 0) keep { shot.inputStream() }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        uris.forEach { uri -> keep { context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("That photo could not be opened") } }
    }
    var viewing by remember { mutableStateOf<Int?>(null) }

    val serviceJobs = remember { Maintenance.load(settings) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    "Cancel", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable { onDone(null, false) }.padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                Text(if (initial == null) "New entry" else "Edit entry", style = Type.title)
            }
        }
        item {
            Heading("What was done")
            TextBox(title, "Timing belt and water pump", "Title") { title = it.take(80) }
        }
        item {
            Heading("When")
            DayStepper(dayStart, today, canForward = dayOffset < 0, onBack = { if (dayOffset > -3650) dayOffset-- }, onForward = { dayOffset++ })
            if (withTime) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("At", style = Type.label.copy(fontSize = Type.body.fontSize), modifier = Modifier.weight(1f))
                    TwoDigits(hour, "Hour") { hour = it }
                    Text("  :  ", style = Type.body)
                    TwoDigits(minute, "Minute") { minute = it }
                    Text("  Remove", style = Type.label.copy(color = Palette.Accent), modifier = Modifier.pressable { withTime = false }.padding(6.dp))
                }
                if (timeBad) Text("Check the time: it cannot be later than now.", style = Type.label.copy(color = Palette.Caution))
            } else {
                Text("+  Add a time", style = Type.label.copy(color = Palette.Accent), modifier = Modifier.pressable { withTime = true }.padding(vertical = 6.dp))
            }
        }
        item {
            NumberField("Odometer", odometer, "km") { odometer = it; odoTyped = true }
            NumberField("Cost", cost, "PKR") { cost = it }
            Heading("Where (workshop or shop)")
            TextBox(shop, "Workshop or shop", "Where") { shop = it.take(80) }
        }
        item { Heading("Parts") }
        items(parts.size) { i ->
            val p = parts[i]
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextBox(p.name, "Part", "Part ${i + 1}") { parts[i] = p.copy(name = it.take(80)) }
                TextBox(p.brand, "Make", "Make ${i + 1}") { parts[i] = p.copy(brand = it.take(60)) }
                TextBox(p.number, "Part number", "Part number ${i + 1}") { parts[i] = p.copy(number = it.take(60)) }
                Text("Remove", style = Type.label.copy(color = Palette.Accent), modifier = Modifier.pressable { parts.removeAt(i) }.padding(4.dp))
            }
        }
        item {
            Text("+  Add a part", style = Type.body.copy(color = Palette.Accent), modifier = Modifier.pressable { parts += Part("") }.padding(vertical = 6.dp))
        }
        item {
            Heading("Service jobs it covers (logged as done that day)")
            ToggleChips(serviceJobs.map { it.key }, jobs, { k -> serviceJobs.first { it.key == k }.name }) { k -> jobs = if (k in jobs) jobs - k else jobs + k }
        }
        item {
            Heading("Photos of receipts, parts and the work")
            if (photos.isNotEmpty()) {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photos.take(4).forEachIndexed { i, p ->
                        Box {
                            Photo(diary.photo(p), Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).pressable { viewing = i }, px = 200)
                            Box(
                                Modifier.align(Alignment.TopEnd).size(24.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f))
                                    .semantics { contentDescription = "Remove photo ${i + 1}" }.pressable { photos.removeAt(i) },
                                contentAlignment = Alignment.Center,
                            ) { Text("×", style = Type.body.copy(color = Color.White)) }
                        }
                    }
                }
                if (photos.size > 4) Text("and ${photos.size - 4} more", style = Type.label)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlineButton("Take photo", color = Palette.Accent) {
                        runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", shot)) }
                            .onFailure { photoProblem = "No camera app to take it with" }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OutlineButton("From gallery", color = Palette.Accent) {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            photoProblem?.let { Text(it, style = Type.label.copy(color = Palette.Caution), modifier = Modifier.padding(top = 6.dp)) }
        }
        item {
            Heading("Notes: how it was done, what to remember")
            TextBox(notes, "Notes", "Notes", minLines = 4) { notes = it.take(4000) }
        }
        item {
            Spacer(Modifier.height(16.dp))
            val ok = title.isNotBlank() && !timeBad
            OutlineButton(
                when {
                    title.isBlank() -> "Say what was done"
                    timeBad -> "Check the time"
                    else -> if (initial == null) "Save entry" else "Save changes"
                },
                color = if (ok) Palette.Accent else Palette.Label,
            ) {
                if (!ok) return@OutlineButton
                val entry = DiaryEntry(
                    id = initial?.id ?: -1, timeMs = timeMs, hasTime = withTime, title = title.trim(), notes = notes,
                    odometerKm = odometer.toDoubleOrNull(), cost = cost.toDoubleOrNull(), shop = shop,
                    parts = parts.filter { it.name.isNotBlank() }, jobs = serviceJobs.map { it.key }.filter { it in jobs },
                    photos = photos.toList(),
                )
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        diary.save(entry).also { s ->
                            if (s.jobs.isNotEmpty()) Maintenance.save(settings, Maintenance.fromDiary(Maintenance.load(settings), s))
                        }
                    }
                    onDone(saved, false)
                }
            }
            if (initial != null) {
                Spacer(Modifier.height(10.dp))
                OutlineButton(if (confirmDelete) "Tap again to delete this entry" else "Delete this entry", color = Palette.Warning) {
                    if (!confirmDelete) confirmDelete = true
                    else scope.launch {
                        withContext(Dispatchers.IO) { diary.delete(initial) }
                        onDone(null, true)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    viewing?.let { PhotoViewer(photos.map(diary::photo), it) { viewing = null } }
}
