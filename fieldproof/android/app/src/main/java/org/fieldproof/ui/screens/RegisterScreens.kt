package org.fieldproof.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Farmer
import org.fieldproof.data.Land
import org.fieldproof.data.Photo
import org.fieldproof.data.Village
import org.fieldproof.data.isValidMobile
import org.fieldproof.data.newId
import org.fieldproof.data.normalizeMobile
import org.fieldproof.photo.CameraCapture
import org.fieldproof.photo.Frame
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.CardRow
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpCheckbox
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.FpSwitch
import org.fieldproof.ui.components.FpTextField
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.IconTile
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.Segmented
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TextLink
import org.fieldproof.ui.components.TintButton
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.components.ValueRow
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.File
import java.util.Locale

/** Shows "98220 41376" while the value stays 10 bare digits. */
object PhoneTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val d = text.text
        val out = if (d.length > 5) d.substring(0, 5) + " " + d.substring(5) else d
        return TransformedText(AnnotatedString(out), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 5) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 5) (offset - 1).coerceAtMost(d.length) else offset.coerceAtMost(d.length)
        })
    }
}

/** No cut/copy/paste menu: the confirm-number box must be typed. */
private object NoPasteToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) = Unit
}

@Composable
private fun PhoneRow(value: String, onChange: (String) -> Unit, placeholder: String, noPaste: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    CardRow {
        Text("+91", style = FpType.Body.copy(fontSize = 17.sp, color = Fp.Text2))
        Spacer(Modifier.width(18.dp))
        val field = @Composable {
            FpTextField(
                value = value,
                onValueChange = { v ->
                    val digits = normalizeMobile(v)
                    // A jump of more than one digit at once is a paste or autofill: refuse it.
                    if (noPaste && digits.length - value.length > 1) return@FpTextField
                    onChange(digits)
                },
                placeholder = placeholder,
                keyboardType = KeyboardType.Phone,
                visualTransformation = PhoneTransformation,
                style = FpType.Body.copy(fontSize = 17.sp, letterSpacing = 0.4.sp),
                modifier = Modifier.weight(1f),
            )
        }
        if (noPaste) CompositionLocalProvider(LocalTextToolbar provides NoPasteToolbar) { field() } else field()
        trailing?.invoke()
    }
}

@Composable
fun SavedOnPhone() = stringResource(R.string.saved_on_phone)

@Composable
private fun rememberDraft(): Farmer? {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    return s.draftId?.let { s.farmers[it] }
}

@Composable
fun FarmerDetailsScreen(nav: Navigator) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val f = rememberDraft() ?: return
    var showErrors by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    fun edit(t: (Farmer) -> Farmer) = repo.editFarmer(f.id, t)

    val nameOk = f.fullName.trim().length >= 3
    val mobileOk = isValidMobile(f.mobile)
    val match = mobileOk && f.mobileConfirm == f.mobile
    val waOk = f.whatsappSame || f.whatsapp.isEmpty() || isValidMobile(f.whatsapp)
    val otherOk = f.otherMobile.isEmpty() || isValidMobile(f.otherMobile)
    val valid = nameOk && match && waOk && otherOk && f.villageId != null && f.treeInterest == true

    FpScreen(
        top = { TopBar(left = { TextLink(stringResource(R.string.cancel), { confirmCancel = true }) }, right = SavedOnPhone()) },
        bottom = {
            FpButton(stringResource(R.string.next_aadhaar), onClick = {
                if (valid) nav.push(Screen.Aadhaar(f.id)) else showErrors = true
            })
        },
    ) {
        StepHeader(stringResource(R.string.new_farmer_step, 1), stringResource(R.string.farmer_details), 1)

        SectionLabel(stringResource(R.string.full_name))
        FpCard { CardRow { FpTextField(f.fullName, { v -> edit { it.copy(fullName = v) } }, stringResource(R.string.full_name_placeholder), Modifier.fillMaxWidth()) } }
        Helper(if (showErrors && !nameOk) stringResource(R.string.err_name) else stringResource(R.string.full_name_help), color = if (showErrors && !nameOk) Fp.Red else Fp.Text2)

        SectionLabel(stringResource(R.string.mobile_number))
        FpCard {
            PhoneRow(f.mobile, { v -> edit { it.copy(mobile = v) } }, stringResource(R.string.mobile_placeholder))
            RowDivider()
            PhoneRow(f.mobileConfirm, { v -> edit { it.copy(mobileConfirm = v) } }, stringResource(R.string.mobile_again), noPaste = true) {
                if (match) Icon(Lucide.Check, null, tint = Fp.Green, modifier = Modifier.size(18.dp))
            }
        }
        val mismatch = f.mobileConfirm.length == 10 && f.mobileConfirm != f.mobile
        Helper(
            when {
                mismatch -> stringResource(R.string.err_mobile_mismatch)
                showErrors && !mobileOk -> stringResource(R.string.err_mobile)
                showErrors && !match -> stringResource(R.string.err_mobile_mismatch)
                else -> stringResource(R.string.mobile_help)
            },
            color = if (mismatch || (showErrors && !match)) Fp.Red else Fp.Text2,
        )

        SectionLabel(stringResource(R.string.whatsapp))
        FpCard {
            CardRow {
                Text(stringResource(R.string.whatsapp_same), style = FpType.Body.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
                FpSwitch(f.whatsappSame) { v -> edit { it.copy(whatsappSame = v) } }
            }
            if (!f.whatsappSame) {
                RowDivider()
                PhoneRow(f.whatsapp, { v -> edit { it.copy(whatsapp = v) } }, stringResource(R.string.whatsapp_number))
            }
        }
        Helper(if (showErrors && !waOk) stringResource(R.string.err_mobile) else stringResource(R.string.whatsapp_help), color = if (showErrors && !waOk) Fp.Red else Fp.Text2)

        SectionLabel(stringResource(R.string.other_number))
        FpCard { PhoneRow(f.otherMobile, { v -> edit { it.copy(otherMobile = v) } }, stringResource(R.string.other_number_placeholder)) }
        if (showErrors && !otherOk) Helper(stringResource(R.string.err_mobile), color = Fp.Red)

        SectionLabel(stringResource(R.string.village))
        VillageSection(f, showErrors) { t -> edit(t) }

        SectionLabel(stringResource(R.string.farmer_interest))
        FpCard {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.interest_question), style = FpType.Body.copy(fontSize = 17.sp))
                Spacer(Modifier.height(12.dp))
                Segmented(
                    listOf(stringResource(R.string.yes), stringResource(R.string.no)),
                    when (f.treeInterest) { true -> 0; false -> 1; null -> null },
                    { i -> edit { it.copy(treeInterest = i == 0) } },
                    Modifier.fillMaxWidth(),
                )
            }
        }
        if (f.treeInterest == false) Helper(stringResource(R.string.interest_no_help), color = Fp.Amber)
        else if (showErrors && f.treeInterest == null) Helper(stringResource(R.string.err_interest), color = Fp.Red)
        Spacer(Modifier.height(16.dp))
    }

    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text(stringResource(R.string.cancel_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.cancel_body), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { confirmCancel = false; scope.launch { repo.discardDraft(); nav.reset(Screen.Home) } }) { Text(stringResource(R.string.discard), color = Fp.Red) } },
        dismissButton = {
            TextButton(onClick = { confirmCancel = false; nav.reset(Screen.Home) }) { Text(stringResource(R.string.keep_draft), color = Fp.Green) }
        },
        containerColor = Color.White,
    )
}

// ---------------- Aadhaar ----------------

@Composable
fun AadhaarScreen(nav: Navigator, args: Screen.Aadhaar) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val f = s.farmers[args.farmerId] ?: return
    val retake = args.retakeActionId != null
    var camera by remember { mutableStateOf<String?>(null) }
    var showRetakeDone by remember { mutableStateOf(false) }
    // In retake mode only photos taken now count.
    val sinceMs = remember { if (retake) System.currentTimeMillis() else 0L }
    val front = f.photo("aadhaar_front")?.takeIf { it.capturedAt >= sinceMs }
    val back = f.photo("aadhaar_back")?.takeIf { it.capturedAt >= sinceMs }
    fun edit(t: (Farmer) -> Farmer) = repo.editFarmer(f.id, t)

    val label = when {
        front == null && back == null -> stringResource(R.string.take_front_to_continue)
        front == null -> stringResource(R.string.take_front_to_continue)
        back == null -> stringResource(R.string.take_back_to_continue)
        !front.readable || !back.readable -> stringResource(R.string.retake_blurry)
        !f.aadhaarNameMatch || !f.aadhaarConsent -> stringResource(R.string.tick_checks)
        retake -> stringResource(R.string.send_new_photos)
        else -> stringResource(R.string.next_land)
    }
    val ready = front?.readable == true && back?.readable == true && f.aadhaarNameMatch && f.aadhaarConsent

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }, right = if (retake) null else SavedOnPhone()) },
        bottom = {
            FpButton(label, enabled = ready, onClick = {
                if (retake) scope.launch {
                    val item = s.actionItems.find { it.id == args.retakeActionId }
                    if (item != null) repo.completeAction(item)
                    showRetakeDone = true
                } else nav.push(Screen.LandDetails)
            })
        },
    ) {
        StepHeader(
            if (retake) stringResource(R.string.retake_caption, f.shortName) else stringResource(R.string.farmer_step, f.shortName, 2),
            stringResource(R.string.aadhaar_card),
            if (retake) null else 2,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.aadhaar_intro), style = FpType.Secondary.copy(fontSize = 16.sp, lineHeight = 23.sp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoSlot(Modifier.weight(1f), stringResource(R.string.front), front) { camera = "aadhaar_front" }
            PhotoSlot(Modifier.weight(1f), stringResource(R.string.back_side), back) { camera = "aadhaar_back" }
        }
        SectionLabel(stringResource(R.string.check))
        FpCard {
            CardRow(minHeight = 60.dp) {
                Text(annotatedBold(stringResource(R.string.name_matches) + " ", f.fullName), style = FpType.Body.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                FpCheckbox(f.aadhaarNameMatch) { v -> edit { it.copy(aadhaarNameMatch = v) } }
            }
            RowDivider()
            CardRow(minHeight = 60.dp) {
                Text(stringResource(R.string.aadhaar_consent), style = FpType.Body.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                FpCheckbox(f.aadhaarConsent) { v -> edit { it.copy(aadhaarConsent = v) } }
            }
        }
        Helper(stringResource(R.string.aadhaar_footer))
        Spacer(Modifier.height(16.dp))
    }

    camera?.let { kind ->
        CameraCapture(
            kind = kind, landId = null,
            title = stringResource(if (kind == "aadhaar_front") R.string.aadhaar_front_title else R.string.aadhaar_back_title),
            frame = Frame.CARD,
            onDone = { p -> camera = null; scope.launch { repo.addPhoto(f.id, p) } },
            onCancel = { camera = null },
        )
    }

    if (showRetakeDone) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.retake_sent_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.retake_sent_body), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { nav.reset(Screen.Home) }) { Text(stringResource(R.string.back_to_home), color = Fp.Green) } },
        containerColor = Color.White,
    )
}

/** Card-shaped slot. A taken Aadhaar photo is never shown: only a placeholder card (numbers stay off-screen). */
@Composable
private fun PhotoSlot(modifier: Modifier, label: String, photo: Photo?, onTake: () -> Unit) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.22f).clip(RoundedCornerShape(16.dp))
                .background(if (photo != null) Color(0xFFE5E5EA) else Color.White)
                .clickable(onClick = onTake),
        ) {
            if (photo == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Fp.GreenTint), contentAlignment = Alignment.Center) {
                        Icon(Lucide.Camera, null, tint = Fp.Green, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.take_photo), style = FpType.Headline.copy(color = Fp.Green, fontWeight = FontWeight.Bold))
                }
            } else {
                // Stylised card: photo block, two text lines, number bar.
                Box(Modifier.padding(start = 14.dp, top = 16.dp).size(width = 34.dp, height = 42.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFC9C9CE)))
                Column(Modifier.padding(start = 58.dp, top = 22.dp, end = 30.dp)) {
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Color(0xFFC9C9CE)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.6f).height(7.dp).clip(CircleShape).background(Color(0xFFC9C9CE)))
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFBDBDC2)))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.number_hidden), style = FpType.Small.copy(color = Fp.Text, fontSize = 12.sp))
                }
                Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 8.dp).size(24.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(if (photo.readable) Fp.Green else Fp.Red), contentAlignment = Alignment.Center) {
                    Icon(if (photo.readable) Lucide.Check else Lucide.X, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(label, style = FpType.BodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            if (photo != null) {
                Text(" · ", style = FpType.BodyMedium.copy(fontSize = 15.sp))
                Text(
                    stringResource(if (photo.readable) R.string.readable else R.string.blurry_retake),
                    style = FpType.BodyMedium.copy(fontSize = 15.sp, color = if (photo.readable) Fp.Green else Fp.Red, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

// ---------------- Land details ----------------

private fun fmtAc(v: Double) = String.format(Locale.US, "%.2f", v)

@Composable
fun LandDetailsScreen(nav: Navigator) {
    val repo = FieldproofApp.instance.repo
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val f = rememberDraft() ?: return
    var showErrors by remember { mutableStateOf(false) }
    var editSurvey by remember { mutableStateOf<Land?>(null) }
    var editArea by remember { mutableStateOf<Land?>(null) }
    var cameraFor by remember { mutableStateOf<Land?>(null) }
    var pdfFor by remember { mutableStateOf<Land?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun editLand(id: String, t: (Land) -> Land) = repo.editFarmer(f.id) { x -> x.copy(lands = x.lands.map { if (it.id == id) t(it) else it }) }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val land = pdfFor ?: return@rememberLauncherForActivityResult
        pdfFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = newId()
            val out = File(repo.photoDir, "$id.pdf")
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.exists() && out.length() > 0) repo.addPhoto(f.id, Photo(id, "land_record_pdf", out.absolutePath, "application/pdf", land.id, capturedAt = System.currentTimeMillis()), replaceKind = true)
        }
    }

    fun landProblem(l: Land): Int? = when {
        l.surveyNo.isBlank() -> R.string.err_survey
        l.areaHa <= 0 -> R.string.err_area
        l.registerAcres < 0.25 || l.registerAcres > l.maxRegisterAcres + 1e-9 -> R.string.err_register_acres
        f.photos.none { it.landId == l.id && (it.kind == "land_record" || it.kind == "land_record_pdf") } -> R.string.err_record_copy
        !l.recordsConsent -> R.string.err_records_consent
        else -> null
    }
    val valid = f.lands.isNotEmpty() && f.lands.all { landProblem(it) == null }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }, right = SavedOnPhone()) },
        bottom = {
            FpButton(stringResource(R.string.register_farmer), enabled = !busy, onClick = {
                if (!valid) { showErrors = true; return@FpButton }
                busy = true
                scope.launch {
                    val reg = repo.registerDraft()
                    nav.reset(Screen.Home)
                    nav.push(Screen.Registered(reg.id))
                }
            })
        },
    ) {
        StepHeader(stringResource(R.string.farmer_step, f.shortName, 3), stringResource(R.string.land_details), 3)
        f.lands.forEachIndexed { idx, land ->
            if (f.lands.size > 1) {
                SectionLabel(stringResource(R.string.survey_number_n, idx + 1), Modifier.padding(top = 8.dp)) {
                    if (idx > 0) TextLink(stringResource(R.string.remove), {
                        scope.launch {
                            f.photos.filter { it.landId == land.id }.forEach { repo.removePhoto(f.id, it.id) }
                            repo.updateFarmer(f.id) { x -> x.copy(lands = x.lands.filterNot { it.id == land.id }) }
                        }
                    }, color = Fp.Red)
                }
            }
            SectionLabel(stringResource(R.string.land_to_register))
            FpCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtAc(land.registerAcres), style = FpType.Title.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    Text("  " + stringResource(R.string.acres), style = FpType.Body.copy(color = Fp.Text2, fontSize = 17.sp))
                    Spacer(Modifier.weight(1f))
                    RoundIcon(Lucide.Minus, land.registerAcres > 0.25) { editLand(land.id) { it.copy(registerAcres = (it.registerAcres - 0.25).coerceAtLeast(0.25)) } }
                    Spacer(Modifier.width(12.dp))
                    RoundIcon(Lucide.Plus, land.registerAcres + 0.25 <= land.maxRegisterAcres + 1e-9) {
                        editLand(land.id) { it.copy(registerAcres = (it.registerAcres + 0.25).coerceAtMost(it.maxRegisterAcres)) }
                    }
                }
            }
            Helper(stringResource(R.string.land_to_register_help))

            SectionLabel(stringResource(R.string.satbara))
            FpCard {
                ValueRow(stringResource(R.string.survey_gat), land.surveyNo.ifEmpty { stringResource(R.string.add) }, onClick = { editSurvey = land },
                    valueColor = if (land.surveyNo.isEmpty()) Fp.Green else Fp.Text, valueWeight = FontWeight.Medium)
                RowDivider()
                ValueRow(
                    stringResource(R.string.area_on_712),
                    if (land.areaHa > 0) String.format(Locale.US, "%.2f ha · %.2f ac", land.areaHa, land.areaAcres) else stringResource(R.string.add),
                    onClick = { editArea = land },
                    valueColor = if (land.areaHa > 0) Fp.Text else Fp.Green, valueWeight = FontWeight.Medium,
                )
                RowDivider()
                CardRow {
                    Text(stringResource(R.string.owner_on_712), style = FpType.Body, modifier = Modifier.weight(1f))
                    Segmented(
                        listOf(stringResource(R.string.owner_farmer), stringResource(R.string.owner_family)),
                        if (land.ownerType == "family") 1 else 0,
                        { i -> editLand(land.id) { it.copy(ownerType = if (i == 1) "family" else "farmer") } },
                        Modifier.width(140.dp), height = 32.dp,
                    )
                }
            }

            val pages = f.photos.filter { it.landId == land.id && it.kind == "land_record" }
            val pdf = f.photos.lastOrNull { it.landId == land.id && it.kind == "land_record_pdf" }
            val count = pages.size + (if (pdf != null) 1 else 0)
            SectionLabel(stringResource(R.string.land_record_copy) + " · " + pluralStringResource(R.plurals.n_pages, count, count))
            val tiles = mutableListOf<@Composable (Modifier) -> Unit>()
            pages.forEach { p -> tiles += { m -> DocTile(m, Lucide.File, null, true, p.readable) { scope.launch { repo.removePhoto(f.id, p.id) } } } }
            if (pdf != null) tiles += { m -> DocTile(m, Lucide.FileDown, "PDF", true, true) { scope.launch { repo.removePhoto(f.id, pdf.id) } } }
            tiles += { m -> DocTile(m, Lucide.Camera, stringResource(R.string.add_page), false, true) { cameraFor = land } }
            if (pdf == null) tiles += { m -> DocTile(m, Lucide.FileDown, "PDF", false, true) { pdfFor = land; pdfPicker.launch(arrayOf("application/pdf")) } }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { it(Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Helper(stringResource(R.string.land_record_help))
            Spacer(Modifier.height(12.dp))
            FpCard {
                CardRow(minHeight = 60.dp) {
                    Text(stringResource(R.string.records_consent), style = FpType.Body.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    FpCheckbox(land.recordsConsent) { v -> editLand(land.id) { it.copy(recordsConsent = v) } }
                }
            }
            if (showErrors) landProblem(land)?.let { Helper(stringResource(it), color = Fp.Red) }
        }
        Spacer(Modifier.height(8.dp))
        TextLink("+ " + stringResource(R.string.add_another_survey), { scope.launch { repo.updateFarmer(f.id) { it.copy(lands = it.lands + Land()) } } })
        Spacer(Modifier.height(16.dp))
    }

    editSurvey?.let { land ->
        TextInputDialog(stringResource(R.string.survey_gat), stringResource(R.string.survey_hint), land.surveyNo, KeyboardType.Text, null, { v ->
            editSurvey = null; editLand(land.id) { it.copy(surveyNo = v.trim()) }
        }, { editSurvey = null })
    }
    editArea?.let { land ->
        TextInputDialog(stringResource(R.string.area_on_712), stringResource(R.string.area_hint), if (land.areaHa > 0) land.areaHa.toString() else "", KeyboardType.Decimal,
            { v -> v.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.US, "= %.2f acres", it * org.fieldproof.geo.Geo.ACRES_PER_HECTARE) } },
            { v ->
                editArea = null
                val ha = v.replace(',', '.').toDoubleOrNull() ?: return@TextInputDialog
                editLand(land.id) {
                    val n = it.copy(areaHa = ha)
                    n.copy(registerAcres = if (it.registerAcres <= 0 || it.registerAcres > n.maxRegisterAcres) n.maxRegisterAcres.coerceAtLeast(0.0) else it.registerAcres)
                }
            }, { editArea = null })
    }
    cameraFor?.let { land ->
        CameraCapture("land_record", land.id, stringResource(R.string.land_record_title), Frame.DOCUMENT,
            onDone = { p -> cameraFor = null; scope.launch { repo.addPhoto(f.id, p, replaceKind = false) } },
            onCancel = { cameraFor = null })
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Fp.Chip).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) Fp.Text else Fp.Text3, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun DocTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, done: Boolean, ok: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(92.dp).clip(RoundedCornerShape(14.dp)).background(if (done) Color(0xFFE5E5EA) else Color.White).clickable(onClick = onClick),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (done) Fp.Text2 else Fp.Green, modifier = Modifier.size(22.dp))
            if (label != null) {
                Spacer(Modifier.height(6.dp))
                Text(label, style = FpType.BodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (done) Fp.Text2 else Fp.Green))
            }
        }
        if (done) Box(
            Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(22.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(if (ok) Fp.Green else Fp.Amber),
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
    }
}

@Composable
fun TextInputDialog(title: String, hint: String, initial: String, keyboard: KeyboardType, preview: ((String) -> String?)?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = FpType.Headline) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fp.Bg).padding(14.dp)) {
                    FpTextField(v, { v = it }, hint, Modifier.fillMaxWidth().focusRequester(focus), keyboardType = keyboard)
                }
                preview?.invoke(v)?.let { Helper(it) }
            }
        },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onSave(v) }) { Text(stringResource(R.string.save), color = Fp.Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Fp.Text2) } },
        containerColor = Color.White,
    )
}

// ---------------- Registered ----------------

@Composable
fun RegisteredScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val f = s.farmers[farmerId] ?: return
    BackHandler { nav.reset(Screen.Home) }
    val synced = s.outbox.none { it.farmerId == farmerId }
    val status = stringResource(if (synced) R.string.waiting else R.string.on_phone)
    FpScreen(
        bottom = {
            FpButton(stringResource(R.string.at_farm_now), onClick = {
                nav.reset(Screen.Home)
                nav.push(Screen.BoundaryIntro(f.id, f.lands.firstOrNull()?.id))
            })
            TintButton(stringResource(R.string.back_to_home), onClick = { nav.reset(Screen.Home) })
        },
    ) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.align(Alignment.CenterHorizontally).size(76.dp).clip(CircleShape).background(Fp.Green), contentAlignment = Alignment.Center) {
            Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.is_registered, f.shortName), style = FpType.Title.copy(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            if (f.code.isNotEmpty()) stringResource(R.string.farmer_id_village, f.code, f.villageName) else stringResource(R.string.farmer_id_pending, f.villageName),
            style = FpType.Secondary.copy(fontSize = 16.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.sent_for_review), Modifier.padding(top = 14.dp))
        FpCard {
            ValueRow(stringResource(R.string.farmer_details), status)
            RowDivider()
            ValueRow(stringResource(R.string.aadhaar), status)
            RowDivider()
            ValueRow(stringResource(R.string.land_record_ac, fmtAc(f.lands.sumOf { it.registerAcres })), status)
        }
        Spacer(Modifier.height(20.dp))
        FpCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Fp.Orange, 30.dp) { Icon(Lucide.MapPin, null, tint = Color.White, modifier = Modifier.size(17.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.next_at_farm), style = FpType.Label.copy(color = Fp.Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.next_at_farm_title), style = FpType.Headline.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.next_at_farm_body), style = FpType.Secondary)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
