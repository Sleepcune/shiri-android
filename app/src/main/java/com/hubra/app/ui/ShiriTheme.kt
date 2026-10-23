package com.hubra.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.app.Activity

@Composable fun ShiriTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val palette = if (dark) darkColorScheme(
        primary=Color(0xFFC6BFFF), onPrimary=Color(0xFF30236C), primaryContainer=Color(0xFF463583), onPrimaryContainer=Color(0xFFE8DEFF),
        secondary=Color(0xFFBDC5EB), secondaryContainer=Color(0xFF3B4263), background=Color(0xFF13131C), surface=Color(0xFF1B1B27),
        surfaceVariant=Color(0xFF313140), onSurface=Color(0xFFE8E5F2), onSurfaceVariant=Color(0xFFC4C0D2), outline=Color(0xFF8E899D)
    ) else lightColorScheme(
        primary=Color(0xFF6559CF), onPrimary=Color.White, primaryContainer=Color(0xFFE8E3FF), onPrimaryContainer=Color(0xFF261957),
        secondary=Color(0xFF586793), secondaryContainer=Color(0xFFE1E7FC), background=Color(0xFFF6F7FC), surface=Color(0xFFFEFBFF),
        surfaceVariant=Color(0xFFEEECF7), onSurface=Color(0xFF242330), onSurfaceVariant=Color(0xFF666174), outline=Color(0xFF908B9D)
    )
    val view=LocalView.current
    if(!view.isInEditMode)SideEffect {
        val window=(view.context as? Activity)?.window?:return@SideEffect
        window.statusBarColor=palette.background.toArgb()
        window.navigationBarColor=palette.surface.toArgb()
        WindowCompat.getInsetsController(window,view).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
    }
    MaterialTheme(colorScheme=palette, shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp)),content=content)
}
