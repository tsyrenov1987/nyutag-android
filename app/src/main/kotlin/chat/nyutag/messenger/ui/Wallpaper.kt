// Нютаг. Обои чатов, как на iOS: общие для всех чатов и свои для отдельного чата. Выбор живёт только на
// этом телефоне (на сервер не уходит). Узоры рисуются кодом, без картинок: волны Байкала, холмы степи, звёзды.
// Своё фото из галереи хранится файлом рядом с данными приложения и приглушается, чтобы пузыри читались.

package chat.nyutag.messenger.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.messenger.BuildConfig
import chat.nyutag.messenger.data.Pictures
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Wallpaper(val raw: String) {
    MILK("milk"), BAIKAL("baikal"), STEPPE("steppe"), DAWN("dawn"), NIGHT("night"), PHOTO("photo");

    val phrase: Phrase
        get() = when (this) {
            MILK -> P.wallpaperMilk
            BAIKAL -> P.wallpaperBaikal
            STEPPE -> P.wallpaperSteppe
            DAWN -> P.wallpaperDawn
            NIGHT -> P.wallpaperNight
            PHOTO -> P.wallpaperPhoto
        }

    companion object {
        /** Варианты-узоры; фото выбирают отдельной кнопкой. */
        val drawn = listOf(MILK, BAIKAL, STEPPE, DAWN, NIGHT)

        fun of(raw: String): Wallpaper? = entries.firstOrNull { it.raw == raw }
    }
}

/** Где что выбрано. Ключ «all» — для всех чатов, иначе номер комнаты. */
class Wallpapers(context: Context) {
    private val prefs = context.getSharedPreferences("nyutag.wallpapers", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "wallpapers")
    private val choices = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (key, value) -> if (value is String) put(key, value) }
    }
    /** Меняется при каждой замене фото: картинку надо перечитать с диска. */
    var photoVersion by mutableIntStateOf(0)
        private set
    private val photos = HashMap<String, Pair<Int, Bitmap>>()

    /** Своё для чата или `null` — как во всех. */
    fun own(roomID: String): Wallpaper? = choices[roomID]?.let(Wallpaper::of)

    fun general(): Wallpaper = choices[ALL]?.let(Wallpaper::of) ?: Wallpaper.MILK

    fun resolved(roomID: String): Pair<Wallpaper, String> = own(roomID)?.let { it to roomID } ?: (general() to ALL)

    fun set(wallpaper: Wallpaper?, key: String) {
        if (wallpaper == null) choices.remove(key) else choices[key] = wallpaper.raw
        prefs.edit().apply { if (wallpaper == null) remove(key) else putString(key, wallpaper.raw) }.apply()
        if (wallpaper != Wallpaper.PHOTO) {
            photoFile(key).delete()
            photos.remove(key)
        }
    }

    /** Ужать фото и положить файлом. Тяжёлая часть — на IO, выбор меняем уже на главном потоке. */
    suspend fun setPhoto(data: ByteArray, key: String): Boolean {
        val saved = withContext(Dispatchers.IO) {
            val jpeg = runCatching { Pictures.shrunkJPEG(data) }.getOrNull() ?: return@withContext false
            runCatching {
                folder.mkdirs()
                val temporary = File(folder, "${photoFile(key).name}.part")
                temporary.writeBytes(jpeg)
                temporary.renameTo(photoFile(key))
            }.getOrDefault(false)
        }
        if (!saved) return false
        set(Wallpaper.PHOTO, key)
        photos.remove(key)
        photoVersion += 1
        return true
    }

    /** Фото с диска; раскодированное держим, пока его не заменили. */
    suspend fun photo(key: String): Bitmap? {
        val version = photoVersion
        photos[key]?.takeIf { it.first == version }?.let { return it.second }
        val image = withContext(Dispatchers.IO) {
            photoFile(key).takeIf { it.isFile }?.let { runCatching { Pictures.decode(it.readBytes(), 2000) }.getOrNull() }
        } ?: return null
        photos[key] = version to image
        return image
    }

    /** Имя файла без «!» и «:» из номера комнаты. */
    private fun photoFile(key: String) = File(folder, if (key == ALL) "all.jpg" else "${stableHash(key)}.jpg")

    companion object {
        const val ALL = "all"
    }
}

val LocalWallpapers = staticCompositionLocalOf<Wallpapers?> { null }

/** Фон под лентой чата. */
@Composable
fun WallpaperBackground(roomID: String, modifier: Modifier = Modifier) {
    val wallpapers = LocalWallpapers.current
    val (wallpaper, key) = wallpapers?.resolved(roomID) ?: (Wallpaper.MILK to Wallpapers.ALL)
    WallpaperFill(wallpaper, key, modifier)
}

/** Узор или фото. `key` — чьё фото показывать, если выбрано фото. */
@Composable
fun WallpaperFill(wallpaper: Wallpaper, key: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val night = isSystemInDarkTheme()
    fun tone(light: Long, dark: Long) = Color(0xFF000000 or if (night) dark else light)
    when (wallpaper) {
        Wallpaper.MILK -> Box(modifier.background(palette.milk))
        Wallpaper.BAIKAL -> Drawn(tone(0xDCEAF5, 0x0A1A2B), tone(0xB9D3EA, 0x0E2742), modifier) { waves(palette.ink.copy(alpha = 0.07f)) }
        Wallpaper.STEPPE -> Drawn(tone(0xF1EDD6, 0x12160E), tone(0xD6E3BC, 0x1B2614), modifier) { hills(tone(0x6E8F3A, 0x9BC05A).copy(alpha = 0.045f)) }
        Wallpaper.DAWN -> Drawn(tone(0xF8E3D6, 0x1E1420), tone(0xF3D7A6, 0x2B1F14), modifier) { stars(palette.gold.copy(alpha = 0.35f), 26) }
        Wallpaper.NIGHT -> Drawn(tone(0x2A3358, 0x070B18), tone(0x18203F, 0x111A33), modifier) { stars(Color.White.copy(alpha = 0.55f), 70) }
        Wallpaper.PHOTO -> {
            val wallpapers = LocalWallpapers.current
            val version = wallpapers?.photoVersion
            val photo by produceState<Bitmap?>(null, key, version) { value = wallpapers?.photo(key) }
            Box(modifier.background(palette.milk)) {
                photo?.let {
                    Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(palette.milk.copy(alpha = if (night) 0.45f else 0.25f)))
                }
            }
        }
    }
}

@Composable
private fun Drawn(top: Color, bottom: Color, modifier: Modifier, pattern: DrawScope.() -> Unit) {
    Canvas(modifier.background(Brush.verticalGradient(listOf(top, bottom)))) { pattern() }
}

/** Байкал: ряды мелких волн, каждый ряд сдвинут на полволны. */
private fun DrawScope.waves(line: Color) {
    val step = 34.dp.toPx()
    val length = 46.dp.toPx()
    val height = 5.dp.toPx()
    var row = 0
    var y = 12.dp.toPx()
    while (y < size.height + step) {
        val path = Path()
        var x = -length + if (row % 2 == 0) 0f else length / 2
        path.moveTo(x, y)
        while (x < size.width + length) {
            path.quadraticTo(x + length / 4, y - height, x + length / 2, y)
            path.quadraticTo(x + length * 3 / 4, y + height, x + length, y)
            x += length
        }
        drawPath(path, line, style = Stroke(width = 1.4.dp.toPx()))
        y += step
        row += 1
    }
}

/** Степь: пологие холмы внизу, чем ниже, тем плотнее. */
private fun DrawScope.hills(fill: Color) {
    val stepX = 6.dp.toPx()
    for (index in 0 until 7) {
        val base = size.height * (0.38f + index * 0.09f)
        val amplitude = (18 + (index % 3) * 8).dp.toPx()
        val period = size.width * (0.9f + (index % 2) * 0.5f)
        val phase = index * 1.7f
        val path = Path()
        path.moveTo(0f, base)
        var x = 0f
        while (x <= size.width) {
            path.lineTo(x, base - amplitude * sin(x / period * 2 * PI.toFloat() + phase))
            x += stepX
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path, fill)
    }
}

/** Звёзды: места выводятся из номера, поэтому одинаковы при каждой перерисовке и на iOS. */
private fun DrawScope.stars(ink: Color, count: Int) {
    var seed = 2_166_136_261u
    fun next(): Float {
        seed = seed * 1_664_525u + 1_013_904_223u
        return (seed shr 8).toFloat() / (1 shl 24).toFloat()
    }
    val tile = 400.dp.toPx()
    val spots = List(count) { Triple(next() * tile, next() * tile, (0.8f + next() * 1.8f).dp.toPx()) }
    for (row in 0..(size.height / tile).toInt()) {
        for (column in 0..(size.width / tile).toInt()) {
            for ((x, y, radius) in spots) drawCircle(ink, radius, Offset(x + column * tile, y + row * tile))
        }
    }
}

/** Выбор обоев: для всех чатов (`roomID == null`) или для одного. */
@Composable
fun WallpaperPicker(roomID: String?, onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallpapers = LocalWallpapers.current ?: return
    val key = roomID ?: Wallpapers.ALL
    var busy by remember { mutableStateOf(false) }
    var photoFailed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            photoFailed = !(data?.let { wallpapers.setPhoto(it, key) } ?: false)
            busy = false
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "обои-фото ${if (roomID == null) "всех чатов" else "чата"}: ${if (photoFailed) "ошибка" else "ок"}")
        }
    }

    FormPage(words(if (roomID == null) P.wallpaper else P.wallpaperForChat), onClose, closeTitle = words(P.done)) {
        // Как будет выглядеть чат: два пузыря на выбранном фоне.
        val (shown, shownKey) = roomID?.let(wallpapers::resolved) ?: (wallpapers.general() to Wallpapers.ALL)
        Box(Modifier.fillMaxWidth().heightIn(min = 170.dp).clip(RoundedCornerShape(22.dp))) {
            WallpaperFill(shown, shownKey, Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SampleBubble(words(P.wallpaperSampleTheirs), mine = false)
                SampleBubble(words(P.wallpaperSampleMine), mine = true)
            }
        }
        val tiles: List<Wallpaper?> = (if (roomID != null) listOf(null) else emptyList()) + Wallpaper.drawn
        for (line in tiles.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (wallpaper in line) {
                    val chosen = if (roomID != null) wallpapers.own(roomID) == wallpaper else wallpapers.general() == wallpaper
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable {
                        wallpapers.set(wallpaper, key)
                        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "обои ${if (roomID == null) "всех чатов" else "чата"} = ${wallpaper?.raw ?: "как во всех"}")
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp))
                                .border(if (chosen) 3.dp else 1.dp, if (chosen) palette.sky else palette.plate, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center) {
                            if (wallpaper != null) {
                                WallpaperFill(wallpaper, key, Modifier.matchParentSize())
                            } else {
                                WallpaperFill(wallpapers.general(), Wallpapers.ALL, Modifier.matchParentSize())
                                Text("↺", color = palette.quiet, fontSize = 26.sp)
                            }
                        }
                        Text(words(wallpaper?.phrase ?: P.wallpaperAsEverywhere), color = if (chosen) palette.sky else palette.ink,
                             fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp)).background(palette.card)
                .clickable(enabled = !busy) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.padding(2.dp).height(18.dp), strokeWidth = 2.dp)
            val photoChosen = (if (roomID != null) wallpapers.own(roomID) else wallpapers.general()) == Wallpaper.PHOTO
            Text((if (photoChosen) "✓ " else "") + words(P.wallpaperPickPhoto), color = palette.sky, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (photoFailed) FailureNote(words(P.wallpaperPhotoFailed))
    }
}

@Composable
private fun SampleBubble(text: String, mine: Boolean) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, color = if (mine) Color.White else palette.ink, fontSize = 16.sp,
             modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (mine) palette.mine else palette.card)
                 .padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
