package org.fieldproof.data

import kotlinx.serialization.Serializable

// Payment and plantation, after onboarding is approved. The server decides
// which step a farmer is at (GET /v1/farmers/{id}/plantation); the phone keeps
// the last answer for offline use and what the surveyor entered until it syncs.

const val TIMBER_RATE = 5
const val FRUIT_RATE = 10
const val SPACING_M = 2.0

@Serializable
data class SaplingPlan(
    val areaAcres: Double = 0.0,
    val areaM2: Double = 0.0,
    val maxSaplings: Int = 0,
    val timberPercent: Int = 40,
    val timber: Int = 0,
    val fruit: Int = 0,
    val timberSpecies: String = "Timber",
    val fruitSpecies: String = "Fruit",
    val timberRate: Int = TIMBER_RATE,
    val fruitRate: Int = FRUIT_RATE,
    val amountDue: Int = 0,
)

@Serializable
data class PayInfo(
    val timber: Int = 0,
    val fruit: Int = 0,
    val amountDue: Int = 0,
    val amountPaid: Int = 0,
    val method: String = "upi",
    val reference: String = "",
    val paidOn: String = "",
    val status: String = "",     // waiting | approved | rejected | pending
    val message: String = "",
    val reviewer: String = "",
    val reviewedAt: String? = null,
    val submittedAt: String = "",
)

@Serializable
data class PlantInfo(
    val preAreaM2: Double? = null,
    val postAreaM2: Double? = null,
    val timberCounted: Int? = null,
    val fruitCounted: Int? = null,
    val healthy: Boolean = false,
    val rightSpecies: Boolean = false,
    val satisfied: Boolean = false,
    val short: Boolean = false,
    val deliveryAt: String? = null,
    val otpSentAt: String? = null,
    val otpVerifiedAt: String? = null,
    val timberPlanted: Int? = null,
    val fruitPlanted: Int? = null,
    val plantedAt: String? = null,
    val reviewStatus: String = "",
    val reviewMessage: String = "",
    val reviewer: String = "",
    val reviewedAt: String? = null,
    // Land swap check of the walk before planting.
    val preOverlap: Double? = null,
    val preDistanceM: Double? = null,
    val landNote: String = "",
    val landChange: LandChangeInfo? = null,
)

@Serializable
data class LandChangeInfo(
    val status: String = "",   // waiting | approved | rejected | pending
    val reason: String = "",
    val reasonText: String = "",
    val surveyNo: String = "",
    val areaHa: Double? = null,
    val ownerType: String = "farmer",
    val message: String = "",
    val reviewer: String = "",
    val reviewedAt: String? = null,
)

/** A land change reported on this phone with the walk of the new land ([boundaryId]). */
@Serializable
data class LandChangeLocal(
    val boundaryId: String = "",
    val reason: String,
    val surveyNo: String,
    val areaHa: Double,
    val ownerType: String,
    val at: Long = System.currentTimeMillis(),
)

@Serializable data class LandChangeIn(val reason: String, val surveyNo: String, val areaHa: Double, val ownerType: String)

@Serializable
data class PlanStep(val key: String, val label: String = "", val state: String = "locked") // done | current | stopped | locked

@Serializable
data class PlanEvent(val event: String, val detail: String = "", val by: String = "", val at: String = "")

@Serializable
data class PlanState(
    val farmerId: String,
    val farmerMobile: String = "",
    val onboardingOk: Boolean = false,
    val payment: String = "locked",     // locked | none | waiting | approved | rejected | pending
    val plantation: String = "locked",  // locked | pre_walk | delivery | short | otp | planting | post_walk | to_review | rejected | pending | done
    val plan: SaplingPlan = SaplingPlan(),
    val pay: PayInfo? = null,
    val plant: PlantInfo? = null,
    val steps: List<PlanStep> = emptyList(),
    val events: List<PlanEvent> = emptyList(),
    val fetchedAt: Long = 0,
)

/** What the surveyor entered for the payment, kept until the server has it. */
@Serializable
data class PaymentLocal(
    val timber: Int,
    val fruit: Int,
    val amountPaid: Int,
    val method: String,
    val reference: String,
    val paidOn: String,
    val sentAt: Long = System.currentTimeMillis(),
)

@Serializable
data class DeliveryLocal(
    val timberCounted: Int,
    val fruitCounted: Int,
    val healthy: Boolean,
    val rightSpecies: Boolean,
    val satisfied: Boolean,
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class PlantedLocal(val timberPlanted: Int, val fruitPlanted: Int, val at: Long = System.currentTimeMillis())

@Serializable data class PaymentIn(val timber: Int, val fruit: Int, val amountPaid: Int, val method: String, val reference: String, val paidOn: String)
@Serializable data class DeliveryIn(val timberCounted: Int, val fruitCounted: Int, val healthy: Boolean, val rightSpecies: Boolean, val satisfied: Boolean)
@Serializable data class PlantedIn(val timberPlanted: Int, val fruitPlanted: Int)
@Serializable data class PlantOtpSent(val sentTo: String = "", val resendAfter: Int = 30, val sms: String = "", val devCode: String? = null)
@Serializable data class PlantOtpVerify(val code: String, val lat: Double?, val lng: Double?)

fun amountDue(timber: Int, fruit: Int) = timber * TIMBER_RATE + fruit * FRUIT_RATE

/** ₹12,135 — Indian digit grouping (1,23,456). */
fun rupees(n: Int): String = "₹" + indian(n)

fun indian(n: Int): String {
    val s = kotlin.math.abs(n).toString()
    if (s.length <= 3) return (if (n < 0) "-" else "") + s
    val head = s.dropLast(3)
    val groups = head.reversed().chunked(2).map { it.reversed() }.reversed()
    return (if (n < 0) "-" else "") + groups.joinToString(",") + "," + s.takeLast(3)
}

/** UPI or bank reference as the server accepts it: 4–40 letters, digits, / or -. */
fun cleanReference(s: String) = s.replace(" ", "").uppercase()
fun isValidReference(s: String) = Regex("^[A-Z0-9/-]{4,40}$").matches(cleanReference(s))
