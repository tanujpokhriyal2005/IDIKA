package org.fieldproof.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.ApiException
import org.fieldproof.data.isValidMobile
import org.fieldproof.data.normalizeMobile
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.CardShape
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.FpTextField
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.TextLink
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.IOException

/** "98765 43210" */
fun formatMobile(d: String): String = if (d.length > 5) d.substring(0, 5) + " " + d.substring(5) else d

@Composable
fun errorText(e: Throwable): String = when (e) {
    is IOException -> stringResource(R.string.error_offline)
    is ApiException -> e.message
    else -> e.message ?: e.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppLogo(onLongPress: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Fp.Green), contentAlignment = Alignment.Center) {
            Icon(Lucide.Sprout, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(11.dp))
        Text(stringResource(R.string.app_name), style = FpType.Title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
fun LoginScreen(nav: Navigator) {
    val repo = FieldproofApp.instance.repo
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var showServer by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }

    FpScreen(
        bottom = {
            FpButton(stringResource(R.string.send_otp), enabled = isValidMobile(phone) && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val r = repo.requestOtp(phone)
                        nav.push(Screen.Otp(phone, r.devCode, r.resendAfter))
                    } catch (e: Exception) {
                        error = e
                    }
                    busy = false
                }
            })
            Text(stringResource(R.string.not_registered), style = FpType.Helper, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Spacer(Modifier.height(56.dp))
        AppLogo(onLongPress = { showServer = true })
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.login_title), style = FpType.LargeTitle)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_subtitle), style = FpType.Subtitle)
        Spacer(Modifier.height(30.dp))
        Text(stringResource(R.string.mobile_number).uppercase(), style = FpType.Label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        PhoneField(phone, { phone = normalizeMobile(it) }, Modifier.focusRequester(focus))
        error?.let { Helper(errorText(it), color = Fp.Red) }
    }

    if (showServer) ServerDialog { showServer = false }
}

@Composable
fun PhoneField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(CardShape).background(Color.White).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+91", style = FpType.Body.copy(fontSize = 18.sp, color = Fp.Text2))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).height(24.dp).background(Fp.Separator))
        Spacer(Modifier.width(14.dp))
        FpTextField(
            value = value,
            onValueChange = onChange,
            visualTransformation = PhoneTransformation,
            placeholder = "98765 43210",
            keyboardType = KeyboardType.Phone,
            style = FpType.Body.copy(fontSize = 19.sp, letterSpacing = 0.6.sp),
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Hidden setting (long-press the logo): which server the app talks to. */
@Composable
private fun ServerDialog(onDismiss: () -> Unit) {
    val repo = FieldproofApp.instance.repo
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(repo.apiUrl()) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_title), style = FpType.Headline) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.server_help), style = FpType.Helper)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fp.Bg).padding(12.dp)) {
                    FpTextField(url, { url = it }, "http://127.0.0.1:8080", keyboardType = KeyboardType.Uri, style = FpType.Body)
                }
                result?.let { Helper(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    repo.setApiUrl(url)
                    result = try { repo.api.health(); "Connected" } catch (e: Exception) { "Not reachable: ${e.message}" }
                }
            }) { Text(stringResource(R.string.server_save), color = Fp.Green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = Fp.Text2) } },
        containerColor = Color.White,
    )
}

@Composable
fun OtpScreen(nav: Navigator, args: Screen.Otp) {
    val repo = FieldproofApp.instance.repo
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var devCode by remember { mutableStateOf(args.devCode) }
    var resendIn by remember { mutableIntStateOf(args.resendAfter) }
    var resendTick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(resendTick) {
        while (resendIn > 0) { delay(1000); resendIn-- }
    }
    LaunchedEffect(Unit) { delay(250); runCatching { focus.requestFocus() } }
    // No SMS gateway in local mode: the server hands back the code and it fills in
    // the way SMS auto-read would.
    LaunchedEffect(devCode) {
        val c = devCode ?: return@LaunchedEffect
        delay(1200)
        if (code.isEmpty()) for (i in 1..c.length) { code = c.take(i); delay(140) }
    }

    fun verify() {
        if (code.length != 4 || busy) return
        busy = true; error = null
        scope.launch {
            try {
                repo.verifyOtp(args.phone, code)
                nav.reset(Screen.Home)
            } catch (e: Exception) {
                error = e
                code = ""
            }
            busy = false
        }
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = { FpButton(stringResource(R.string.verify_login), enabled = code.length == 4 && !busy, onClick = ::verify) },
    ) {
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.enter_otp), style = FpType.LargeTitle)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.otp_sent_to, formatMobile(args.phone)) + " ", style = FpType.Subtitle)
            Text(
                stringResource(R.string.change),
                style = FpType.Subtitle.copy(color = Fp.Green, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable { nav.pop() },
            )
        }
        Spacer(Modifier.height(26.dp))
        Box {
            BasicTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(4); error = null },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.focusRequester(focus).size(1.dp),
                textStyle = FpType.Body.copy(color = Color.Transparent),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    val focused = i == code.length.coerceAtMost(3) && code.length < 4
                    Box(
                        Modifier
                            .weight(1f)
                            .height(68.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .then(if (focused) Modifier.border(2.dp, Fp.Green, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { runCatching { focus.requestFocus() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(code.getOrNull(i)?.toString() ?: "", style = FpType.Title.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.otp_auto), style = FpType.Secondary, modifier = Modifier.padding(horizontal = 2.dp))
        if (resendIn > 0) {
            Text(stringResource(R.string.resend_in, "0:%02d".format(resendIn)), style = FpType.Secondary, modifier = Modifier.padding(horizontal = 2.dp))
        } else {
            TextLink(stringResource(R.string.resend_code), onClick = {
                scope.launch {
                    try {
                        val r = repo.requestOtp(args.phone)
                        devCode = r.devCode; code = ""; resendIn = r.resendAfter; resendTick++
                    } catch (e: Exception) { error = e }
                }
            })
        }
        error?.let { Helper(errorText(it), color = Fp.Red) }
    }
}

@Composable
fun annotatedBold(prefix: String, bold: String) = buildAnnotatedString {
    append(prefix)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
}
