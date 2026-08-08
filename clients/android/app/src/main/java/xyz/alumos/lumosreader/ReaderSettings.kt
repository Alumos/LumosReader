package xyz.alumos.lumosreader

import android.content.Context

data class ReaderSettings(
    val fontSize: Int,
    val lineSpacing: Float,
    val margin: Int,
    val fontName: String,
    val titleFontName: String = "",
    val customSpacing: Boolean = false,
    val customMargins: Boolean = false,
    val letterSpacing: Float = 0f,
    val wordSpacing: Float = 1f,
    val paragraphSpacing: Float = 0f,
    val topMargin: Int = 24,
    val bottomMargin: Int = 24,
    val leftMargin: Int = 28,
    val rightMargin: Int = 28,
    val indent: Int = 2,
    val alignment: String = "left",
    val background: String = "white",
    val templateName: String = "舒适阅读",
)

class ReaderSettingsStore(context: Context) {
    private val context = context
    private val prefs = context.getSharedPreferences("lumos_reader", Context.MODE_PRIVATE)

    fun load(eink: Boolean) = ReaderSettings(
        fontSize = prefs.getInt("font_size", if (eink) 21 else 19),
        lineSpacing = prefs.getFloat("line_spacing", 1.75f),
        margin = prefs.getInt("page_margin", 28),
        fontName = prefs.getString("font_name", "").orEmpty(),
        titleFontName = prefs.getString("title_font_name", "").orEmpty(),
        customSpacing = prefs.getBoolean("custom_spacing", false),
        customMargins = prefs.getBoolean("custom_margins", false),
        letterSpacing = prefs.getFloat("letter_spacing", 0f),
        wordSpacing = prefs.getFloat("word_spacing", 1f),
        paragraphSpacing = prefs.getFloat("paragraph_spacing", 0f),
        topMargin = prefs.getInt("top_margin", 24),
        bottomMargin = prefs.getInt("bottom_margin", 24),
        leftMargin = prefs.getInt("left_margin", 28),
        // The UI intentionally exposes one horizontal-margin value. Normalize
        // legacy asymmetric settings so old profiles cannot reintroduce drift.
        rightMargin = prefs.getInt("left_margin", 28),
        indent = prefs.getInt("indent", 2),
        alignment = normalizeAlignment(prefs.getString("alignment", "left").orEmpty()),
        background = if (eink) "white" else prefs.getString("reading_background", "white").orEmpty(),
        templateName = prefs.getString("template_name", "舒适阅读").orEmpty(),
    )

    fun save(value: ReaderSettings) = prefs.edit()
        .putInt("font_size", value.fontSize)
        .putFloat("line_spacing", value.lineSpacing)
        .putInt("page_margin", value.margin)
        .putString("font_name", value.fontName)
        .putString("title_font_name", value.titleFontName)
        .putBoolean("custom_spacing", value.customSpacing)
        .putBoolean("custom_margins", value.customMargins)
        .putFloat("letter_spacing", value.letterSpacing)
        .putFloat("word_spacing", value.wordSpacing)
        .putFloat("paragraph_spacing", value.paragraphSpacing)
        .putInt("top_margin", value.topMargin)
        .putInt("bottom_margin", value.bottomMargin)
        .putInt("left_margin", value.leftMargin)
        .putInt("right_margin", value.rightMargin)
        .putInt("indent", value.indent)
        .putString("alignment", value.alignment)
        .putString("reading_background", value.background)
        .putString("template_name", value.templateName)
        .apply()

    fun saveTemplate(name: String, value: ReaderSettings) {
        context.getSharedPreferences("lumos_reader_templates", Context.MODE_PRIVATE)
            .edit().putString(name, encode(value.copy(templateName = name))).apply()
    }

    fun templates(): List<Pair<String, ReaderSettings>> =
        context.getSharedPreferences("lumos_reader_templates", Context.MODE_PRIVATE).all
            .mapNotNull { (name, raw) -> (raw as? String)?.let { name to decode(it) } }
            .sortedBy { it.first }

    fun deleteTemplate(name: String) {
        context.getSharedPreferences("lumos_reader_templates", Context.MODE_PRIVATE).edit().remove(name).apply()
    }

    private fun encode(value: ReaderSettings) = listOf(
        value.fontSize, value.lineSpacing, value.margin, value.fontName, value.titleFontName,
        value.customSpacing, value.letterSpacing, value.wordSpacing, value.paragraphSpacing,
        value.topMargin, value.bottomMargin, value.leftMargin, value.rightMargin, value.indent,
        value.alignment, value.customMargins, value.background,
    ).joinToString("\u001f")

    private fun decode(raw: String): ReaderSettings {
        val p = raw.split("\u001f")
        return load(false).copy(
            fontSize = p.getOrNull(0)?.toIntOrNull() ?: 19,
            lineSpacing = p.getOrNull(1)?.toFloatOrNull() ?: 1.75f,
            margin = p.getOrNull(2)?.toIntOrNull() ?: 28,
            fontName = p.getOrNull(3).orEmpty(),
            titleFontName = p.getOrNull(4).orEmpty(),
            customSpacing = p.getOrNull(5)?.toBoolean() ?: false,
            letterSpacing = p.getOrNull(6)?.toFloatOrNull() ?: 0f,
            wordSpacing = p.getOrNull(7)?.toFloatOrNull() ?: 1f,
            paragraphSpacing = p.getOrNull(8)?.toFloatOrNull() ?: 0f,
            topMargin = p.getOrNull(9)?.toIntOrNull() ?: 24,
            bottomMargin = p.getOrNull(10)?.toIntOrNull() ?: 24,
            leftMargin = p.getOrNull(11)?.toIntOrNull() ?: 28,
            rightMargin = p.getOrNull(12)?.toIntOrNull() ?: 28,
            indent = p.getOrNull(13)?.toIntOrNull() ?: 2,
            alignment = normalizeAlignment(p.getOrNull(14).orEmpty()),
            customMargins = p.getOrNull(15)?.toBoolean() ?: false,
            background = p.getOrNull(16).orEmpty().ifBlank { "white" },
        )
    }

    private fun normalizeAlignment(value: String) = when (value) {
        "center", "right", "justify", "left" -> value
        "居中" -> "center"
        "右对齐" -> "right"
        "两端对齐" -> "justify"
        else -> "left"
    }
}
