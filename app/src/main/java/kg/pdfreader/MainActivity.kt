package kg.pdfreader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val uriState = mutableStateOf<Uri?>(null)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uriState.value = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Reader(uriState.value, onOpen = { picker.launch(arrayOf("application/pdf")) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { uriState.value = it }
    }
}

// ---------- Документ ----------

class Doc(
    val name: String,
    val file: File,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val sizes: List<IntArray>
) {
    private val lock = Any()
    private var closed = false
    val count get() = sizes.size

    fun render(index: Int, width: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        val page = renderer.openPage(index)
        try {
            val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(-1) // белый фон
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

fun displayName(ctx: Context, uri: Uri): String {
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
}

fun loadDoc(ctx: Context, uri: Uri): Doc {
    val name = displayName(ctx, uri)
    val dir = File(ctx.cacheDir, "pdf").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
    val f = File(dir, safe)
    (ctx.contentResolver.openInputStream(uri) ?: error("no stream")).use { i ->
        f.outputStream().use { o -> i.copyTo(o) }
    }
    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        val r = PdfRenderer(pfd)
        val sizes = (0 until r.pageCount).map {
            val p = r.openPage(it)
            try { intArrayOf(p.width, p.height) } finally { p.close() }
        }
        return Doc(safe.removeSuffix(".pdf").removeSuffix(".PDF"), f, pfd, r, sizes)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
}

fun shareDoc(ctx: Context, d: Doc) {
    val u = FileProvider.getUriForFile(ctx, "kg.pdfreader.files", d.file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, u)
        clipData = ClipData.newRawUri("", u)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, null))
}

// ---------- Интерфейс ----------

@Composable
fun Reader(uri: Uri?, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color.Black else Color(0xFFF2F2F7)
    val bar = if (dark) Color(0xFF1C1C1E) else Color(0xFFF9F9F9)
    val txt = if (dark) Color.White else Color.Black
    val blue = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val sep = if (dark) Color(0xFF38383A) else Color(0xFFC6C6C8)

    var doc by remember { mutableStateOf<Doc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        error = null
        doc?.close()
        doc = null
        scale = 1f
        doc = try {
            withContext(Dispatchers.IO) { loadDoc(ctx, uri) }
        } catch (e: Exception) {
            error = "Не удалось открыть файл. Возможно, он повреждён или защищён паролем."
            null
        }
    }

    Column(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.background(bar).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpen) { Text("Открыть", color = blue, fontSize = 16.sp) }
                Text(
                    doc?.name ?: "PDF Ридер",
                    Modifier.weight(1f),
                    color = txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                )
                TextButton(onClick = { doc?.let { shareDoc(ctx, it) } }, enabled = doc != null) {
                    Text("Поделиться", color = if (doc != null) blue else Color.Gray, fontSize = 16.sp)
                }
            }
            HorizontalDivider(color = sep)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = doc
            if (d != null) Pages(d, scale, { scale = it }) else Empty(error, onOpen, txt, blue)
        }
    }
}

@Composable
fun Empty(error: String?, onOpen: () -> Unit, txt: Color, blue: Color) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📄", fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text("Откройте PDF", color = txt, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Файл из WhatsApp, почты или памяти телефона", color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpen,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = blue)
        ) { Text("Выбрать файл", fontSize = 17.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = Color(0xFFFF3B30), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Pages(doc: Doc, scale: Float, setScale: (Float) -> Unit) {
    val density = LocalDensity.current
    val state = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scaleNow = rememberUpdatedState(scale)
    val setNow = rememberUpdatedState(setScale)
    var jump by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // щипок двумя пальцами — масштаб
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.size >= 2) {
                            val z = ev.calculateZoom()
                            if (z != 1f) {
                                setNow.value((scaleNow.value * z).coerceIn(1f, 3f))
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                // двойное нажатие — приблизить / вернуть
                detectTapGestures(onDoubleTap = { setNow.value(if (scaleNow.value > 1.2f) 1f else 2f) })
            }
    ) {
        val pageW = (maxWidth - 16.dp) * scale
        val pagePx = with(density) { pageW.roundToPx() }
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(
                state = state,
                modifier = Modifier.width(pageW + 16.dp).fillMaxHeight(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(doc.count) { i -> PageView(doc, i, pageW, pagePx) }
            }
        }
        Text(
            "${state.firstVisibleItemIndex + 1} / ${doc.count}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC1C1C1E))
                .clickable { jump = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White, fontSize = 15.sp
        )
    }

    if (jump) {
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text("Страница (1–${doc.count})") },
            text = {
                OutlinedTextField(
                    value = t,
                    onValueChange = { t = it.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    t.toIntOrNull()?.let { n ->
                        scope.launch { state.scrollToItem((n - 1).coerceIn(0, doc.count - 1)) }
                    }
                    jump = false
                }) { Text("Перейти") }
            },
            dismissButton = { TextButton(onClick = { jump = false }) { Text("Отмена") } }
        )
    }
}

@Composable
fun PageView(doc: Doc, index: Int, width: Dp, widthPx: Int) {
    val target = widthPx.coerceIn(200, 2500)
    var bmp by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index, target / 150) {
        if (bmp != null) delay(250) // не перерисовывать на каждом шаге щипка
        val b = withContext(Dispatchers.IO) { doc.render(index, target) }
        if (b != null) bmp = b
    }
    val size = doc.sizes[index]
    Box(
        Modifier
            .width(width)
            .aspectRatio(size[0].toFloat() / size[1].toFloat())
            .background(Color.White)
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}
