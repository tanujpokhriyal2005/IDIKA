package org.fieldproof.data

import kotlinx.serialization.Serializable
import org.fieldproof.geo.BoundaryPoint
import org.fieldproof.geo.Geo
import org.fieldproof.geo.WalkSnapshot
import java.util.UUID
import kotlin.math.floor

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class Surveyor(
    val id: String,
    val phone: String,
    val name: String,
    val district: String,
    val block: String,
    val language: String = "en",
    val blocks: List<String> = emptyList(),
) {
    val firstName: String get() = name.trim().split(Regex("\\s+")).first()
}

@Serializable
data class Village(val id: Int, val name: String, val block: String, val district: String)

@Serializable
data class Photo(
    val id: String,
    val kind: String,           // aadhaar_front, aadhaar_back, land_record, land_record_pdf, water_source, agreement, plantation_proof
    val path: String,           // local file
    val mime: String = "image/jpeg",
    val landId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Double? = null,
    val capturedAt: Long = 0,
    val sharpness: Double? = null,
    val readable: Boolean = true,
    val uploaded: Boolean = false,
)

@Serializable
data class Land(
    val id: String = newId(),
    val surveyNo: String = "",
    val areaHa: Double = 0.0,
    val registerAcres: Double = 0.0,
    val ownerType: String = "farmer",
    val recordsConsent: Boolean = false,
) {
    val areaAcres: Double get() = areaHa * Geo.ACRES_PER_HECTARE
    /** Land-record area rounded down to a 0.25 acre step (the most that can be registered). */
    val maxRegisterAcres: Double get() = floor(areaHa * Geo.ACRES_PER_HECTARE * 4 + 1e-6) / 4
}

@Serializable
data class Overlap(val farmerCode: String, val farmerName: String, val overlapM2: Double, val flagged: Boolean)

@Serializable
data class ServerCheck(
    val areaM2: Double = 0.0,
    val areaAcres: Double = 0.0,
    val points: Int = 0,
    val valid: Boolean = false,
    val problem: String? = null,
    val overlaps: List<Overlap> = emptyList(),
    val overlapOk: Boolean = true,
    val toleranceM2: Double = 0.0,
)

@Serializable
data class Boundary(
    val id: String,
    val landId: String?,
    val points: List<BoundaryPoint>,
    val areaM2: Double,
    val walkedM: Double,
    val accuracyM: Double,
    val startedAt: Long,
    val finishedAt: Long,
    val synced: Boolean = false,
    val check: ServerCheck? = null,
    val purpose: String = "onboarding", // onboarding | pre_plant | post_plant
    val landNote: String = "",          // pre_plant walk partly off the onboarding land: why that's fine
)

@Serializable
data class FarmDetails(
    val waterSources: List<String> = emptyList(),
    val waterPhotoId: String? = null,
    val timberSpecies: List<String> = emptyList(),
    val fruitSpecies: List<String> = emptyList(),
    val timberPercent: Int = 40,
    val landUse: List<String> = emptyList(),
    val kharif: Map<String, List<String>> = emptyMap(),
    val kharifSame: Boolean? = null,
    val rabi: Map<String, List<String>> = emptyMap(),
    val rabiSame: Boolean? = null,
)

@Serializable
data class Farmer(
    val id: String = newId(),
    val number: Int = 0,
    val code: String = "",
    val fullName: String = "",
    val mobile: String = "",
    val mobileConfirm: String = "",
    val whatsappSame: Boolean = false,
    val whatsapp: String = "",
    val otherMobile: String = "",
    val villageId: Int? = null,
    val villageName: String = "",
    val block: String = "",
    val district: String = "",
    val treeInterest: Boolean? = null,
    val aadhaarNameMatch: Boolean = false,
    val aadhaarConsent: Boolean = false,
    val lands: List<Land> = listOf(Land()),
    val photos: List<Photo> = emptyList(),
    val boundaries: List<Boundary> = emptyList(),
    val farmDetails: FarmDetails? = null,
    val status: String = "draft",   // draft, registered, boundary_done, submitted
    val createdAt: Long = 0,
    // Payment and plantation, as entered on this phone (the server's view is AppState.plans).
    val plantWalks: List<Boundary> = emptyList(),
    val payment: PaymentLocal? = null,
    val delivery: DeliveryLocal? = null,
    val planted: PlantedLocal? = null,
    val landChange: LandChangeLocal? = null,
) {
    /** "Sunanda Ramrao Raut" -> "Sunanda Raut". */
    val shortName: String
        get() {
            val w = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (w.size <= 2) w.joinToString(" ") else "${w.first()} ${w.last()}"
        }

    fun photo(kind: String, landId: String? = null) = photos.lastOrNull { it.kind == kind && (landId == null || it.landId == landId) }
    fun boundaryFor(landId: String?) = boundaries.lastOrNull { it.landId == landId }
}

@Serializable
data class FarmerSummary(
    val id: String,
    val code: String,
    val fullName: String,
    val mobile: String = "",
    val village: String = "",
    val status: String = "",
    val lands: Int = 0,
    val acres: Double = 0.0,
    val boundaries: Int = 0,
    val areaM2: Double = 0.0,
)

@Serializable
data class ActionItem(
    val id: String,
    val kind: String,         // walk_boundary, retake_aadhaar, collect_agreement, plantation_proof, fix_<doc>
    val farmerId: String,
    val farmerName: String,
    val farmerCode: String = "",
    val village: String = "",
    val note: String = "",    // for review tasks: the reviewer's message
    val lands: Int = 0,
    val doc: String = "",     // review tasks: aadhaar, land_record, water_source, boundary
    val reviewerName: String = "",
    val decision: String = "", // rejected | pending
    val attention: Boolean = false, // sent back from the dashboard: red dot
    val updatedAt: String = "",
    val docs: Map<String, String> = emptyMap(),
) {
    val isFix: Boolean get() = kind.startsWith("fix_")
    /** Payment and plantation steps: made by the server from the farmer's stage, closed by doing the step. */
    val isPlan: Boolean get() = id.startsWith("plan-")
    val updatedAtMs: Long get() = runCatching { java.time.Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L)
}

@Serializable
data class OutboxOp(
    val id: String = newId(),
    val type: String,          // put_farmer, upload_file, put_boundary, put_farm_details, complete_action, set_language
    val farmerId: String? = null,
    val refId: String? = null,
    val attempts: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppState(
    val apiUrl: String? = null,
    val token: String? = null,
    val surveyor: Surveyor? = null,
    val language: String = "en",
    val villages: List<Village> = emptyList(),
    val numbers: List<Int> = emptyList(),
    val farmers: Map<String, Farmer> = emptyMap(),
    val summaries: List<FarmerSummary> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val doneActions: Set<String> = emptySet(),
    val outbox: List<OutboxOp> = emptyList(),
    val draftId: String? = null,
    val lastSyncAt: Long = 0,
    val walkSnapshot: WalkSnapshot? = null,
    val plans: Map<String, PlanState> = emptyMap(),
) {
    val loggedIn: Boolean get() = token != null && surveyor != null
}

/** Farmer ID shown in the app: number + first three letters of the first name. Mirrors backend FarmerCode. */
fun farmerCode(number: Int, fullName: String): String {
    val p = StringBuilder()
    for (c in fullName.lowercase()) {
        if (c in 'a'..'z') {
            p.append(c)
            if (p.length == 3) break
        } else if (p.isNotEmpty() && c.isWhitespace()) break
    }
    while (p.length < 3) p.append('x')
    return "$number$p"
}

fun normalizeMobile(s: String): String = s.filter { it.isDigit() }.take(10)

fun isValidMobile(s: String): Boolean = s.length == 10 && s[0] in '6'..'9'
