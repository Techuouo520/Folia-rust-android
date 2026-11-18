package io.github.techuouo520.folia.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.security.MessageDigest

internal object SpatialCoverSource {
    private const val PREFIX = "spatial-cover-v1:"
    private val sidecars = arrayOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png")

    fun key(path: String, url: String): String =
        if (path.isEmpty() && url.isEmpty()) "" else "$PREFIX${path.length}:$path$url"

    fun isLocal(key: String): Boolean = localPath(key).isNotEmpty()

    // File metadata is read only by the artwork workers.
    fun version(key: String): String {
        val path = localPath(key)
        if (path.isEmpty()) return key
        val audio = File(path)
        val files = listOf(audio) + sidecars.map { File(audio.parentFile, it) }
        return buildString {
            append(key)
            files.forEach { file ->
                append('|')
                try {
                    append(file.lastModified()).append(':').append(file.length())
                } catch (_: SecurityException) {
                    append("unavailable")
                }
            }
        }
    }

    private fun localPath(key: String): String {
        if (!key.startsWith(PREFIX)) return ""
        val separator = key.indexOf(':', PREFIX.length)
        if (separator < 0) return ""
        val length = key.substring(PREFIX.length, separator).toIntOrNull() ?: return ""
        val start = separator + 1
        if (length <= 0 || length > key.length - start) return ""
        return key.substring(start, start + length)
    }
}

internal class CoverThumbnailCache(context: Context) {
    private val app = context.applicationContext
    private val directory by lazy { File(app.cacheDir, "cover-thumbnails-v1") }

    private class Index(val files: LinkedHashMap<String, Long>, var bytes: Long)

    companion object {
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_FILES = 2048
        private const val MAX_ENTRY_BYTES = 128L * 1024
        private val lock = Any()
        private val indexes = HashMap<String, Index>()
        private val names = Regex("[0-9a-f]{64}\\.png")
    }

    fun read(version: String): Bitmap? = synchronized(lock) {
        val name = name(version)
        val file = File(directory, name)
        val index = index()
        try {
            if (!file.isFile) {
                forget(index, name)
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (file.length() > MAX_ENTRY_BYTES || bounds.outWidth != DailySpatialCovers.TILE || bounds.outHeight != DailySpatialCovers.TILE) {
                file.delete()
                forget(index, name)
                return null
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
            if (bitmap == null) {
                file.delete()
                forget(index, name)
            } else {
                index.files[name]
                val now = System.currentTimeMillis()
                if (now - file.lastModified() > 60_000) file.setLastModified(now)
            }
            bitmap
        } catch (error: Exception) {
            Log.w("FoliaThumbnails", "Could not read a cached thumbnail", error)
            file.delete()
            forget(index, name)
            null
        }
    }

    fun write(version: String, bitmap: Bitmap): Unit = synchronized(lock) {
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val name = name(version)
            val index = index()
            val destination = File(directory, name)
            if (index.files[name] != null && destination.isFile) return
            temporary = File.createTempFile("thumbnail-", ".tmp", directory)
            temporary.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return
            }
            if (!temporary.renameTo(destination)) return
            temporary = null
            forget(index, name)
            val size = destination.length()
            index.files[name] = size
            index.bytes += size
            prune(index)
        } catch (error: Exception) {
            Log.w("FoliaThumbnails", "Could not store a thumbnail", error)
        } finally {
            temporary?.delete()
        }
    }

    private fun name(version: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(version.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) } + ".png"
    }

    private fun index(): Index = indexes.getOrPut(directory.absolutePath) {
        val entries = LinkedHashMap<String, Long>(16, .75f, true)
        var bytes = 0L
        val files = directory.listFiles().orEmpty()
        files.filter { it.isFile && it.name.startsWith("thumbnail-") && it.name.endsWith(".tmp") }
            .forEach { it.delete() }
        files.filter { it.isFile && names.matches(it.name) }
            .sortedBy { it.lastModified() }.forEach { file ->
                val size = file.length()
                entries[file.name] = size
                bytes += size
            }
        Index(entries, bytes).also(::prune)
    }

    private fun forget(index: Index, name: String) {
        index.files.remove(name)?.let { index.bytes -= it }
    }

    private fun prune(index: Index) {
        val entries = index.files.entries.iterator()
        while ((index.bytes > MAX_BYTES || index.files.size > MAX_FILES) && entries.hasNext()) {
            val entry = entries.next()
            val file = File(directory, entry.key)
            if (!file.exists() || file.delete()) {
                index.bytes -= entry.value
                entries.remove()
            }
        }
    }
}
