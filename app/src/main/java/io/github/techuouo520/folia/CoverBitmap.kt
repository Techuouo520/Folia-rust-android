// Turns the RGBA cover returned by the Rust library into a Compose image.
package io.github.techuouo520.folia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.techuouo520.folia.bridge.CoverImage

// Subsamples an encoded cover down to the pixels it will actually be drawn at.
fun decodeCover(bytes: ByteArray, edge: Int): ImageBitmap? {
    if (bytes.isEmpty() || edge <= 0) {
        return null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private fun sampleSize(width: Int, height: Int, edge: Int): Int {
    var sample = 1
    var nextWidth = width
    var nextHeight = height
    while (nextWidth / 2 >= edge && nextHeight / 2 >= edge) {
        nextWidth /= 2
        nextHeight /= 2
        sample *= 2
    }
    return sample
}

fun coverBitmap(cover: CoverImage): ImageBitmap? {
    if (cover.width <= 0 || cover.height <= 0) {
        return null
    }
    val pixels = IntArray(cover.width * cover.height)
    var index = 0
    for (pixel in pixels.indices) {
        if (index + 3 >= cover.rgba.size) {
            break
        }
        val red = cover.rgba[index].toInt() and 0xff
        val green = cover.rgba[index + 1].toInt() and 0xff
        val blue = cover.rgba[index + 2].toInt() and 0xff
        val alpha = cover.rgba[index + 3].toInt() and 0xff
        pixels[pixel] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        index += 4
    }
    val bitmap = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, cover.width, 0, 0, cover.width, cover.height)
    return bitmap.asImageBitmap()
}
