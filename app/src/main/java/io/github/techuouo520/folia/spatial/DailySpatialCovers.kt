package io.github.techuouo520.folia.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded IO/decode and staging storage; neither Compose nor GL decodes artwork. */
internal class DailySpatialCovers(context: Context, private val source: (String) -> ByteArray?) : AutoCloseable {
    companion object {
        const val TILE = 64
        const val SIZE = 768
        val MIPMAPS = DailySpatialMipmaps(SIZE)
        const val PREVIEWS = 2048
        const val INITIAL_PREVIEWS = 1024
        const val ATLAS_SIDE = 32
        private val sharedPreviews=object: LruCache<String,Preview>(16*1024*1024) {
            override fun sizeOf(key: String,value: Preview)=value.bitmap.allocationByteCount
        }
        private val cacheWorker = (Executors.newFixedThreadPool(1) { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "Folia-ThumbnailCache")
        } as ThreadPoolExecutor).apply {
            setKeepAliveTime(10, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
        fun trimShared() { sharedPreviews.evictAll() }
        private const val STAGING_BYTES = 48 * 1024 * 1024
    }
    class Preview(val bitmap: Bitmap, val color: Int)
    private val main = Handler(Looper.getMainLooper())
    private val workers = (Executors.newFixedThreadPool(4) { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "Folia-WallCovers")
    } as ThreadPoolExecutor).apply {
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val palette = ThreadLocal.withInitial { IntArray(TILE * TILE) }
    private val thumbnailCache = CoverThumbnailCache(context)
    private val previewKeys = LruCache<String, String>(PREVIEWS)
    private val pixels = DailySpatialPixels(MIPMAPS.bytes, STAGING_BYTES)
    private val pending = mutableSetOf<String>()
    private val pendingFull = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()
    private val revision = AtomicInteger()
    private val warmEpoch = AtomicInteger()
    private val dirtyPosted = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var highActive = true
    private var dirty: () -> Unit = {}
    private val notifyDirty = Runnable { dirtyPosted.set(false); if (!closed) dirty() }

    fun setDirty(callback: () -> Unit) { dirty = callback }
    fun contentRevision() = revision.get()
    fun preview(url: String?): Preview? {
        if (url == null) return null
        previewKeys.get(url)?.let { return sharedPreviews.get(it) }
        if (SpatialCoverSource.isLocal(url)) return null
        return sharedPreviews.get(url)?.also { previewKeys.put(url, url) }
    }
    fun full(url: String?) = url?.let(pixels::ready)
    fun uploaded(url: String?) { if (url != null) pixels.uploaded(url) }
    fun highPreparation(active: Boolean) = Unit

    fun resumeHigh() = synchronized(pending) { highActive = true }

    fun releaseHigh() = synchronized(pending) {
        highActive = false
        for (task in workers.queue.toTypedArray()) {
            if (task is DecodeJob && task.high && workers.remove(task)) pendingFull.remove(task.url)
        }
        pixels.trim()
    }

    fun retainHigh(wanted: Set<String?>) = synchronized(pending) {
        // Cancel queued work, never recycle a buffer still borrowed by the GL upload.
        for (task in workers.queue.toTypedArray()) {
            if (task is DecodeJob && task.high && task.url !in wanted && workers.remove(task)) {
                pendingFull.remove(task.url)
            }
        }
        pixels.retain(wanted)
    }

    fun unavailable(url: String?): Boolean = synchronized(pending) {
        url != null && failed[url]?.let { SystemClock.uptimeMillis() - it < 30_000 } == true
    }

    fun request(url: String?, high: Boolean) {
        if (url.isNullOrEmpty() || closed) return
        if (if (high) pixels.ready(url) != null else preview(url) != null) return
        val work = if (high) pendingFull else pending
        synchronized(pending) {
            // Recheck publication under the admission lock: GL may already borrow it.
            if (closed || (high && !highActive) || (if (high) pixels.ready(url) != null else preview(url) != null)) return
            if (url in work || unavailable(url)) return
            if (high && (pixels.readyCount() + pendingFull.size + 1) * MIPMAPS.bytes > STAGING_BYTES) return
            if (high && pixels.promote(url)) { changed(); return }
            if (pending.size + pendingFull.size >= 12) return
            work.add(url)
            try { workers.execute(DecodeJob(url, high)) }
            catch (_: RejectedExecutionException) { work.remove(url) }
        }
    }

    private fun changed() {
        revision.incrementAndGet()
        if (!closed && dirtyPosted.compareAndSet(false, true)) main.post(notifyDirty)
    }

    private inner class DecodeJob(val url: String, val high: Boolean) : Runnable {
        override fun run() {
            var staging: ByteBuffer? = null
            try {
                if (closed || (high && !highActive)) return
                if (!high) {
                    loadPreview(url, false)
                    return
                }
                // Reserve pooled upload storage before spending CPU on an HD decode.
                staging = pixels.acquire() ?: return
                val version = SpatialCoverSource.version(url)
                val image = decode(url, SIZE) ?: return
                val buffer = checkNotNull(staging)
                try {
                    if (version != SpatialCoverSource.version(url)) return
                    if (sharedPreviews.get(version) == null) {
                        storePreview(url, version, Bitmap.createScaledBitmap(image, TILE, TILE, true), true)
                    } else {
                        previewKeys.put(url, version)
                    }
                    writeMipmaps(image, buffer)
                    buffer.flip()
                } finally { image.recycle() }
                synchronized(pending) {
                    if (closed || !highActive) return
                    pixels.publish(url, buffer)
                    staging = null
                }
            } finally {
                synchronized(pending) {
                    staging?.let(pixels::release)
                    (if (high) pendingFull else pending).remove(url)
                    if (!highActive) pixels.trim()
                }
                // Failed jobs also invalidate demand so the renderer can leave warm-up.
                changed()
            }
        }
    }

    private fun writeMipmaps(image: Bitmap, buffer: ByteBuffer) {
        // Build once on the decode worker. Never regenerate the entire texture array
        // on GL, or resize textures while a pinch is being drawn.
        var previous = image
        try {
            for (level in MIPMAPS.levels) {
                if (previous.width != level.side) {
                    val next = Bitmap.createScaledBitmap(previous, level.side, level.side, true)
                    if (previous !== image) previous.recycle()
                    previous = next
                }
                buffer.position(level.offset)
                previous.copyPixelsToBuffer(buffer)
            }
        } finally {
            if (previous !== image) previous.recycle()
        }
    }

    private fun loadPreview(url: String, cachedOnly: Boolean): Preview? {
        if (closed) return null
        val version = SpatialCoverSource.version(url)
        sharedPreviews.get(version)?.let {
            previewKeys.put(url, version)
            return it
        }
        val cached = thumbnailCache.read(version)
        val image = cached ?: if (cachedOnly) null else decode(url, TILE)
        if (image == null) {
            previewKeys.remove(url)
            return null
        }
        if (version != SpatialCoverSource.version(url)) {
            image.recycle()
            previewKeys.remove(url)
            return null
        }
        return storePreview(url, version, image, cached == null)
    }

    private fun storePreview(url: String, version: String, image: Bitmap, persist: Boolean): Preview {
        val preview = coloredPreview(image)
        val retained = synchronized(sharedPreviews) {
            sharedPreviews.get(version) ?: preview.also { sharedPreviews.put(version, it) }
        }
        if (retained !== preview) image.recycle()
        if (!closed) previewKeys.put(url, version)
        synchronized(pending) { failed.remove(url) }
        changed()
        if (persist) thumbnailCache.write(version, retained.bitmap)
        return retained
    }

    private fun coloredPreview(image: Bitmap): Preview {
        val scratch = checkNotNull(palette.get())
        image.getPixels(scratch, 0, TILE, 0, 0, TILE, TILE)
        val color = SpatialNative.coverAccent(scratch)
        return Preview(image, if (color != 0) color else image.getPixel(TILE / 2, TILE / 2))
    }

    private fun decode(url: String, size: Int): Bitmap? {
        return try {
            val bytes = source(url)
            if (closed) return null
            val image = bytes?.let { if (Build.VERSION.SDK_INT >= 28) decodeScaled(it, size) else decodeLegacy(it, size) }
            if (image == null) markFailed(url)
            image
        } catch (_: Exception) { markFailed(url); null }
    }

    private fun decodeScaled(bytes: ByteArray, size: Int): Bitmap? {
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val side = minOf(info.size.width, info.size.height)
            val width = maxOf(size, Math.round(info.size.width.toDouble() * size / side).toInt())
            val height = maxOf(size, Math.round(info.size.height.toDouble() * size / side).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(width, height)
            val left = (width - size) / 2
            val top = (height - size) / 2
            decoder.crop = Rect(left, top, left + size, top + size)
        }
        // The upload ABI is exactly RGBA8 even for high-bit-depth source images.
        if (image.config == Bitmap.Config.ARGB_8888) return image
        return try { image.copy(Bitmap.Config.ARGB_8888, false) } finally { image.recycle() }
    }

    /** Folia also supports API 26/27, where ImageDecoder is unavailable. */
    private fun decodeLegacy(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        try {
            val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val side = minOf(decoded.width, decoded.height)
            val x = (decoded.width - side) / 2
            val y = (decoded.height - side) / 2
            Canvas(image).drawBitmap(decoded, Rect(x, y, x + side, y + side), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            return image
        } finally { decoded.recycle() }
    }

    private fun markFailed(url: String) = synchronized(pending) {
        if (!closed) failed[url] = SystemClock.uptimeMillis()
    }

    // Disk reads run independently of network/decode work so cached entry frames cannot queue behind it.
    fun warmCached(items: List<SpatialItem>, ready: () -> Unit) {
        val epoch = warmEpoch.incrementAndGet()
        val urls = items.map { it.coverUrl }.filter { it.isNotEmpty() }.distinct()
        val missing = urls.filter { SpatialCoverSource.isLocal(it) || preview(it) == null }
        if (missing.isEmpty()) {
            main.post { if (!closed && epoch == warmEpoch.get()) ready() }
            return
        }
        cacheWorker.execute {
            try {
                for (url in missing) {
                    if (closed || epoch != warmEpoch.get()) return@execute
                    loadPreview(url, true)
                }
            } finally {
                main.post {
                    if (!closed && epoch == warmEpoch.get()) {
                        changed()
                        ready()
                    }
                }
            }
        }
    }

    fun warm(items: List<SpatialItem>, limit: Int=INITIAL_PREVIEWS, progress: (Int, Int) -> Unit, ready: () -> Unit) {
        val epoch = warmEpoch.incrementAndGet()
        val urls = items.take(limit.coerceIn(1,INITIAL_PREVIEWS)).map { it.coverUrl }.filter { it.isNotEmpty() }.distinct()
        val cursor = AtomicInteger()
        val done = AtomicInteger()
        val active = AtomicInteger(4)
        progress(0, urls.size)
        repeat(4) { workers.execute {
            try {
                while (!closed && epoch == warmEpoch.get()) {
                    val i = cursor.getAndIncrement()
                    if (i >= urls.size) break
                    if (preview(urls[i]) == null && !unavailable(urls[i]) && loadPreview(urls[i], false) != null) revision.incrementAndGet()
                    val n = done.incrementAndGet()
                    if (n % 16 == 0 || n == urls.size) main.post {
                        if (!closed && epoch == warmEpoch.get()) progress(n, urls.size)
                    }
                }
            } finally { if (active.decrementAndGet() == 0) main.post {
                // Missing local artwork is legitimate; preserve placeholder cards.
                if (!closed && epoch == warmEpoch.get()) ready()
            } }
        } }
    }

    fun cancelWarm() { warmEpoch.incrementAndGet() }
    override fun close() {
        closed = true
        cancelWarm()
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
        synchronized(pending) {
            pixels.close(); previewKeys.evictAll(); pending.clear(); pendingFull.clear(); failed.clear()
        }
    }
}
