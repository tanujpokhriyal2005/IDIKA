package org.fieldproof.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fieldproof.FieldproofApp
import org.fieldproof.R
import org.fieldproof.ui.screens.AadhaarScreen
import org.fieldproof.ui.screens.BoundaryIntroScreen
import org.fieldproof.ui.screens.BoundaryReviewScreen
import org.fieldproof.ui.screens.BoundaryWalkScreen
import org.fieldproof.ui.screens.EvidenceScreen
import org.fieldproof.ui.screens.FarmDetailsScreen
import org.fieldproof.ui.screens.FarmerDetailsScreen
import org.fieldproof.ui.screens.FarmerProfileScreen
import org.fieldproof.ui.screens.FarmersScreen
import org.fieldproof.ui.screens.FarmerOtpScreen
import org.fieldproof.ui.screens.FarmerRecordScreen
import org.fieldproof.ui.screens.DeliveryScreen
import org.fieldproof.ui.screens.FindFarmScreen
import org.fieldproof.ui.screens.FixScreen
import org.fieldproof.ui.screens.LandChangeScreen
import org.fieldproof.ui.screens.PaymentScreen
import org.fieldproof.ui.screens.PlanScreen
import org.fieldproof.ui.screens.PlantingScreen
import org.fieldproof.ui.screens.HomeScreen
import org.fieldproof.ui.screens.LandDetailsScreen
import org.fieldproof.ui.screens.LoginScreen
import org.fieldproof.ui.screens.OtpScreen
import org.fieldproof.ui.screens.RegisteredScreen
import org.fieldproof.ui.screens.SyncScreen
import org.fieldproof.ui.theme.Fp
import org.fieldproof.ui.theme.FpType

sealed interface Screen {
    data object Login : Screen
    data class Otp(val phone: String, val devCode: String?, val resendAfter: Int) : Screen
    data object Home : Screen
    data object Farmers : Screen
    data object Sync : Screen
    data object FarmerDetails : Screen
    data class Aadhaar(val farmerId: String, val retakeActionId: String? = null) : Screen
    data object LandDetails : Screen
    data class Registered(val farmerId: String) : Screen
    // purpose: onboarding, or pre_plant / post_plant for the walks on planting day
    data class BoundaryIntro(val farmerId: String, val landId: String?, val purpose: String = "onboarding") : Screen
    data class BoundaryWalk(val farmerId: String, val landId: String?, val purpose: String = "onboarding") : Screen
    data class BoundaryReview(val farmerId: String, val landId: String?, val purpose: String = "onboarding") : Screen
    data class FarmDetails(val farmerId: String) : Screen
    data class FarmerProfile(val farmerId: String) : Screen
    data class Evidence(val actionId: String) : Screen
    data class Fix(val actionId: String) : Screen
    data class Plan(val farmerId: String) : Screen
    data class Payment(val farmerId: String) : Screen
    data class Delivery(val farmerId: String) : Screen
    data class FarmerOtp(val farmerId: String) : Screen
    data class Planting(val farmerId: String) : Screen
    data class FarmerRecord(val farmerId: String) : Screen
    data class FindFarm(val farmerId: String, val landId: String?) : Screen
    data class LandChange(val farmerId: String) : Screen
}

class Navigator(start: Screen) {
    val stack = mutableStateListOf(start)
    var forward by mutableStateOf(true)
    val top: Screen get() = stack.last()

    fun push(s: Screen) { forward = true; stack.add(s) }
    fun pop() { if (stack.size > 1) { forward = false; stack.removeAt(stack.lastIndex) } }
    fun replace(s: Screen) { forward = true; stack[stack.lastIndex] = s }
    fun reset(s: Screen) { forward = false; stack.clear(); stack.add(s) }
    /** Pop back to the first screen matching [pred], or reset to [fallback]. */
    fun popTo(fallback: Screen, pred: (Screen) -> Boolean) {
        forward = false
        val i = stack.indexOfLast(pred)
        if (i >= 0) while (stack.size > i + 1) stack.removeAt(stack.lastIndex) else reset(fallback)
    }
}

private val tabRoots = setOf(Screen.Home, Screen.Farmers, Screen.Sync)

@Composable
fun FieldproofRoot() {
    val repo = FieldproofApp.instance.repo
    val state by repo.state.collectAsState()
    val nav = remember { Navigator(if (state.loggedIn) Screen.Home else Screen.Login) }
    if (!state.loggedIn && nav.top !is Screen.Login && nav.top !is Screen.Otp) nav.reset(Screen.Login)

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    Column(Modifier.fillMaxSize().background(Fp.Bg)) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = nav.top,
                transitionSpec = {
                    val tab = initialState in tabRoots && targetState in tabRoots
                    if (tab) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    else if (nav.forward) slideInHorizontally(tween(260)) { it } togetherWith slideOutHorizontally(tween(260)) { -it / 4 }
                    else slideInHorizontally(tween(260)) { -it / 4 } togetherWith slideOutHorizontally(tween(260)) { it }
                },
                label = "nav",
            ) { screen ->
                when (screen) {
                    Screen.Login -> LoginScreen(nav)
                    is Screen.Otp -> OtpScreen(nav, screen)
                    Screen.Home -> HomeScreen(nav)
                    Screen.Farmers -> FarmersScreen(nav)
                    Screen.Sync -> SyncScreen(nav)
                    Screen.FarmerDetails -> FarmerDetailsScreen(nav)
                    is Screen.Aadhaar -> AadhaarScreen(nav, screen)
                    Screen.LandDetails -> LandDetailsScreen(nav)
                    is Screen.Registered -> RegisteredScreen(nav, screen.farmerId)
                    is Screen.BoundaryIntro -> BoundaryIntroScreen(nav, screen.farmerId, screen.landId, screen.purpose)
                    is Screen.BoundaryWalk -> BoundaryWalkScreen(nav, screen.farmerId, screen.landId, screen.purpose)
                    is Screen.BoundaryReview -> BoundaryReviewScreen(nav, screen.farmerId, screen.landId, screen.purpose)
                    is Screen.FarmDetails -> FarmDetailsScreen(nav, screen.farmerId)
                    is Screen.FarmerProfile -> FarmerProfileScreen(nav, screen.farmerId)
                    is Screen.Evidence -> EvidenceScreen(nav, screen.actionId)
                    is Screen.Fix -> FixScreen(nav, screen.actionId)
                    is Screen.Plan -> PlanScreen(nav, screen.farmerId)
                    is Screen.Payment -> PaymentScreen(nav, screen.farmerId)
                    is Screen.Delivery -> DeliveryScreen(nav, screen.farmerId)
                    is Screen.FarmerOtp -> FarmerOtpScreen(nav, screen.farmerId)
                    is Screen.Planting -> PlantingScreen(nav, screen.farmerId)
                    is Screen.FarmerRecord -> FarmerRecordScreen(nav, screen.farmerId)
                    is Screen.FindFarm -> FindFarmScreen(nav, screen.farmerId, screen.landId)
                    is Screen.LandChange -> LandChangeScreen(nav, screen.farmerId)
                }
            }
        }
        if (nav.top in tabRoots) BottomTabs(nav)
    }
}

@Composable
private fun BottomTabs(nav: Navigator) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF9F9F9))) {
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(Color(0xFFD8D8DC)))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp)) {
            Tab(Lucide.House, stringResource(R.string.tab_home), nav.top == Screen.Home) { nav.reset(Screen.Home) }
            Tab(Lucide.Users, stringResource(R.string.tab_farmers), nav.top == Screen.Farmers) { nav.reset(Screen.Farmers) }
            Tab(Lucide.RefreshCw, stringResource(R.string.tab_sync), nav.top == Screen.Sync) { nav.reset(Screen.Sync) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Fp.Green else Fp.Text3
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = FpType.Small.copy(color = color, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium))
    }
}
