package org.fieldproof.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType

val CardShape = RoundedCornerShape(14.dp)
val ButtonShape = RoundedCornerShape(14.dp)
const val ScreenPad = 20

/**
 * Standard screen: grey background, optional top bar, scrolling content and
 * buttons pinned to the bottom above the navigation bar / keyboard.
 */
@Composable
fun FpScreen(
    top: (@Composable () -> Unit)? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Fp.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (top != null) top()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = ScreenPad.dp),
            content = content,
        )
        if (bottom != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = ScreenPad.dp, end = ScreenPad.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = bottom,
            )
        }
    }
}

@Composable
fun TopBar(left: (@Composable () -> Unit)? = null, right: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = ScreenPad.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (left != null) left()
        Spacer(Modifier.weight(1f))
        if (right != null) Text(right, style = FpType.Helper.copy(fontSize = 14.sp))
    }
}

@Composable
fun BackLink(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = Fp.Green, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = FpType.BodyMedium.copy(color = Fp.Green, fontSize = 17.sp))
    }
}

@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Fp.Green) {
    Text(
        text,
        style = FpType.BodyMedium.copy(color = color, fontSize = 17.sp),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/** "NEW FARMER · STEP 1 OF 5", big title and the 5-segment progress bar. */
@Composable
fun StepHeader(caption: String, title: String, step: Int?, total: Int = 5) {
    Text(caption.uppercase(), style = FpType.Label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.sp * 10))
    Spacer(Modifier.height(4.dp))
    Text(title, style = FpType.LargeTitle)
    if (step != null) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < step) Fp.Green else Color(0xFFD1D1D6)),
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = FpType.Label, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Helper(text: String, modifier: Modifier = Modifier, color: Color = Fp.Text2) {
    Text(text, style = FpType.Helper.copy(color = color), modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
}

@Composable
fun FpCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(Fp.Card), content = content)
}

@Composable
fun RowDivider(start: Dp = 16.dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.6.dp).background(Fp.Separator))
}

/** One line inside a card: label on the left, value / control on the right. */
@Composable
fun CardRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null, valueColor: Color = Fp.Text2, chevron: Boolean = false, valueWeight: FontWeight = FontWeight.Normal) {
    CardRow(onClick = onClick) {
        Text(label, style = FpType.Body, modifier = Modifier.weight(1f))
        Text(value, style = FpType.Body.copy(color = valueColor, fontWeight = valueWeight), textAlign = TextAlign.End)
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = valueColor, modifier = Modifier.size(20.dp))
        }
    }
}

/** Borderless text input used inside cards. */
@Composable
fun FpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    style: TextStyle = FpType.Body.copy(fontSize = 17.sp),
    enabled: Boolean = true,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        enabled = enabled,
        textStyle = style,
        cursorBrush = SolidColor(Fp.Green),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Fp.Text3))
                inner()
            }
        },
    )
}

@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    return if (pressed) 0.98f else 1f
}

@Composable
fun FpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Fp.Green,
    content: Color = Color.White,
    leading: (@Composable () -> Unit)? = null,
    height: Dp = 54.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            !enabled -> Fp.GrayButton
            pressed && container == Fp.Green -> Fp.GreenPressed
            pressed -> container.copy(alpha = 0.85f)
            else -> container
        },
        label = "btn",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(ButtonShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = FpType.Button.copy(color = if (enabled) content else Fp.Text2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GrayButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    FpButton(text, onClick, modifier, enabled, container = Fp.GrayButton, content = Fp.Text)

@Composable
fun TintButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    FpButton(text, onClick, modifier, container = Fp.GreenLight, content = Fp.Green)

/** iOS-style switch. */
@Composable
fun FpSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Fp.Green else Color(0xFFE9E9EA), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, label = "thumb")
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
fun FpCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) Fp.Green else Color.White)
            .border(if (checked) BorderStroke(0.dp, Color.Transparent) else BorderStroke(2.dp, Color(0xFFC7C7CC)), RoundedCornerShape(5.dp))
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Two-or-more option segmented control (Yes / No, Farmer / Family). */
@Composable
fun Segmented(options: List<String>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 34.dp) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFFE3E3E8))
            .padding(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(if (sel) Modifier.shadow(1.dp, RoundedCornerShape(7.dp)) else Modifier)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (sel) Color.White else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = FpType.BodyMedium.copy(fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = Fp.Text))
            }
        }
    }
}

@Composable
fun FpChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) Fp.Green else Fp.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(text, style = FpType.BodyMedium.copy(fontSize = 15.sp, color = if (selected) Color.White else Fp.Text))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(options: List<Pair<String, String>>, selected: Collection<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) -> FpChip(label, key in selected) { onToggle(key) } }
    }
}

/** Rounded square tile with an icon, as in the Home action list. */
@Composable
fun IconTile(color: Color, size: Dp = 30.dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center, content = content)
}

/** Banner like "You're 4 m from the start." */
@Composable
fun Banner(text: String, icon: (@Composable () -> Unit)? = null, bg: Color = Fp.GreenLight, fg: Color = Fp.Green, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = FpType.BodyMedium.copy(color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Small white stat tile (Walked / Points / Area). */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = FpType.Helper.copy(fontSize = 13.sp))
        Spacer(Modifier.height(2.dp))
        Text(value, style = FpType.StatValue, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, dot: Color? = null, textColor: Color = Fp.Text, bg: Color = Color.White, modifier: Modifier = Modifier, animateDots: Boolean = false) {
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        val style = FpType.BodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textColor)
        if (animateDots) {
            Text(text.trimEnd('…', '.', ' '), style = style)
            AnimatedDots(style)
        } else {
            Text(text, style = style)
        }
    }
}

/** Three dots that fill in one after another, so a waiting state visibly "works". */
@Composable
fun AnimatedDots(style: TextStyle) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 4f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    Row {
        repeat(3) { i ->
            // Each dot fades in after the previous one; all fade out together.
            val a = if (phase.toInt() > i) 1f else 0.2f
            Text(".", style = style.copy(color = style.color.copy(alpha = a * style.color.alpha)))
        }
    }
}
