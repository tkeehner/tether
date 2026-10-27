package com.tyler.primelink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tyler.primelink.R

/** Emit's Matrix palette (ui/ChatColors.kt Pal) and fonts, so Tether reads as part of the same set. */
object Neon {
    val BG = Color(0xFF000000); val S1 = Color(0xFF0B0F0C); val S2 = Color(0xFF111814)
    val LINE = Color(0xFF1C2A20); val LINE2 = Color(0xFF25382B)
    val PRIMARY = Color(0xFF3DFF7A); val BODY = Color(0xFFB8F5C8); val DIM = Color(0xFF7A9C83)
    val CYAN = Color(0xFF4FE3F0); val AMBER = Color(0xFFFFB000); val DANGER = Color(0xFFFF4D6D); val PINK = Color(0xFFFF3EA5)
    val Mono = FontFamily(Font(R.font.jbmono_regular), Font(R.font.jbmono_bold, FontWeight.Bold))
    val Title = FontFamily(Font(R.font.oxanium))
}
