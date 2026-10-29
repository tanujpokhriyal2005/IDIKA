package org.fieldproof.ui.screens

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.data.Farmer
import org.fieldproof.data.Village
import org.fieldproof.ui.Lucide
import org.fieldproof.ui.components.AnimatedDots
import org.fieldproof.ui.components.CardRow
import org.fieldproof.ui.components.FpCard
import org.fieldproof.ui.components.FpTextField
import org.fieldproof.ui.components.Helper
import org.fieldproof.ui.components.RowDivider
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType

/**
 * District / Block / Village. GPS fills all three for a new farmer (the server
 * looks the point up in its OpenStreetMap copy of India's talukas and
 * villages); Block and Village are dropdowns to correct it. Offline, the lists
 * come from villages already on the phone.
 */
@Composable
fun VillageSection(f: Farmer, showErrors: Boolean, edit: ((Farmer) -> Farmer) -> Unit) {
    val app = FieldproofApp.instance
    val repo = app.repo
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteIsError by remember { mutableStateOf(false) }
    var blocks by remember { mutableStateOf(if (f.district.isNotEmpty()) repo.blocksFor(f.district) else emptyList()) }
    var villages by remember { mutableStateOf(repo.state.value.villages.filter { it.district == f.district && it.block == f.block }.sortedBy { it.name }) }
    var blockOpen by remember { mutableStateOf(false) }
    var villageOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filledMsg = stringResource(R.string.gps_filled)
    val failedMsg = stringResource(R.string.gps_fill_failed)
    val noPermMsg = stringResource(R.string.need_location_permission)

    fun fallbackLists() {
        val d = f.district.ifEmpty { repo.state.value.surveyor?.district.orEmpty() }
        if (f.district.isEmpty() && d.isNotEmpty()) edit { it.copy(district = d) }
        blocks = repo.blocksFor(d)
    }

    fun detect() {
        if (locating) return
        locating = true
        note = null
        scope.launch {
            app.gps.acquire()
            val loc = withTimeoutOrNull(25_000) {
                app.gps.lastAnyLocation.first {
                    it != null && it.accuracy <= 300f && SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 120_000_000_000L
                }
            }
            app.gps.release()
            if (loc == null) {
                note = failedMsg; noteIsError = true; locating = false
                fallbackLists()
                return@launch
            }
            try {
                val r = repo.locate(loc.latitude, loc.longitude)
                blocks = r.blocks
                villages = r.villages
                edit { it.copy(district = r.district, block = r.block, villageId = r.village?.id, villageName = r.village?.name.orEmpty()) }
                note = String.format(filledMsg, loc.accuracy.toInt().coerceAtLeast(1)); noteIsError = false
            } catch (e: Exception) {
                note = failedMsg; noteIsError = true
                fallbackLists()
            }
            locating = false
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) detect() else { note = noPermMsg; noteIsError = true; fallbackLists() }
    }
    fun detectWithPermission() {
        if (app.gps.hasPermission()) detect() else perm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    LaunchedEffect(Unit) { if (f.villageId == null) detectWithPermission() }

    FpCard {
        CardRow {
            Text(stringResource(R.string.district), style = FpType.Body, modifier = Modifier.weight(1f))
            if (locating) {
                val st = FpType.Body.copy(color = Fp.Text2)
                Text(stringResource(R.string.detecting_location), style = st)
                AnimatedDots(st)
            } else {
                Text(f.district.ifEmpty { "—" }, style = FpType.Body.copy(color = Fp.Text2))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Fp.GreenTint).clickable { detectWithPermission() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Lucide.Crosshair, stringResource(R.string.detect_again), tint = Fp.Green, modifier = Modifier.size(18.dp)) }
            }
        }
        RowDivider()
        Box {
            CardRow(onClick = { if (blocks.isNotEmpty()) blockOpen = true }) {
                Text(stringResource(R.string.block), style = FpType.Body, modifier = Modifier.weight(1f))
                Text(f.block.ifEmpty { stringResource(R.string.choose) }, style = FpType.BodyMedium.copy(color = if (f.block.isEmpty()) Fp.Green else Fp.Text))
                Spacer(Modifier.width(6.dp))
                Icon(Lucide.ChevronDown, null, tint = Fp.Text2, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(blockOpen, { blockOpen = false }, containerColor = Color.White, modifier = Modifier.heightIn(max = 360.dp)) {
                blocks.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(b, style = FpType.Body.copy(fontWeight = if (b == f.block) FontWeight.SemiBold else FontWeight.Normal)) },
                        onClick = {
                            blockOpen = false
                            if (b != f.block) {
                                val district = f.district
                                edit { it.copy(block = b, villageId = null, villageName = "") }
                                villages = emptyList()
                                scope.launch { villages = repo.villagesFor(district, b) }
                            }
                        },
                    )
                }
            }
        }
        RowDivider()
        Box {
            CardRow(onClick = { if (f.block.isNotEmpty()) { query = ""; villageOpen = true } }) {
                Text(stringResource(R.string.village_row), style = FpType.Body, modifier = Modifier.weight(1f))
                Text(f.villageName.ifEmpty { stringResource(R.string.choose) }, style = FpType.BodyMedium.copy(color = Fp.Green))
                Spacer(Modifier.width(6.dp))
                Icon(Lucide.ChevronDown, null, tint = Fp.Green, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(villageOpen, { villageOpen = false }, containerColor = Color.White, modifier = Modifier.width(300.dp).heightIn(max = 420.dp)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Fp.Bg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Search, null, tint = Fp.Text3, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    FpTextField(query, { query = it }, stringResource(R.string.search_village), Modifier.fillMaxWidth(), style = FpType.Body)
                }
                val list: List<Village> = villages.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                if (list.isEmpty()) Text(stringResource(R.string.no_villages), style = FpType.Secondary, modifier = Modifier.padding(16.dp))
                list.take(300).forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.name, style = FpType.Body.copy(fontWeight = if (v.id == f.villageId) FontWeight.SemiBold else FontWeight.Normal)) },
                        onClick = {
                            villageOpen = false
                            edit { it.copy(villageId = v.id, villageName = v.name, block = v.block, district = v.district) }
                        },
                    )
                }
            }
        }
    }
    val err = showErrors && f.villageId == null
    Helper(
        when {
            err -> stringResource(R.string.err_village)
            note != null -> note!!
            else -> stringResource(R.string.village_help)
        },
        color = if (err || noteIsError) Fp.Red else Fp.Text2,
    )
}
