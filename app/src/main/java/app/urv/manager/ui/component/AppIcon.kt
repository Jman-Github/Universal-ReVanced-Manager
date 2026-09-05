package app.urv.manager.ui.component

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import io.github.fornewid.placeholder.material3.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.appiconloader.AppIconLoader

@Composable
fun AppIcon(
    packageInfo: PackageInfo?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconOverride: Drawable? = null
) {
    if (iconOverride != null) {
        Image(
            painter = rememberDrawablePainter(drawable = iconOverride),
            contentDescription = contentDescription,
            modifier = modifier
        )
        return
    }

    if (packageInfo == null) {
        val fallbackPainter = rememberVectorPainter(Icons.Default.Android)
        val colorFilter = ColorFilter.tint(LocalContentColor.current)
        Image(
            painter = fallbackPainter,
            contentDescription = contentDescription,
            modifier = modifier,
            colorFilter = colorFilter
        )
        return
    }

    val context = LocalContext.current
    val appContext = context.applicationContext ?: context
    val iconKey = remember(packageInfo, appContext) {
        runCatching { AppIconLoader.getIconKey(packageInfo, appContext) }
            .getOrDefault("${packageInfo.packageName}:${packageInfo.versionName.orEmpty()}")
    }
    val cachedBitmap = remember(iconKey) { AppIconStore.get(iconKey) }
    val iconState by produceState(
        initialValue = AppIconState(
            bitmap = cachedBitmap,
            loaded = cachedBitmap != null
        ),
        key1 = iconKey
    ) {
        if (value.bitmap == null) {
            val bitmap = withContext(Dispatchers.IO) {
                AppIconStore.get(iconKey) ?: runCatching {
                    AppIconStore.load(appContext, iconKey, packageInfo)
                }.getOrNull()
            }
            value = AppIconState(bitmap = bitmap, loaded = true)
        }
    }

    iconState.bitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier
        )
        return
    }

    val fallbackPainter = rememberVectorPainter(Icons.Default.Android)
    val colorFilter = ColorFilter.tint(LocalContentColor.current)
    Image(
        painter = fallbackPainter,
        contentDescription = contentDescription,
        modifier = Modifier.placeholder(
            visible = !iconState.loaded,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(100)
        ).then(modifier),
        colorFilter = colorFilter
    )
}

private data class AppIconState(
    val bitmap: Bitmap?,
    val loaded: Boolean
)

private object AppIconStore {
    private const val ICON_SIZE = 512
    private const val CACHE_MEMORY_DIVISOR = 16L
    private const val MIN_CACHE_SIZE_KIB = 8 * 1024L
    private const val MAX_CACHE_SIZE_KIB = 32 * 1024L
    private const val BYTES_PER_KIB = 1024

    private val cacheSizeKib = (Runtime.getRuntime().maxMemory() / BYTES_PER_KIB / CACHE_MEMORY_DIVISOR)
        .coerceIn(MIN_CACHE_SIZE_KIB, MAX_CACHE_SIZE_KIB)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKib) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / BYTES_PER_KIB).coerceAtLeast(1)
    }

    @Volatile
    private var loader: AppIconLoader? = null

    fun get(key: String): Bitmap? = cache.get(key)

    fun load(context: Context, key: String, packageInfo: PackageInfo): Bitmap? {
        get(key)?.let { return it }
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val bitmap = getLoader(context).loadIcon(applicationInfo)
        cache.put(key, bitmap)
        return bitmap
    }

    private fun getLoader(context: Context): AppIconLoader =
        loader ?: synchronized(this) {
            loader ?: AppIconLoader(ICON_SIZE, true, context.applicationContext ?: context)
                .also { loader = it }
        }
}
