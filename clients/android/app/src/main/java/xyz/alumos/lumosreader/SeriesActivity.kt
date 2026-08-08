package xyz.alumos.lumosreader

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.alumos.lumosreader.core.LumosSession
import uniffi.lumos_core.Book

class SeriesActivity : ComponentActivity() {
    private val volumes by lazy { LumosSession.selectedCollection.sortedWith { left, right -> naturalCompare(left.fileName, right.fileName) } }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val eink = getSharedPreferences("lumos_connection", MODE_PRIVATE).getBoolean("force_eink", false)
            LumosTheme(eink) {
                Scaffold(topBar = { TopAppBar(title = { Text(volumes.firstOrNull()?.series.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(::finish) { Text("返回") } }) }) { padding ->
                    BoxWithConstraints(Modifier.fillMaxSize().padding(padding).safeDrawingPadding()) {
                        val wide = maxWidth >= 720.dp
                        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 16.dp)) {
                            Text("${volumes.size} 卷 · 按文件名排序", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                itemsIndexed(volumes, key = { _, book -> book.id }) { index, book -> VolumeCard(index, book, wide) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun VolumeCard(index: Int, book: Book, wide: Boolean) = Card(Modifier.fillMaxWidth().clickable { LumosSession.selectedBook = book; startActivity(Intent(this, ReaderActivity::class.java)) }, shape = LumosShape, border = lumosBorder(getSharedPreferences("lumos_connection", MODE_PRIVATE).getBoolean("force_eink", false))) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            var bitmap by remember(book.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(book.id) { if (book.coverUrl.isNotBlank()) LumosSession.bytes(book.coverUrl) { result -> result.onSuccess { bytes -> LumosSession.task({ decoded -> decoded.onSuccess { bitmap = it } }) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("封面无效") } } } }
            Surface(Modifier.size(if (wide) 62.dp else 68.dp, if (wide) 86.dp else 96.dp), shape = LumosShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                if (bitmap == null) Box(contentAlignment = Alignment.Center) { Text("${index + 1}") } else Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
                Text(book.fileName.substringBeforeLast('.'), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp)); Text("第 ${index + 1} 卷 · ${if (book.progressTime.isBlank()) "未读" else "已读 ${(book.progress * 100).toInt()}%"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun naturalCompare(left: String, right: String): Int {
        val pattern = Regex("\\d+|\\D+")
        val a = pattern.findAll(left.lowercase()).map { it.value }.toList(); val b = pattern.findAll(right.lowercase()).map { it.value }.toList()
        for (index in 0 until minOf(a.size, b.size)) {
            val x = a[index]; val y = b[index]
            val comparison = if (x.firstOrNull()?.isDigit() == true && y.firstOrNull()?.isDigit() == true) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.toBigIntegerOrNull()?.compareTo(y.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO) ?: 0 else x.compareTo(y)
            if (comparison != 0) return comparison
        }
        return a.size.compareTo(b.size)
    }
}
