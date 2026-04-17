package app.urv.manager.ui.component

import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import coil.compose.AsyncImage
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import io.github.fornewid.placeholder.material3.placeholder

@Composable
fun AppIcon(
    packageInfo: PackageInfo?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconOverride: Drawable? = null
) {
    val iconKey = iconOverride?.constantState?.hashCode()?.toString()
        ?: packageInfo?.packageName
        ?: "android-fallback"
    var showPlaceHolder by remember(iconKey) {
        mutableStateOf(iconOverride == null && packageInfo != null)
    }

    if (iconOverride != null) {
        Image(
            painter = rememberDrawablePainter(drawable = iconOverride),
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else if (packageInfo == null) {
        val image = rememberVectorPainter(Icons.Default.Android)
        val colorFilter = ColorFilter.tint(LocalContentColor.current)

        Image(
            image,
            contentDescription,
            modifier,
            colorFilter = colorFilter
        )
    } else {
        val fallbackPainter = rememberVectorPainter(Icons.Default.Android)
        AsyncImage(
            model = packageInfo,
            contentDescription = contentDescription,
            modifier = Modifier.placeholder(
                visible = showPlaceHolder,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(100)
            ).then(modifier),
            error = fallbackPainter,
            fallback = fallbackPainter,
            onLoading = {
                showPlaceHolder = true
            },
            onSuccess = {
                showPlaceHolder = false
            },
            onError = {
                showPlaceHolder = false
            }
        )
    }
}
