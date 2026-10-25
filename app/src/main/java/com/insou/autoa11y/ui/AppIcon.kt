package com.insou.autoa11y.ui

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Process-wide icon cache: getApplicationIcon is an IPC + drawable build. */
private val iconCache = object : LruCache<String, Drawable>(128) {}

/** App icon resolved from PackageManager, null-safe for uninstalled packages. */
@Composable
fun AppIcon(packageName: String, size: Dp = 30.dp) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        iconCache.get(packageName) ?: runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull()?.also { iconCache.put(packageName, it) }
    }
    if (icon != null) {
        AsyncImage(model = icon, contentDescription = null, modifier = Modifier.size(size))
    }
}
