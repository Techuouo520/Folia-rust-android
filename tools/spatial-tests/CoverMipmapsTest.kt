package io.github.techuouo520.folia.spatial

fun main() {
    val layout = DailySpatialMipmaps(768)
    check(layout.levels.map { it.side } == listOf(768, 384, 192, 96, 48, 24, 12, 6, 3, 1))
    check(layout.bytes == 3_145_720)
    check(layout.levels.last().offset == layout.bytes - 4)
    check(DailySpatialMipmaps(1).bytes == 4)
    check(DailySpatialMipmaps(3).levels.map { it.side } == listOf(3, 1))
    check(runCatching { DailySpatialMipmaps(0) }.isFailure)

    // All levels must survive publication, upload, retained-cache reuse and promotion.
    // Ready storage is borrowed by GL and must never be reclaimed to satisfy a decode.
    val pool = DailySpatialPixels(layout.bytes, layout.bytes * 2)
    val first = checkNotNull(pool.acquire())
    check(first.isDirect && first.capacity() == layout.bytes)
    for ((index, level) in layout.levels.withIndex()) {
        first.position(level.offset)
        repeat(level.bytes) { first.put((index + 1).toByte()) }
    }
    first.flip()
    pool.publish("a", first)
    val second = checkNotNull(pool.acquire())
    check(second !== first)
    pool.publish("b", second)
    check(runCatching { pool.acquire() }.isFailure) { "Recycled borrowed GL storage" }
    pool.uploaded("a")
    check(pool.promote("a"))
    val promoted = checkNotNull(pool.ready("a"))
    check(promoted === first && promoted.position() == 0 && promoted.remaining() == layout.bytes)
    for ((index, level) in layout.levels.withIndex()) {
        check((level.offset until level.offset + level.bytes).all { promoted.get(it) == (index + 1).toByte() })
    }
    pool.uploaded("a")
    val recycled = checkNotNull(pool.acquire())
    check(recycled === first && recycled.remaining() == layout.bytes)
    check(pool.ready("b") === second)
    pool.release(recycled)
    pool.retain(emptySet())
    pool.trim()
    check(pool.readyCount() == 0)
    val fresh = checkNotNull(pool.acquire())
    check(fresh.capacity() == layout.bytes)
    pool.release(fresh)
    pool.close()
    check(pool.acquire() == null && !pool.promote("b"))
    println("Cover mip layout and staging lifecycle passed")
}
