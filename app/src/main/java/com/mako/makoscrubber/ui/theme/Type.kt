package com.mako.makoscrubber.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mako.makoscrubber.R

val CauseFont = FontFamily(
    Font(R.font.cause, FontWeight.Normal)
)

// Explicitly define the type as androidx.compose.material3.Typography
val Typography: androidx.compose.material3.Typography = androidx.compose.material3.Typography(
    bodyLarge = TextStyle(
        fontFamily = CauseFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = CauseFont,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    )
)