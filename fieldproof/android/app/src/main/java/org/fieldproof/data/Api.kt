package org.fieldproof.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.fieldproof.geo.BoundaryPoint
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Server said no (4xx/5xx) — distinct from being offline (IOException). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable data class OtpRequest(val phone: String)
@Serializable data class OtpRequested(val resendAfter: Int = 30, val expiresIn: Int = 300, val devCode: String? = null)
@Serializable data class OtpVerify(val phone: String, val code: String)
@Serializable data class LoginResult(val token: String, val surveyor: Surveyor)
@Serializable data class Numbers(val numbers: List<Int>)

@Serializable data class LandOut(val id: String, val surveyNo: String, val areaHa: Double, val registerAcres: Double, val ownerType: String, val recordsConsent: Boolean)
@Serializable data class FarmerIn(
    val number: Int,
    val fullName: String,
    val mobile: String,
    val whatsappSame: Boolean,
    val whatsapp: String,
    val otherMobile: String,
    val villageId: Int,
    val treeInterest: Boolean,
    val aadhaarNameMatch: Boolean,
    val aadhaarConsent: Boolean,
    val lands: List<LandOut>,
    val createdAt: String?,
)

@Serializable data class RemoteVillage(val id: Int = 0, val name: String = "", val block: String = "", val district: String = "")
@Serializable data class RemoteBoundary(
    val id: String, val landId: String? = null, val points: List<BoundaryPoint> = emptyList(),
    val areaM2: Double = 0.0, val pointCount: Int = 0, val accuracyM: Double? = null, val walkedM: Double? = null,
)
@Serializable data class RemoteFarmer(
    val id: String,
    val number: Int,
    val code: String,
    val fullName: String,
    val mobile: String,
    val whatsappSame: Boolean = false,
    val whatsapp: String = "",
    val otherMobile: String = "",
    val village: RemoteVillage = RemoteVillage(),
    val treeInterest: Boolean = true,
    val aadhaarNameMatch: Boolean = false,
    val aadhaarConsent: Boolean = false,
    val status: String = "registered",
    val lands: List<LandOut> = emptyList(),
    val boundaries: List<RemoteBoundary>? = null,
    val farmDetails: JsonElement? = null,
)

@Serializable data class BoundaryIn(
    val farmerId: String,
    val landId: String?,
    val points: List<BoundaryPoint>,
    val clientAreaM2: Double,
    val walkedM: Double,
    val accuracyM: Double,
    val startedAt: String?,
    val finishedAt: String?,
    val purpose: String = "onboarding",
    val landNote: String = "",
    val landChange: LandChangeIn? = null,
)
@Serializable data class BoundarySaved(val id: String, val check: ServerCheck)
@Serializable data class FarmDetailsOut(
    val waterSources: List<String>,
    val waterPhotoId: String,
    val timberSpecies: List<String>,
    val fruitSpecies: List<String>,
    val timberPercent: Int,
    val fruitPercent: Int,
    val landUse: List<String>,
    val kharif: Map<String, List<String>>,
    val kharifSame: Boolean,
    val rabi: Map<String, List<String>>,
    val rabiSame: Boolean,
)
@Serializable data class LanguageIn(val language: String)
@Serializable data class LocateResult(
    val district: String,
    val block: String,
    val village: Village? = null,
    val distanceM: Double = 0.0,
    val blocks: List<String> = emptyList(),
    val villages: List<Village> = emptyList(),
)
@Serializable data class NumbersIn(val count: Int)

fun isoOrNull(ms: Long): String? = if (ms > 0) Instant.ofEpochMilli(ms).toString() else null

class Api(private val baseUrl: () -> String, private val token: () -> String?) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun req(path: String) = Request.Builder().url(baseUrl().trimEnd('/') + path).also { b ->
        token()?.let { b.header("Authorization", "Bearer $it") }
    }

    private suspend fun call(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val (code, msg) = runCatching {
                    val o = json.parseToJsonElement(body) as JsonObject
                    (o["error"]?.jsonPrimitive?.content ?: "http_${resp.code}") to (o["message"]?.jsonPrimitive?.content ?: body)
                }.getOrElse { "http_${resp.code}" to "Server error ${resp.code}" }
                throw ApiException(resp.code, code, msg)
            }
            body
        }
    }

    private suspend inline fun <reified T> get(path: String): T = json.decodeFromString(call(req(path).get().build()))
    private suspend inline fun <reified I, reified T> send(method: String, path: String, body: I): T {
        val rb: RequestBody = json.encodeToString(body).toRequestBody(jsonType)
        return json.decodeFromString(call(req(path).method(method, rb).build()))
    }
    private suspend inline fun <reified I> sendNoResult(method: String, path: String, body: I) {
        call(req(path).method(method, json.encodeToString(body).toRequestBody(jsonType)).build())
    }

    suspend fun requestOtp(phone: String): OtpRequested = send("POST", "/v1/auth/otp/request", OtpRequest(phone))
    suspend fun verifyOtp(phone: String, code: String): LoginResult = send("POST", "/v1/auth/otp/verify", OtpVerify(phone, code))
    suspend fun me(): Surveyor = get("/v1/me")
    suspend fun villages(): List<Village> = get("/v1/villages")
    suspend fun locate(lat: Double, lng: Double): LocateResult = get("/v1/places/locate?lat=$lat&lng=$lng")
    suspend fun placeVillages(district: String, block: String): List<Village> =
        get("/v1/places/villages?district=${enc(district)}&block=${enc(block)}")
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    suspend fun reserveNumbers(n: Int): Numbers = send("POST", "/v1/farmer-numbers", NumbersIn(n))
    suspend fun farmers(): List<FarmerSummary> = get("/v1/farmers")
    suspend fun farmer(id: String): RemoteFarmer = get("/v1/farmers/$id")
    suspend fun actionItems(): List<ActionItem> = get("/v1/action-items")
    suspend fun putFarmer(id: String, f: FarmerIn): RemoteFarmer = send("PUT", "/v1/farmers/$id", f)
    suspend fun checkBoundary(b: BoundaryIn): ServerCheck = send("POST", "/v1/boundaries/check", b)
    suspend fun putBoundary(farmerId: String, id: String, b: BoundaryIn): BoundarySaved = send("PUT", "/v1/farmers/$farmerId/boundaries/$id", b)
    suspend fun putFarmDetails(farmerId: String, d: FarmDetailsOut) = sendNoResult("PUT", "/v1/farmers/$farmerId/farm-details", d)
    suspend fun completeAction(id: String) = sendNoResult("POST", "/v1/action-items/$id/complete", JsonObject(emptyMap()))
    suspend fun setLanguage(lang: String) = sendNoResult("PUT", "/v1/me/language", LanguageIn(lang))
    suspend fun health() = call(req("/healthz").get().build())

    suspend fun plantation(farmerId: String): PlanState = get("/v1/farmers/$farmerId/plantation")
    suspend fun putPayment(farmerId: String, p: PaymentIn): PlanState = send("PUT", "/v1/farmers/$farmerId/payment", p)
    suspend fun postDelivery(farmerId: String, d: DeliveryIn) = sendNoResult("POST", "/v1/farmers/$farmerId/delivery", d)
    suspend fun plantOtpRequest(farmerId: String): PlantOtpSent = send("POST", "/v1/farmers/$farmerId/planting-otp/request", JsonObject(emptyMap()))
    suspend fun plantOtpVerify(farmerId: String, v: PlantOtpVerify): PlanState = send("POST", "/v1/farmers/$farmerId/planting-otp/verify", v)
    suspend fun postPlanted(farmerId: String, p: PlantedIn): PlanState = send("POST", "/v1/farmers/$farmerId/planted", p)

    suspend fun uploadFile(farmerId: String, p: Photo) {
        val file = File(p.path)
        if (!file.exists()) throw ApiException(400, "missing_file", "Photo file is missing on the phone.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("id", p.id)
            .addFormDataPart("kind", p.kind)
            .apply {
                p.landId?.let { addFormDataPart("landId", it) }
                p.lat?.let { addFormDataPart("lat", it.toString()) }
                p.lng?.let { addFormDataPart("lng", it.toString()) }
                p.accuracy?.let { addFormDataPart("accuracy", it.toString()) }
                p.sharpness?.let { addFormDataPart("sharpness", it.toString()) }
                if (p.capturedAt > 0) addFormDataPart("capturedAt", Instant.ofEpochMilli(p.capturedAt).toString())
            }
            .addFormDataPart("file", file.name, file.asRequestBody(p.mime.toMediaType()))
            .build()
        call(req("/v1/farmers/$farmerId/files").post(body).build())
    }

    companion object {
        fun isOffline(e: Throwable) = e is IOException
    }
}
