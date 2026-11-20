package io.github.techuouo520.folia.spatial

import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Ready buffers are borrowed by GL; only recent/free storage can be reclaimed by IO. */
internal class DailySpatialPixels(private val bytes: Int,budget: Int) {
    private val capacity=budget/bytes
    private val ready=mutableMapOf<String,ByteBuffer>()
    private val recent=linkedMapOf<String,ByteBuffer>()
    private val free=ArrayDeque<ByteBuffer>()
    private var allocated=0
    private var closed=false
    @Synchronized fun readyCount() = ready.size
    @Synchronized fun ready(url: String) = ready[url]
    @Synchronized fun promote(url: String): Boolean {
        if(closed) return false
        if(url in ready) return true
        val pixels=recent.remove(url) ?: return false
        pixels.position(0);pixels.limit(bytes);ready[url]=pixels;return true
    }
    @Synchronized fun acquire(): ByteBuffer? {
        if(closed) return null
        var pixels=free.pollFirst()
        if(pixels==null&&recent.isNotEmpty()) {
            val iterator=recent.values.iterator();pixels=iterator.next();iterator.remove()
        }
        if(pixels==null) { check(allocated<capacity) { "HD staging reservation exhausted" };pixels=ByteBuffer.allocateDirect(bytes);allocated++ }
        pixels.clear();return pixels
    }
    @Synchronized fun publish(url: String,pixels: ByteBuffer) {
        if(closed) return
        check(url !in ready) { "Duplicate HD publication" };ready[url]=pixels
    }
    @Synchronized fun release(pixels: ByteBuffer) { if(!closed) { pixels.clear();free.addLast(pixels) } }
    @Synchronized fun uploaded(url: String) { ready.remove(url)?.let { retainRecent(url,it) } }
    @Synchronized fun retain(wanted: Set<String?>) {
        val iterator=ready.entries.iterator()
        while(iterator.hasNext()) { val entry=iterator.next()
            if(entry.key !in wanted) { retainRecent(entry.key,entry.value);iterator.remove() } }
    }
    private fun retainRecent(url: String,pixels: ByteBuffer) {
        recent.remove(url)?.takeIf { it!==pixels }?.let(free::addLast)
        pixels.position(0);pixels.limit(bytes);recent[url]=pixels
    }
    // The GL owner calls this after destroying its context; decoder reservations remain counted.
    @Synchronized fun trim() {
        allocated -= ready.size + recent.size + free.size
        ready.clear()
        recent.clear()
        free.clear()
    }
    @Synchronized fun close() { closed=true;ready.clear();recent.clear();free.clear() }
}
