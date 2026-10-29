package org.fieldproof.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.FarmDetails
import org.fieldproof.geo.Geo
import org.fieldproof.geo.LatLng
import org.fieldproof.geo.XY
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
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.Segmented
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MORE = "__more"
private const val NONE = "none"

val waterSources = listOf("borewell" to R.string.ws_borewell, "open_well" to R.string.ws_open_well, "canal" to R.string.ws_canal, "farm_pond" to R.string.ws_farm_pond, "rain_only" to R.string.ws_rain_only)
val timberTrees = listOf("mahogany" to R.string.tr_mahogany, "teak" to R.string.tr_teak, "red_sanders" to R.string.tr_red_sanders, "melia_dubia" to R.string.tr_melia_dubia)
val fruitTrees = listOf("mango" to R.string.tr_mango, "guava" to R.string.tr_guava, "custard_apple" to R.string.tr_custard_apple, "jamun" to R.string.tr_jamun)
val landUses = listOf("cropland" to R.string.lu_cropland, "fallow" to R.string.lu_fallow, "barren" to R.string.lu_barren, "grassland" to R.string.lu_grassland, "water_body" to R.string.lu_water_body, "structures" to R.string.lu_structures)
val kharifMain = listOf("soybean" to R.string.cr_soybean, "cotton" to R.string.cr_cotton, "tur" to R.string.cr_tur, "moong" to R.string.cr_moong, "jowar" to R.string.cr_jowar)
val kharifMore = listOf("udid" to R.string.cr_udid, "bajra" to R.string.cr_bajra, "maize" to R.string.cr_maize, "sugarcane" to R.string.cr_sugarcane, "groundnut" to R.string.cr_groundnut, "turmeric" to R.string.cr_turmeric, "vegetables" to R.string.cr_vegetables)
val rabiMain = listOf("wheat" to R.string.cr_wheat, "chickpea" to R.string.cr_chickpea, "jowar" to R.string.cr_jowar, "mustard" to R.string.cr_mustard)
val rabiMore = listOf("safflower" to R.string.cr_safflower, "sunflower" to R.string.cr_sunflower, "onion" to R.string.cr_onion, "vegetables" to R.string.cr_vegetables, "maize" to R.string.cr_maize)

/** Distance from a point to the nearest edge of the farm boundary (0 if inside). */
fun distanceToBoundary(p: LatLng, ring: List<LatLng>): Double {
    if (ring.size < 3) return Double.NaN
    val t = Geo.Enu(p)
    val xy = ring.map { t.project(it) }
    var inside = false
    for (i in xy.indices) {
        val a = xy[i]; val b = xy[(i + 1) % xy.size]
        if ((a.n > 0) != (b.n > 0) && 0 < (b.e - a.e) * (0 - a.n) / (b.n - a.n) + a.e) inside = !inside
    }
    if (inside) return 0.0
    return xy.indices.minOf { i ->
        val a = xy[i]; val b = xy[(i + 1) % xy.size]
        val dx = b.e - a.e; val dy = b.n - a.n
        val l2 = dx * dx + dy * dy
        val u = if (l2 == 0.0) 0.0 else ((-a.e) * dx + (-a.n) * dy) / l2
        val q = XY(a.e + u.coerceIn(0.0, 1.0) * dx, a.n + u.coerceIn(0.0, 1.0) * dy)
        hypot(q.e, q.n)
    }
}

@Composable
private fun labels(list: List<Pair<String, Int>>) = list.map { it.first to stringResource(it.second) }

@Composable
fun FarmDetailsScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val f = s.farmers[farmerId] ?: return
    val d = f.farmDetails ?: FarmDetails()
    var showErrors by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf<Pair<String, String>?>(null) } // season to year
    var done by remember { mutableStateOf(false) }
    fun edit(t: (FarmDetails) -> FarmDetails) = repo.saveFarmDetailsDraft(farmerId, t(repo.state.value.farmers[farmerId]?.farmDetails ?: FarmDetails()))
    fun toggle(list: List<String>, k: String) = if (k in list) list - k else list + k

    val waterPhoto = d.waterPhotoId?.let { id -> f.photos.find { it.id == id } }
    val needPhoto = d.waterSources.isNotEmpty() && d.waterSources != listOf("rain_only")
    val problems = buildList {
        if (d.waterSources.isEmpty()) add(R.string.err_water)
        if (needPhoto && waterPhoto == null) add(R.string.err_water_photo)
        if (d.timberPercent > 0 && d.timberSpecies.isEmpty()) add(R.string.err_timber)
        if (d.timberPercent < 100 && d.fruitSpecies.isEmpty()) add(R.string.err_fruit)
        if (d.landUse.isEmpty()) add(R.string.err_land_use)
        if (d.kharif["2025"].isNullOrEmpty() || d.kharifSame == null) add(R.string.err_kharif)
        if (d.kharifSame == false && (d.kharif["2024"].isNullOrEmpty() || d.kharif["2023"].isNullOrEmpty())) add(R.string.err_kharif_years)
        if (d.rabi["2025"].isNullOrEmpty() || d.rabiSame == null) add(R.string.err_rabi)
        if (d.rabiSame == false && (d.rabi["2024"].isNullOrEmpty() || d.rabi["2023"].isNullOrEmpty())) add(R.string.err_rabi_years)
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }, right = SavedOnPhone()) },
        bottom = {
            FpButton(stringResource(R.string.submit_review), onClick = {
                if (problems.isNotEmpty()) { showErrors = true; return@FpButton }
                scope.launch { repo.submitFarmDetails(farmerId, d); done = true }
            })
        },
    ) {
        StepHeader(stringResource(R.string.farmer_step, f.shortName, 5), stringResource(R.string.farm_details), 5)

        SectionLabel(stringResource(R.string.water_source))
        ChipGroup(labels(waterSources), d.waterSources, { k -> edit { it.copy(waterSources = toggle(it.waterSources, k)) } })
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (waterPhoto != null) {
                val bmp by produceState<ImageBitmap?>(null, waterPhoto.path) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeFile(waterPhoto.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull()
                    }
                }
                val ring = f.boundaries.firstOrNull()?.points?.map { LatLng(it.lat, it.lng) }.orEmpty()
                val dist = if (waterPhoto.lat != null && waterPhoto.lng != null) distanceToBoundary(LatLng(waterPhoto.lat, waterPhoto.lng), ring) else Double.NaN
                val srcName = waterSources.find { it.first == d.waterSources.firstOrNull() }?.second?.let { stringResource(it) } ?: ""
                Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(14.dp)).background(Fp.SatGreen).clickable { camera = true }) {
                    bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                    Text(
                        when {
                            dist.isNaN() -> srcName
                            dist < 1 -> stringResource(R.string.photo_inside_farm, srcName)
                            else -> stringResource(R.string.photo_m_away, srcName, dist.roundToInt())
                        },
                        style = FpType.Small.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                    Box(Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(22.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Fp.Green), contentAlignment = Alignment.Center) {
                        Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { camera = true }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Lucide.Camera, null, tint = Fp.Green, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(if (waterPhoto == null) R.string.add_photo else R.string.retake_photo), style = FpType.BodyMedium.copy(fontSize = 14.sp, color = Fp.Green, fontWeight = FontWeight.SemiBold))
                }
            }
            if (waterPhoto == null) Spacer(Modifier.weight(1f))
        }

        SectionLabel(stringResource(R.string.trees_wanted))
        FpCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.timber_boundary), style = FpType.Headline.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("${d.timberPercent}%", style = FpType.Headline.copy(fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.height(10.dp))
                ChipGroup(labels(timberTrees), d.timberSpecies, { k -> edit { it.copy(timberSpecies = toggle(it.timberSpecies, k)) } })
            }
            RowDivider()
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fruit_blocks), style = FpType.Headline.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("${100 - d.timberPercent}%", style = FpType.Headline.copy(fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.height(10.dp))
                ChipGroup(labels(fruitTrees), d.fruitSpecies, { k -> edit { it.copy(fruitSpecies = toggle(it.fruitSpecies, k)) } })
                Spacer(Modifier.height(16.dp))
                SplitBar(d.timberPercent) { v -> edit { it.copy(timberPercent = v) } }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.split_help), style = FpType.Helper)
            }
        }

        SectionLabel(stringResource(R.string.land_use_today))
        ChipGroup(labels(landUses), d.landUse, { k -> edit { it.copy(landUse = toggle(it.landUse, k)) } })

        CropSection(stringResource(R.string.kharif_crops, 2025), "kharif", d.kharif, d.kharifSame, kharifMain, kharifMore, onToggle = { year, k ->
            edit { it.copy(kharif = toggleCrop(it.kharif, year, k)) }
        }, onSame = { v -> edit { it.copy(kharifSame = v) } }, onMore = { year -> more = "kharif" to year })
        CropSection(stringResource(R.string.rabi_crops, 2025), "rabi", d.rabi, d.rabiSame, rabiMain, rabiMore, onToggle = { year, k ->
            edit { it.copy(rabi = toggleCrop(it.rabi, year, k)) }
        }, onSame = { v -> edit { it.copy(rabiSame = v) } }, onMore = { year -> more = "rabi" to year })
        Helper(stringResource(R.string.same_crops_help))
        if (showErrors && problems.isNotEmpty()) problems.distinct().forEach { Helper(stringResource(it), color = Fp.Red) }
        Spacer(Modifier.height(16.dp))
    }

    if (camera) CameraCapture("water_source", null, stringResource(R.string.water_photo_title), Frame.SCENE,
        onDone = { p -> camera = false; scope.launch { repo.addPhoto(farmerId, p); edit { it.copy(waterPhotoId = p.id) } } },
        onCancel = { camera = false })

    more?.let { (season, year) ->
        val opts = labels(if (season == "kharif") kharifMore else rabiMore)
        val current = (if (season == "kharif") d.kharif else d.rabi)[year].orEmpty()
        AlertDialog(
            onDismissRequest = { more = null },
            title = { Text(stringResource(R.string.more_crops), style = FpType.Headline) },
            text = { ChipGroup(opts, current, { k -> edit { if (season == "kharif") it.copy(kharif = toggleCrop(it.kharif, year, k)) else it.copy(rabi = toggleCrop(it.rabi, year, k)) } }) },
            confirmButton = { TextButton(onClick = { more = null }) { Text(stringResource(R.string.done), color = Fp.Green) } },
            containerColor = Color.White,
        )
    }

    if (done) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.submitted_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.submitted_body, f.shortName), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { nav.reset(Screen.Home) }) { Text(stringResource(R.string.back_to_home), color = Fp.Green) } },
        containerColor = Color.White,
    )
}

private fun toggleCrop(m: Map<String, List<String>>, year: String, k: String): Map<String, List<String>> {
    val cur = m[year].orEmpty()
    val next = when {
        k == NONE -> if (NONE in cur) emptyList() else listOf(NONE)
        k in cur -> cur - k
        else -> cur - NONE + k
    }
    return m + (year to next)
}

@Composable
private fun CropSection(
    title: String,
    season: String,
    crops: Map<String, List<String>>,
    same: Boolean?,
    main: List<Pair<String, Int>>,
    extra: List<Pair<String, Int>>,
    onToggle: (String, String) -> Unit,
    onSame: (Boolean) -> Unit,
    onMore: (String) -> Unit,
) {
    @Composable
    fun chips(year: String) {
        val sel = crops[year].orEmpty()
        val shownExtra = extra.filter { it.first in sel }
        val opts = labels(main) + labels(shownExtra) + listOf(MORE to stringResource(R.string.more), NONE to stringResource(R.string.none))
        ChipGroup(opts, sel, { k -> if (k == MORE) onMore(year) else onToggle(year, k) })
    }
    SectionLabel(title)
    chips("2025")
    Spacer(Modifier.height(12.dp))
    FpCard {
        CardRow {
            Text(stringResource(R.string.same_crops_q), style = FpType.Body, modifier = Modifier.weight(1f))
            Segmented(listOf(stringResource(R.string.yes), stringResource(R.string.no)), when (same) { true -> 0; false -> 1; null -> null }, { onSame(it == 0) }, Modifier.width(120.dp), height = 32.dp)
        }
    }
    if (same == false) {
        listOf("2024", "2023").forEach { y ->
            SectionLabel(stringResource(if (season == "kharif") R.string.kharif_crops else R.string.rabi_crops, y.toInt()))
            chips(y)
        }
    }
}

/** Two-tone bar: drag or tap to split land between timber and fruit, in 5% steps. Always totals 100%. */
@Composable
private fun SplitBar(timber: Int, onChange: (Int) -> Unit) {
    var width by remember { mutableStateOf(1) }
    fun at(x: Float) = ((x / width * 100) / 5).roundToInt().coerceIn(0, 20) * 5
    Box(
        Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width }
            .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
            if (timber > 0) Box(Modifier.weight(timber.toFloat()).fillMaxHeight().background(Fp.Green))
            if (timber < 100) Box(Modifier.weight((100 - timber).toFloat()).fillMaxHeight().background(Color(0xFF6CC48A)))
        }
    }
}
