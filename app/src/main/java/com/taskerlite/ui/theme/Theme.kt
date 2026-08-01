package com.taskerlite.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val NightBlue = Color(0xFF0D1B2A)
private val SoftAmber = Color(0xFFFFB74D)
private val DeepIndigo = Color(0xFF1B263B)
private val Mist = Color(0xFFE0E6ED)

private val DarkColors = darkColorScheme(
    primary = SoftAmber,
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF90CAF9),
    background = NightBlue,
    surface = DeepIndigo,
    onBackground = Mist,
    onSurface = Mist,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB36B00),
    secondary = Color(0xFF1565C0),
    background = Color(0xFFF7F5F2),
    surface = Color.White,
)

@Composable
fun TaskerLiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
