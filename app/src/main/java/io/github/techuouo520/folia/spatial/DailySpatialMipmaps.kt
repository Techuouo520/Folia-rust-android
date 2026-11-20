package io.github.techuouo520.folia.spatial

/** Tightly packed RGBA8 levels shared by the decoder, staging pool and GL uploader. */
internal class DailySpatialMipmaps(side: Int) {
    data class Level(val side: Int, val offset: Int, val bytes: Int)

    val levels: List<Level>
    val bytes: Int

    init {
        require(side > 0)
        val result = ArrayList<Level>()
        var size = side
        var offset = 0
        while (true) {
            val count = Math.multiplyExact(Math.multiplyExact(size, size), 4)
            result.add(Level(size, offset, count))
            offset = Math.addExact(offset, count)
            if (size == 1) break
            size = maxOf(1, size / 2)
        }
        levels = result
        bytes = offset
    }
}
