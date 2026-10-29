package org.fieldproof.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fieldproof.BuildConfig
import org.fieldproof.geo.CapturedBoundary
import org.fieldproof.geo.WalkSnapshot
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Single source of truth. Everything is written to the phone first
 * (state.json, atomically) and queued in an outbox that syncs whenever the
 * server can be reached.
 */
class Repository(private val ctx: Context) {
    private val file = File(ctx.filesDir, "state.json")
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** null = not tried yet; reflects the last real request, not Wi-Fi status (USB testing goes over adb reverse). */
    val serverReachable = MutableStateFlow<Boolean?>(null)
    val syncing = MutableStateFlow(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api = Api({ _state.value.apiUrl ?: BuildConfig.DEFAULT_API_URL }, { _state.value.token })
    val photoDir: File get() = File(ctx.filesDir, "photos").apply { mkdirs() }

    private fun load(): AppState = runCatching { json.decodeFromString<AppState>(file.readText()) }.getOrElse { AppState() }

    private val writeLock = Any()
    private val persistSoon = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { for (x in persistSoon) write() }
    }

    private fun write() = synchronized(writeLock) {
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(json.encodeToString(_state.value))
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    /** Change state and write it to the phone before returning. */
    suspend fun update(f: (AppState) -> AppState): AppState = mutex.withLock {
        val s = _state.updateAndGet(f)
        withContext(Dispatchers.IO) { write() }
        s
    }

    /** Form typing: applied instantly in memory (no lag between keystrokes), written to the phone right after. */
    fun edit(f: (AppState) -> AppState) {
        _state.updateAndGet(f)
        persistSoon.trySend(Unit)
    }

    fun editFarmer(id: String, f: (Farmer) -> Farmer) = edit { s ->
        val cur = s.farmers[id] ?: return@edit s
        s.copy(farmers = s.farmers + (id to f(cur)))
    }

    suspend fun updateFarmer(id: String, f: (Farmer) -> Farmer) = update { s ->
        val cur = s.farmers[id] ?: return@update s
        s.copy(farmers = s.farmers + (id to f(cur)))
    }

    private suspend fun <T> track(block: suspend () -> T): T = try {
        block().also { serverReachable.value = true }
    } catch (e: IOException) {
        serverReachable.value = false
        throw e
    } catch (e: ApiException) {
        serverReachable.value = true
        throw e
    }

    // ---------- auth ----------

    suspend fun setApiUrl(url: String?) = update { it.copy(apiUrl = url?.trim()?.trimEnd('/')?.ifEmpty { null }) }
    fun apiUrl(): String = _state.value.apiUrl ?: BuildConfig.DEFAULT_API_URL

    suspend fun requestOtp(phone: String) = track { api.requestOtp(phone) }

    suspend fun verifyOtp(phone: String, code: String) {
        val r = track { api.verifyOtp(phone, code) }
        update { it.copy(token = r.token, surveyor = r.surveyor, language = r.surveyor.language) }
        refresh()
    }

    suspend fun logout() {
        update { AppState(apiUrl = it.apiUrl, language = it.language) }
    }

    suspend fun setLanguage(lang: String) {
        if (_state.value.language == lang) return
        update { it.copy(language = lang, surveyor = it.surveyor?.copy(language = lang)) }
        enqueue(OutboxOp(type = "set_language", refId = lang))
    }

    // ---------- refresh from server ----------

    suspend fun refresh(): Boolean {
        if (!_state.value.loggedIn) return false
        return try {
            val me = track { api.me() }
            val villages = api.villages()
            val farmers = api.farmers()
            val items = api.actionItems()
            var nums = _state.value.numbers
            if (nums.size < 10) nums = nums + api.reserveNumbers(20).numbers
            update { s ->
                // Language comes from the profile at login; after that the phone's choice wins
                // (changing it mid-walk would restart the screen).
                s.copy(
                    surveyor = me.copy(language = s.language),
                    villages = (villages + s.villages).distinctBy { it.id },
                    summaries = farmers,
                    actionItems = items,
                    numbers = nums,
                    doneActions = s.doneActions.filter { id -> items.any { it.id == id } }.toSet(),
                )
            }
            for (it in items) if (it.farmerId !in _state.value.farmers) runCatching { loadFarmer(it.farmerId) }
            true
        } catch (e: Exception) {
            if (e is ApiException && e.status == 401) logout()
            false
        }
    }

    /** Makes sure a farmer registered elsewhere (or seeded) is available offline with its lands. */
    suspend fun loadFarmer(id: String): Farmer? {
        _state.value.farmers[id]?.let { return it }
        val r = track { api.farmer(id) }
        val f = Farmer(
            id = r.id, number = r.number, code = r.code, fullName = r.fullName, mobile = r.mobile, mobileConfirm = r.mobile,
            whatsappSame = r.whatsappSame, whatsapp = r.whatsapp, otherMobile = r.otherMobile,
            villageId = r.village.id, villageName = r.village.name, block = r.village.block, district = r.village.district,
            treeInterest = r.treeInterest, aadhaarNameMatch = r.aadhaarNameMatch, aadhaarConsent = r.aadhaarConsent,
            lands = r.lands.map { Land(it.id, it.surveyNo, it.areaHa, it.registerAcres, it.ownerType, it.recordsConsent) },
            boundaries = r.boundaries.orEmpty().map {
                Boundary(it.id, it.landId, it.points, it.areaM2, it.walkedM ?: 0.0, it.accuracyM ?: 0.0, 0, 0, synced = true)
            },
            status = r.status,
            createdAt = 1,
        )
        update { s -> if (id in s.farmers) s else s.copy(farmers = s.farmers + (id to f)) }
        return _state.value.farmers[id]
    }

    // ---------- GPS place lookup ----------

    /** District, block and nearest village for a GPS point; villages are kept on the phone for offline use. */
    suspend fun locate(lat: Double, lng: Double): LocateResult {
        val r = track { api.locate(lat, lng) }
        update { s -> s.copy(villages = (s.villages + r.villages + listOfNotNull(r.village)).distinctBy { it.id }) }
        return r
    }

    /** Villages of a block: from the server when reachable, else what the phone already has. */
    suspend fun villagesFor(district: String, block: String): List<Village> = try {
        val vs = track { api.placeVillages(district, block) }
        update { s -> s.copy(villages = (s.villages + vs).distinctBy { it.id }) }
        vs
    } catch (e: Exception) {
        _state.value.villages.filter { it.district == district && it.block == block }.sortedBy { it.name }
    }

    fun blocksFor(district: String): List<String> =
        _state.value.villages.filter { it.district == district }.map { it.block }.distinct().sorted()

    // ---------- registering a farmer ----------

    suspend fun startDraft(): Farmer {
        _state.value.draftId?.let { id -> _state.value.farmers[id]?.let { return it } }
        val f = Farmer()
        update { it.copy(draftId = f.id, farmers = it.farmers + (f.id to f)) }
        return f
    }

    suspend fun discardDraft() {
        val id = _state.value.draftId ?: return
        val f = _state.value.farmers[id]
        f?.photos?.forEach { File(it.path).delete() }
        update { it.copy(draftId = null, farmers = it.farmers - id) }
    }

    suspend fun registerDraft(): Farmer {
        val id = _state.value.draftId ?: error("no draft")
        val s = update { s ->
            val f = s.farmers.getValue(id)
            val number = s.numbers.firstOrNull() ?: 0
            val reg = f.copy(
                number = number,
                code = if (number > 0) farmerCode(number, f.fullName) else "",
                fullName = f.fullName.trim().replace(Regex("\\s+"), " "),
                whatsapp = if (f.whatsappSame) f.mobile else f.whatsapp,
                status = "registered",
                createdAt = System.currentTimeMillis(),
            )
            s.copy(farmers = s.farmers + (id to reg), numbers = s.numbers.drop(1), draftId = null)
        }
        val f = s.farmers.getValue(id)
        val ops = listOf(OutboxOp(type = "put_farmer", farmerId = id)) +
            f.photos.map { OutboxOp(type = "upload_file", farmerId = id, refId = it.id) }
        enqueue(*ops.toTypedArray())
        return f
    }

    /** Adds a photo; uploads right away for registered farmers, at registration for drafts. */
    suspend fun addPhoto(farmerId: String, p: Photo, replaceKind: Boolean = true) {
        val old = _state.value.farmers[farmerId]?.photos?.filter { replaceKind && it.kind == p.kind && it.landId == p.landId && !it.uploaded }.orEmpty()
        updateFarmer(farmerId) { f ->
            f.copy(photos = f.photos.filterNot { o -> old.any { it.id == o.id } } + p)
        }
        old.forEach { File(it.path).delete() }
        val f = _state.value.farmers[farmerId] ?: return
        if (f.status != "draft") {
            update { s -> s.copy(outbox = s.outbox.filterNot { op -> op.type == "upload_file" && old.any { it.id == op.refId } }) }
            enqueue(OutboxOp(type = "upload_file", farmerId = farmerId, refId = p.id))
        }
    }

    suspend fun removePhoto(farmerId: String, photoId: String) {
        val p = _state.value.farmers[farmerId]?.photos?.find { it.id == photoId } ?: return
        if (p.uploaded) return
        updateFarmer(farmerId) { f -> f.copy(photos = f.photos.filterNot { it.id == photoId }) }
        update { s -> s.copy(outbox = s.outbox.filterNot { it.refId == photoId }) }
        File(p.path).delete()
    }

    // ---------- boundary and farm details ----------

    suspend fun saveWalkSnapshot(s: WalkSnapshot?) = update { it.copy(walkSnapshot = s) }

    suspend fun saveBoundary(farmerId: String, landId: String?, c: CapturedBoundary, check: ServerCheck?): Boundary {
        val b = Boundary(newId(), landId, c.points, c.areaM2, c.walkedM, c.accuracyM, c.startedAtMs, c.finishedAtMs, check = check)
        updateFarmer(farmerId) { f ->
            f.copy(
                boundaries = f.boundaries.filterNot { it.landId == landId } + b,
                status = if (f.status == "registered") "boundary_done" else f.status,
            )
        }
        update { s -> s.copy(walkSnapshot = null, outbox = s.outbox.filterNot { op -> op.type == "put_boundary" && op.farmerId == farmerId && s.farmers[farmerId]?.boundaries?.none { it.id == op.refId } == true }) }
        enqueue(OutboxOp(type = "put_boundary", farmerId = farmerId, refId = b.id))
        return b
    }

    fun saveFarmDetailsDraft(farmerId: String, d: FarmDetails) = editFarmer(farmerId) { it.copy(farmDetails = d) }

    suspend fun submitFarmDetails(farmerId: String, d: FarmDetails) {
        updateFarmer(farmerId) { it.copy(farmDetails = d, status = "submitted") }
        val actions = _state.value.actionItems.filter { it.farmerId == farmerId && it.kind == "walk_boundary" }.map { it.id }
        update { it.copy(doneActions = it.doneActions + actions) }
        enqueue(OutboxOp(type = "put_farm_details", farmerId = farmerId))
    }

    suspend fun completeAction(item: ActionItem) {
        update { it.copy(doneActions = it.doneActions + item.id) }
        if (!item.id.startsWith("local-") && !item.isPlan) enqueue(OutboxOp(type = "complete_action", farmerId = item.farmerId, refId = item.id))
    }

    // ---------- payment and plantation ----------

    /** The server's view of this farmer's payment and plantation; the last one kept when offline. */
    suspend fun loadPlan(farmerId: String): PlanState? = try {
        val p = track { api.plantation(farmerId) }.copy(fetchedAt = System.currentTimeMillis())
        update { s -> s.copy(plans = s.plans + (farmerId to p)) }
        p
    } catch (e: Exception) {
        _state.value.plans[farmerId]
    }

    /** Hides a plan task on the phone until the server moves on (the next step's task has a new id). */
    suspend fun closePlanTasks(farmerId: String, vararg kinds: String) {
        val ids = kinds.map { "plan-$farmerId-$it" }
        update { s -> s.copy(doneActions = s.doneActions + ids.filter { id -> s.actionItems.any { it.id == id } }) }
    }

    suspend fun submitPayment(farmerId: String, p: PaymentLocal) {
        updateFarmer(farmerId) { it.copy(payment = p) }
        closePlanTasks(farmerId, "collect_payment", "fix_payment")
        enqueue(OutboxOp(type = "put_payment", farmerId = farmerId))
    }

    suspend fun saveDelivery(farmerId: String, d: DeliveryLocal) {
        updateFarmer(farmerId) { it.copy(delivery = d) }
        enqueue(OutboxOp(type = "post_delivery", farmerId = farmerId))
    }

    /** Sends the OTP to the farmer. Needs the server, so anything waiting for this farmer is sent first. */
    suspend fun requestPlantOtp(farmerId: String): PlantOtpSent {
        if (_state.value.outbox.any { it.farmerId == farmerId }) syncOnce()
        return track { api.plantOtpRequest(farmerId) }
    }

    suspend fun verifyPlantOtp(farmerId: String, code: String, lat: Double?, lng: Double?): PlanState {
        val p = track { api.plantOtpVerify(farmerId, PlantOtpVerify(code, lat, lng)) }.copy(fetchedAt = System.currentTimeMillis())
        update { s -> s.copy(plans = s.plans + (farmerId to p)) }
        closePlanTasks(farmerId, "sapling_delivery")
        return p
    }

    suspend fun savePlanted(farmerId: String, p: PlantedLocal) {
        updateFarmer(farmerId) { it.copy(planted = p) }
        closePlanTasks(farmerId, "planting")
        enqueue(OutboxOp(type = "post_planted", farmerId = farmerId))
    }

    /** Boundary walked just before planting or after it. Kept apart from the onboarding walks. */
    suspend fun savePlantWalk(
        farmerId: String, purpose: String, c: CapturedBoundary, check: ServerCheck?,
        landNote: String = "", landChange: LandChangeLocal? = null,
    ): Boundary {
        val b = Boundary(newId(), null, c.points, c.areaM2, c.walkedM, c.accuracyM, c.startedAtMs, c.finishedAtMs, check = check, purpose = purpose, landNote = landNote.trim())
        updateFarmer(farmerId) { f ->
            f.copy(
                plantWalks = f.plantWalks.filterNot { it.purpose == purpose } + b,
                landChange = if (purpose == "pre_plant") landChange?.copy(boundaryId = b.id) else f.landChange,
            )
        }
        update { s -> s.copy(walkSnapshot = null) }
        closePlanTasks(farmerId, if (purpose == "pre_plant") "walk_pre_plant" else "walk_post_plant", "fix_plantation", "fix_land_change", "land_change_review")
        enqueue(OutboxOp(type = "put_plant_walk", farmerId = farmerId, refId = b.id))
        return b
    }

    /** Server check for overlap with other farmers, when reachable. */
    suspend fun checkBoundary(farmerId: String, landId: String?, c: CapturedBoundary): ServerCheck? = try {
        track { api.checkBoundary(boundaryIn(farmerId, landId, c.points, c.areaM2, c.walkedM, c.accuracyM, c.startedAtMs, c.finishedAtMs)) }
    } catch (e: Exception) {
        null
    }

    private fun boundaryIn(farmerId: String, landId: String?, pts: List<org.fieldproof.geo.BoundaryPoint>, area: Double, walked: Double, acc: Double, start: Long, end: Long, purpose: String = "onboarding") =
        BoundaryIn(farmerId, landId, pts, area, walked, acc, isoOrNull(start), isoOrNull(end), purpose)

    // ---------- sync ----------

    suspend fun enqueue(vararg ops: OutboxOp) {
        update { it.copy(outbox = it.outbox + ops) }
        scheduleSync()
        scope.launch { syncOnce() }
    }

    fun scheduleSync() {
        // No network constraint on purpose: over USB the phone reaches the
        // laptop through adb reverse while Android reports "no network".
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, req)
    }

    suspend fun retryFailed() {
        update { s -> s.copy(outbox = s.outbox.map { it.copy(error = null, attempts = 0) }) }
        syncOnce()
    }

    /** Pushes the outbox in order. Returns true when everything is synced. */
    suspend fun syncOnce(): Boolean {
        if (!_state.value.loggedIn) return false
        if (!syncMutex.tryLock()) return false
        syncing.value = true
        try {
            val blocked = mutableSetOf<String>()
            var offline = false
            while (true) {
                val op = _state.value.outbox.firstOrNull { it.farmerId == null || it.farmerId !in blocked } ?: break
                try {
                    track { execute(op) }
                    update { s -> s.copy(outbox = s.outbox.filterNot { it.id == op.id }) }
                } catch (e: IOException) {
                    offline = true
                    break
                } catch (e: ApiException) {
                    if (e.status == 401) { logout(); break }
                    if (e.status >= 500) break
                    update { s -> s.copy(outbox = s.outbox.map { if (it.id == op.id) it.copy(error = e.message, attempts = it.attempts + 1) else it }) }
                    if (op.farmerId != null) blocked += op.farmerId else update { s -> s.copy(outbox = s.outbox.filterNot { it.id == op.id }) }
                } catch (e: Exception) {
                    update { s -> s.copy(outbox = s.outbox.map { if (it.id == op.id) it.copy(error = e.message ?: e.toString(), attempts = it.attempts + 1) else it }) }
                    if (op.farmerId != null) blocked += op.farmerId
                }
            }
            if (!offline) {
                refresh()
                // Keep each farmer's step current after sending something for them.
                _state.value.plans.keys.forEach { runCatching { loadPlan(it) } }
                update { it.copy(lastSyncAt = System.currentTimeMillis()) }
            }
            return _state.value.outbox.isEmpty()
        } finally {
            syncing.value = false
            syncMutex.unlock()
        }
    }

    private suspend fun execute(op: OutboxOp) {
        val s = _state.value
        val f = op.farmerId?.let { s.farmers[it] }
        when (op.type) {
            "put_farmer" -> {
                f ?: return
                val r = api.putFarmer(f.id, FarmerIn(
                    number = f.number, fullName = f.fullName, mobile = f.mobile, whatsappSame = f.whatsappSame,
                    whatsapp = if (f.whatsappSame) f.mobile else f.whatsapp, otherMobile = f.otherMobile,
                    villageId = f.villageId ?: 0, treeInterest = f.treeInterest ?: false,
                    aadhaarNameMatch = f.aadhaarNameMatch, aadhaarConsent = f.aadhaarConsent,
                    lands = f.lands.map { LandOut(it.id, it.surveyNo, it.areaHa, it.registerAcres, it.ownerType, it.recordsConsent) },
                    createdAt = isoOrNull(f.createdAt),
                ))
                updateFarmer(f.id) { it.copy(number = r.number, code = r.code) }
            }
            "upload_file" -> {
                val p = f?.photos?.find { it.id == op.refId } ?: return
                if (p.uploaded) return
                api.uploadFile(f.id, p)
                updateFarmer(f.id) { x -> x.copy(photos = x.photos.map { if (it.id == p.id) it.copy(uploaded = true) else it }) }
            }
            "put_boundary" -> {
                val b = f?.boundaries?.find { it.id == op.refId } ?: return
                val r = api.putBoundary(f.id, b.id, boundaryIn(f.id, b.landId, b.points, b.areaM2, b.walkedM, b.accuracyM, b.startedAt, b.finishedAt))
                updateFarmer(f.id) { x -> x.copy(boundaries = x.boundaries.map { if (it.id == b.id) it.copy(synced = true, check = r.check) else it }) }
            }
            "put_farm_details" -> {
                val d = f?.farmDetails ?: return
                api.putFarmDetails(f.id, FarmDetailsOut(
                    waterSources = d.waterSources, waterPhotoId = d.waterPhotoId.orEmpty(),
                    timberSpecies = d.timberSpecies, fruitSpecies = d.fruitSpecies,
                    timberPercent = d.timberPercent, fruitPercent = 100 - d.timberPercent,
                    landUse = d.landUse, kharif = d.kharif, kharifSame = d.kharifSame ?: true,
                    rabi = d.rabi, rabiSame = d.rabiSame ?: true,
                ))
            }
            "complete_action" -> api.completeAction(op.refId ?: return)
            "put_payment" -> {
                val p = f?.payment ?: return
                api.putPayment(f.id, PaymentIn(p.timber, p.fruit, p.amountPaid, p.method, p.reference, p.paidOn))
            }
            "post_delivery" -> {
                val d = f?.delivery ?: return
                api.postDelivery(f.id, DeliveryIn(d.timberCounted, d.fruitCounted, d.healthy, d.rightSpecies, d.satisfied))
            }
            "post_planted" -> {
                val p = f?.planted ?: return
                api.postPlanted(f.id, PlantedIn(p.timberPlanted, p.fruitPlanted))
            }
            "put_plant_walk" -> {
                val b = f?.plantWalks?.find { it.id == op.refId } ?: return
                val lc = f.landChange?.takeIf { it.boundaryId == b.id }?.let { LandChangeIn(it.reason, it.surveyNo, it.areaHa, it.ownerType) }
                val r = api.putBoundary(f.id, b.id, boundaryIn(f.id, null, b.points, b.areaM2, b.walkedM, b.accuracyM, b.startedAt, b.finishedAt, b.purpose).copy(landNote = b.landNote, landChange = lc))
                updateFarmer(f.id) { x -> x.copy(plantWalks = x.plantWalks.map { if (it.id == b.id) it.copy(synced = true, check = r.check) else it }) }
            }
            "set_language" -> api.setLanguage(op.refId ?: return)
        }
    }

    // ---------- derived views ----------

    /** Action items: server list, minus what's done on this phone, plus walks for farmers registered offline. */
    fun visibleActions(s: AppState): List<ActionItem> {
        val server = s.actionItems.filter { it.id !in s.doneActions }.filterNot { item ->
            item.kind == "walk_boundary" && s.farmers[item.farmerId]?.status == "submitted"
        }.filterNot { item ->
            // A new walk saved on this phone answers a "walk again" task even before it syncs.
            item.kind == "fix_boundary" && s.farmers[item.farmerId]?.boundaries?.any { it.startedAt > item.updatedAtMs } == true
        }.sortedByDescending { it.attention }
        val local = s.farmers.values
            .filter { it.status in setOf("registered", "boundary_done") && it.createdAt > 1 }
            .filter { f -> server.none { it.farmerId == f.id && it.kind == "walk_boundary" } && "local-${f.id}" !in s.doneActions }
            .filter { f -> s.actionItems.none { it.farmerId == f.id && it.kind == "walk_boundary" } }
            .sortedBy { it.createdAt }
            .map { ActionItem("local-${it.id}", "walk_boundary", it.id, it.fullName, it.code, it.villageName, "", it.lands.size) }
        val (fix, rest) = server.partition { it.attention }
        return fix + local.reversed() + rest
    }

    /** Open review tasks of one kind for a farmer (e.g. after walking the boundary again). */
    suspend fun completeFixes(farmerId: String, kind: String) {
        _state.value.actionItems.filter { it.farmerId == farmerId && it.kind == kind && it.id !in _state.value.doneActions }
            .forEach { completeAction(it) }
    }

    fun farmerCount(s: AppState): Int = (s.summaries.map { it.id } + s.farmers.values.filter { it.status != "draft" }.map { it.id }).toSet().size
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = org.fieldproof.FieldproofApp.instance.repo
        if (!repo.state.value.loggedIn) return Result.success()
        val done = repo.syncOnce()
        return if (done || repo.state.value.outbox.all { it.error != null }) Result.success() else Result.retry()
    }
}
