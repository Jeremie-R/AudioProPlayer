package com.roberrini.audiopro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.roberrini.audiopro.net.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Small image cache: memory first, then disk, then the network. Local paths are read directly. */
private object ImageCache {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val TARGET_PX = 400
    val memory = LruCache<String, Bitmap>(48)

    suspend fun load(context: Context, source: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = if (source.startsWith("/")) File(source) else cached(context, source)
        decode(file)?.also { memory.put(source, it) }
    }

    private fun cached(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(File(context.cacheDir, "art").apply { mkdirs() }, hash)
        if (file.length() > 0) return file
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            val bytes = conn.inputStream.use { it.readNBytes(MAX_BYTES) }
            file.writeBytes(bytes)
        } finally {
            conn.disconnect()
        }
        return file
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

@Composable
fun RemoteImage(
    source: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf(source?.let { ImageCache.memory.get(it) }) }
    LaunchedEffect(source) {
        if (source == null || bitmap != null) return@LaunchedEffect
        try {
            bitmap = ImageCache.load(context, source)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onError("Image failed: ${e.describe()}")
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}
