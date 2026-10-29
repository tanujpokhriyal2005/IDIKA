package org.fieldproof.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.ActionItem
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.Navigator
import org.fieldproof.ui.Screen
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpScreen
import org.fieldproof.ui.components.IconTile
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType

val languages = listOf("en" to "English", "hi" to "हिन्दी", "mr" to "मराठी")

@Composable
fun SyncStatus() {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val reachable by repo.serverReachable.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val pending = s.outbox.size
    val failed = s.outbox.count { it.error != null }
    val (dot, text) = when {
        pending == 0 -> Fp.Green to stringResource(R.string.all_synced)
        failed > 0 -> Fp.Red to stringResource(R.string.sync_problem, failed)
        syncing && reachable != false -> Fp.Amber to stringResource(R.string.syncing)
        else -> Fp.Amber to pluralStringResource(R.plurals.saved_on_phone_n, pending, pending)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, style = FpType.Secondary.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun HomeScreen(nav: Navigator) {
    val repo = FieldproofApp.instance.repo
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var langOpen by remember { mutableStateOf(false) }
    // Check the server when Home shows and every 30 s while it stays open, so a
    // reviewer's new or changed decision reaches the list without a manual sync.
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            while (true) {
                repo.refresh()
                delay(30_000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    val sv = s.surveyor ?: return
    val actions = repo.visibleActions(s)

    FpScreen {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SyncStatus()
            Spacer(Modifier.weight(1f))
            Box {
                Row(
                    Modifier.clip(CircleShape).background(Color(0xFFE5E5EA)).clickable { langOpen = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Globe, null, tint = Fp.Text, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(languages.firstOrNull { it.first == s.language }?.second ?: "English", style = FpType.BodyMedium.copy(fontSize = 15.sp))
                    Spacer(Modifier.width(5.dp))
                    Icon(Lucide.ChevronDown, null, tint = Fp.Text, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(langOpen, { langOpen = false }, containerColor = Color.White) {
                    languages.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label, style = FpType.Body.copy(fontWeight = if (code == s.language) FontWeight.SemiBold else FontWeight.Normal)) },
                            onClick = { langOpen = false; scope.launch { repo.setLanguage(code) } },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.namaste, sv.firstName), style = FpType.Greeting)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.block_district, sv.block, sv.district), style = FpType.Secondary)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().height(134.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Fp.Green)
                    .clickable { scope.launch { repo.startDraft(); nav.push(Screen.FarmerDetails) } }
                    .padding(16.dp),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Plus, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.add_new_farmer), style = FpType.Headline.copy(color = Color.White, fontWeight = FontWeight.Bold))
            }
            Column(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable { nav.reset(Screen.Farmers) }
                    .padding(16.dp),
            ) {
                Text(repo.farmerCount(s).toString(), style = FpType.Greeting.copy(fontSize = 34.sp))
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.my_farmers), style = FpType.Headline.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Icon(Lucide.ChevronRight, null, tint = Fp.Text3, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.action_items), style = FpType.Title, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.n_to_do, actions.size), style = FpType.Secondary)
        }
        Spacer(Modifier.height(12.dp))
        FpCard {
            if (actions.isEmpty()) {
                Text(stringResource(R.string.nothing_waiting), style = FpType.Secondary, modifier = Modifier.padding(16.dp))
            }
            actions.forEachIndexed { i, a ->
                ActionRow(a) {
                    when (a.kind) {
                        "walk_boundary" -> scope.launch {
                            val f = runCatching { repo.loadFarmer(a.farmerId) }.getOrNull()
                            if (f != null) nav.push(Screen.BoundaryIntro(f.id, f.lands.firstOrNull { l -> f.boundaryFor(l.id) == null }?.id ?: f.lands.firstOrNull()?.id))
                        }
                        "fix_aadhaar", "fix_land_record", "fix_water_source", "fix_boundary" -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) nav.push(Screen.Fix(a.id))
                        }
                        "collect_payment", "fix_payment" -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) nav.push(Screen.Payment(a.farmerId))
                        }
                        "sapling_delivery" -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) {
                                nav.push(if (a.note == "ask the farmer for the OTP") Screen.FarmerOtp(a.farmerId) else Screen.Delivery(a.farmerId))
                            }
                        }
                        "planting" -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) nav.push(Screen.Planting(a.farmerId))
                        }
                        "payment_review", "walk_pre_plant", "walk_post_plant", "plantation_review", "fix_plantation", "land_change_review", "fix_land_change" -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) nav.push(Screen.Plan(a.farmerId))
                        }
                        "retake_aadhaar" -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) nav.push(Screen.Aadhaar(a.farmerId, a.id))
                        }
                        else -> scope.launch {
                            if (runCatching { repo.loadFarmer(a.farmerId) }.getOrNull() != null) nav.push(Screen.Evidence(a.id))
                        }
                    }
                }
                if (i < actions.lastIndex) RowDivider(start = 62.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

data class ActionLook(val icon: ImageVector, val color: Color, val title: Int)

fun actionLook(kind: String) = when (kind) {
    "fix_aadhaar" -> ActionLook(Lucide.Camera, Fp.Red, R.string.action_fix_aadhaar)
    "fix_land_record" -> ActionLook(Lucide.FileText, Fp.Amber, R.string.action_fix_land_record)
    "fix_water_source" -> ActionLook(Lucide.Camera, Fp.Red, R.string.action_fix_water_source)
    "fix_boundary" -> ActionLook(Lucide.Map, Fp.Orange, R.string.action_fix_boundary)
    "walk_boundary" -> ActionLook(Lucide.Map, Fp.Orange, R.string.action_walk_boundary)
    "retake_aadhaar" -> ActionLook(Lucide.RotateCw, Fp.Red, R.string.action_retake_aadhaar)
    "collect_agreement" -> ActionLook(Lucide.FileText, Fp.Blue, R.string.action_collect_agreement)
    "collect_payment" -> ActionLook(Lucide.IndianRupee, Fp.Green, R.string.action_collect_payment)
    "payment_review" -> ActionLook(Lucide.Hourglass, Fp.Text3, R.string.action_payment_review)
    "fix_payment" -> ActionLook(Lucide.IndianRupee, Fp.Red, R.string.action_fix_payment)
    "walk_pre_plant" -> ActionLook(Lucide.Map, Fp.Orange, R.string.action_walk_pre_plant)
    "sapling_delivery" -> ActionLook(Lucide.Truck, Fp.Blue, R.string.action_sapling_delivery)
    "planting" -> ActionLook(Lucide.Shovel, Fp.Green, R.string.action_planting)
    "walk_post_plant" -> ActionLook(Lucide.Map, Fp.Orange, R.string.action_walk_post_plant)
    "plantation_review" -> ActionLook(Lucide.Hourglass, Fp.Text3, R.string.action_plantation_review)
    "fix_plantation" -> ActionLook(Lucide.Sprout, Fp.Red, R.string.action_fix_plantation)
    "land_change_review" -> ActionLook(Lucide.Hourglass, Fp.Text3, R.string.action_land_change_review)
    "fix_land_change" -> ActionLook(Lucide.Map, Fp.Red, R.string.action_fix_land_change)
    else -> ActionLook(Lucide.Sprout, Fp.Green, R.string.action_plantation_proof)
}

@Composable
private fun ActionRow(a: ActionItem, onClick: () -> Unit) {
    val look = actionLook(a.kind)
    val words = a.farmerName.trim().split(Regex("\\s+"))
    val short = if (words.size <= 2) a.farmerName else "${words.first()} ${words.last()}"
    val detail = when {
        a.isFix -> stringResource(if (a.decision == "rejected") R.string.decision_rejected else R.string.decision_pending)
        a.kind == "sapling_delivery" && a.note == "ask the farmer for the OTP" -> stringResource(R.string.note_ask_otp)
        a.kind == "walk_pre_plant" && a.attention -> stringResource(R.string.note_land_rejected)
        a.kind == "walk_pre_plant" -> stringResource(R.string.note_payment_approved)
        a.kind == "land_change_review" -> listOf(stringResource(R.string.note_in_review), a.note).filter { it.isNotBlank() }.joinToString(" · ")
        a.kind == "walk_post_plant" -> stringResource(R.string.note_planted)
        a.kind == "payment_review" || a.kind == "plantation_review" -> listOf(stringResource(R.string.note_in_review), a.note).filter { it.isNotBlank() }.joinToString(" · ")
        a.isPlan -> a.note.ifBlank { a.village }
        else -> when (a.kind) {
        "retake_aadhaar" -> if (a.note.isEmpty() || a.note == "sent back") stringResource(R.string.sent_back) else a.note
        "plantation_proof" -> pluralStringResource(R.plurals.n_lands, a.lands, a.lands)
        else -> a.village
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconTile(look.color) { Icon(look.icon, null, tint = Color.White, modifier = Modifier.size(17.dp)) }
            if (a.attention) {
                // Red dot: a reviewer sent this back.
                val cd = stringResource(R.string.needs_attention)
                Box(
                    Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(12.dp).clip(CircleShape).background(Color.White).padding(2.dp)
                        .clip(CircleShape).background(Color(0xFFE0201B)).semantics { contentDescription = cd },
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(look.title), style = FpType.Body.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$short · $detail", style = FpType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Lucide.ChevronRight, null, tint = Fp.Text3, modifier = Modifier.size(18.dp))
    }
}
