package org.fieldproof.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Api
import org.fieldproof.data.ApiException
import org.fieldproof.data.DeliveryLocal
import org.fieldproof.data.Farmer
import org.fieldproof.data.PaymentLocal
import org.fieldproof.data.Photo
import org.fieldproof.data.PlanState
import org.fieldproof.data.PlantOtpSent
import org.fieldproof.data.PlantedLocal
import org.fieldproof.data.amountDue
import org.fieldproof.data.cleanReference
import org.fieldproof.data.indian
import org.fieldproof.data.isValidReference
import org.fieldproof.data.newId
import org.fieldproof.data.rupees
import org.fieldproof.geo.Geo
import org.fieldproof.photo.CameraCapture
import org.fieldproof.photo.Frame
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.BackLink
import org.fieldproof.ui.components.Banner
import org.fieldproof.ui.components.CardRow
import org.fieldproof.ui.components.FpButton
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpCheckbox
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.FpTextField
import org.fieldproof.ui.components.GrayButton
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.components.SectionLabel
import org.fieldproof.ui.components.Segmented
import org.fieldproof.ui.components.StatTile
import org.fieldproof.ui.components.StepHeader
import org.fieldproof.ui.components.TextLink
import org.fieldproof.ui.components.TopBar
import org.fieldproof.ui.components.ValueRow
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

// Payment and plantation on the phone. The server decides the farmer's step;
// what the surveyor did on this phone but has not synced yet moves the step
// forward locally (see [effectiveStage]).

private fun ac(m2: Double) = String.format(Locale.US, "%.2f ac", m2 / Geo.SQ_M_PER_ACRE)

/** False when no species was chosen at onboarding and the server sent the plain kind ("Timber"). */
private fun named(species: String, kind: String) = !species.equals(kind, ignoreCase = true)

private fun caption(f: Farmer) = listOf(f.shortName, f.code, f.villageName).filter { it.isNotBlank() }.joinToString(" · ")

private fun whenText(iso: String?): String {
    val ms = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}

private fun whenText(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Payment and plantation step, moved forward by what is still waiting to sync on this phone. */
private fun effectiveStage(p: PlanState, f: Farmer, ops: List<org.fieldproof.data.OutboxOp>): Pair<String, String> {
    val mine = ops.filter { it.farmerId == f.id }
    var pay = p.payment
    var plant = p.plantation
    if (mine.any { it.type == "put_payment" }) pay = "waiting"
    if (pay != "approved") return pay to "locked"
    val walk = { purpose: String -> mine.any { op -> op.type == "put_plant_walk" && f.plantWalks.any { it.id == op.refId && it.purpose == purpose } } }
    if (plant in setOf("pre_walk", "delivery", "short", "land_review", "land_fix") && walk("pre_plant")) {
        val b = f.plantWalks.lastOrNull { it.purpose == "pre_plant" }
        plant = if (b != null && f.landChange?.boundaryId == b.id) "land_review" else "delivery"
    }
    if (plant == "land_fix" && mine.any { op -> op.type == "upload_file" && f.photos.any { it.id == op.refId && it.kind == "land_change_record" } }) plant = "land_review"
    if (plant in setOf("delivery", "short") && mine.any { it.type == "post_delivery" }) {
        val d = f.delivery
        val ok = d != null && p.pay != null && d.timberCounted == p.pay.timber && d.fruitCounted == p.pay.fruit && d.healthy && d.rightSpecies && d.satisfied
        plant = if (ok) "otp" else "short"
    }
    if (plant == "planting" && mine.any { it.type == "post_planted" }) plant = "post_walk"
    if (plant in setOf("post_walk", "rejected", "pending") && walk("post_plant")) plant = "to_review"
    if (plant in setOf("rejected", "pending") && mine.any { op -> op.type == "upload_file" && f.photos.any { it.id == op.refId && it.kind in setOf("plant_pits", "plant_planted") } }) plant = "to_review"
    return pay to plant
}

private data class StepRow(val key: String, val state: String)

private fun stepsFor(onboardingOk: Boolean, pay: String, plant: String): List<StepRow> {
    val order = listOf("onboarding", "payment", "approval", "pre_walk", "delivery", "planting", "post_walk", "review")
    var reached = 0
    var stopped = false
    when {
        !onboardingOk -> reached = 0
        pay == "none" -> reached = 1
        pay != "approved" -> { reached = 2; stopped = pay == "rejected" || pay == "pending" }
        else -> when (plant) {
            "pre_walk", "land_review" -> reached = 3
            "land_fix" -> { reached = 3; stopped = true }
            "delivery", "otp" -> reached = 4
            "short" -> { reached = 4; stopped = true }
            "planting" -> reached = 5
            "post_walk" -> reached = 6
            "to_review" -> reached = 7
            "rejected", "pending" -> { reached = 7; stopped = true }
            "done" -> reached = 8
        }
    }
    return order.mapIndexed { i, k -> StepRow(k, if (i < reached) "done" else if (i == reached) (if (stopped) "stopped" else "current") else "locked") }
}

private val STEP_LABEL = mapOf(
    "onboarding" to R.string.ps_onboarding, "payment" to R.string.ps_payment, "approval" to R.string.ps_approval,
    "pre_walk" to R.string.ps_pre_walk, "delivery" to R.string.ps_delivery, "planting" to R.string.ps_planting,
    "post_walk" to R.string.ps_post_walk, "review" to R.string.ps_review,
)
private val STEP_HINT = mapOf(
    "onboarding" to R.string.ps_onboarding_hint, "payment" to R.string.ps_payment_hint, "approval" to R.string.ps_approval_hint,
    "pre_walk" to R.string.ps_pre_walk_hint, "delivery" to R.string.ps_delivery_hint, "planting" to R.string.ps_planting_hint,
    "post_walk" to R.string.ps_post_walk_hint, "review" to R.string.ps_review_hint,
)

@Composable
private fun StepsCard(rows: List<StepRow>, sub: Map<String, String>) {
    FpCard {
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg) = when (r.state) {
                    "done" -> Fp.Green to Color.White
                    "current" -> Color(0xFFE8EEFC) to Fp.Blue
                    "stopped" -> Fp.RedLight to Color(0xFFB42318)
                    else -> Color(0xFFF2F2F7) to Fp.Text3
                }
                Box(Modifier.size(26.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    if (r.state == "done") Icon(Lucide.Check, null, tint = fg, modifier = Modifier.size(15.dp))
                    else Text(if (r.state == "stopped") "!" else "${i + 1}", style = FpType.Small.copy(color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val strong = r.state == "current" || r.state == "stopped"
                    Text(stringResource(STEP_LABEL.getValue(r.key)), style = FpType.Body.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal, color = if (r.state == "locked") Fp.Text3 else Fp.Text))
                    Text(sub[r.key] ?: stringResource(STEP_HINT.getValue(r.key)), style = FpType.Helper.copy(color = if (r.state == "stopped") Color(0xFFB42318) else Fp.Text2))
                }
            }
            if (i < rows.lastIndex) RowDivider(start = 56.dp)
        }
    }
}

/** The reviewer's message when the payment or plantation was sent back. */
@Composable
private fun ReviewerBox(what: String, decision: String, message: String, reviewer: String, at: String?) {
    val rejected = decision == "rejected"
    val tone = if (rejected) Color(0xFFB42318) else Color(0xFF92400E)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (rejected) Fp.RedLight else Fp.AmberLight).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("$what · ${stringResource(if (rejected) R.string.st_rejected else R.string.st_pending)}".uppercase(), style = FpType.Label.copy(color = tone, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        Text("“$message”", style = FpType.Body.copy(lineHeight = 23.sp))
        if (reviewer.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.fix_reviewer_line, reviewer, whenText(at)), style = FpType.Helper.copy(color = tone))
        }
    }
}

@Composable
private fun WaitingToSync(f: Farmer) {
    val s by FieldproofApp.instance.repo.state.collectAsState()
    if (s.outbox.any { it.farmerId == f.id }) {
        Spacer(Modifier.height(12.dp))
        Banner(stringResource(R.string.plan_saved_on_phone), bg = Fp.AmberLight, fg = Fp.Amber)
    }
}

@Composable
private fun loadPlanState(farmerId: String): Pair<Farmer?, PlanState?> {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    LaunchedEffect(farmerId) {
        runCatching { repo.loadFarmer(farmerId) }
        repo.loadPlan(farmerId)
    }
    return s.farmers[farmerId] to s.plans[farmerId]
}

@Composable
private fun Loading(nav: Navigator) {
    FpScreen(top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) }) {
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.plan_loading), style = FpType.Secondary)
    }
}

// ---------- the steps of one farmer ----------

@Composable
fun PlanScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val (f, p) = loadPlanState(farmerId)
    if (f == null || p == null) { Loading(nav); return }
    val (pay, plant) = effectiveStage(p, f, s.outbox)
    val plan = p.plan
    val paid = p.pay
    val saplings = paid?.let { it.timber + it.fruit } ?: f.payment?.let { it.timber + it.fruit } ?: plan.timber + plan.fruit
    var camera by remember { mutableStateOf<String?>(null) } // photo kind being taken
    var photoSent by remember { mutableStateOf(false) }
    val lc = p.plant?.landChange
    val gat = f.landChange?.surveyNo ?: lc?.surveyNo ?: ""

    val sub = buildMap {
        if (paid != null || f.payment != null) {
            val t = paid?.timber ?: f.payment!!.timber
            val fr = paid?.fruit ?: f.payment!!.fruit
            put("payment", stringResource(R.string.ps_payment_done, indian(t), indian(fr), rupees(amountDue(t, fr))))
        }
        if (pay == "rejected" || pay == "pending") put("approval", stringResource(R.string.ps_approval_back))
        if (plant == "short") put("delivery", stringResource(R.string.ps_delivery_short))
        if (plant == "rejected" || plant == "pending") put("review", stringResource(R.string.ps_review_back))
        p.plant?.preAreaM2?.let { put("pre_walk", ac(it)) }
        when {
            plant == "land_review" -> put("pre_walk", stringResource(R.string.ps_land_review))
            plant == "land_fix" -> put("pre_walk", stringResource(R.string.ps_land_fix))
            plant == "pre_walk" && p.plant?.landChange?.status == "rejected" -> put("pre_walk", stringResource(R.string.ps_land_rejected))
        }
        p.plant?.postAreaM2?.let { put("post_walk", ac(it)) }
    }

    val title = when {
        !p.onboardingOk -> R.string.pt_onboarding
        pay == "none" -> R.string.pt_collect
        pay == "waiting" -> R.string.pt_pay_review
        pay != "approved" -> R.string.pt_pay_fix
        plant == "land_review" -> R.string.pt_land_review
        plant == "land_fix" -> R.string.pt_land_fix
        plant == "pre_walk" -> R.string.walk_pre_plant_title
        plant == "delivery" -> R.string.pt_delivery
        plant == "short" -> R.string.pt_short
        plant == "otp" -> R.string.pt_otp
        plant == "planting" -> R.string.pt_planting
        plant == "post_walk" -> R.string.walk_post_plant_title
        plant == "to_review" -> R.string.pt_complete
        plant == "done" -> R.string.pt_done
        else -> R.string.pt_plant_fix
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            when {
                pay == "none" || pay == "rejected" || pay == "pending" ->
                    FpButton(stringResource(if (pay == "none") R.string.pt_collect else R.string.pa_fix_button), onClick = { nav.push(Screen.Payment(farmerId)) })
                pay != "approved" -> {}
                plant == "pre_walk" -> FpButton(stringResource(R.string.start_walking), onClick = { nav.push(Screen.BoundaryIntro(farmerId, null, "pre_plant")) })
                plant == "land_review" -> {}
                plant == "land_fix" -> FpButton(stringResource(R.string.pt_new_712), onClick = { camera = "land_change_record" })
                plant == "delivery" -> FpButton(stringResource(R.string.pd_arrived_button), onClick = { nav.push(Screen.Delivery(farmerId)) })
                plant == "short" -> FpButton(stringResource(R.string.pd_count_again), onClick = { nav.push(Screen.Delivery(farmerId)) })
                plant == "otp" -> FpButton(stringResource(R.string.po_ask_button), onClick = { nav.push(Screen.FarmerOtp(farmerId)) })
                plant == "planting" -> FpButton(stringResource(R.string.pp_open_button), onClick = { nav.push(Screen.Planting(farmerId)) })
                plant == "post_walk" -> FpButton(stringResource(R.string.start_walking), onClick = { nav.push(Screen.BoundaryIntro(farmerId, null, "post_plant")) })
                plant == "rejected" || plant == "pending" -> {
                    FpButton(stringResource(R.string.pt_new_photo), onClick = { camera = "plant_planted" })
                    GrayButton(stringResource(R.string.pt_walk_again), onClick = { nav.push(Screen.BoundaryIntro(farmerId, null, "post_plant")) })
                }
                else -> FpButton(stringResource(R.string.pr_open), onClick = { nav.push(Screen.FarmerRecord(farmerId)) })
            }
            if (!(plant == "rejected" || plant == "pending")) GrayButton(stringResource(R.string.back_to_home), onClick = { nav.reset(Screen.Home) })
        },
    ) {
        StepHeader(caption(f), stringResource(title), null)
        Spacer(Modifier.height(8.dp))
        val desc = when {
            !p.onboardingOk -> stringResource(R.string.pt_onboarding_desc)
            pay == "none" -> stringResource(R.string.pt_collect_desc, rupees(plan.amountDue), indian(plan.timber + plan.fruit))
            pay == "waiting" -> stringResource(R.string.pt_pay_review_desc, rupees(paid?.amountPaid ?: f.payment?.amountPaid ?: 0))
            pay != "approved" -> ""
            plant == "pre_walk" -> stringResource(R.string.walk_pre_plant_help)
            plant == "land_review" -> stringResource(R.string.pt_land_review_desc, gat)
            plant == "land_fix" -> ""
            plant == "delivery" -> stringResource(R.string.pt_delivery_desc)
            plant == "short" -> stringResource(R.string.pd_short_body, indian(saplings))
            plant == "otp" -> stringResource(R.string.pt_otp_desc)
            plant == "planting" -> stringResource(R.string.pt_planting_desc)
            plant == "post_walk" -> stringResource(R.string.walk_post_plant_help)
            plant == "to_review" -> stringResource(R.string.pt_complete_desc)
            plant == "done" -> stringResource(R.string.pt_done_desc)
            else -> ""
        }
        if (desc.isNotEmpty()) Text(desc, style = FpType.Secondary.copy(lineHeight = 22.sp))
        WaitingToSync(f)
        if (plant == "land_review") {
            Spacer(Modifier.height(12.dp))
            Banner(stringResource(R.string.pt_land_hold), bg = Fp.AmberLight, fg = Fp.Amber)
        }
        if (lc != null && (plant == "land_fix" || plant == "pre_walk" && lc.status == "rejected")) {
            Spacer(Modifier.height(12.dp))
            ReviewerBox(stringResource(R.string.land_what), if (plant == "land_fix") "pending" else "rejected", lc.message, lc.reviewer, lc.reviewedAt)
            if (photoSent) {
                Spacer(Modifier.height(10.dp))
                Banner(stringResource(R.string.fix_sent_body))
            }
        }
        if ((pay == "rejected" || pay == "pending") && paid != null) {
            Spacer(Modifier.height(12.dp))
            ReviewerBox(stringResource(R.string.pa_what), pay, paid.message, paid.reviewer, paid.reviewedAt)
        }
        if ((plant == "rejected" || plant == "pending") && p.plant != null) {
            Spacer(Modifier.height(12.dp))
            ReviewerBox(stringResource(R.string.pt_plant_what), plant, p.plant.reviewMessage, p.plant.reviewer, p.plant.reviewedAt)
            if (photoSent) {
                Spacer(Modifier.height(10.dp))
                Banner(stringResource(R.string.fix_sent_body))
            }
        }
        if (plant == "pre_walk") {
            SectionLabel(stringResource(R.string.pt_planting_day))
            FpCard {
                ValueRow(stringResource(R.string.pt_saplings_planned), stringResource(R.string.n_timber_fruit, indian(paid?.timber ?: 0), indian(paid?.fruit ?: 0)))
                RowDivider()
                ValueRow(stringResource(R.string.pt_needs_area), stringResource(R.string.at_least_ac, String.format(Locale.US, "%.2f", saplings * 4.0 / Geo.SQ_M_PER_ACRE)))
                if (f.boundaries.isNotEmpty()) {
                    RowDivider()
                    ValueRow(stringResource(R.string.pt_onboarding_walk), ac(f.boundaries.sumOf { it.areaM2 }))
                }
            }
        }
        if (plant in setOf("to_review", "done", "rejected", "pending")) {
            Spacer(Modifier.height(14.dp))
            val pl = p.plant
            val planted = (pl?.timberPlanted ?: f.planted?.timberPlanted ?: 0) + (pl?.fruitPlanted ?: f.planted?.fruitPlanted ?: 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.pt_planted), indian(planted), Modifier.weight(1f))
                StatTile(stringResource(R.string.pt_area_after), (pl?.postAreaM2 ?: f.plantWalks.lastOrNull { it.purpose == "post_plant" }?.areaM2)?.let { ac(it) } ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.pt_area_before), (pl?.preAreaM2 ?: f.plantWalks.lastOrNull { it.purpose == "pre_plant" }?.areaM2)?.let { ac(it) } ?: "—", Modifier.weight(1f))
                StatTile(stringResource(R.string.pt_paid), rupees(paid?.amountPaid ?: 0), Modifier.weight(1f))
            }
        }
        if (pay == "approved" && plant !in setOf("to_review", "done")) FarmLocationCard(nav, f)
        SectionLabel(stringResource(R.string.ps_title))
        StepsCard(stepsFor(p.onboardingOk, pay, plant), sub)
        if (pay == "approved" || plant != "locked") {
            Spacer(Modifier.height(6.dp))
            TextLink(stringResource(R.string.pr_open), { nav.push(Screen.FarmerRecord(farmerId)) })
        }
        Spacer(Modifier.height(16.dp))
    }

    camera?.let { kind ->
        CameraCapture(
            kind = kind, landId = null,
            title = stringResource(if (kind == "land_change_record") R.string.lc_712 else R.string.pt_new_photo),
            frame = if (kind == "land_change_record") Frame.DOCUMENT else Frame.SCENE,
            onDone = { ph ->
                camera = null
                scope.launch {
                    repo.addPhoto(farmerId, ph, replaceKind = false)
                    if (kind == "land_change_record") repo.closePlanTasks(farmerId, "fix_land_change")
                    photoSent = true
                }
            },
            onCancel = { camera = null },
        )
    }
}

// ---------- payment ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val (f, p) = loadPlanState(farmerId)
    if (f == null || p == null) { Loading(nav); return }
    val plan = p.plan
    val prev = p.pay
    val fix = prev != null && (prev.status == "rejected" || prev.status == "pending")
    val since = remember { System.currentTimeMillis() }
    var timber by remember { mutableStateOf((prev?.timber ?: plan.timber).toString()) }
    var fruit by remember { mutableStateOf((prev?.fruit ?: plan.fruit).toString()) }
    var method by remember { mutableIntStateOf(if (prev?.method == "bank") 1 else 0) }
    var amount by remember { mutableStateOf("") }
    var amountTouched by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf(prev?.reference.orEmpty()) }
    var paidOn by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val proofs = f.photos.filter { it.kind == "payment_proof" && it.capturedAt >= since }

    val t = timber.toIntOrNull() ?: 0
    val fr = fruit.toIntOrNull() ?: 0
    val due = amountDue(t, fr)
    val paid = amount.toIntOrNull() ?: 0
    val over = t + fr > plan.maxSaplings
    val countsOk = t >= 0 && fr >= 0 && t + fr > 0 && !over
    val valid = countsOk && paid > 0 && isValidReference(reference) && proofs.isNotEmpty()
    // The amount follows the counts until the surveyor types what the farmer actually paid.
    LaunchedEffect(due) { if (!amountTouched) amount = due.toString() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = newId()
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val out = File(repo.photoDir, id + if (mime.contains("png")) ".png" else ".jpg")
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.exists() && out.length() > 0) repo.addPhoto(f.id, Photo(id, "payment_proof", out.absolutePath, mime, capturedAt = System.currentTimeMillis()), replaceKind = false)
        }
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            FpButton(
                stringResource(if (proofs.isEmpty()) R.string.pa_add_screenshot_first else R.string.pa_send),
                enabled = valid,
                onClick = {
                    tried = true
                    scope.launch {
                        repo.submitPayment(f.id, PaymentLocal(t, fr, paid, if (method == 1) "bank" else "upi", cleanReference(reference), paidOn.toString()))
                        nav.replace(Screen.Plan(f.id))
                    }
                },
            )
        },
    ) {
        StepHeader(caption(f), stringResource(if (fix) R.string.pt_pay_fix else R.string.pt_collect), null)
        if (fix) {
            Spacer(Modifier.height(14.dp))
            ReviewerBox(stringResource(R.string.pa_what), prev!!.status, prev.message, prev.reviewer, prev.reviewedAt)
            Spacer(Modifier.height(10.dp))
            FpCard {
                ValueRow(stringResource(R.string.pa_due_for, indian(prev.timber + prev.fruit)), rupees(prev.amountDue), valueWeight = FontWeight.SemiBold)
                RowDivider()
                ValueRow(stringResource(R.string.pa_paid_so_far), rupees(prev.amountPaid))
                if (prev.amountPaid < prev.amountDue) {
                    RowDivider()
                    ValueRow(stringResource(R.string.pa_still_to_collect), rupees(prev.amountDue - prev.amountPaid), valueColor = Color(0xFFB42318), valueWeight = FontWeight.SemiBold)
                }
            }
        }
        SectionLabel(stringResource(R.string.pa_plan))
        FpCard {
            ValueRow(stringResource(R.string.pa_area), "${String.format(Locale.US, "%.2f", plan.areaAcres)} ac · ${indian(plan.areaM2.toInt())} m²")
            RowDivider()
            ValueRow(stringResource(R.string.pa_spacing), "2 m × 2 m")
            RowDivider()
            ValueRow(stringResource(R.string.pa_fits), stringResource(R.string.n_saplings, indian(plan.maxSaplings)), valueWeight = FontWeight.SemiBold)
        }
        Helper(stringResource(R.string.pa_split, plan.timberPercent, 100 - plan.timberPercent))
        SectionLabel(stringResource(R.string.pa_saplings))
        FpCard {
            CountRow(
                if (named(plan.timberSpecies, "timber")) stringResource(R.string.pa_timber, plan.timberSpecies) else stringResource(R.string.kind_timber).replaceFirstChar { it.uppercase() },
                stringResource(R.string.pa_each, rupees(plan.timberRate)), timber,
            ) { timber = it }
            RowDivider()
            CountRow(
                if (named(plan.fruitSpecies, "fruit")) stringResource(R.string.pa_fruit, plan.fruitSpecies) else stringResource(R.string.kind_fruit).replaceFirstChar { it.uppercase() },
                stringResource(R.string.pa_each, rupees(plan.fruitRate)), fruit,
            ) { fruit = it }
        }
        if (over) Helper(stringResource(R.string.pa_too_many, indian(t + fr), indian(plan.maxSaplings)), color = Color(0xFFB42318))
        Spacer(Modifier.height(10.dp))
        FpCard {
            ValueRow("${indian(t)} × ${rupees(plan.timberRate)}", rupees(t * plan.timberRate))
            RowDivider()
            ValueRow("${indian(fr)} × ${rupees(plan.fruitRate)}", rupees(fr * plan.fruitRate))
            RowDivider()
            CardRow {
                Text(stringResource(R.string.pa_farmer_pays), style = FpType.Headline, modifier = Modifier.weight(1f))
                Text(rupees(due), style = FpType.Title)
            }
        }
        SectionLabel(stringResource(R.string.pa_how))
        Segmented(listOf(stringResource(R.string.pa_upi), stringResource(R.string.pa_bank)), method, { method = it }, Modifier.fillMaxWidth(), height = 40.dp)
        Spacer(Modifier.height(10.dp))
        FpCard {
            CardRow {
                Text(stringResource(R.string.pa_amount_paid), style = FpType.Body, modifier = Modifier.weight(1f))
                Text("₹ ", style = FpType.Body.copy(fontSize = 17.sp))
                FpTextField(amount, { v -> amount = v.filter { it.isDigit() }.take(8); amountTouched = true }, "0", Modifier.width(110.dp), keyboardType = KeyboardType.Number)
            }
            RowDivider()
            CardRow {
                Text(stringResource(if (method == 1) R.string.pa_bank_ref else R.string.pa_upi_ref), style = FpType.Body, modifier = Modifier.weight(1f))
                FpTextField(reference, { v -> reference = v.take(40) }, "430912775184", Modifier.width(170.dp), keyboardType = KeyboardType.Ascii)
            }
            RowDivider()
            ValueRow(stringResource(R.string.pa_paid_on), paidOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), onClick = { pickDate = true }, chevron = true)
        }
        if (paid > 0 && paid != due) Helper(stringResource(R.string.pa_amount_differs, rupees(paid), rupees(due)), color = Fp.Amber)
        if (reference.isNotEmpty() && !isValidReference(reference)) Helper(stringResource(R.string.pa_bad_ref), color = Color(0xFFB42318))
        SectionLabel(stringResource(R.string.pa_screenshot))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PickTile(Modifier.weight(1f), Lucide.Image, stringResource(R.string.pa_choose_screenshot)) { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            PickTile(Modifier.weight(1f), Lucide.Camera, stringResource(R.string.pa_photo_of_phone)) { camera = true }
        }
        if (proofs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Thumbs(proofs) { ph -> scope.launch { repo.removePhoto(f.id, ph.id) } }
        }
        Helper(stringResource(R.string.pa_readable))
        if (fix && prev != null) Helper(stringResource(R.string.pa_fix_note))
        Spacer(Modifier.height(16.dp))
    }

    if (camera) CameraCapture(
        kind = "payment_proof", landId = null, title = stringResource(R.string.pa_screenshot), frame = Frame.DOCUMENT,
        onDone = { ph -> camera = false; scope.launch { repo.addPhoto(f.id, ph, replaceKind = false) } },
        onCancel = { camera = false },
    )
    if (pickDate) {
        val today = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = paidOn.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today && utcTimeMillis > today - 120L * 86_400_000
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { paidOn = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text(stringResource(R.string.ok), color = Fp.Green) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel), color = Fp.Text2) } },
        ) { DatePicker(state) }
    }
}

/** "Timber · Mahogany  ₹5 each   −  607  +" */
@Composable
private fun CountRow(title: String, sub: String, value: String, onChange: (String) -> Unit) {
    CardRow {
        Column(Modifier.weight(1f)) {
            Text(title, style = FpType.Body)
            Text(sub, style = FpType.Helper)
        }
        val n = value.toIntOrNull() ?: 0
        RoundButton("−") { onChange((n - 1).coerceAtLeast(0).toString()) }
        BasicTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
            singleLine = true,
            textStyle = FpType.Headline.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(76.dp),
        )
        RoundButton("+") { onChange((n + 1).toString()) }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Fp.GrayButton).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = FpType.Headline.copy(fontSize = 20.sp))
    }
}

@Composable
private fun PickTile(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier.height(88.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Fp.Green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = FpType.BodyMedium.copy(color = Fp.Green, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Thumbs(photos: List<Photo>, onRemove: ((Photo) -> Unit)? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEach { ph ->
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE5E5EA))) {
                val bmp = remember(ph.path) { runCatching { BitmapFactory.decodeFile(ph.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull() }
                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp))
                if (onRemove != null && !ph.uploaded) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White).clickable { onRemove(ph) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.X, stringResource(R.string.remove), tint = Fp.Text, modifier = Modifier.size(14.dp)) }
                }
                if (ph.uploaded) Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Fp.Green).padding(3.dp))
            }
        }
    }
}

// ---------- saplings arrive: farmer counts them ----------

@Composable
fun DeliveryScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val (f, p) = loadPlanState(farmerId)
    if (f == null || p == null) { Loading(nav); return }
    val pay = p.pay
    if (pay == null) { Loading(nav); return }
    val (_, stage) = effectiveStage(p, f, s.outbox)
    LaunchedEffect(stage) { if (stage == "otp") nav.replace(Screen.FarmerOtp(farmerId)) }
    val plan = p.plan
    var tc by remember { mutableStateOf("") }
    var fc by remember { mutableStateOf("") }
    var healthy by remember { mutableStateOf(false) }
    var species by remember { mutableStateOf(false) }
    var satisfied by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    var savedShort by remember { mutableStateOf(false) }
    val photo = f.photos.lastOrNull { it.kind == "sapling_delivery" }
    val t = tc.toIntOrNull()
    val fr = fc.toIntOrNull()
    val counted = t != null && fr != null
    val countsMatch = t == pay.timber && fr == pay.fruit
    val matched = countsMatch && healthy && species && satisfied
    val total = pay.timber + pay.fruit
    val pre = f.plantWalks.lastOrNull { it.purpose == "pre_plant" }?.areaM2 ?: p.plant?.preAreaM2

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            if (matched) {
                FpButton(stringResource(if (photo == null) R.string.pd_photo_first else R.string.pd_match_button), enabled = photo != null, onClick = {
                    scope.launch {
                        repo.saveDelivery(f.id, DeliveryLocal(t!!, fr!!, healthy, species, satisfied))
                        nav.replace(Screen.FarmerOtp(f.id))
                    }
                })
            } else {
                FpButton(
                    stringResource(if (photo == null && counted) R.string.pd_photo_first else R.string.pd_save_short),
                    enabled = counted && photo != null,
                    container = Color(0xFFB42318),
                    onClick = {
                        scope.launch {
                            repo.saveDelivery(f.id, DeliveryLocal(t!!, fr!!, healthy, species, satisfied))
                            savedShort = true
                        }
                    },
                )
            }
        },
    ) {
        StepHeader(caption(f), stringResource(R.string.pd_title), null)
        if (pre != null) Helper(stringResource(R.string.pd_pre_walk, ac(pre), indian((pre / 4).toInt())))
        if (stage == "short" && p.plant?.timberCounted != null) {
            Spacer(Modifier.height(10.dp))
            Banner(stringResource(R.string.pd_last_count, indian((p.plant.timberCounted ?: 0) + (p.plant.fruitCounted ?: 0)), indian(total)), bg = Fp.RedLight, fg = Color(0xFFB42318))
        }
        SectionLabel(stringResource(R.string.pd_farmer_counts))
        FpCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.pd_sapling).uppercase(), style = FpType.Label, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.pd_planned).uppercase(), style = FpType.Label, modifier = Modifier.width(70.dp), textAlign = TextAlign.End)
                Text(stringResource(R.string.pd_counted).uppercase(), style = FpType.Label, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
            }
            RowDivider(start = 0.dp)
            CountedRow(plan.timberSpecies, if (named(plan.timberSpecies, "timber")) stringResource(R.string.kind_timber) else "", pay.timber, tc) { tc = it }
            RowDivider()
            CountedRow(plan.fruitSpecies, if (named(plan.fruitSpecies, "fruit")) stringResource(R.string.kind_fruit) else "", pay.fruit, fc) { fc = it }
        }
        if (counted && !countsMatch) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fp.RedLight).padding(14.dp)) {
                Text(stringResource(R.string.pd_cant_start), style = FpType.Headline.copy(color = Color(0xFFB42318)))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pd_short_body, indian(total)), style = FpType.Secondary.copy(color = Color(0xFF7A1A12)))
            }
        }
        SectionLabel(stringResource(R.string.pd_quality))
        FpCard {
            CheckRow(stringResource(R.string.pd_healthy), healthy) { healthy = it }
            RowDivider()
            CheckRow(stringResource(R.string.pd_species), species) { species = it }
            RowDivider()
            CheckRow(stringResource(R.string.pd_satisfied), satisfied) { satisfied = it }
        }
        SectionLabel(stringResource(R.string.pd_photo))
        if (photo != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbs(listOf(photo))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pd_photo_taken, whenText(photo.capturedAt)), style = FpType.Secondary)
                    TextLink(stringResource(R.string.retake), { camera = true })
                }
            }
        } else {
            PickTile(Modifier.fillMaxWidth(), Lucide.Camera, stringResource(R.string.take_photo)) { camera = true }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (camera) CameraCapture(
        kind = "sapling_delivery", landId = null, title = stringResource(R.string.pd_photo), frame = Frame.SCENE,
        onDone = { ph -> camera = false; scope.launch { repo.addPhoto(f.id, ph, replaceKind = false) } },
        onCancel = { camera = false },
    )
    if (savedShort) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.pd_short_saved_title), style = FpType.Headline) },
        text = { Text(stringResource(R.string.pd_short_saved_body), style = FpType.Secondary) },
        confirmButton = { TextButton(onClick = { nav.reset(Screen.Home) }) { Text(stringResource(R.string.back_to_home), color = Fp.Green) } },
        containerColor = Color.White,
    )
}

@Composable
private fun CountedRow(species: String, kind: String, planned: Int, value: String, onChange: (String) -> Unit) {
    val n = value.toIntOrNull()
    CardRow {
        Column(Modifier.weight(1f)) {
            Text(species, style = FpType.Body)
            val diff = n?.let { planned - it } ?: 0
            if (kind.isNotEmpty() || (n != null && diff != 0)) Text(
                when {
                    n == null || diff == 0 -> kind
                    diff > 0 -> stringResource(R.string.n_short, indian(diff))
                    else -> stringResource(R.string.n_extra, indian(-diff))
                },
                style = FpType.Helper.copy(color = if (n != null && diff != 0) Color(0xFFB42318) else Fp.Text2, fontWeight = if (n != null && diff != 0) FontWeight.SemiBold else FontWeight.Normal),
            )
        }
        Text(indian(planned), style = FpType.Body, modifier = Modifier.width(70.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.width(84.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2F2F7))
                .then(if (n != null && n == planned) Modifier.border(2.dp, Fp.Green, RoundedCornerShape(10.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
                singleLine = true,
                textStyle = FpType.Headline.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (value.isEmpty()) Text("—", style = FpType.Headline.copy(color = Fp.Text3)); inner() } },
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CardRow(onClick = { onChange(!checked) }) {
        Text(label, style = FpType.Body, modifier = Modifier.weight(1f))
        FpCheckbox(checked, onChange)
    }
}

// ---------- farmer's OTP ----------

@Composable
fun FarmerOtpScreen(nav: Navigator, farmerId: String) {
    val app = FieldproofApp.instance
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val (f, p) = loadPlanState(farmerId)
    var sent by remember { mutableStateOf<PlantOtpSent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var resendIn by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val offlineMsg = stringResource(R.string.po_needs_internet)
    DisposableEffect(Unit) {
        app.gps.acquire()
        onDispose { app.gps.release() }
    }
    fun explain(e: Exception) = if (Api.isOffline(e)) offlineMsg else (e as? ApiException)?.message ?: e.toString()
    fun request() {
        busy = true; error = null
        scope.launch {
            try {
                val r = repo.requestPlantOtp(farmerId)
                sent = r; resendIn = r.resendAfter; tick++; code = ""
            } catch (e: Exception) { error = explain(e) }
            busy = false
        }
    }
    LaunchedEffect(Unit) { request() }
    LaunchedEffect(tick) { while (resendIn > 0) { delay(1000); resendIn-- } }
    LaunchedEffect(sent) {
        if (sent != null) { delay(250); runCatching { focus.requestFocus() } }
        // No SMS gateway in local mode: the server hands back the code and it fills in as auto-read would.
        val c = sent?.devCode ?: return@LaunchedEffect
        delay(1500)
        if (code.isEmpty()) for (i in 1..c.length) { code = c.take(i); delay(140) }
    }
    if (f == null || p == null) { Loading(nav); return }
    val pay = p.pay
    val counted = (p.plant?.timberCounted ?: f.delivery?.timberCounted ?: 0) + (p.plant?.fruitCounted ?: f.delivery?.fruitCounted ?: 0)

    fun verify() {
        if (code.length != 4 || busy) return
        busy = true; error = null
        scope.launch {
            try {
                val loc = app.gps.lastAnyLocation.value
                repo.verifyPlantOtp(farmerId, code, loc?.latitude, loc?.longitude)
                nav.replace(Screen.Planting(farmerId))
            } catch (e: Exception) {
                error = explain(e); code = ""
            }
            busy = false
        }
    }

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            if (sent == null && error != null) FpButton(stringResource(R.string.try_again), enabled = !busy, onClick = { request() })
            else FpButton(stringResource(R.string.po_verify), enabled = code.length == 4 && !busy, onClick = { verify() })
        },
    ) {
        StepHeader(listOf(f.shortName, stringResource(R.string.po_counted, indian(counted))).joinToString(" · "), stringResource(R.string.pt_otp), null)
        Spacer(Modifier.height(8.dp))
        if (sent != null) {
            Text(stringResource(R.string.po_sent_to, "+91 " + formatMobile(p.farmerMobile.ifEmpty { f.mobile })), style = FpType.Subtitle)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) {
                Text(stringResource(R.string.po_sms_label).uppercase(), style = FpType.Label)
                Spacer(Modifier.height(6.dp))
                Text(sent!!.sms, style = FpType.Secondary.copy(color = Fp.Text, lineHeight = 21.sp))
            }
            SectionLabel(stringResource(R.string.po_code_from_farmer))
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
                            Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                                .then(if (focused) Modifier.border(2.dp, Fp.Green, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable { runCatching { focus.requestFocus() } },
                            contentAlignment = Alignment.Center,
                        ) { Text(code.getOrNull(i)?.toString() ?: "", style = FpType.Title.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (resendIn > 0) Text(stringResource(R.string.resend_in, "0:%02d".format(resendIn)), style = FpType.Secondary, modifier = Modifier.padding(horizontal = 2.dp))
            else TextLink(stringResource(R.string.resend_code), { request() })
            Helper(stringResource(R.string.po_note))
        } else if (error == null) {
            Text(stringResource(R.string.po_sending), style = FpType.Subtitle)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Banner(it, bg = Fp.RedLight, fg = Color(0xFFB42318))
        }
        if (pay != null) {
            SectionLabel(stringResource(R.string.pd_farmer_counts))
            FpCard {
                ValueRow(p.plan.timberSpecies, indian(p.plant?.timberCounted ?: f.delivery?.timberCounted ?: 0))
                RowDivider()
                ValueRow(p.plan.fruitSpecies, indian(p.plant?.fruitCounted ?: f.delivery?.fruitCounted ?: 0))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------- pit digging and planting ----------

@Composable
fun PlantingScreen(nav: Navigator, farmerId: String) {
    val repo = FieldproofApp.instance.repo
    val scope = rememberCoroutineScope()
    val (f, p) = loadPlanState(farmerId)
    var camera by remember { mutableStateOf<String?>(null) }
    if (f == null || p == null) { Loading(nav); return }
    val ct = p.plant?.timberCounted ?: f.delivery?.timberCounted ?: 0
    val cf = p.plant?.fruitCounted ?: f.delivery?.fruitCounted ?: 0
    var tp by remember { mutableStateOf(ct.toString()) }
    var fp by remember { mutableStateOf(cf.toString()) }
    val verifiedMs = p.plant?.otpVerifiedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    // Only photos taken after the farmer's OTP count as planting photos.
    val pits = f.photos.filter { it.kind == "plant_pits" && it.capturedAt >= verifiedMs - 60_000 }
    val planted = f.photos.filter { it.kind == "plant_planted" && it.capturedAt >= verifiedMs - 60_000 }
    val countsOk = tp.toIntOrNull() == ct && fp.toIntOrNull() == cf
    val ready = countsOk && pits.isNotEmpty() && planted.isNotEmpty()

    FpScreen(
        top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) },
        bottom = {
            FpButton(stringResource(R.string.pp_done_button), enabled = ready, onClick = {
                scope.launch {
                    repo.savePlanted(f.id, PlantedLocal(ct, cf))
                    nav.replace(Screen.BoundaryIntro(f.id, null, "post_plant"))
                }
            })
        },
    ) {
        StepHeader(caption(f), stringResource(R.string.pt_planting), null)
        if (p.plant?.otpVerifiedAt != null) Helper(stringResource(R.string.pp_confirmed, indian(ct + cf), whenText(p.plant.otpVerifiedAt)))
        SectionLabel(stringResource(R.string.pp_planted))
        FpCard {
            PlantedRow(p.plan.timberSpecies, ct, tp) { tp = it }
            RowDivider()
            PlantedRow(p.plan.fruitSpecies, cf, fp) { fp = it }
        }
        if (!countsOk) Helper(stringResource(R.string.pp_plant_all, indian(ct + cf)), color = Color(0xFFB42318))
        SectionLabel(stringResource(R.string.pp_pits, pits.size)) { TextLink("+ " + stringResource(R.string.add), { camera = "plant_pits" }) }
        if (pits.isEmpty()) PickTile(Modifier.fillMaxWidth(), Lucide.Camera, stringResource(R.string.take_photo)) { camera = "plant_pits" }
        else Thumbs(pits) { ph -> scope.launch { repo.removePhoto(f.id, ph.id) } }
        SectionLabel(stringResource(R.string.pp_planted_photos, planted.size)) { TextLink("+ " + stringResource(R.string.add), { camera = "plant_planted" }) }
        if (planted.isEmpty()) PickTile(Modifier.fillMaxWidth(), Lucide.Camera, stringResource(R.string.take_photo)) { camera = "plant_planted" }
        else Thumbs(planted) { ph -> scope.launch { repo.removePhoto(f.id, ph.id) } }
        Helper(stringResource(R.string.pp_camera_note))
        Spacer(Modifier.height(16.dp))
    }

    camera?.let { kind ->
        CameraCapture(
            kind = kind, landId = null, title = stringResource(if (kind == "plant_pits") R.string.pp_pits_title else R.string.pp_planted_title), frame = Frame.SCENE,
            onDone = { ph -> camera = null; scope.launch { repo.addPhoto(f.id, ph, replaceKind = false) } },
            onCancel = { camera = null },
        )
    }
}

@Composable
private fun PlantedRow(species: String, of: Int, value: String, onChange: (String) -> Unit) {
    CardRow {
        Text(species, style = FpType.Body, modifier = Modifier.weight(1f))
        Box(Modifier.width(84.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2F2F7)), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = value,
                onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
                singleLine = true,
                textStyle = FpType.Headline.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.of_n, indian(of)), style = FpType.Secondary)
    }
}

// ---------- everything done for one farmer ----------

private val EVENT_LABEL = mapOf(
    "payment_submitted" to R.string.ev_payment_submitted, "payment_approved" to R.string.ev_payment_approved,
    "payment_rejected" to R.string.ev_payment_rejected, "payment_pending" to R.string.ev_payment_pending,
    "pre_walk" to R.string.walk_pre_plant_title, "delivery_ok" to R.string.ev_delivery_ok, "delivery_short" to R.string.ev_delivery_short,
    "otp_sent" to R.string.ev_otp_sent, "otp_verified" to R.string.ev_otp_verified, "planted" to R.string.ev_planted,
    "post_walk" to R.string.walk_post_plant_title, "plant_resubmitted" to R.string.ev_plant_resubmitted,
    "plantation_approved" to R.string.ev_plantation_approved, "plantation_rejected" to R.string.ev_plantation_rejected,
    "plantation_pending" to R.string.ev_plantation_pending,
)

@Composable
fun FarmerRecordScreen(nav: Navigator, farmerId: String) {
    val (f, p) = loadPlanState(farmerId)
    if (f == null || p == null) { Loading(nav); return }
    val s by FieldproofApp.instance.repo.state.collectAsState()
    val (pay, plant) = effectiveStage(p, f, s.outbox)
    val paid = p.pay
    val pl = p.plant
    val planted = (pl?.timberPlanted ?: f.planted?.timberPlanted ?: 0) + (pl?.fruitPlanted ?: f.planted?.fruitPlanted ?: 0)

    FpScreen(top = { TopBar(left = { BackLink(stringResource(R.string.back)) { nav.pop() } }) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val initials = f.shortName.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
            Box(Modifier.size(52.dp).clip(CircleShape).background(Fp.GreenLight), contentAlignment = Alignment.Center) {
                Text(initials, style = FpType.Headline.copy(color = Fp.Green, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.fullName, style = FpType.Title)
                Text(listOf(f.code, listOf(f.villageName, f.block, f.district).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "), style = FpType.Secondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        val status = when {
            plant == "done" -> stringResource(R.string.pt_done)
            plant == "to_review" -> stringResource(R.string.pr_in_review)
            else -> stringResource(STEP_LABEL.getValue(stepsFor(p.onboardingOk, pay, plant).firstOrNull { it.state != "done" }?.key ?: "review"))
        }
        Banner(status, bg = if (plant == "done") Fp.GreenLight else Color(0xFFE8EEFC), fg = if (plant == "done") Fp.Green else Fp.Blue)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.pt_paid), if (paid != null && pay == "approved") rupees(paid.amountPaid) else "—", Modifier.weight(1f))
            StatTile(stringResource(R.string.pt_planted), if (planted > 0) indian(planted) else "—", Modifier.weight(1f))
        }

        SectionLabel(stringResource(R.string.pr_every_step))
        FpCard {
            val rows = buildList {
                if (f.createdAt > 1) add(stringResource(R.string.pr_registered) to whenText(f.createdAt))
                if (p.onboardingOk) add(stringResource(R.string.ps_onboarding) to "")
                p.events.forEach { e ->
                    val label = EVENT_LABEL[e.event]?.let { stringResource(it) } ?: e.event
                    // "Payment approved: message" → the title already says "Payment approved".
                    val decision = e.event.startsWith("plantation_") || (e.event.startsWith("payment_") && e.event != "payment_submitted")
                    val detail = if (decision) e.detail.substringAfter(": ", "") else e.detail
                    add(label to listOf(detail, e.by, whenText(e.at)).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
            rows.forEachIndexed { i, (label, detail) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(Fp.Green))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = FpType.Body)
                        if (detail.isNotBlank()) Text(detail, style = FpType.Helper)
                    }
                }
                if (i < rows.lastIndex) RowDivider(start = 37.dp)
            }
            if (rows.isEmpty()) Text(stringResource(R.string.plan_loading), style = FpType.Secondary, modifier = Modifier.padding(16.dp))
        }

        SectionLabel(stringResource(R.string.pr_farmer_land))
        FpCard {
            ValueRow(stringResource(R.string.mobile_number), "+91 " + formatMobile(f.mobile))
            f.lands.forEach { l ->
                RowDivider()
                ValueRow("Gat ${l.surveyNo}", String.format(Locale.US, "%.2f ha · %.2f ac", l.areaHa, l.registerAcres))
            }
        }

        if (paid != null) {
            SectionLabel(stringResource(R.string.pr_saplings_payment))
            FpCard {
                ValueRow("${indian(paid.timber)} ${p.plan.timberSpecies} × ${rupees(p.plan.timberRate)}", rupees(paid.timber * p.plan.timberRate))
                RowDivider()
                ValueRow("${indian(paid.fruit)} ${p.plan.fruitSpecies} × ${rupees(p.plan.fruitRate)}", rupees(paid.fruit * p.plan.fruitRate))
                RowDivider()
                ValueRow(stringResource(R.string.pt_paid), rupees(paid.amountPaid), valueColor = Fp.Text, valueWeight = FontWeight.SemiBold)
                RowDivider()
                ValueRow(stringResource(if (paid.method == "bank") R.string.pa_bank_ref else R.string.pa_upi_ref), paid.reference)
            }
        }

        SectionLabel(stringResource(R.string.pr_reviews))
        FpCard {
            ValueRow(stringResource(R.string.pr_payment_proof), stringResource(statusRes(if (pay == "none" || pay == "locked") "missing" else if (pay == "waiting") "new" else pay)))
            RowDivider()
            ValueRow(stringResource(R.string.pt_plant_what), stringResource(statusRes(when (plant) { "done" -> "approved"; "rejected", "pending" -> plant; "to_review" -> "new"; else -> "missing" })))
        }

        SectionLabel(stringResource(R.string.boundaries))
        FpCard {
            ValueRow(stringResource(R.string.pr_at_onboarding), if (f.boundaries.isNotEmpty()) ac(f.boundaries.sumOf { it.areaM2 }) else "—")
            RowDivider()
            ValueRow(stringResource(R.string.walk_pre_plant_title), (pl?.preAreaM2 ?: f.plantWalks.lastOrNull { it.purpose == "pre_plant" }?.areaM2)?.let { ac(it) } ?: "—")
            RowDivider()
            ValueRow(stringResource(R.string.walk_post_plant_title), (pl?.postAreaM2 ?: f.plantWalks.lastOrNull { it.purpose == "post_plant" }?.areaM2)?.let { ac(it) } ?: "—")
        }

        val phonePhotos = f.photos.filter { File(it.path).exists() && it.mime.startsWith("image") }
        if (phonePhotos.isNotEmpty()) {
            SectionLabel(stringResource(R.string.pr_photos, phonePhotos.size))
            Thumbs(phonePhotos)
        }
        Helper(stringResource(R.string.pr_kept_on_server))
        Spacer(Modifier.height(20.dp))
    }
}

private fun statusRes(st: String) = when (st) {
    "approved" -> R.string.st_approved
    "rejected" -> R.string.st_rejected
    "pending" -> R.string.st_pending
    "missing" -> R.string.st_missing
    else -> R.string.st_new
}

