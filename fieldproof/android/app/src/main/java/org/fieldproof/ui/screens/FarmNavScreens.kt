package org.fieldproof.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Farmer
import org.fieldproof.geo.Geo
import org.fieldproof.geo.LandFit
import org.fieldproof.geo.LatLng
import org.fieldproof.location.FarmTarget
import org.fieldproof.location.distanceText
import org.fieldproof.location.farmTargets
import org.fieldproof.location.openGoogleMaps
import org.fieldproof.location.rememberHeading
import org.fieldproof.map.MapContent
import org.fieldproof.map.MapFit
import org.fieldproof.map.SatelliteMap
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.Banner
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.GrayButton
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.Pill
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.TintButton
import org.fieldproof.ui.components.ValueRow
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Getting the surveyor to the farmer's field: Google Maps for the roads, then
// "Find the farm" (GPS + the boundary saved on the phone) for the walk across
// the fields. Works without internet except for the satellite picture.

private val COMPASS = listOf(R.string.dir_n, R.string.dir_ne, R.string.dir_e, R.string.dir_se, R.string.dir_s, R.string.dir_sw, R.string.dir_w, R.string.dir_nw)

@Composable
private fun targetTitle(t: FarmTarget) = buildString {
    if (t.label.isNotEmpty()) append(t.label)
    if (t.areaM2 > 0) { if (isNotEmpty()) append(" · "); append(String.format(Locale.US, "%.2f ac", t.areaM2 / Geo.SQ_M_PER_ACRE)) }
}.ifEmpty { stringResource(R.string.nav_photo_spot) }

/** "Farm location" with Navigate to farm / Find the farm on foot. Nothing when the farm's place isn't known. */
@Composable
fun FarmLocationCard(nav: Navigator, f: Farmer) {
    val ctx = LocalContext.current
    val targets = remember(f.boundaries, f.photos) { farmTargets(f) }
    if (targets.isEmpty()) return
    val here by FieldproofApp.instance.gps.lastAnyLocation.collectAsState()
    var choose by remember { mutableStateOf<String?>(null) } // "maps" | "find" while picking a plot
    SectionLabel(stringResource(R.string.nav_farm_location))
    FpCard {
        targets.forEachIndexed { i, t ->
            val d = here?.let { Geo.distance(LatLng(it.latitude, it.longitude), t.point) }
            ValueRow(targetTitle(t), d?.let { stringResource(R.string.nav_away, distanceText(it)) } ?: "")
            if (i < targets.lastIndex) RowDivider()
        }
    }
    if (targets.first().ring != null) Helper(stringResource(R.string.nav_pin_note))
    else Helper(stringResource(R.string.nav_photo_note))
    Spacer(Modifier.height(10.dp))
    FpButton(
        stringResource(R.string.nav_navigate), leading = { Icon(Lucide.Navigation, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
        onClick = { if (targets.size > 1) choose = "maps" else openGoogleMaps(ctx, targets.first().point) },
    )
    Spacer(Modifier.height(10.dp))
    TintButton(stringResource(R.string.nav_find), onClick = { if (targets.size > 1) choose = "find" else nav.push(Screen.FindFarm(f.id, targets.first().landId)) })
    Helper(stringResource(R.string.nav_help))

    choose?.let { what ->
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text(stringResource(R.string.nav_which_plot), style = FpType.Headline) },
            text = {
                Column {
                    Text(stringResource(R.string.nav_which_plot_body, targets.size), style = FpType.Secondary)
                    Spacer(Modifier.height(8.dp))
                    targets.forEach { t ->
                        val d = here?.let { Geo.distance(LatLng(it.latitude, it.longitude), t.point) }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                choose = null
                                if (what == "maps") openGoogleMaps(ctx, t.point) else nav.push(Screen.FindFarm(f.id, t.landId))
                            }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(targetTitle(t), style = FpType.BodyMedium, modifier = Modifier.weight(1f))
                            if (d != null) Text(distanceText(d), style = FpType.Secondary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = null }) { Text(stringResource(R.string.cancel), color = Fp.Text2) } },
            containerColor = Color.White,
        )
    }
}

@Composable
fun FindFarmScreen(nav: Navigator, farmerId: String, landId: String?) {
    val app = FieldproofApp.instance
    val repo = app.repo
    val ctx = LocalContext.current
    val s by repo.state.collectAsState()
    val reachable by repo.serverReachable.collectAsState()
    // GPS needs precise location permission. A fresh install may not have it yet
    // (it was only asked for on the boundary walk), so ask here too.
    var permitted by remember { mutableStateOf(app.gps.hasPermission()) }
    var asked by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        permitted = app.gps.hasPermission()
    }
    LaunchedEffect(Unit) {
        if (!permitted) perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    DisposableEffect(permitted) {
        if (permitted) app.gps.acquire()
        onDispose { if (permitted) app.gps.release() }
    }
    val gpsOn by app.gps.gpsEnabled.collectAsState()
    val loc by app.gps.lastAnyLocation.collectAsState()
    val heading = rememberHeading()
    val f = s.farmers[farmerId] ?: return
    val targets = remember(f.boundaries, f.photos) { farmTargets(f) }
    val t = targets.firstOrNull { it.landId == landId } ?: targets.firstOrNull()
    val cur = loc?.let { LatLng(it.latitude, it.longitude) }
    val acc = loc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
    val rings = listOfNotNull(t?.ring)
    val signed = if (cur != null && rings.isNotEmpty()) LandFit.signedDistance(cur, rings) else null
    // Count as arrived once inside; leave only after walking 10 m back out, so GPS jitter at the edge doesn't flicker.
    var reached by remember { mutableStateOf(false) }
    LaunchedEffect(signed) { signed?.let { if (it < 0) reached = true else if (it > 10) reached = false } }
    val aim = if (cur != null && rings.isNotEmpty()) LandFit.nearestPoint(cur, rings) ?: t!!.point else t?.point
    val dist = when {
        cur == null || t == null -> null
        signed != null -> signed.coerceAtLeast(0.0)
        else -> Geo.distance(cur, t.point)
    }
    val bearing = if (cur != null && aim != null && aim != cur) LandFit.bearing(cur, aim) else null
    val weak = acc != null && acc > 20
    val sheetMap = MapContent(
        current = cur, accuracyM = acc, refRings = rings, target = t?.point,
        guideTo = if (!reached) aim else null, heading = heading, fitBottomPad = 0.8,
    )

    Box(Modifier.fillMaxSize().background(Fp.SatGreen)) {
        SatelliteMap(sheetMap, MapFit.FIT_ALL, Modifier.fillMaxSize(), fallbackCenter = t?.point)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹ " + stringResource(R.string.back), modifier = Modifier.clip(CircleShape).clickable { nav.pop() })
            Spacer(Modifier.weight(1f))
            when {
                !permitted || !gpsOn -> Pill(stringResource(R.string.gps_off), dot = Fp.Red, textColor = Fp.Red)
                acc == null -> Pill(stringResource(R.string.gps_searching), textColor = Fp.Amber, animateDots = true)
                weak -> Pill(stringResource(R.string.nav_gps_weak, acc.roundToInt()), dot = Fp.Amber, textColor = Fp.Amber)
                else -> Pill(stringResource(R.string.nav_gps_good, acc.roundToInt().coerceAtLeast(1)), dot = Fp.Green, textColor = Fp.Green)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Fp.Bg)
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color(0xFFC7C7CC)))
            Spacer(Modifier.height(12.dp))
            Text(listOf(f.shortName, t?.let { targetTitle(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").uppercase(), style = FpType.Label.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            when {
                t == null -> Text(stringResource(R.string.nav_no_location), style = FpType.Secondary)
                !permitted -> {
                    Banner(stringResource(R.string.nav_need_permission), bg = Fp.RedLight, fg = Fp.Red)
                    Spacer(Modifier.height(12.dp))
                    FpButton(stringResource(R.string.nav_allow_location), onClick = {
                        // After "Don't ask again" Android shows no dialog: open the app's settings instead.
                        if (asked) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        else perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    })
                    Spacer(Modifier.height(10.dp))
                    GrayButton(stringResource(R.string.nav_open_maps), onClick = { openGoogleMaps(ctx, t.point) })
                }
                !gpsOn -> {
                    Banner(stringResource(R.string.turn_on_location), bg = Fp.RedLight, fg = Fp.Red)
                    Spacer(Modifier.height(12.dp))
                    FpButton(stringResource(R.string.open_settings), onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                }
                cur == null || dist == null -> {
                    Text(stringResource(R.string.nav_finding_you), style = FpType.Secondary)
                    Helper(stringResource(R.string.nav_finding_help))
                    Spacer(Modifier.height(12.dp))
                    GrayButton(stringResource(R.string.nav_open_maps), onClick = { openGoogleMaps(ctx, t.point) })
                }
                reached -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(Fp.GreenLight), contentAlignment = Alignment.Center) {
                            Icon(Lucide.Check, null, tint = Fp.Green, modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.nav_reached), style = FpType.Title.copy(fontWeight = FontWeight.ExtraBold))
                            Text(stringResource(R.string.nav_inside, targetTitle(t)), style = FpType.Secondary)
                        }
                    }
                    if (signed != null && signed < 0) {
                        Spacer(Modifier.height(12.dp))
                        Banner(stringResource(R.string.nav_inside_by, abs(signed).roundToInt()))
                    }
                    Spacer(Modifier.height(12.dp))
                    FpButton(stringResource(R.string.nav_continue), onClick = { nav.pop() })
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(if (weak) Fp.AmberLight else Fp.Green), contentAlignment = Alignment.Center) {
                            Icon(
                                Lucide.ArrowUp, null, tint = if (weak) Fp.Amber else Color.White,
                                modifier = Modifier.size(38.dp).rotate(((bearing ?: 0.0) - (heading ?: 0f)).toFloat()),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text((if (weak) "≈ " else "") + distanceText(dist), style = FpType.Greeting.copy(fontSize = 38.sp, lineHeight = 42.sp))
                            val dir = bearing?.let { stringResource(COMPASS[LandFit.compassPoint(it)]) }.orEmpty()
                            Text(
                                stringResource(if (rings.isNotEmpty()) R.string.nav_to_edge else R.string.nav_to_photo, dir) +
                                    (if (weak) " · " + stringResource(R.string.nav_could_be_off, acc!!.roundToInt()) else ""),
                                style = FpType.Secondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        weak -> Banner(stringResource(R.string.nav_weak_help), bg = Fp.AmberLight, fg = Fp.Amber)
                        rings.isEmpty() -> Banner(stringResource(R.string.nav_no_boundary_help), bg = Fp.AmberLight, fg = Fp.Amber)
                        else -> Text(stringResource(R.string.nav_straight_help), style = FpType.Secondary, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp))
                    }
                    if (heading == null) Helper(stringResource(R.string.nav_no_compass))
                    if (reachable == false) Helper(stringResource(R.string.nav_offline))
                    Spacer(Modifier.height(12.dp))
                    GrayButton(stringResource(R.string.nav_open_maps), onClick = { openGoogleMaps(ctx, t.point) })
                }
            }
        }
    }
}

