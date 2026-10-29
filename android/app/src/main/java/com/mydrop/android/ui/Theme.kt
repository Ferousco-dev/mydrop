package com.mydrop.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark palette using Apple's system colors, on true black. */
object MyDropColors {
    val Black = Color(0xFF000000)
    val Sheet = Color(0xFF141416)
    val CardOnSheet = Color(0xFF232326)
    val Divider = Color(0xFF2C2C2E)
    val Card = Color(0xFF1C1C1E)
    val CardHigh = Color(0xFF2C2C2E)
    val Track = Color(0xFF3A3A3C)
    val Secondary = Color(0xFF8E8E93)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF453A)
    val Orange = Color(0xFFFF9F0A)
    val Green = Color(0xFF30D158)
    val Avatar = Color(0xFF7C86D9)
}

@Composable
fun MyDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MyDropColors.Blue,
            background = MyDropColors.Black,
            surface = MyDropColors.Card,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        content = content,
    )
}
