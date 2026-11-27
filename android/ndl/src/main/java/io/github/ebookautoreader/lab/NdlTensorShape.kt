package io.github.ebookautoreader.lab

internal fun ndlImageShape(shape: LongArray): LongArray {
    require(shape.size == 4 && shape[1] == 3L &&
        shape[2] in 1L..Int.MAX_VALUE.toLong() && shape[3] in 1L..Int.MAX_VALUE.toLong()
    ) { "Expected an NDL RGB input [batch, 3, height, width] with fixed spatial dimensions: ${shape.contentToString()}" }
    return ndlSingleBatch(shape)
}

internal fun ndlTargetSizeShape(shape: LongArray): LongArray {
    require(shape.size == 2 && shape[1] == 2L) {
        "Expected an NDL target-size input [batch, 2]: ${shape.contentToString()}"
    }
    return ndlSingleBatch(shape)
}

private fun ndlSingleBatch(shape: LongArray): LongArray {
    require(shape[0] == -1L || shape[0] == 1L) {
        "NDL requires a dynamic or single-image batch: ${shape.contentToString()}"
    }
    return shape.copyOf().apply { this[0] = 1L }
}
