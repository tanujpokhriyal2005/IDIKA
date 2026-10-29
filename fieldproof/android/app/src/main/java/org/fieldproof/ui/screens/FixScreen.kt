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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Photo
import org.fieldproof.data.newId
import org.fieldproof.photo.CameraCapture
import org.fieldproof.photo.Frame
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.File
import java.text.DateFormat
import java.util.Date

private val DOC_LABEL = mapOf(
    "aadhaar" to R.string.doc_aadhaar, "land_record" to R.string.doc_land_record,
    "water_source" to R.string.doc_water_source, "boundary" to R.string.doc_boundary,
)
private val DOC_REPLACE = mapOf(
    "aadhaar" to R.string.fix_replace_aadhaar, "land_record" to R.string.fix_replace_land_record,
    "water_source" to R.string.fix_replace_water_source, "boundary" to R.string.fix_replace_boundary,
)

private data class StatusLook(val label: Int, val fg: Color, val bg: Color)

private fun statusLook(st: String) = when (st) {
    "approved" -> StatusLook(R.string.st_approved, Color(0xFF1F6B35), Fp.GreenLight)
    "rejected" -> StatusLook(R.string.st_rejected, Color(0xFFB42318), Fp.RedLight)
    "pending" -> StatusLook(R.string.st_pending, Color(0xFF92400E), Fp.AmberLight)
    "missing" -> StatusLook(R.string.st_missing, Fp.Text2, Color(0xFFF2F2F7))
    else -> StatusLook(R.string.st_new, Fp.Blue, Color(0xFFE8EEFC))
}

/**
 * A document a reviewer sent back from the dashboard: their message, and the
 * one thing to redo (retake a photo, attach a PDF, or walk the boundary again).
 */
@Composable
fun FixScreen(nav: Navigator, actionId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Keep the task while this screen is open: the server closes it as soon as the
    // first new photo uploads, and the refreshed list no longer has it.
    val opened = remember { s.actionItems.find { it.id == actionId } }
    val item = s.actionItems.find { it.id == actionId } ?: opened ?: return
    val f = s.farmers[item.farmerId] ?: return
    // Only what is taken on this screen counts as the fix.
    val since = remember { System.currentTimeMillis() }
    var camera by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var pdfFor by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    // The list on the phone can be older than the reviewer's latest decision: check now.
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.refresh(); checked = true }
    val fresh = f.photos.filter { it.capturedAt >= since }
    val kinds = when (item.doc) {
        "aadhaar" -> setOf("aadhaar_front", "aadhaar_back")
        "land_record" -> setOf("land_record", "land_record_pdf")
        "water_source" -> setOf("water_source")
        else -> emptySet()
    }
    val ready = fresh.any { it.kind in kinds && it.readable }
    fun freshOf(kind: String, landId: String? = null) = fresh.lastOrNull { it.kind == kind && (landId == null || it.landId == landId) }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val landId = pdfFor ?: return@rememberLauncherForActivityResult
        pdfFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = newId()
            val out = File(repo.photoDir, "$id.pdf")
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.exists() && out.length() > 0) repo.addPhoto(f.id, Photo(id, "land_record_pdf", out.absolutePath, "application/pdf", landId, capturedAt = System.currentTimeMillis()))
        }
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            // A boundary fix is sent by walking it again (the walk's own Save).
            if (item.doc != "boundary") {
                FpButton(stringResource(if (ready) R.string.fix_send else R.string.fix_take_first), enabled = ready, onClick = {
                    scope.launch { repo.completeAction(item); sent = true }
                })
            }
        },
    ) {
        StepHeader(listOf(f.shortName, f.code, f.villageName).filter { it.isNotBlank() }.joinToString(" · "), stringResource(actionLook(item.kind).title), null)
        Spacer(Modifier.height(14.dp))
        val rejected = item.decision == "rejected"
        val tone = if (rejected) Color(0xFFB42318) else Color(0xFF92400E)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (rejected) Fp.RedLight else Fp.AmberLight).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "${stringResource(DOC_LABEL[item.doc] ?: R.string.doc_aadhaar)} · ${stringResource(if (rejected) R.string.st_rejected else R.string.st_pending)}".uppercase(),
                style = FpType.Label.copy(color = tone, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            Text("“${item.note}”", style = FpType.Body.copy(lineHeight = 23.sp))
            if (item.reviewerName.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                val at = item.updatedAtMs.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: ""
                Text(stringResource(R.string.fix_reviewer_line, item.reviewerName, at), style = FpType.Helper.copy(color = tone))
            }
        }
        SectionLabel(stringResource(DOC_REPLACE[item.doc] ?: R.string.fix_replace_aadhaar))
        when (item.doc) {
            "aadhaar" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FixTile(Modifier.weight(1f), Lucide.Camera, stringResource(R.string.front), freshOf("aadhaar_front")?.let { stringResource(R.string.fix_new_taken) }) { camera = "aadhaar_front" to null }
                FixTile(Modifier.weight(1f), Lucide.Camera, stringResource(R.string.back_side), freshOf("aadhaar_back")?.let { stringResource(R.string.fix_new_taken) }) { camera = "aadhaar_back" to null }
            }
            "land_record" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                f.lands.forEach { land ->
                    if (f.lands.size > 1) Text(stringResource(R.string.fix_gat, land.surveyNo), style = FpType.BodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FixTile(Modifier.weight(1f), Lucide.Camera, stringResource(R.string.fix_retake_page), freshOf("land_record", land.id)?.let { stringResource(R.string.fix_new_taken) }) { camera = "land_record" to land.id }
                        FixTile(Modifier.weight(1f), Lucide.FileText, stringResource(R.string.fix_attach_pdf), freshOf("land_record_pdf", land.id)?.let { stringResource(R.string.fix_pdf_attached) }) {
                            pdfFor = land.id
                            pdfPicker.launch(arrayOf("application/pdf"))
                        }
                    }
                }
            }
            "water_source" -> FixTile(Modifier.fillMaxWidth(), Lucide.Camera, stringResource(R.string.take_photo), freshOf("water_source")?.let { stringResource(R.string.fix_new_taken) }) { camera = "water_source" to null }
            "boundary" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Helper(stringResource(R.string.fix_walk_help))
                val lands = f.lands.ifEmpty { listOf(null) }
                lands.forEach { land ->
                    FpButton(
                        if (land != null && f.lands.size > 1) "${stringResource(R.string.fix_walk_again)} · ${stringResource(R.string.fix_gat, land.surveyNo)}" else stringResource(R.string.fix_walk_again),
                        onClick = { nav.push(Screen.BoundaryIntro(f.id, land?.id)) },
                    )
                }
            }
        }
        if (item.docs.isNotEmpty()) {
            SectionLabel(stringResource(R.string.fix_other_docs))
            FpCard {
                val rows = listOf("aadhaar", "land_record", "water_source", "boundary").filter { it != item.doc }
                rows.forEachIndexed { i, d ->
                    val look = statusLook(item.docs[d] ?: "new")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(DOC_LABEL.getValue(d)), style = FpType.Body, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(look.label),
                            style = FpType.Small.copy(color = look.fg, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(look.bg).padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                    if (i < rows.lastIndex) RowDivider()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    camera?.let { (kind, landId) ->
        CameraCapture(
            kind = kind, landId = landId,
            title = stringResource(actionLook(item.kind).title),
            frame = when (kind) { "aadhaar_front", "aadhaar_back" -> Frame.CARD; "land_record" -> Frame.DOCUMENT; else -> Frame.SCENE },
            onDone = { p -> camera = null; scope.launch { repo.addPhoto(f.id, p) } },
            onCancel = { camera = null },
        )
    }

    // The reviewer approved it (or changed their mind) since the list was loaded.
    val gone = checked && !sent && s.actionItems.none { it.id == actionId } && fresh.none { it.kind in kinds }
    if (gone) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.fix_gone_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.fix_gone_body), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { nav.reset(Screen.Home) }) { Text(stringResource(R.string.back_to_home), color = Fp.Green) } },
        containerColor = Color.White,
    )

    if (sent) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.fix_sent_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.fix_sent_body), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { nav.reset(Screen.Home) }) { Text(stringResource(R.string.back_to_home), color = Fp.Green) } },
        containerColor = Color.White,
    )
}

@Composable
private fun FixTile(modifier: Modifier, icon: ImageVector, label: String, done: String?, onClick: () -> Unit) {
    Column(
        modifier.height(88.dp).clip(RoundedCornerShape(14.dp)).background(if (done != null) Fp.GreenLight else Color.White).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (done != null) Lucide.Check else icon, null, tint = Fp.Green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(done ?: label, style = FpType.BodyMedium.copy(color = Fp.Green, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
    }
}
