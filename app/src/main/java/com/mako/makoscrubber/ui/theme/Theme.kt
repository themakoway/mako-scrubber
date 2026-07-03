package com.mako.makoscrubber.ui.theme

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
import androidx.compose.material3.Typography

// Define your Mako Coral directly here since Color.kt is gone
val MakoCoral = Color(0xFFF7717D)

private val DarkColorScheme = darkColorScheme(
    primary = MakoCoral,
    secondary = Color(0xFF625b71), // Hardcoded from your previous PurpleGrey40
    tertiary = Color(0xFF7D5260)   // Hardcoded from your previous Pink40
)

private val LightColorScheme = lightColorScheme(
    primary = MakoCoral,
    secondary = Color(0xFFCCC2DC), // Hardcoded from your previous PurpleGrey80
    tertiary = Color(0xFFEFB8C8)   // Hardcoded from your previous Pink80
)

@Composable
fun MakoScrubberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}