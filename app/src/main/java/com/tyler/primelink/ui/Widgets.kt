package com.tyler.primelink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sparse katakana code rain (same idea as Emit/launcher rain), drawn dim so text stays readable. */
@Composable
fun CodeRain(modifier: Modifier = Modifier, color: Color = Neon.PRIMARY, alpha: Float = 0.16f) {
    val glyphs = remember { "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉ01" }
    val measurer = rememberTextMeasurer()
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { t = it } }
    val style = remember { TextStyle(fontSize = 14.sp, fontFamily = Neon.Mono) }
    Canvas(modifier.fillMaxSize()) {
        val col = 22.dp.toPx(); val cols = (size.width / col).toInt() + 1; val row = 18.dp.toPx(); val rows = (size.height / row).toInt() + 1
        for (i in 0 until cols) {
            val speed = 40 + (i * 37) % 70
            val head = ((t / speed + i * 13) % (rows + 12)).toInt()
            for (k in 0 until 8) {
                val r = head - k; if (r < 0 || r >= rows) continue
                val g = glyphs[(i * 7 + r * 3 + (t / 400).toInt()) % glyphs.length].toString()
                drawText(measurer, g, Offset(i * col, r * row), style.copy(color = color.copy(alpha = (alpha * (1f - k / 8f) * (if (k == 0) 2.2f else 1f)).coerceIn(0f, 1f))))
            }
        }
    }
}

fun glow(c: Color, r: Float = 18f) = Shadow(color = c.copy(alpha = 0.85f), offset = Offset.Zero, blurRadius = r)

@Composable
fun NeonTitle(text: String, color: Color = Neon.PRIMARY, size: Int = 28) =
    Text(text, style = TextStyle(fontFamily = Neon.Title, fontSize = size.sp, color = color, shadow = glow(color), letterSpacing = 2.sp))

@Composable
fun MonoText(text: String, color: Color = Neon.BODY, size: Int = 14, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, style = TextStyle(fontFamily = Neon.Mono, fontSize = size.sp, color = color))

@Composable
fun NeonButton(label: String, color: Color = Neon.PRIMARY, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.border(1.dp, color, CutCornerShape(6.dp)).background(color.copy(alpha = 0.08f), CutCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("[ $label ]", style = TextStyle(fontFamily = Neon.Mono, fontSize = 14.sp, color = color, shadow = glow(color, 10f)))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.border(1.dp, Neon.LINE2, CutCornerShape(8.dp)).background(Neon.S1.copy(alpha = 0.92f), CutCornerShape(8.dp)).padding(12.dp)) { content() }
}
