package io.github.ebookautoreader.reader

internal enum class TurnMethod(val label: String) {
    TAP("左右端タップ"),
    NODE("アクセシビリティのページ操作"),
    SWIPE("横スワイプ"),
}

internal data class TurnSettings(val method: TurnMethod, val rightward: Boolean, val delayed: Boolean) {
    val key: String get() = "${method.name}:$rightward:$delayed"
}
