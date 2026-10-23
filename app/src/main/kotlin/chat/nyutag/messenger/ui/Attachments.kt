// Нютаг. Вложения в чате, как на iOS: картинка в пузыре по своим пропорциям, ролик с ▶ и длительностью,
// плашка файла с именем и размером, голосовое с волной, исчезающее фото (один просмотр, без снимков экрана),
// стикер без пузыря. Запись голосового (AAC в m4a, моно 24 кГц) и панель стикеров: свои и набор «Соёмбо».

package chat.nyutag.messenger.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.text.format.Formatter
import android.util.LruCache
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.Gravity
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import chat.nyutag.core.media.Attachment
import chat.nyutag.core.media.Voice
import chat.nyutag.core.rooms.Sticker
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.data.OutgoingFile
import chat.nyutag.messenger.data.Pictures
import chat.nyutag.messenger.data.SoyomboSticker
import chat.nyutag.messenger.session.AppSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private fun check(text: String) { if (BuildConfig.DEBUG) android.util.Log.d("ui-check", text) }

/** Уменьшенные картинки ленты в памяти: прокрутка не расшифровывает и не раскодирует их заново. */
object ImageMemory {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, image: Bitmap) { cache.put(key, image) }
}

/** Ширина картинки в пузыре и высота по пропорциям, в разумных пределах. */
const val PICTURE_WIDTH = 240

fun pictureHeight(width: Int?, height: Int?, image: Bitmap?): Dp {
    val ratio = when {
        width != null && height != null && width > 0 && height > 0 -> height.toFloat() / width
        image != null && image.width > 0 -> image.height.toFloat() / image.width
        else -> 1f
    }
    return (PICTURE_WIDTH * ratio).coerceIn(120f, 320f).dp
}

/** Картинка или кадр ролика в пузыре. Пока грузится — подложка тех же пропорций, чтобы лента не прыгала. */
@Composable
fun AttachmentPicture(session: AppSession, key: String, attachment: Attachment?, local: ByteArray?, width: Int?, height: Int?) {
    val palette = LocalPalette.current
    val image by produceState(ImageMemory.get(key), key) {
        if (value != null) return@produceState
        val data = local ?: attachment?.let { session.attachmentData(it, preview = true) } ?: return@produceState
        value = withContext(Dispatchers.Default) { Pictures.decode(data, 900) }?.also { ImageMemory.put(key, it) }
    }
    Box(Modifier.size(PICTURE_WIDTH.dp, pictureHeight(width, height, image)).clip(RoundedCornerShape(12.dp)).background(palette.plate),
        contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: CircularProgressIndicator(Modifier.size(24.dp), color = palette.quiet, strokeWidth = 2.dp)
    }
}

/** Поверх кадра ролика: значок воспроизведения и длительность в уголке. */
@Composable
fun VideoBadge(duration: Int?, busy: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("▶", color = Color.White, fontSize = 24.sp)
        }
        duration?.let {
            Text(Voice.clock(it + 500), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(10.dp))
                     .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** Плашка файла: значок, имя, размер. Пока файл открывается — крутилка вместо значка. */
@Composable
fun FileChip(name: String, size: Int?, ink: Color, soft: Color, busy: Boolean) {
    val context = LocalContext.current
    Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            else Text("📄", fontSize = 18.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            size?.let { Text(Formatter.formatShortFileSize(context, it.toLong()), color = soft, fontSize = 12.sp) }
        }
    }
}

/** Заглушка исчезающего фото в пузыре: миниатюры нет, открыть можно один раз. */
@Composable
fun ViewOnceChip(mine: Boolean, seen: Boolean, ink: Color, soft: Color, busy: Boolean) {
    val words = LocalWords.current
    Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            else Text("🔥", fontSize = 18.sp, modifier = Modifier.graphicsLayer { alpha = if (seen) 0.45f else 1f })
        }
        Text(words(if (mine) P.viewOnceSent else if (seen) P.viewOnceOpened else P.viewOnceReceived),
             color = if (seen) soft else ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Стикер в ленте: картинка без пузыря. */
@Composable
fun StickerPicture(session: AppSession, key: String, attachment: Attachment?, local: ByteArray?, side: Dp = 150.dp) {
    val image by produceState(ImageMemory.get(key), key) {
        if (value != null) return@produceState
        val data = local ?: attachment?.let { session.attachmentData(it, preview = false) } ?: return@produceState
        value = withContext(Dispatchers.Default) { Pictures.decode(data, 512) }?.also { ImageMemory.put(key, it) }
    }
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Голосовое в пузыре: кнопка, волна (прослушанная часть ярче) и время. Играет одно голосовое за раз. */
@Composable
fun VoiceBubble(session: AppSession, id: String, attachment: Attachment, ink: Color, soft: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by VoicePlayer.state.collectAsState()
    val playing = state.id == id && state.playing
    val loading = state.id == id && state.loading
    val total = attachment.duration ?: 0
    val progress = if (state.id == id && total > 0) (state.position.toFloat() / total).coerceIn(0f, 1f) else 0f
    Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = 0.16f))
                .clickable { VoicePlayer.toggle(scope, context, session, id, attachment) },
            contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            else Text(if (playing) "❚❚" else "▶", color = ink, fontSize = if (playing) 14.sp else 18.sp)
        }
        Column(Modifier.weight(1f)) {
            val bars = remember(attachment.waveform) { Voice.display(attachment.waveform, 36) }
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val step = size.width / bars.size
                val bar = step * 0.55f
                bars.forEachIndexed { index, level ->
                    val h = max(size.height * level.toFloat(), 3f)
                    val played = (index + 0.5f) / bars.size <= progress
                    drawRoundRect(if (played) ink else soft.copy(alpha = 0.6f),
                                  topLeft = Offset(index * step, (size.height - h) / 2), size = Size(bar, h),
                                  cornerRadius = CornerRadius(bar / 2))
                }
            }
            Text(Voice.clock(if (state.id == id && state.position > 0) state.position else total), color = soft, fontSize = 11.sp)
        }
    }
}

/** Проигрыватель голосовых: одно за раз, позиция для волны в пузыре. */
object VoicePlayer {
    data class State(val id: String? = null, val playing: Boolean = false, val loading: Boolean = false, val position: Int = 0)

    private val flow = MutableStateFlow(State())
    val state: StateFlow<State> = flow
    private var player: MediaPlayer? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val player = player ?: return
            if (player.isPlaying) {
                flow.value = flow.value.copy(position = player.currentPosition)
                main.postDelayed(this, 50)
            }
        }
    }

    fun toggle(scope: CoroutineScope, context: Context, session: AppSession, id: String, attachment: Attachment) {
        val current = flow.value
        if (current.id == id && player != null) {
            val player = player!!
            if (player.isPlaying) {
                player.pause()
                flow.value = current.copy(playing = false)
            } else {
                player.start()
                flow.value = current.copy(playing = true)
                main.post(tick)
            }
            return
        }
        stop()
        flow.value = State(id = id, loading = true)
        scope.launch {
            val data = session.attachmentData(attachment, preview = false)
            if (flow.value.id != id) return@launch
            if (data == null) { flow.value = State(); check("голосовое не скачалось"); return@launch }
            val file = File(File(context.cacheDir, "voice").apply { mkdirs() }, "$id.m4a".replace('/', '_').replace('$', '_'))
            withContext(Dispatchers.IO) { if (!file.exists()) file.writeBytes(data) }
            if (flow.value.id != id) return@launch
            val created = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.path)
                    prepare()
                }
            }.getOrNull()
            if (created == null) { flow.value = State(); check("голосовое не играет"); return@launch }
            created.setOnCompletionListener { stop() }
            player = created
            created.start()
            flow.value = State(id = id, playing = true)
            check("голосовое играет, ${created.duration} мс")
            main.post(tick)
        }
    }

    fun stop() {
        main.removeCallbacks(tick)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        flow.value = State()
    }
}

/** Запись голосового. Не больше пяти минут; короче секунды не отправляем — это случайное нажатие. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    private val levels = ArrayList<Float>()

    val elapsed: Int get() = if (recorder == null) 0 else (System.currentTimeMillis() - started).toInt()
    val recording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        VoicePlayer.stop()
        val out = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(24_000)
            created.setAudioChannels(1)
            created.setAudioEncodingBitRate(32_000)
            created.setMaxDuration(LIMIT_MS)
            created.setOutputFile(out.path)
            created.prepare()
            created.start()
            recorder = created
            file = out
            started = System.currentTimeMillis()
            levels.clear()
            true
        } catch (e: Exception) {
            check("запись не пошла: $e")
            created.release()
            out.delete()
            false
        }
    }

    /** Уровень громкости раз в 50 мс, в дБ (0 — максимум). */
    fun sample() {
        val amplitude = runCatching { recorder?.maxAmplitude }.getOrNull() ?: return
        levels += if (amplitude <= 0) -160f else (20 * log10(amplitude / 32767.0)).toFloat()
    }

    /** Готовый к отправке файл; `null` — слишком коротко или не записалось. */
    fun finish(): OutgoingFile? {
        val recorder = recorder ?: return null
        val file = file ?: return null
        val duration = min(elapsed, LIMIT_MS)
        val stopped = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        this.recorder = null
        this.file = null
        try {
            if (!stopped || duration < 1000) return null
            return OutgoingFile.voice(file.readBytes(), duration, Voice.waveform(levels.toList()))
        } finally {
            file.delete()
        }
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }

    companion object {
        const val LIMIT_MS = 300_000
    }
}

/** Открытые для просмотра файлы: расшифрованная копия во временной папке, отдаём системе через FileProvider. */
object OpenedFiles {
    fun folder(context: Context) = File(context.cacheDir, "opened")

    fun write(context: Context, data: ByteArray, name: String): File? = runCatching {
        val dir = File(folder(context), UUID.randomUUID().toString()).apply { mkdirs() }
        File(dir, name.replace('/', '_').ifEmpty { "file" }).apply { writeBytes(data) }
    }.getOrNull()

    /** Открыть в подходящем приложении. `false` — открыть нечем. */
    fun open(context: Context, file: File, mimetype: String?): Boolean {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val type = mimetype ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(intent, null))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun clear(context: Context) { folder(context).deleteRecursively() }

    /** Имя с расширением: у файла без него приложение-просмотрщик не поймёт тип. */
    fun name(attachment: Attachment): String {
        val name = attachment.name.ifEmpty { "file" }
        if (name.substringAfterLast('.', "").isNotEmpty()) return name
        val ext = attachment.mimetype?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: return name
        return "$name.$ext"
    }
}

/** Исчезающие фото, которые мы уже открыли: второй раз не откроются, даже если удаление не дошло до сервера. */
object ViewOnceSeen {
    private const val KEY = "opened"
    private val ids = MutableStateFlow<Set<String>>(emptySet())
    val seen: StateFlow<Set<String>> = ids
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        ids.value = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
    }

    fun mark(context: Context, eventID: String) {
        if (eventID in ids.value) return
        ids.value = (ids.value + eventID).toList().takeLast(500).toSet()
        prefs(context).edit().putStringSet(KEY, ids.value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("nyutag.viewOnce", Context.MODE_PRIVATE)
}

/** Картинка во весь экран: щипок увеличивает, нажатие закрывает. */
@Composable
fun ImageViewer(image: Bitmap, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }.clickable(onClick = onClose)) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
            CloseMark(Modifier.align(Alignment.TopEnd), onClose)
        }
    }
}

/** Одноразовый просмотр во весь экран. Окно защищено: на снимке экрана и записи фото нет. */
@Composable
fun ViewOnceViewer(image: Bitmap, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            check("защита от снимка экрана ${if (window != null) "включена" else "не нашлась"}")
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            CloseMark(Modifier.align(Alignment.TopEnd), onClose)
        }
    }
}

/** Ролик во весь экран: системный проигрыватель внутри приложения, внизу — пауза и перемотка. */
@Composable
fun VideoViewer(file: File, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Поверхность ролика пробивает окно насквозь: чёрная подложка — в самом представлении, а не в Compose.
            AndroidView({ context ->
                val video = VideoView(context).apply {
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoPath(file.path)
                    setOnPreparedListener { start(); check("видео играет, ${it.duration} мс") }
                    setOnErrorListener { _, what, _ -> check("видео не играет: $what"); false }
                }
                FrameLayout(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                }
            }, Modifier.fillMaxSize(), onRelease = { (it.getChildAt(0) as? VideoView)?.stopPlayback() })
            CloseMark(Modifier.align(Alignment.TopEnd), onClose)
        }
    }
}

@Composable
private fun CloseMark(modifier: Modifier, onClose: () -> Unit) {
    Box(modifier.padding(top = 40.dp, end = 16.dp).size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Панель стикеров под полем ввода: свои (долгое нажатие — убрать) и встроенный набор «Соёмбо». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPanel(session: AppSession, revision: Int, onPick: (Sticker?, SoyomboSticker?) -> Unit, onAdd: () -> Unit,
                 onRemove: (Sticker) -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    var tab by remember { mutableStateOf(0) }
    val loaded by produceState<List<Sticker>?>(null, revision) { value = session.myStickers().stickers }
    val mine = loaded.orEmpty()
    LaunchedEffect(loaded?.isEmpty()) { if (loaded?.isEmpty() == true) tab = 1 }
    // Только что добавили свой — показать его.
    var counted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(loaded?.size) {
        val size = loaded?.size ?: return@LaunchedEffect
        counted?.let { if (size > it) tab = 0 }
        counted = size
    }
    Column(Modifier.fillMaxWidth().height(290.dp).background(palette.card)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            for ((index, title) in listOf(words(P.stickerMine), words(P.stickerSoyombo)).withIndex()) {
                Text(title, color = if (tab == index) Color.White else palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                     modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (tab == index) palette.sky else palette.milk)
                         .clickable { tab = index }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Box(Modifier.weight(1f))
            Text("＋", color = palette.sky, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onAdd).padding(horizontal = 10.dp))
        }
        if (tab == 0 && loaded == null) {
            Box(Modifier.fillMaxSize())
        } else if (tab == 0 && mine.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(words(P.stickerNone), color = palette.quiet, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                if (tab == 0) {
                    items(mine, key = { it.shortcode }) { sticker ->
                        var menu by remember { mutableStateOf(false) }
                        Box(Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp))
                                .combinedClickable(onClick = { onPick(sticker, null) }, onLongClick = { menu = true })) {
                            StickerPicture(session, "sticker-${sticker.mxc}", Attachment(msgtype = chat.nyutag.core.sync.EventType.STICKER,
                                source = Attachment.Source.Plain(sticker.mxc), name = sticker.body), null, 76.dp)
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(words(P.stickerRemove)) }, { menu = false; onRemove(sticker) })
                            }
                        }
                    }
                } else {
                    items(SoyomboSticker.all, key = { it.shortcode }) { item ->
                        val image = remember(item) { ImageMemory.get("soyombo-${item.shortcode}") ?: item.image(256).also { ImageMemory.put("soyombo-${item.shortcode}", it) } }
                        Image(image.asImageBitmap(), item.body, Modifier.padding(4.dp).size(76.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(null, item) })
                    }
                }
            }
        }
    }
}
