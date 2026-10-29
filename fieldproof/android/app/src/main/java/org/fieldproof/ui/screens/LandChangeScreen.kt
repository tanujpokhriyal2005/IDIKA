package org.fieldproof.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.LandChangeLocal
import org.fieldproof.data.Photo
import org.fieldproof.data.newId
import org.fieldproof.geo.LandFit
import org.fieldproof.geo.LatLng
import org.fieldproof.location.distanceText
import org.fieldproof.photo.CameraCapture
import org.fieldproof.photo.Frame
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.CardRow
import org.fieldproof.ui.components.ChipGroup
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.FpTextField
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.Segmented
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.File
import kotlin.math.roundToInt

private val REASONS = listOf(
    "changed_mind" to R.string.lc_r_changed_mind, "no_water" to R.string.lc_r_no_water, "family" to R.string.lc_r_family,
    "dispute" to R.string.lc_r_dispute, "other" to R.string.lc_r_other,
)

/**
 * The farmer wants the plantation on a different land than the one checked at
 * onboarding. The walk just finished becomes the new land's boundary; an NGO,
 * state lead or admin approves it before planting continues.
 */
@Composable
fun LandChangeScreen(nav: Navigator, farmerId: String) {
    val app = FieldproofApp.instance
    val repo = app.repo
    val walk = app.walk
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val s by repo.state.collectAsState()
    val f = s.farmers[farmerId] ?: return
    val c = walk.captured
    val since = remember { System.currentTimeMillis() }
    var reason by remember { mutableStateOf<String?>(null) }
    var gat by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var owner by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val files = f.photos.filter { it.kind == "land_change_record" && it.capturedAt >= since }
    val ha = area.replace(',', '.').toDoubleOrNull()
    val valid = c != null && reason != null && gat.isNotBlank() && ha != null && ha > 0 && ha <= 100 && files.isNotEmpty()

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = newId()
            val out = File(repo.photoDir, "$id.pdf")
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.exists() && out.length() > 0) repo.addPhoto(f.id, Photo(id, "land_change_record", out.absolutePath, "application/pdf", capturedAt = System.currentTimeMillis()), replaceKind = false)
        }
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            FpButton(
                stringResource(if (files.isEmpty()) R.string.lc_add_712_first else R.string.pa_send),
                enabled = valid && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        repo.savePlantWalk(
                            f.id, "pre_plant", c!!, null,
                            landChange = LandChangeLocal(reason = reason!!, surveyNo = gat.trim(), areaHa = ha!!, ownerType = if (owner == 1) "family" else "farmer"),
                        )
                        walk.close()
                        nav.reset(Screen.Home)
                        nav.push(Screen.Plan(f.id))
                    }
                },
            )
        },
    ) {
        StepHeader(listOf(f.shortName, f.code).filter { it.isNotBlank() }.joinToString(" · "), stringResource(R.string.ls_report_change), null)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.lc_who_decides), style = FpType.Secondary)
        if (c == null) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.lc_no_walk), style = FpType.Secondary.copy(color = Color(0xFFB42318)))
            return@FpScreen
        }
        val onb = f.boundaries.map { b -> b.points.map { LatLng(it.lat, it.lng) } }
        val share = LandFit.share(c.ring, onb)
        if (share != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.lc_walk_summary, (share * 100).roundToInt(), distanceText(LandFit.centreDistance(c.ring, onb))),
                style = FpType.Secondary.copy(color = Color(0xFF7A1A12)),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fp.RedLight).padding(12.dp),
            )
        }
        SectionLabel(stringResource(R.string.lc_why))
        ChipGroup(REASONS.map { it.first to stringResource(it.second) }, listOfNotNull(reason), { reason = it })
        SectionLabel(stringResource(R.string.lc_new_land))
        FpCard {
            CardRow {
                Text(stringResource(R.string.lc_gat), style = FpType.Body, modifier = Modifier.weight(1f))
                FpTextField(gat, { gat = it.take(40) }, "52/1", Modifier.width(120.dp), keyboardType = KeyboardType.Ascii)
            }
            RowDivider()
            CardRow {
                Text(stringResource(R.string.lc_area), style = FpType.Body, modifier = Modifier.weight(1f))
                FpTextField(area, { v -> area = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) }, "0.00", Modifier.width(70.dp), keyboardType = KeyboardType.Decimal)
                Text(" ha", style = FpType.Body)
            }
            RowDivider()
            CardRow {
                Text(stringResource(R.string.lc_owner), style = FpType.Body, modifier = Modifier.weight(1f))
                Segmented(listOf(stringResource(R.string.lc_owner_farmer), stringResource(R.string.lc_owner_family)), owner, { owner = it }, Modifier.width(180.dp))
            }
        }
        SectionLabel(stringResource(R.string.lc_712))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcTile(Modifier.weight(1f), Lucide.Camera, stringResource(R.string.lc_photo_712)) { camera = true }
            LcTile(Modifier.weight(1f), Lucide.FileText, stringResource(R.string.fix_attach_pdf)) { pdfPicker.launch(arrayOf("application/pdf")) }
        }
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            files.forEach { ph ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Check, null, tint = Fp.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (ph.mime == "application/pdf") R.string.lc_pdf_added else R.string.lc_photo_added), style = FpType.Body, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.remove), style = FpType.BodyMedium.copy(color = Color(0xFFB42318)), modifier = Modifier.clickable { scope.launch { repo.removePhoto(f.id, ph.id) } })
                }
            }
        }
        Helper(stringResource(R.string.lc_walk_attached))
        Spacer(Modifier.height(16.dp))
    }

    if (camera) CameraCapture(
        kind = "land_change_record", landId = null, title = stringResource(R.string.lc_712), frame = Frame.DOCUMENT,
        onDone = { ph -> camera = false; scope.launch { repo.addPhoto(f.id, ph, replaceKind = false) } },
        onCancel = { camera = false },
    )
}

@Composable
private fun LcTile(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier.height(84.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Fp.Green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = FpType.BodyMedium.copy(color = Fp.Green, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), textAlign = TextAlign.Center)
    }
}
