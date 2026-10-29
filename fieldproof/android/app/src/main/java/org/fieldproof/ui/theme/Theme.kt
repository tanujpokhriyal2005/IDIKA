package org.fieldproof.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.fieldproof.R

/** Colours sampled from the Fieldproof screen designs. */
object Fp {
    val Bg = Color(0xFFF2F2F7)
    val Card = Color.White
    val Green = Color(0xFF1F7A3A)
    val GreenPressed = Color(0xFF186530)
    val GreenLight = Color(0xFFE8F3EC)
    val GreenTint = Color(0xFFE3F0E6)
    val Text = Color(0xFF1C1C1E)
    val Text2 = Color(0xFF6C6C70)
    val Text3 = Color(0xFF8E8E93)
    val Separator = Color(0xFFE3E3E8)
    val GrayButton = Color(0xFFE5E5EA)
    val Chip = Color(0xFFE9E9EE)
    val Orange = Color(0xFFC9411B)
    val Red = Color(0xFFE02B2B)
    val Blue = Color(0xFF1D4ED8)
    val Amber = Color(0xFFB45309)
    val AmberLight = Color(0xFFFEF3C7)
    val RedLight = Color(0xFFFDECEC)
    val GpsBlue = Color(0xFF1546E0)
    val Trail = Color(0xFFD9381E)
    val SatGreen = Color(0xFF6E8A4E)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

object FpType {
    val LargeTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em, color = Fp.Text)
    val Greeting = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 33.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em, color = Fp.Text)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, color = Fp.Text)
    val Headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = Fp.Text)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, color = Fp.Text)
    val BodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = Fp.Text)
    val Secondary = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp, color = Fp.Text2)
    val Subtitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, color = Fp.Text2)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.03.em, color = Fp.Text2)
    val Helper = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = Fp.Text2)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val StatValue = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, color = Fp.Text)
    val Small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, color = Fp.Text2)
}

@Composable
fun FieldproofTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Fp.Green,
            onPrimary = Color.White,
            background = Fp.Bg,
            surface = Fp.Card,
            onSurface = Fp.Text,
        ),
        typography = MaterialTheme.typography.copy(
            bodyLarge = FpType.Body,
            bodyMedium = FpType.Secondary,
            titleLarge = FpType.Title,
            labelLarge = FpType.Button,
        ),
        content = content,
    )
}
