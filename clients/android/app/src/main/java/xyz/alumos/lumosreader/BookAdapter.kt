package xyz.alumos.lumosreader

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import xyz.alumos.lumosreader.core.LumosSession
import uniffi.lumos_core.Book
import java.util.LinkedHashMap

sealed interface LibraryItem {
    data class Header(val title: String) : LibraryItem
    data class BookEntry(val book: Book) : LibraryItem
}

class BookAdapter(private val open: (Book) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<LibraryItem> = emptyList()

    fun submit(value: List<LibraryItem>) { items = value; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is LibraryItem.Header) HEADER else BOOK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val density = parent.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        if (viewType == HEADER) return HeaderHolder(TextView(parent.context).apply {
            textSize = 17f; setTextColor(Color.BLACK); setPadding(dp(6), dp(18), dp(6), dp(10)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        val vendor = "${android.os.Build.MANUFACTURER} ${android.os.Build.BRAND}".lowercase()
        val eink = parent.context.getSharedPreferences("lumos_connection", android.content.Context.MODE_PRIVATE).getBoolean("force_eink", false) ||
            vendor.contains("onyx") || vendor.contains("boox") || vendor.contains("ireader") || vendor.contains("zhangyue")
        val root = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(18)); isClickable = true; isFocusable = true }
        val cover = FrameLayout(parent.context).apply { background = ColorDrawable(if (eink) Color.WHITE else Color.rgb(231, 238, 232)) }
        val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val fallback = TextView(parent.context).apply { gravity = Gravity.CENTER; textSize = 30f; setTextColor(if (eink) Color.BLACK else Color.rgb(55, 106, 75)) }
        cover.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cover.addView(fallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))
        val title = TextView(parent.context).apply { textSize = 14f; setTextColor(if (eink) Color.BLACK else Color.rgb(38, 52, 43)); maxLines = 2; setPadding(0, dp(9), 0, 0) }
        val subtitle = TextView(parent.context).apply { textSize = 11f; setTextColor(if (eink) Color.BLACK else Color.rgb(98, 112, 103)); maxLines = 1 }
        root.addView(title)
        root.addView(subtitle)
        return Holder(root, image, fallback, title, subtitle)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (val item = items[position]) {
        is LibraryItem.Header -> (holder as HeaderHolder).text.text = item.title
        is LibraryItem.BookEntry -> (holder as Holder).bind(item.book)
    }

    class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    inner class Holder(
        root: LinearLayout,
        private val image: ImageView,
        private val fallback: TextView,
        private val title: TextView,
        private val subtitle: TextView,
    ) : RecyclerView.ViewHolder(root) {
        private var boundId = ""
        fun bind(book: Book) {
            boundId = book.id
            title.text = book.title
            subtitle.text = "${(book.progress * 100).toInt()}% · ${book.author.ifBlank { book.format.uppercase() }}"
            fallback.text = book.title.take(1)
            fallback.visibility = android.view.View.VISIBLE
            image.setImageDrawable(null)
            image.visibility = android.view.View.INVISIBLE
            itemView.setOnClickListener { open(book) }
            itemView.contentDescription = "${book.title}，已阅读 ${(book.progress * 100).toInt()}%"
            if (book.coverUrl.isNotBlank()) loadCover(book)
        }

        private fun loadCover(book: Book) {
            CoverCache.get(book.id)?.let { bitmap ->
                image.setImageBitmap(bitmap); image.visibility = android.view.View.VISIBLE; fallback.visibility = android.view.View.GONE
                return
            }
            LumosSession.bytes(book.coverUrl) { result ->
                if (boundId != book.id) return@bytes
                result.onSuccess { bytes -> LumosSession.task({ decoded ->
                    if (boundId != book.id) return@task
                    decoded.onSuccess { bitmap ->
                        CoverCache.put(book.id, bitmap)
                        image.setImageBitmap(bitmap); image.visibility = android.view.View.VISIBLE; fallback.visibility = android.view.View.GONE
                    }
                }) { decodeCover(bytes) } }
            }
        }

        private fun decodeCover(bytes: ByteArray): android.graphics.Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 480) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }) ?: error("封面无效")
        }
    }

    companion object { const val HEADER = 0; const val BOOK = 1 }
}

private object CoverCache {
    private const val MAX = 24
    private val items = object : LinkedHashMap<String, android.graphics.Bitmap>(MAX, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > MAX
    }
    @Synchronized fun get(id: String) = items[id]
    @Synchronized fun put(id: String, bitmap: android.graphics.Bitmap) { items[id] = bitmap }
}
