package id.gatra.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream

private val cache = HashMap<String, ImageBitmap>()

private fun fetchBitmap(url: String): ImageBitmap? {
    synchronized(cache) { cache[url]?.let { return it } }
    return try {
        val req = Request.Builder().url(url).build()
        Svc.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val bmp = loadImageBitmap(ByteArrayInputStream(bytes))
            synchronized(cache) {
                if (cache.size > 300) cache.clear()
                cache[url] = bmp
            }
            bmp
        }
    } catch (_: Exception) {
        null
    }
}

/** Gambar dari internet (thumbnail, foto profil channel). Selama dimuat tampil kotak abu-abu. */
@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bmp = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) { fetchBitmap(url) }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        androidx.compose.foundation.layout.Box(modifier.background(G.SurfaceHigh))
    }
}
