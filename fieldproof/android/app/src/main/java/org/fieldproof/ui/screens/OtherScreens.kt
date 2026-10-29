package org.fieldproof.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Farmer
import org.fieldproof.data.Photo
import org.fieldproof.geo.Geo
import org.fieldproof.geo.Kml
import org.fieldproof.geo.KmlParcel
import org.fieldproof.geo.LatLng
import org.fieldproof.map.MapContent
import org.fieldproof.map.MapFit
import org.fieldproof.map.SatelliteMap
import org.fieldproof.photo.CameraCapture
import org.fieldproof.photo.Frame
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.CardRow
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.FpTextField
import org.fieldproof.ui.components.GrayButton
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TextLink
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.components.ValueRow
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun statusText(status: String) = stringResource(
    when (status) {
        "submitted" -> R.string.status_submitted
        "boundary_done" -> R.string.status_boundary_done
        "draft" -> R.string.status_draft
        else -> R.string.status_registered
    },
)

@Composable
fun FarmersScreen(nav: Navigator) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { repo.refresh() }
    data class Row4(val id: String, val code: String, val name: String, val village: String, val status: String, val walked: Boolean)
    val local = s.farmers.values.filter { it.status != "draft" && it.createdAt > 1 }
        .map { Row4(it.id, it.code, it.fullName, it.villageName, it.status, it.boundaries.isNotEmpty()) }
    val remote = s.summaries.filter { r -> local.none { it.id == r.id } }
        .map { r -> Row4(r.id, r.code, r.fullName, r.village, s.farmers[r.id]?.status ?: r.status, r.boundaries > 0 || s.farmers[r.id]?.boundaries?.isNotEmpty() == true) }
    val all = (local.sortedByDescending { s.farmers[it.id]?.createdAt ?: 0 } + remote)
        .filter { q.isBlank() || it.name.contains(q.trim(), true) || it.code.contains(q.trim(), true) || it.village.contains(q.trim(), true) }

    FpScreen {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.my_farmers), style = FpType.LargeTitle)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFE3E3E8)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Search, null, tint = Fp.Text2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            FpTextField(q, { q = it }, stringResource(R.string.search_farmers), Modifier.fillMaxWidth(), style = FpType.Body)
        }
        Spacer(Modifier.height(14.dp))
        FpCard {
            all.forEachIndexed { i, r ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(Screen.FarmerProfile(r.id)) }.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = FpType.Body.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(r.code, r.village).filter { it.isNotEmpty() }.joinToString(" · "), style = FpType.Secondary)
                    }
                    if (r.walked) Icon(Lucide.Map, null, tint = Fp.Green, modifier = Modifier.size(16.dp).padding(end = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(statusText(r.status), style = FpType.Small.copy(color = if (r.status == "submitted") Fp.Green else Fp.Text2))
                    Icon(Lucide.ChevronRight, null, tint = Fp.Text3, modifier = Modifier.size(18.dp))
                }
                if (i < all.lastIndex) RowDivider()
            }
            if (all.isEmpty()) Text(stringResource(R.string.no_farmers), style = FpType.Secondary, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Writes the farmer's walked boundaries as KML and opens the share sheet. */
fun shareKml(ctx: Context, f: Farmer) {
    val parcels = f.boundaries.mapIndexed { i, b ->
        val land = f.lands.find { it.id == b.landId }
        KmlParcel(
            name = "${f.code.ifEmpty { f.shortName }} · " + (land?.let { "Gat ${it.surveyNo}" } ?: "parcel ${i + 1}"),
            ring = b.points.map { LatLng(it.lat, it.lng) },
            farmerName = f.fullName, farmerCode = f.code, village = listOf(f.villageName, f.block, f.district).filter { it.isNotEmpty() }.joinToString(", "),
            surveyNo = land?.surveyNo.orEmpty(), accuracyM = b.accuracyM, walkedM = b.walkedM, startedAtMs = b.startedAt, finishedAtMs = b.finishedAt,
        )
    }
    val doc = Kml.build("${f.fullName} · ${f.code}", parcels)
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "${f.code.ifEmpty { f.id.take(8) }}-boundary.kml").apply { writeText(doc) }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.google-earth.kml+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_kml)))
}

@Composable
fun FarmerProfileScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(farmerId) { if (farmerId !in s.farmers) runCatching { repo.loadFarmer(farmerId) }.onFailure { error = it.message } }
    val f = s.farmers[farmerId]

    FpScreen(top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) }) {
        if (f == null) {
            Text(error ?: stringResource(R.string.loading), style = FpType.Secondary)
            return@FpScreen
        }
        StepHeader(listOf(f.code, f.villageName).filter { it.isNotEmpty() }.joinToString(" · "), f.fullName, null)
        Spacer(Modifier.height(4.dp))
        Text(statusText(f.status), style = FpType.Secondary.copy(color = Fp.Green, fontWeight = FontWeight.SemiBold))
        if (f.boundaries.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(Fp.SatGreen)) {
                val all = f.boundaries.flatMap { b -> b.points.map { LatLng(it.lat, it.lng) } }
                SatelliteMap(MapContent(polygon = f.boundaries.first().points.map { LatLng(it.lat, it.lng) }), MapFit.FIT_POLYGON, Modifier.fillMaxSize(), interactive = true, fallbackCenter = all.firstOrNull())
            }
            SectionLabel(stringResource(R.string.boundaries))
            FpCard {
                f.boundaries.forEachIndexed { i, b ->
                    val land = f.lands.find { it.id == b.landId }
                    ValueRow(
                        land?.let { "Gat ${it.surveyNo}" } ?: stringResource(R.string.parcel_n, i + 1),
                        String.format(Locale.US, "%.2f ac · %d pts · ±%.0f m", b.areaM2 / Geo.SQ_M_PER_ACRE, b.points.size, b.accuracyM),
                    )
                    if (i < f.boundaries.lastIndex) RowDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
            FpButton(stringResource(R.string.share_kml), onClick = { shareKml(ctx, f) }, leading = { Icon(Lucide.Share, null, tint = Color.White, modifier = Modifier.size(18.dp)) })
        }
        SectionLabel(stringResource(R.string.farmer_details))
        FpCard {
            ValueRow(stringResource(R.string.mobile_number), "+91 " + formatMobile(f.mobile))
            RowDivider()
            ValueRow(stringResource(R.string.whatsapp), if (f.whatsapp.isNotEmpty()) "+91 " + formatMobile(f.whatsapp) else "—")
            RowDivider()
            ValueRow(stringResource(R.string.village_row), listOf(f.villageName, f.block).filter { it.isNotEmpty() }.joinToString(", "))
        }
        SectionLabel(stringResource(R.string.land_details))
        FpCard {
            f.lands.forEachIndexed { i, l ->
                ValueRow("Gat ${l.surveyNo}", String.format(Locale.US, "%.2f ha · %.2f ac registered", l.areaHa, l.registerAcres))
                if (i < f.lands.lastIndex) RowDivider()
            }
        }
        FarmLocationCard(nav, f)
        if (f.status == "submitted") {
            Spacer(Modifier.height(16.dp))
            FpButton(stringResource(R.string.plan_open), onClick = { nav.push(Screen.Plan(f.id)) })
            Spacer(Modifier.height(10.dp))
            GrayButton(stringResource(R.string.pr_open), onClick = { nav.push(Screen.FarmerRecord(f.id)) })
        }
        if (f.status in setOf("registered", "boundary_done")) {
            Spacer(Modifier.height(16.dp))
            GrayButton(stringResource(if (f.boundaries.isEmpty()) R.string.walk_the_boundary else R.string.walk_again), onClick = {
                nav.push(Screen.BoundaryIntro(f.id, f.lands.firstOrNull { l -> f.boundaryFor(l.id) == null }?.id ?: f.lands.firstOrNull()?.id))
            })
            if (f.boundaries.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FpButton(stringResource(R.string.farm_details), onClick = { nav.push(Screen.FarmDetails(f.id)) })
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun SyncScreen(nav: Navigator) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val reachable by repo.serverReachable.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    FpScreen {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.tab_sync), style = FpType.LargeTitle)
        Spacer(Modifier.height(10.dp))
        SyncStatus()
        SectionLabel(stringResource(R.string.waiting_to_sync))
        FpCard {
            if (s.outbox.isEmpty()) Text(stringResource(R.string.everything_synced), style = FpType.Secondary, modifier = Modifier.padding(16.dp))
            s.outbox.forEachIndexed { i, op ->
                val f = op.farmerId?.let { s.farmers[it] }
                CardRow {
                    Column(Modifier.weight(1f)) {
                        Text(opLabel(op.type), style = FpType.Body)
                        Text(listOfNotNull(f?.shortName, op.error).joinToString(" · "), style = FpType.Secondary.copy(color = if (op.error != null) Fp.Red else Fp.Text2))
                    }
                }
                if (i < s.outbox.lastIndex) RowDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        FpButton(stringResource(if (syncing) R.string.syncing else R.string.sync_now), enabled = !syncing, onClick = { scope.launch { repo.retryFailed() } })
        Helper(
            when (reachable) {
                true -> stringResource(R.string.server_ok, repo.apiUrl())
                false -> stringResource(R.string.server_unreachable, repo.apiUrl())
                null -> repo.apiUrl()
            },
        )
        if (s.lastSyncAt > 0) Helper(stringResource(R.string.last_sync, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastSyncAt))))
        SectionLabel(stringResource(R.string.account))
        FpCard {
            ValueRow(s.surveyor?.name.orEmpty(), "+91 " + formatMobile(s.surveyor?.phone.orEmpty()))
        }
        Spacer(Modifier.height(10.dp))
        TextLink(stringResource(R.string.log_out), {
            scope.launch { if (repo.state.value.outbox.isEmpty()) repo.logout() }
        }, color = if (s.outbox.isEmpty()) Fp.Red else Fp.Text3)
        if (s.outbox.isNotEmpty()) Helper(stringResource(R.string.logout_blocked))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun opLabel(type: String) = stringResource(
    when (type) {
        "put_farmer" -> R.string.op_farmer
        "upload_file" -> R.string.op_photo
        "put_boundary" -> R.string.op_boundary
        "put_farm_details" -> R.string.op_farm_details
        "complete_action" -> R.string.op_action
        else -> R.string.op_settings
    },
)

@Composable
fun EvidenceScreen(nav: Navigator, actionId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val item = s.actionItems.find { it.id == actionId } ?: return
    val f = s.farmers[item.farmerId] ?: return
    val kind = if (item.kind == "collect_agreement") "agreement" else "plantation_proof"
    val since = remember { System.currentTimeMillis() }
    val photos = f.photos.filter { it.kind == kind && it.capturedAt >= since }
    var camera by remember { mutableStateOf(false) }
    val look = actionLook(item.kind)

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            FpButton(stringResource(R.string.send_for_review), enabled = photos.isNotEmpty(), onClick = {
                scope.launch { repo.completeAction(item); nav.reset(Screen.Home) }
            })
        },
    ) {
        StepHeader(f.shortName + " · " + f.villageName, stringResource(look.title), null)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(if (kind == "agreement") R.string.agreement_intro else R.string.plantation_intro), style = FpType.Secondary.copy(fontSize = 16.sp))
        SectionLabel(stringResource(R.string.photos_n, photos.size))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { p: Photo ->
                FpCard {
                    ValueRow(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.capturedAt)),
                        if (p.lat != null) String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) else stringResource(R.string.no_location),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GrayButton(stringResource(R.string.take_photo), onClick = { camera = true })
        Helper(stringResource(R.string.camera_only_note))
    }
    if (camera) CameraCapture(kind, null, stringResource(look.title), if (kind == "agreement") Frame.DOCUMENT else Frame.SCENE,
        onDone = { p -> camera = false; scope.launch { repo.addPhoto(f.id, p, replaceKind = false) } },
        onCancel = { camera = false })
}
