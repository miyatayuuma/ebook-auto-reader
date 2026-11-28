package io.github.ebookautoreader.reader

/** Stable preference keys; labels are presentation only. */
internal enum class ReadingMode(val key: String, val label: String, val description: String) {
    EARS("ears", "耳で聞く", "音声の連続性を優先し、表示は先読みページまで進みます。"),
    EYES("eyes", "目で追う", "読み上げ中は表示ページを維持し、読み終えてから次のページへ進みます。");

    companion object {
        // Preserve the existing reader behavior for users upgrading from P2.5.
        val DEFAULT = EARS
        fun fromKey(key: String?): ReadingMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
