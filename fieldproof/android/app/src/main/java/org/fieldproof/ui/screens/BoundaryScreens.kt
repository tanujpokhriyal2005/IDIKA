package org.fieldproof.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Farmer
import org.fieldproof.data.ServerCheck
import org.fieldproof.data.newId
import org.fieldproof.geo.BoundaryRules
import org.fieldproof.geo.Geo
import org.fieldproof.geo.LandFit
import org.fieldproof.geo.LatLng
import org.fieldproof.geo.PauseReason
import org.fieldproof.geo.WalkPhase
import org.fieldproof.geo.WalkState
import org.fieldproof.geo.WalkWarning
import org.fieldproof.map.MapContent
import org.fieldproof.map.MapFit
import org.fieldproof.map.SatelliteMap
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.Banner
import org.fieldproof.ui.components.CardRow
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.GrayButton
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.Pill
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.StatTile
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TextLink
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun acres(m2: Double) = String.format(Locale.US, "%.2f ac", m2 / Geo.SQ_M_PER_ACRE)

@Composable
private fun gpsChip(s: WalkState, gpsOn: Boolean): Triple<String, Color, Color> {
    val acc = s.accuracyM
    return when {
        !gpsOn -> Triple(stringResource(R.string.gps_off), Fp.Red, Fp.Red)
        s.warning == WalkWarning.MOCK_LOCATION -> Triple(stringResource(R.string.gps_mock), Fp.Red, Fp.Red)
        acc == null || s.warning == WalkWarning.GPS_LOST -> Triple(stringResource(R.string.gps_searching), Fp.Amber, Fp.Amber)
        acc <= BoundaryRules.START_ACCURACY_M -> Triple(stringResource(R.string.gps_good, acc.roundToInt().coerceAtLeast(1)), Fp.Green, Fp.Green)
        else -> Triple(stringResource(R.string.gps_weak, acc.roundToInt()), Fp.Amber, Fp.Amber)
    }
}

@Composable
private fun walkHeaderCaption(f: Farmer, purpose: String) =
    if (purpose == "onboarding") stringResource(R.string.farmer_step, f.shortName, 4) else listOf(f.shortName, f.code).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun walkTitle(purpose: String) = stringResource(
    when (purpose) { "pre_plant" -> R.string.walk_pre_plant_title; "post_plant" -> R.string.walk_post_plant_title; else -> R.string.walk_the_boundary },
)

@Composable
fun BoundaryIntroScreen(nav: Navigator, farmerId: String, landId: String?, purpose: String = "onboarding") {
    val app = FieldproofApp.instance
    val repo = app.repo
    val walk = app.walk
    val ctx = LocalContext.current
    val s by repo.state.collectAsState()
    val f = s.farmers[farmerId] ?: return
    var permitted by remember { mutableStateOf(app.gps.hasPermission()) }
    val gpsOn by app.gps.gpsEnabled.collectAsState()
    val ws by walk.state.collectAsState()
    val land = f.lands.find { it.id == landId }
    // Before planting: is the surveyor at the land checked at onboarding?
    val onbRings = remember(f.boundaries) { f.boundaries.map { b -> b.points.map { LatLng(it.lat, it.lng) } } }
    val checkLand = purpose == "pre_plant" && onbRings.isNotEmpty()
    val fromLand = if (checkLand) ws.current?.let { LandFit.signedDistance(it, onbRings) } else null
    var otherLand by remember { mutableStateOf(false) }
    val farAway = fromLand != null && fromLand > LandFit.FAR_M && !otherLand
    val gat = f.lands.singleOrNull()?.surveyNo?.let { "Gat $it" } ?: stringResource(R.string.ls_registered_field)

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permitted = r[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!permitted) perms.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
        else if (Build.VERSION.SDK_INT >= 33) perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
    }
    LaunchedEffect(permitted) {
        if (permitted) walk.open(farmerId, landId, s.walkSnapshot)
    }
    val restored = ws.phase == WalkPhase.PAUSED && ws.pauseReason == PauseReason.RESTORED

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { walk.close(); nav.pop() } }) },
        bottom = {
            if (restored) {
                FpButton(
                    if ((ws.distToResumeM ?: 99.0) <= BoundaryRules.RESUME_RADIUS_M) stringResource(R.string.continue_walk) else stringResource(R.string.walk_back_m, (ws.distToResumeM ?: 0.0).roundToInt()),
                    enabled = (ws.distToResumeM ?: 99.0) <= BoundaryRules.RESUME_RADIUS_M,
                    onClick = { if (walk.resume()) nav.replace(Screen.BoundaryWalk(farmerId, landId, purpose)) },
                )
                GrayButton(stringResource(R.string.start_again), onClick = { walk.discard() })
            } else if (farAway) {
                FpButton(stringResource(R.string.ls_navigate_registered), onClick = { nav.push(Screen.FindFarm(farmerId, null)) })
                FpButton(stringResource(R.string.ls_other_land), container = Color.White, content = Color(0xFFB42318), onClick = { otherLand = true })
            } else {
                FpButton(
                    if (ws.gpsGoodForStart || ws.accuracyM == null) stringResource(R.string.start_walking) else stringResource(R.string.wait_for_gps, ws.accuracyM!!.roundToInt()),
                    enabled = permitted && gpsOn && ws.gpsGoodForStart,
                    leading = { Icon(Lucide.Play, null, tint = if (ws.gpsGoodForStart) Color.White else Fp.Text2, modifier = Modifier.size(16.dp)) },
                    onClick = { if (walk.start()) nav.replace(Screen.BoundaryWalk(farmerId, landId, purpose)) },
                )
            }
        },
    ) {
        StepHeader(walkHeaderCaption(f, purpose), walkTitle(purpose), if (purpose == "onboarding") 4 else null)
        if (purpose != "onboarding") Helper(stringResource(if (purpose == "pre_plant") R.string.walk_pre_plant_help else R.string.walk_post_plant_help))
        if (land != null && f.lands.size > 1) Helper(stringResource(R.string.walking_parcel, land.surveyNo, String.format(Locale.US, "%.2f", land.areaAcres)))
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(18.dp)).background(Fp.SatGreen)) {
            val any by app.gps.lastAnyLocation.collectAsState()
            SatelliteMap(
                MapContent(current = ws.current, accuracyM = ws.accuracyM, start = null, refRings = if (checkLand) onbRings else emptyList()),
                if (checkLand) MapFit.FIT_ALL else MapFit.FOLLOW_CURRENT,
                Modifier.fillMaxSize(),
                interactive = false,
                fallbackCenter = any?.let { LatLng(it.latitude, it.longitude) },
            )
            val (text, dot, fg) = gpsChip(ws, gpsOn)
            val searching = gpsOn && ws.warning != WalkWarning.MOCK_LOCATION && (ws.accuracyM == null || ws.warning == WalkWarning.GPS_LOST)
            Pill(text, dot = dot, textColor = fg, modifier = Modifier.padding(12.dp), animateDots = searching)
        }
        Spacer(Modifier.height(16.dp))
        if (fromLand != null) {
            when {
                otherLand -> Banner(stringResource(R.string.ls_other_land_walk), bg = Fp.AmberLight, fg = Fp.Amber)
                fromLand <= 0 -> Banner("✓ " + stringResource(R.string.ls_at_field_inside))
                fromLand <= LandFit.NEAR_M -> Banner("✓ " + stringResource(R.string.ls_at_field_near, fromLand.roundToInt()))
                fromLand <= LandFit.FAR_M -> Banner(stringResource(R.string.ls_near_field, fromLand.roundToInt()), bg = Fp.AmberLight, fg = Fp.Amber)
                else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fp.RedLight).padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.ls_not_registered).uppercase(), style = FpType.Label.copy(color = Color(0xFFB42318), fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ls_far_title, org.fieldproof.location.distanceText(fromLand), gat), style = FpType.Headline.copy(color = Color(0xFFB42318)))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.ls_far_body), style = FpType.Secondary.copy(color = Color(0xFF7A1A12)))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!permitted) {
            Banner(stringResource(R.string.need_location_permission), bg = Fp.RedLight, fg = Fp.Red)
            Spacer(Modifier.height(12.dp))
        } else if (!gpsOn) {
            Banner(stringResource(R.string.turn_on_location), bg = Fp.RedLight, fg = Fp.Red)
            TextLink(stringResource(R.string.open_settings), { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
            Spacer(Modifier.height(12.dp))
        }
        var waitedLong by remember { mutableStateOf(false) }
        LaunchedEffect(ws.gpsGoodForStart) {
            waitedLong = false
            if (!ws.gpsGoodForStart) { kotlinx.coroutines.delay(15_000); waitedLong = true }
        }
        if (permitted && gpsOn && !restored && waitedLong && !ws.gpsGoodForStart) {
            Banner(stringResource(R.string.gps_open_sky_hint), bg = Fp.AmberLight, fg = Fp.Amber)
            Spacer(Modifier.height(12.dp))
        }
        if (restored) {
            Banner(stringResource(R.string.restored_walk, ws.points.size), bg = Fp.AmberLight, fg = Fp.Amber)
            Spacer(Modifier.height(12.dp))
        }
        FpCard {
            listOf(R.string.walk_step_1, R.string.walk_step_2, R.string.walk_step_3).forEachIndexed { i, res ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Fp.GreenTint), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = FpType.BodyMedium.copy(color = Fp.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(res), style = FpType.Body.copy(fontSize = 17.sp, lineHeight = 24.sp), modifier = Modifier.weight(1f))
                }
                if (i < 2) RowDivider(start = 56.dp)
            }
        }
        Helper(stringResource(R.string.walk_rule_note))
        Spacer(Modifier.height(16.dp))
    }
}

private fun mmss(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60) else String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
}

@Composable
fun BoundaryWalkScreen(nav: Navigator, farmerId: String, landId: String?, purpose: String = "onboarding") {
    val app = FieldproofApp.instance
    val walk = app.walk
    val ws by walk.state.collectAsState()
    val st by app.repo.state.collectAsState()
    val onbRings = remember(farmerId) {
        if (purpose != "pre_plant") emptyList() else st.farmers[farmerId]?.boundaries.orEmpty().map { b -> b.points.map { LatLng(it.lat, it.lng) } }
    }
    val outsideM = ws.current?.let { LandFit.signedDistance(it, onbRings) }
    val gpsOn by app.gps.gpsEnabled.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(walk.farmerId) { if (walk.farmerId == null) nav.replace(Screen.BoundaryIntro(farmerId, landId, purpose)) }
    BackHandler { confirmStop = true }

    Box(Modifier.fillMaxSize().background(Fp.SatGreen)) {
        SatelliteMap(
            MapContent(
                trail = ws.points,
                start = ws.start,
                current = ws.current,
                accuracyM = ws.accuracyM,
                startLabel = stringResource(R.string.start_label),
                showSeam = ws.distToStartM != null && ws.distToStartM!! <= 25 && ws.points.size >= BoundaryRules.MIN_POINTS,
                refRings = onbRings,
            ),
            MapFit.FIT_TRAIL,
            Modifier.fillMaxSize(),
        )
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val recText = when (ws.phase) {
                WalkPhase.ANCHORING -> stringResource(R.string.marking_start)
                WalkPhase.PAUSED -> stringResource(R.string.paused_time, mmss(ws.activeMs))
                else -> stringResource(R.string.recording_time, mmss(ws.activeMs))
            }
            Pill(recText, dot = if (ws.phase == WalkPhase.PAUSED) Fp.Amber else Color(0xFFE0201B))
            Spacer(Modifier.weight(1f))
            val acc = ws.accuracyM
            Pill(
                if (acc != null) stringResource(R.string.gps_pm, acc.roundToInt().coerceAtLeast(1)) else stringResource(R.string.gps_searching),
                textColor = if (acc != null && acc <= BoundaryRules.START_ACCURACY_M) Fp.Green else Fp.Amber,
                animateDots = acc == null,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Fp.Bg)
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color(0xFFC7C7CC)))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.walked), "${ws.walkedM.roundToInt()} m", Modifier.weight(1f))
                StatTile(stringResource(R.string.points), "${ws.points.size}", Modifier.weight(1f))
                StatTile(stringResource(R.string.area), acres(ws.areaM2), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            WalkBanner(ws, gpsOn, outsideM)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ws.phase == WalkPhase.PAUSED) {
                    GrayButton(stringResource(R.string.resume), onClick = { walk.resume() }, modifier = Modifier.weight(1f),
                        enabled = (ws.distToResumeM ?: 99.0) <= BoundaryRules.RESUME_RADIUS_M && ws.warning != WalkWarning.TOO_FAST)
                } else {
                    GrayButton(stringResource(R.string.pause), onClick = { walk.pause() }, modifier = Modifier.weight(1f), enabled = ws.phase == WalkPhase.RECORDING)
                }
                FpButton(stringResource(R.string.finish_boundary), enabled = ws.canFinish, modifier = Modifier.weight(1.85f), onClick = {
                    if (walk.finish() != null) nav.replace(Screen.BoundaryReview(farmerId, landId, purpose))
                })
            }
        }
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.stop_walk_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.stop_walk_body), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { confirmStop = false; walk.discard(); nav.replace(Screen.BoundaryIntro(farmerId, landId, purpose)) }) { Text(stringResource(R.string.stop_walk), color = Fp.Red) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.keep_walking), color = Fp.Green) } },
        containerColor = Color.White,
    )
}

@Composable
private fun WalkBanner(ws: WalkState, gpsOn: Boolean, outsideM: Double? = null) {
    val pin = @Composable { c: Color -> Icon(Lucide.MapPin, null, tint = c, modifier = Modifier.size(18.dp)) }
    val d = ws.distToStartM?.roundToInt() ?: 0
    data class B(val text: String, val bg: Color, val fg: Color)
    val b = when {
        !gpsOn -> B(stringResource(R.string.turn_on_location), Fp.RedLight, Fp.Red)
        ws.warning == WalkWarning.MOCK_LOCATION -> B(stringResource(R.string.mock_warning), Fp.RedLight, Fp.Red)
        ws.warning == WalkWarning.TOO_FAST -> B(stringResource(R.string.too_fast, ws.speedKmh.roundToInt()), Fp.RedLight, Fp.Red)
        ws.warning == WalkWarning.GPS_LOST -> B(stringResource(R.string.gps_lost), Fp.RedLight, Fp.Red)
        // Drifting off the field. Farther than FAR_M is a new land the surveyor chose to walk (the start was gated).
        outsideM != null && outsideM > LandFit.NEAR_M && outsideM <= LandFit.FAR_M && ws.phase == WalkPhase.RECORDING ->
            B(stringResource(R.string.ls_walk_outside, outsideM.roundToInt()), Fp.AmberLight, Fp.Amber)
        ws.phase == WalkPhase.ANCHORING -> B(stringResource(R.string.hold_still, ws.anchorFixes, BoundaryRules.ANCHOR_FIXES), Fp.GreenLight, Fp.Green)
        ws.phase == WalkPhase.PAUSED && (ws.distToResumeM ?: 0.0) > BoundaryRules.RESUME_RADIUS_M ->
            B(stringResource(R.string.walk_back_to_resume, (ws.distToResumeM ?: 0.0).roundToInt()), Fp.AmberLight, Fp.Amber)
        ws.phase == WalkPhase.PAUSED -> B(stringResource(R.string.paused_tap_resume), Fp.AmberLight, Fp.Amber)
        ws.warning == WalkWarning.GPS_WEAK -> B(stringResource(R.string.gps_weak_walk, (ws.accuracyM ?: 0.0).roundToInt()), Fp.AmberLight, Fp.Amber)
        ws.canFinish -> B(stringResource(R.string.near_start, d), Fp.GreenLight, Fp.Green)
        ws.points.size >= BoundaryRules.MIN_POINTS && d > BoundaryRules.FINISH_RADIUS_M -> B(stringResource(R.string.keep_walking_m, d), Fp.GreenLight, Fp.Green)
        else -> B(stringResource(R.string.keep_walking_edge), Fp.GreenLight, Fp.Green)
    }
    Banner(b.text, icon = { pin(b.fg) }, bg = b.bg, fg = b.fg)
}

@Composable
fun BoundaryReviewScreen(nav: Navigator, farmerId: String, landId: String?, purpose: String = "onboarding") {
    val app = FieldproofApp.instance
    val repo = app.repo
    val walk = app.walk
    val scope = rememberCoroutineScope()
    val s by repo.state.collectAsState()
    val f = s.farmers[farmerId] ?: return
    val c = walk.captured ?: return
    var check by remember { mutableStateOf<ServerCheck?>(null) }
    var checking by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(c) {
        check = repo.checkBoundary(farmerId, landId, c)
        checking = false
    }
    BackHandler { walk.discard(); nav.replace(Screen.BoundaryIntro(farmerId, landId, purpose)) }

    val plantWalk = purpose != "onboarding"
    val plan = s.plans[farmerId]
    val land = f.lands.find { it.id == landId }
    // A planting-day walk is compared with the walk before it: onboarding, then before planting.
    val refAc = when (purpose) {
        "pre_plant" -> f.boundaries.sumOf { it.areaM2 }.takeIf { it > 0 }?.div(Geo.SQ_M_PER_ACRE)
        "post_plant" -> (f.plantWalks.lastOrNull { it.purpose == "pre_plant" }?.areaM2 ?: plan?.plant?.preAreaM2)?.div(Geo.SQ_M_PER_ACRE)
        else -> land?.areaAcres?.takeIf { it > 0 }
    }
    val saplings = plan?.pay?.let { it.timber + it.fruit } ?: f.payment?.let { it.timber + it.fruit } ?: 0
    // Land swap check: how much of this walk is on the land checked at onboarding.
    val onbRings = remember(f.boundaries) { f.boundaries.map { b -> b.points.map { LatLng(it.lat, it.lng) } } }
    val share = if (purpose == "pre_plant") LandFit.share(c.ring, onbRings) else null
    val apart = if (share != null) LandFit.centreDistance(c.ring, onbRings) else 0.0
    val differentLand = share != null && share < LandFit.MIN
    val partLand = share != null && share >= LandFit.MIN && share < LandFit.OK
    var notePrompt by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val room = (c.areaM2 / (org.fieldproof.data.SPACING_M * org.fieldproof.data.SPACING_M)).toInt()
    val ac = c.areaM2 / Geo.SQ_M_PER_ACRE
    val diff = refAc?.let { (ac - it) / it }
    val pct = diff?.let { abs(it * 100).roundToInt() }
    val areaColor = when {
        diff == null -> Fp.Text2
        abs(diff) <= 0.10 -> Fp.Green
        abs(diff) <= 0.25 -> Fp.Amber
        else -> Fp.Red
    }
    val overlapFlagged = check?.overlapOk == false

    fun save(thenAnother: Boolean) {
        saving = true
        scope.launch {
            if (plantWalk) {
                repo.savePlantWalk(farmerId, purpose, c, check, landNote = note)
                walk.close()
                nav.reset(Screen.Home)
                nav.push(Screen.Plan(farmerId))
                return@launch
            }
            repo.saveBoundary(farmerId, landId, c, check)
            walk.close()
            val fresh = repo.state.value.farmers[farmerId]!!
            if (thenAnother) {
                val next = fresh.lands.firstOrNull { l -> fresh.boundaryFor(l.id) == null }?.id ?: newId()
                nav.reset(Screen.Home)
                nav.push(Screen.BoundaryIntro(farmerId, next))
            } else if (fresh.status == "submitted") {
                // Walked again for a reviewer: farm details are already in, so the new walk goes straight back for review.
                repo.completeFixes(farmerId, "fix_boundary")
                nav.reset(Screen.Home)
            } else {
                nav.reset(Screen.Home)
                nav.push(Screen.FarmDetails(farmerId))
            }
        }
    }
    fun attemptSave(another: Boolean) {
        when {
            differentLand -> return
            partLand && note.isBlank() -> notePrompt = true
            pct != null && pct > 25 -> confirm = if (another) "area-another" else "area"
            overlapFlagged -> confirm = if (another) "overlap-another" else "overlap"
            else -> save(another)
        }
    }

    FpScreen(
        bottom = {
            if (differentLand) {
                GrayButton(stringResource(R.string.ls_walk_registered), onClick = { walk.discard(); nav.replace(Screen.BoundaryIntro(farmerId, landId, purpose)) })
                FpButton(stringResource(R.string.ls_report_change), container = Color.White, content = Color(0xFFB42318), enabled = c.closesCleanly, onClick = { nav.push(Screen.LandChange(farmerId)) })
            } else {
                FpButton(
                    if (c.closesCleanly) stringResource(R.string.save_continue) else stringResource(R.string.cannot_save_crossing),
                    enabled = c.closesCleanly && !saving,
                    onClick = { attemptSave(false) },
                )
                GrayButton(stringResource(R.string.walk_again), onClick = { walk.discard(); nav.replace(Screen.BoundaryIntro(farmerId, landId, purpose)) })
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        StepHeader(walkHeaderCaption(f, purpose), if (plantWalk) walkTitle(purpose) else stringResource(R.string.boundary_captured), null)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(18.dp)).background(Fp.SatGreen)) {
            SatelliteMap(MapContent(polygon = c.ring, refRings = if (purpose == "pre_plant") onbRings else emptyList()), MapFit.FIT_POLYGON, Modifier.fillMaxSize(), interactive = false)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.area), acres(c.areaM2), Modifier.weight(1f))
            StatTile(stringResource(R.string.points), "${c.points.size}", Modifier.weight(1f))
            StatTile(stringResource(R.string.accuracy), "±${c.accuracyM.roundToInt().coerceAtLeast(1)} m", Modifier.weight(1f))
        }
        if (differentLand) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fp.RedLight).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.ls_different_land).uppercase(), style = FpType.Label.copy(color = Color(0xFFB42318), fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ls_only_share, (share!! * 100).roundToInt()), style = FpType.Headline.copy(color = Color(0xFFB42318)))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ls_different_body, org.fieldproof.location.distanceText(apart)), style = FpType.Secondary.copy(color = Color(0xFF7A1A12)))
            }
        }
        SectionLabel(stringResource(R.string.checks))
        FpCard {
            if (share != null) {
                CardRow {
                    Text(stringResource(R.string.ls_on_registered), style = FpType.Body, modifier = Modifier.weight(1f))
                    val col = if (share >= LandFit.OK) Fp.Green else if (share >= LandFit.MIN) Fp.Amber else Color(0xFFB42318)
                    Text("${(share * 100).roundToInt()}%", style = FpType.BodyMedium.copy(color = col, fontWeight = FontWeight.SemiBold))
                }
                RowDivider()
            }
            CardRow {
                Text(
                    when {
                        refAc == null -> stringResource(R.string.no_land_record)
                        purpose == "pre_plant" -> stringResource(R.string.matches_onboarding_walk, String.format(Locale.US, "%.2f", refAc))
                        purpose == "post_plant" -> stringResource(R.string.matches_pre_plant_walk, String.format(Locale.US, "%.2f", refAc))
                        else -> stringResource(R.string.matches_land_record, String.format(Locale.US, "%.2f", refAc))
                    },
                    style = FpType.Body, modifier = Modifier.weight(1f),
                )
                if (pct != null && diff != null) {
                    val word = when {
                        pct == 0 -> stringResource(R.string.same_area)
                        diff < 0 -> stringResource(R.string.pct_less, pct)
                        else -> stringResource(R.string.pct_more, pct)
                    }
                    Text((if (areaColor == Fp.Green) "✓ " else "⚠ ") + word, style = FpType.BodyMedium.copy(color = areaColor, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End)
                }
            }
            if (plantWalk && saplings > 0) {
                RowDivider()
                CardRow {
                    Text(stringResource(R.string.room_for_saplings, org.fieldproof.data.indian(room)), style = FpType.Body, modifier = Modifier.weight(1f))
                    if (room >= saplings) Icon(Lucide.Check, null, tint = Fp.Green, modifier = Modifier.size(18.dp))
                    else Text(stringResource(R.string.needs_room_for, org.fieldproof.data.indian(saplings)), style = FpType.BodyMedium.copy(color = Fp.Red, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End)
                }
            }
            RowDivider()
            CardRow {
                Text(stringResource(R.string.shape_closes), style = FpType.Body, modifier = Modifier.weight(1f))
                if (c.closesCleanly) Icon(Lucide.Check, null, tint = Fp.Green, modifier = Modifier.size(18.dp))
                else Text(stringResource(R.string.crosses_itself), style = FpType.BodyMedium.copy(color = Fp.Red, fontWeight = FontWeight.SemiBold))
            }
            RowDivider()
            CardRow {
                Text(stringResource(R.string.no_overlap), style = FpType.Body, modifier = Modifier.weight(1f))
                val ck = check
                when {
                    checking -> Text(stringResource(R.string.checking), style = FpType.Secondary)
                    ck == null -> Text(stringResource(R.string.checked_when_synced), style = FpType.Secondary)
                    ck.overlapOk -> Icon(Lucide.Check, null, tint = Fp.Green, modifier = Modifier.size(18.dp))
                    else -> Text(
                        stringResource(R.string.overlaps_with, ck.overlaps.filter { it.flagged }.joinToString { it.farmerCode }),
                        style = FpType.BodyMedium.copy(color = Fp.Red, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        if (partLand) Helper(stringResource(R.string.ls_part_help, org.fieldproof.location.distanceText(apart)), color = Fp.Amber)
        if (!c.closesCleanly) Helper(stringResource(R.string.crossing_help), color = Fp.Red)
        else if (areaColor != Fp.Green && pct != null) Helper(stringResource(R.string.area_mismatch_help), color = Fp.Amber)
        Spacer(Modifier.height(8.dp))
        if (!plantWalk) TextLink("+ " + stringResource(R.string.walk_another_parcel), { if (c.closesCleanly) attemptSave(true) })
        Spacer(Modifier.height(16.dp))
    }

    if (notePrompt) AlertDialog(
        onDismissRequest = { notePrompt = false },
        title = { Text(stringResource(R.string.ls_note_title), style = FpType.Headline) },
        text = {
            Column {
                Text(stringResource(R.string.ls_note_body, ((share ?: 0.0) * 100).roundToInt()), style = FpType.Secondary)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fp.Bg).padding(12.dp)) {
                    org.fieldproof.ui.components.FpTextField(note, { note = it.take(300) }, stringResource(R.string.ls_note_hint), Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(enabled = note.isNotBlank(), onClick = { notePrompt = false; save(false) }) { Text(stringResource(R.string.save_anyway), color = if (note.isNotBlank()) Fp.Green else Fp.Text3) } },
        dismissButton = { TextButton(onClick = { notePrompt = false }) { Text(stringResource(R.string.cancel), color = Fp.Text2) } },
        containerColor = Color.White,
    )

    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (kind.startsWith("area")) R.string.confirm_area_title else R.string.confirm_overlap_title), style = FpType.Headline) },
            text = { Text(stringResource(if (kind.startsWith("area")) R.string.confirm_area_body else R.string.confirm_overlap_body, pct ?: 0), style = FpType.Secondary) },
            confirmButton = { TextButton(onClick = { confirm = null; save(kind.endsWith("another")) }) { Text(stringResource(R.string.save_anyway), color = Fp.Green) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel), color = Fp.Text2) } },
            containerColor = Color.White,
        )
    }
}
