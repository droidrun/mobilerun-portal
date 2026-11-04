package com.mobilerun.portal.sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Receives persisted state changes of relay sends, on the store thread; the reporter turns them into sms/sendState. */
object SmsBridgeSendStates {
    @Volatile
    var listener: ((SmsSendRecord) -> Unit)? = null

    internal fun changed(record: SmsSendRecord) {
        if (record.origin != SmsSendRecord.ORIGIN_RELAY) return
        try {
            listener?.invoke(record)
        } catch (e: Exception) {
            Log.w("SmsStore", "send-state listener failed (${e.javaClass.simpleName})")
        }
    }
}

/** Keys of the reporter_state table. */
object SmsReporterStateKeys {
    const val INBOUND_WATERMARK_ID = "inbound_watermark_id"
    const val INBOUND_WATERMARK_DATE = "inbound_watermark_date"
    const val OUT_WATERMARK_ID = "out_watermark_id"
    const val OUT_WATERMARK_DATE = "out_watermark_date"
    const val COVERED_FROM = "covered_from"
    const val OUT_RECHECK = "out_recheck"
}

/**
 * SQLite state for cloud SMS: relay send records (never bodies), the Portal's own provider ids,
 * reporter watermarks and the pending-report queue. Every database call runs on [executor].
 */
class SmsStore private constructor(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : SmsSendRecordStore, SmsReporterStore {
    private val appContext = context.applicationContext
    private val helper = Helper(appContext)

    @Volatile
    private var storeThread: Thread? = null

    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SmsStore").also {
            it.isDaemon = true
            storeThread = it
        }
    }

    /** Records created before this instant were written by an earlier process. */
    val openedAt: Long = clock()

    init {
        executor.execute { safely("recover") { recoverInterrupted() } }
    }

    private fun <T> call(block: (SQLiteDatabase) -> T): T {
        if (Thread.currentThread() === storeThread) return block(helper.writableDatabase)
        val future = executor.submit(Callable { block(helper.writableDatabase) })
        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    /** Runs [block] on the store thread, then [done] (for BroadcastReceiver.goAsync). */
    fun async(name: String, done: () -> Unit = {}, block: (SQLiteDatabase) -> Unit) {
        try {
            executor.execute {
                try {
                    safely(name) { block(helper.writableDatabase) }
                } finally {
                    done()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$name not scheduled (${e.javaClass.simpleName})")
            done()
        }
    }

    // --- send records ---

    override fun find(id: String): SmsSendRecord? = call { db -> findLocked(db, id) }

    override fun insert(record: SmsSendRecord): Boolean = call { db ->
        val row = db.insertWithOnConflict(TABLE_RECORDS, null, record.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
        if (row != -1L) evictLocked(db)
        row != -1L
    }

    override fun markDispatched(id: String, now: Long): SmsSendRecord? = call { db ->
        updateLocked(db, id) { it.withDispatched(now) }
    }

    override fun markFailed(id: String, code: String, message: String, now: Long): SmsSendRecord? = call { db ->
        updateLocked(db, id) { it.withFailure(code, message, now) }?.also(SmsBridgeSendStates::changed)
    }

    /** Applies a sent/delivered result on the store thread; also records the provider row as the Portal's own. */
    internal fun applyCallbackAsync(callback: SmsSendCallback, done: () -> Unit) {
        async("callback", done) { db ->
            val before = findLocked(db, callback.recordId) ?: return@async
            val updated = updateLocked(db, callback.recordId) { SmsSendCallbacks.apply(it, callback, clock()) }
            SmsSendCallbacks.providerIdFromUri(callback.providerUri)?.let { recordOwnSendLocked(db, it, callback.recordId) }
            if (updated != null && updated.state != before.state) SmsBridgeSendStates.changed(updated)
        }
    }

    /** Relay records whose current state was not reported yet. */
    override fun unreportedRelayRecords(): List<SmsSendRecord> = call { db ->
        db.query(
            TABLE_RECORDS, null,
            "origin = ? AND (last_reported_state IS NULL OR last_reported_state != state)",
            arrayOf(SmsSendRecord.ORIGIN_RELAY), null, null, "updated_at ASC",
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toRecord() else null }.toList() }
    }

    override fun markReported(id: String, state: SmsSendState) {
        call { db ->
            db.update(TABLE_RECORDS, ContentValues().apply { put("last_reported_state", state.wire) }, "id = ?", arrayOf(id))
        }
    }

    // --- own sends ---

    fun recordOwnSendAsync(providerUri: String?, recordId: String?, done: () -> Unit) {
        val providerId = SmsSendCallbacks.providerIdFromUri(providerUri)
        if (providerId == null) {
            done()
            return
        }
        async("own-send", done) { db -> recordOwnSendLocked(db, providerId, recordId) }
    }

    override fun ownSendIds(): Set<Long> = call { db -> ownSendIdsLocked(db) }

    // --- reporter state ---

    /** Seeds watermarks at the newest provider row so enabling never backfills; drops queued reports. */
    fun seedReporterState(latest: SmsProviderRow?, coveredFrom: Long) {
        call { db ->
            db.inTransaction {
                clearReporterLocked(db)
                val id = (latest?.id ?: 0L).toString()
                val date = (latest?.date ?: 0L).toString()
                putStateLocked(db, SmsReporterStateKeys.INBOUND_WATERMARK_ID, id)
                putStateLocked(db, SmsReporterStateKeys.INBOUND_WATERMARK_DATE, date)
                putStateLocked(db, SmsReporterStateKeys.OUT_WATERMARK_ID, id)
                putStateLocked(db, SmsReporterStateKeys.OUT_WATERMARK_DATE, date)
                putStateLocked(db, SmsReporterStateKeys.COVERED_FROM, coveredFrom.toString())
            }
        }
    }

    fun clearReporterState() {
        call { db -> db.inTransaction { clearReporterLocked(db) } }
    }

    fun reporterState(): Map<String, String> = call { db ->
        db.query(TABLE_REPORTER_STATE, arrayOf("key", "value"), null, null, null, null, null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
    }

    fun putReporterState(values: Map<String, String>) {
        call { db -> db.inTransaction { values.forEach { (key, value) -> putStateLocked(db, key, value) } } }
    }

    // --- reporter (SmsReporterStore) ---

    override fun <T> atomically(block: () -> T): T = call { db -> db.inTransaction { block() } }

    override fun scanState(): SmsScanState? {
        val state = reporterState()
        val long = { key: String -> state[key]?.toLongOrNull() }
        return SmsScanState(
            inbound = SmsWatermark(
                long(SmsReporterStateKeys.INBOUND_WATERMARK_ID) ?: return null,
                long(SmsReporterStateKeys.INBOUND_WATERMARK_DATE) ?: return null,
            ),
            out = SmsWatermark(
                long(SmsReporterStateKeys.OUT_WATERMARK_ID) ?: return null,
                long(SmsReporterStateKeys.OUT_WATERMARK_DATE) ?: return null,
            ),
            coveredFrom = long(SmsReporterStateKeys.COVERED_FROM) ?: return null,
            recheck = SmsRecheckCodec.decode(state[SmsReporterStateKeys.OUT_RECHECK]),
        )
    }

    override fun seedScanState(latest: SmsProviderRow?, coveredFrom: Long) = seedReporterState(latest, coveredFrom)

    override fun isReported(msgId: String): Boolean = call { db ->
        DatabaseUtils.queryNumEntries(db, TABLE_REPORTED_IDS, "msg_id = ?", arrayOf(msgId)) > 0
    }

    override fun commitScan(state: SmsScanState, reports: List<SmsPendingReport>, now: Long) {
        call { db ->
            db.inTransaction {
                putStateLocked(db, SmsReporterStateKeys.INBOUND_WATERMARK_ID, state.inbound.id.toString())
                putStateLocked(db, SmsReporterStateKeys.INBOUND_WATERMARK_DATE, state.inbound.date.toString())
                putStateLocked(db, SmsReporterStateKeys.OUT_WATERMARK_ID, state.out.id.toString())
                putStateLocked(db, SmsReporterStateKeys.OUT_WATERMARK_DATE, state.out.date.toString())
                putStateLocked(db, SmsReporterStateKeys.OUT_RECHECK, SmsRecheckCodec.encode(state.recheck))
                if (reports.isEmpty()) return@inTransaction
                reports.forEach { report ->
                    db.insertWithOnConflict(TABLE_PENDING_REPORTS, null, report.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
                    val ring = ContentValues().apply {
                        put("msg_id", report.msgId)
                        put("reported_at", now)
                    }
                    db.insertWithOnConflict(TABLE_REPORTED_IDS, null, ring, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.execSQL(
                    "DELETE FROM $TABLE_REPORTED_IDS WHERE msg_id NOT IN (SELECT msg_id FROM $TABLE_REPORTED_IDS " +
                        "ORDER BY reported_at DESC LIMIT ${SmsStorePolicy.MAX_REPORTED_IDS})",
                )
                db.execSQL(
                    "DELETE FROM $TABLE_PENDING_REPORTS WHERE msg_id NOT IN (SELECT msg_id FROM $TABLE_PENDING_REPORTS " +
                        "ORDER BY first_queued_at DESC, provider_id DESC LIMIT ${SmsStorePolicy.MAX_PENDING_REPORTS})",
                )
            }
        }
    }

    override fun duePending(now: Long, limit: Int, exclude: Set<String>): List<SmsPendingReport> = call { db ->
        db.query(
            TABLE_PENDING_REPORTS, null, "next_attempt_at <= ?", arrayOf(now.toString()),
            null, null, "first_queued_at ASC, provider_id ASC", (limit + exclude.size).toString(),
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toPending() else null }.toList() }
            .filter { it.msgId !in exclude }
            .take(limit)
    }

    override fun nextPendingAt(exclude: Set<String>): Long? = call { db ->
        db.query(
            TABLE_PENDING_REPORTS, arrayOf("msg_id", "next_attempt_at"), null, null,
            null, null, "next_attempt_at ASC", (exclude.size + 1).toString(),
        ).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) to cursor.getLong(1) else null }
                .firstOrNull { it.first !in exclude }
                ?.second
        }
    }

    override fun updatePending(report: SmsPendingReport) {
        call { db -> db.update(TABLE_PENDING_REPORTS, report.toValues(), "msg_id = ?", arrayOf(report.msgId)) }
    }

    override fun deletePending(msgId: String) {
        call { db -> db.delete(TABLE_PENDING_REPORTS, "msg_id = ?", arrayOf(msgId)) }
    }

    // --- reset and recovery ---

    /** Settings reset: forget everything but send records younger than 24 h, which a retry may still ask about. */
    fun resetKeepingRecentAsync() {
        async("reset") { db ->
            db.inTransaction {
                val cutoff = (clock() - SmsStorePolicy.RESET_KEEP_MS).toString()
                db.delete(TABLE_RECORDS, "created_at < ?", arrayOf(cutoff))
                db.delete(TABLE_OWN_SENDS, "created_at < ?", arrayOf(cutoff))
                clearReporterLocked(db)
            }
        }
    }

    private fun recoverInterrupted() {
        val db = helper.writableDatabase
        val candidates = db.query(
            TABLE_RECORDS, null, "phase = ? AND created_at < ?",
            arrayOf(SmsSendPhase.PREPARED.wire, openedAt.toString()), null, null, "created_at ASC",
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toRecord() else null }.toList() }
        if (candidates.isEmpty()) return

        val now = clock()
        val own = ownSendIdsLocked(db).toMutableSet()
        val rows = if (hasReadSms()) {
            AndroidSmsProviderSource(appContext).ownSends(appContext.packageName, candidates.minOf { it.createdAt })
        } else {
            emptyList()
        }
        var retryAt: Long? = null
        candidates.forEach { record ->
            when (val decision = SmsSendRecovery.decide(record, now, openedAt, rows, own, appContext.packageName)) {
                is SmsRecoveryDecision.Wait -> retryAt = minOf(retryAt ?: decision.atMs, decision.atMs)
                is SmsRecoveryDecision.Resolve -> {
                    writeLocked(db, decision.record)
                    decision.providerId?.let {
                        recordOwnSendLocked(db, it, record.id)
                        own += it
                    }
                    if (decision.record.state != record.state) SmsBridgeSendStates.changed(decision.record)
                }
                SmsRecoveryDecision.None -> Unit
            }
        }
        retryAt?.let { at ->
            executor.schedule(
                Runnable { safely("recover") { recoverInterrupted() } },
                (at - now).coerceAtLeast(0L) + 50L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun hasReadSms(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    // --- locked helpers (store thread only) ---

    private fun findLocked(db: SQLiteDatabase, id: String): SmsSendRecord? =
        db.query(TABLE_RECORDS, null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }

    private fun updateLocked(
        db: SQLiteDatabase,
        id: String,
        transform: (SmsSendRecord) -> SmsSendRecord?,
    ): SmsSendRecord? {
        val current = findLocked(db, id) ?: return null
        val updated = transform(current) ?: return null
        writeLocked(db, updated)
        return updated
    }

    private fun writeLocked(db: SQLiteDatabase, record: SmsSendRecord) {
        db.update(TABLE_RECORDS, record.toValues(), "id = ?", arrayOf(record.id))
    }

    private fun recordOwnSendLocked(db: SQLiteDatabase, providerId: Long, recordId: String?) {
        val values = ContentValues().apply {
            put("provider_id", providerId)
            put("record_id", recordId)
            put("created_at", clock())
        }
        db.insertWithOnConflict(TABLE_OWN_SENDS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM $TABLE_OWN_SENDS WHERE created_at < ? OR provider_id NOT IN " +
                "(SELECT provider_id FROM $TABLE_OWN_SENDS ORDER BY created_at DESC LIMIT ${SmsStorePolicy.MAX_OWN_SEND_IDS})",
            arrayOf<Any>(clock() - SmsStorePolicy.RECORD_TTL_MS),
        )
    }

    private fun ownSendIdsLocked(db: SQLiteDatabase): Set<Long> =
        db.query(TABLE_OWN_SENDS, arrayOf("provider_id"), null, null, null, null, null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    private fun evictLocked(db: SQLiteDatabase) {
        val candidates = db.query(TABLE_RECORDS, arrayOf("id", "phase", "created_at"), null, null, null, null, null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SmsStorePolicy.EvictionCandidate(
                                id = cursor.getString(0),
                                terminal = cursor.getString(1) == SmsSendPhase.TERMINAL.wire,
                                createdAt = cursor.getLong(2),
                            ),
                        )
                    }
                }
            }
        SmsStorePolicy.evictions(candidates, clock()).forEach { db.delete(TABLE_RECORDS, "id = ?", arrayOf(it)) }
    }

    private fun clearReporterLocked(db: SQLiteDatabase) {
        db.delete(TABLE_REPORTER_STATE, null, null)
        db.delete(TABLE_PENDING_REPORTS, null, null)
        db.delete(TABLE_REPORTED_IDS, null, null)
    }

    private fun putStateLocked(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(TABLE_REPORTER_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
        beginTransaction()
        try {
            val result = block()
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    private inline fun safely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$name failed (${e.javaClass.simpleName})")
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_RECORDS (
                    id TEXT PRIMARY KEY NOT NULL,
                    origin TEXT NOT NULL,
                    to_address TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    body_hash TEXT NOT NULL,
                    subscription_id INTEGER,
                    sim_slot INTEGER,
                    delivery_report INTEGER NOT NULL,
                    phase TEXT NOT NULL,
                    state TEXT NOT NULL,
                    parts INTEGER NOT NULL,
                    sent_parts TEXT NOT NULL DEFAULT '',
                    delivered_parts TEXT NOT NULL DEFAULT '',
                    callback_token TEXT NOT NULL,
                    message_uri TEXT,
                    error_code TEXT,
                    error_message TEXT,
                    last_reported_state TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX send_records_created_at ON $TABLE_RECORDS(created_at)")
            db.execSQL(
                "CREATE TABLE $TABLE_OWN_SENDS (provider_id INTEGER PRIMARY KEY NOT NULL, record_id TEXT, " +
                    "created_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE $TABLE_REPORTED_IDS (msg_id TEXT PRIMARY KEY NOT NULL, reported_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE $TABLE_REPORTER_STATE (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL(
                """
                CREATE TABLE $TABLE_PENDING_REPORTS (
                    msg_id TEXT PRIMARY KEY NOT NULL,
                    provider_id INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    unconfirmed INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL,
                    first_queued_at INTEGER NOT NULL,
                    last_error TEXT
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val TAG = "SmsStore"
        const val DB_NAME = "mobilerun_sms.db"
        private const val DB_VERSION = 1
        private const val CALL_TIMEOUT_SECONDS = 10L

        const val TABLE_RECORDS = "send_records"
        const val TABLE_OWN_SENDS = "own_send_ids"
        const val TABLE_REPORTED_IDS = "reported_ids"
        const val TABLE_REPORTER_STATE = "reporter_state"
        const val TABLE_PENDING_REPORTS = "pending_reports"

        @Volatile
        private var instance: SmsStore? = null

        fun getInstance(context: Context): SmsStore =
            instance ?: synchronized(this) {
                instance ?: SmsStore(context.applicationContext).also { instance = it }
            }

        /** The store, without creating a database that was never used. */
        fun existing(context: Context): SmsStore? =
            instance ?: if (context.getDatabasePath(DB_NAME).exists()) getInstance(context) else null

        internal fun encodeParts(parts: Set<Int>): String = parts.sorted().joinToString(",")

        internal fun decodeParts(raw: String?): Set<Int> =
            raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

        private fun SmsSendRecord.toValues(): ContentValues = ContentValues().apply {
            put("id", id)
            put("origin", origin)
            put("to_address", to)
            put("fingerprint", fingerprint)
            put("body_hash", bodyHash)
            put("subscription_id", subscriptionId)
            put("sim_slot", simSlot)
            put("delivery_report", if (deliveryReport) 1 else 0)
            put("phase", phase.wire)
            put("state", state.wire)
            put("parts", parts)
            put("sent_parts", encodeParts(sentParts))
            put("delivered_parts", encodeParts(deliveredParts))
            put("callback_token", callbackToken)
            put("message_uri", messageUri)
            put("error_code", errorCode)
            put("error_message", errorMessage)
            put("last_reported_state", lastReportedState)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }

        private fun SmsPendingReport.toValues(): ContentValues = ContentValues().apply {
            put("msg_id", msgId)
            put("provider_id", providerId)
            put("direction", direction)
            put("attempts", attempts)
            put("unconfirmed", unconfirmed)
            put("next_attempt_at", nextAttemptAt)
            put("first_queued_at", firstQueuedAt)
            put("last_error", lastError)
        }

        private fun Cursor.toPending(): SmsPendingReport =
            SmsPendingReport(
                msgId = string("msg_id")!!,
                providerId = long("provider_id") ?: 0L,
                direction = string("direction")!!,
                attempts = int("attempts") ?: 0,
                unconfirmed = int("unconfirmed") ?: 0,
                nextAttemptAt = long("next_attempt_at") ?: 0L,
                firstQueuedAt = long("first_queued_at") ?: 0L,
                lastError = string("last_error"),
            )

        private fun Cursor.toRecord(): SmsSendRecord =
            SmsSendRecord(
                id = string("id")!!,
                origin = string("origin")!!,
                to = string("to_address")!!,
                fingerprint = string("fingerprint")!!,
                bodyHash = string("body_hash")!!,
                subscriptionId = int("subscription_id"),
                simSlot = int("sim_slot"),
                deliveryReport = int("delivery_report") == 1,
                phase = SmsSendPhase.fromWire(string("phase")),
                state = smsSendStateFromWire(string("state")),
                parts = int("parts") ?: 1,
                sentParts = decodeParts(string("sent_parts")),
                deliveredParts = decodeParts(string("delivered_parts")),
                callbackToken = string("callback_token")!!,
                messageUri = string("message_uri"),
                errorCode = string("error_code"),
                errorMessage = string("error_message"),
                lastReportedState = string("last_reported_state"),
                createdAt = long("created_at") ?: 0L,
                updatedAt = long("updated_at") ?: 0L,
            )

        private fun Cursor.string(column: String): String? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getString(index)
        }

        private fun Cursor.int(column: String): Int? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getInt(index)
        }

        private fun Cursor.long(column: String): Long? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getLong(index)
        }
    }
}
