package com.mobilerun.portal.sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
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
    const val COVERED_FROM_ID = "covered_from_id"
    const val NEW_ROWS_FROM = "new_rows_from"
    const val OUT_RECHECK = "out_recheck"
    const val INBOUND_RECOVERY = "inbound_recovery"
    const val OUT_RECOVERY = "out_recovery"
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

    private val retry = SmsStoreRetry(
        schedule = { delayMs, task -> executor.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS) },
        log = { Log.w(TAG, it) },
    )

    private val discards = SmsPreparedDiscards(retry) { id, callbackToken ->
        helper.writableDatabase.delete(
            TABLE_RECORDS, "id = ? AND phase = ? AND callback_token = ?",
            arrayOf(id, SmsSendPhase.PREPARED.wire, callbackToken),
        )
    }

    /** Stamped on inserted records: prepared records of any other instance are an earlier process's. */
    private val instanceId: String = UUID.randomUUID().toString()
    private val openedElapsed: Long = SystemClock.elapsedRealtime()

    // Nothing else resolves an earlier process's prepared records, so recovery never gives up.
    init {
        executor.execute { retry.run("recover", untilDone = true) { recoverInterrupted() } }
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

    /**
     * Runs [block] on the store thread, then [done] (for BroadcastReceiver.goAsync). A [retried]
     * block that fails runs again later, after [done], and with [untilDone] never gives up.
     */
    fun async(
        name: String,
        done: () -> Unit = {},
        retried: Boolean = false,
        untilDone: Boolean = false,
        block: (SQLiteDatabase) -> Unit,
    ) {
        try {
            executor.execute {
                try {
                    if (retried) {
                        retry.run(name, untilDone) { block(helper.writableDatabase) }
                    } else {
                        safely(name) { block(helper.writableDatabase) }
                    }
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

    override fun find(id: String): SmsSendRecord? = call { db -> discards.find(id) { findLocked(db, id) } }

    override fun insert(record: SmsSendRecord): Boolean = call { db ->
        db.inTransaction {
            val values = record.copy(instanceId = instanceId, createdElapsed = SystemClock.elapsedRealtime()).toValues()
            val row = db.insertWithOnConflict(TABLE_RECORDS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (row != -1L) evictLocked(db)
            row != -1L
        }
    }

    /** Queued behind any insert still pending after a timeout or interrupt, so it always sees it. */
    override fun discardPrepared(id: String, callbackToken: String) {
        async("discard") { discards.discard(id, callbackToken) }
    }

    /** SmsManager took the send, so a failed write is queued until it lands: a restart would fail it as interrupted. */
    override fun markDispatched(id: String, now: Long): SmsSendRecord? =
        try {
            call { db -> markDispatchedLocked(db, id, now) }
        } catch (e: Exception) {
            async("dispatch", retried = true, untilDone = true) { db -> markDispatchedLocked(db, id, now) }
            throw e
        }

    /** Prepared records only: a callback that landed first is never set back. */
    private fun markDispatchedLocked(db: SQLiteDatabase, id: String, now: Long): SmsSendRecord? =
        updateLocked(db, id) { it.withDispatched(now) }

    /** No callback follows a send that threw, so a failed write is queued again rather than left prepared. */
    override fun markFailed(id: String, code: String, message: String, now: Long): SmsSendRecord? =
        try {
            call { db -> markFailedLocked(db, id, code, message, now) }
        } catch (e: Exception) {
            async("fail", retried = true, untilDone = true) { db -> markFailedLocked(db, id, code, message, now) }
            throw e
        }

    private fun markFailedLocked(db: SQLiteDatabase, id: String, code: String, message: String, now: Long): SmsSendRecord? =
        updateLocked(db, id) { it.withFailure(code, message, now) }?.also(SmsBridgeSendStates::changed)

    /**
     * Applies a sent/delivered result on the store thread; also records the provider row as the Portal's own.
     * Nothing else resolves a dispatched record, so a failed write never gives up.
     */
    internal fun applyCallbackAsync(callback: SmsSendCallback, done: () -> Unit) {
        async("callback", done, retried = true, untilDone = true) { db ->
            val providerId = SmsSendCallbacks.providerIdFromUri(callback.providerUri)
            val rowDate = providerId?.let(::providerRowDate)
            // One transaction, so a retry after a failure starts from the unchanged record.
            val changed = db.inTransaction {
                val before = findLocked(db, callback.recordId) ?: return@inTransaction null
                val updated = updateLocked(db, callback.recordId) { SmsSendCallbacks.apply(it, callback, clock()) }
                providerId?.let { recordOwnSendLocked(db, it, callback.recordId, rowDate) }
                updated?.takeIf { it.state != before.state }
            }
            changed?.let(SmsBridgeSendStates::changed)
        }
    }

    /** Relay records whose current state was not reported yet. */
    override fun unreportedRelayRecords(): List<SmsUnreportedSend> = call { db ->
        db.query(
            TABLE_RECORDS, null,
            "origin = ? AND (last_reported_state IS NULL OR last_reported_state != state)",
            arrayOf(SmsSendRecord.ORIGIN_RELAY), null, null, "updated_at ASC",
        ).use { cursor ->
            generateSequence { if (cursor.moveToNext()) SmsUnreportedSend(cursor.toRecord(), cursor.toRetry()) else null }
                .toList()
        }
    }

    override fun saveSendStateRetry(id: String, retry: SmsSendStateRetry) {
        call { db ->
            val values = ContentValues().apply {
                put("report_state", retry.state.wire)
                put("report_attempts", retry.attempts)
                put("report_unconfirmed", retry.unconfirmed)
                put("report_next_attempt_at", retry.nextAttemptAt)
                put("report_first_queued_at", retry.firstQueuedAt)
            }
            db.update(TABLE_RECORDS, values, "id = ?", arrayOf(id))
        }
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
        async("own-send", done, retried = true) { db ->
            recordOwnSendLocked(db, providerId, recordId, providerRowDate(providerId))
        }
    }

    override fun ownSends(): SmsOwnSends = call { db -> ownSendsLocked(db) }

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
                putStateLocked(db, SmsReporterStateKeys.COVERED_FROM_ID, id)
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
            coveredFromId = long(SmsReporterStateKeys.COVERED_FROM_ID) ?: return null,
            recheck = SmsRecheckCodec.decode(state[SmsReporterStateKeys.OUT_RECHECK]),
            inboundRecovery = SmsRecoveryCodec.decode(state[SmsReporterStateKeys.INBOUND_RECOVERY]),
            outRecovery = SmsRecoveryCodec.decode(state[SmsReporterStateKeys.OUT_RECOVERY]),
            newRowsFrom = long(SmsReporterStateKeys.NEW_ROWS_FROM),
        )
    }

    override fun seedScanState(latest: SmsProviderRow?, coveredFrom: Long) = seedReporterState(latest, coveredFrom)

    override fun isReported(msgId: String): Boolean = call { db ->
        DatabaseUtils.queryNumEntries(db, TABLE_REPORTED_IDS, "msg_id = ?", arrayOf(msgId)) > 0
    }

    override fun pendingCount(): Int = call { db -> DatabaseUtils.queryNumEntries(db, TABLE_PENDING_REPORTS).toInt() }

    override fun commitScan(state: SmsScanState, reports: List<SmsPendingReport>, now: Long) {
        call { db ->
            db.inTransaction {
                putStateLocked(db, SmsReporterStateKeys.INBOUND_WATERMARK_ID, state.inbound.id.toString())
                putStateLocked(db, SmsReporterStateKeys.INBOUND_WATERMARK_DATE, state.inbound.date.toString())
                putStateLocked(db, SmsReporterStateKeys.OUT_WATERMARK_ID, state.out.id.toString())
                putStateLocked(db, SmsReporterStateKeys.OUT_WATERMARK_DATE, state.out.date.toString())
                putStateLocked(db, SmsReporterStateKeys.COVERED_FROM, state.coveredFrom.toString())
                putStateLocked(db, SmsReporterStateKeys.COVERED_FROM_ID, state.coveredFromId.toString())
                putStateLocked(db, SmsReporterStateKeys.OUT_RECHECK, SmsRecheckCodec.encode(state.recheck))
                putStateLocked(db, SmsReporterStateKeys.INBOUND_RECOVERY, SmsRecoveryCodec.encode(state.inboundRecovery))
                putStateLocked(db, SmsReporterStateKeys.OUT_RECOVERY, SmsRecoveryCodec.encode(state.outRecovery))
                putStateLocked(db, SmsReporterStateKeys.NEW_ROWS_FROM, state.newRowsFrom?.toString().orEmpty())
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
        val now = clock()
        val candidates = db.query(
            TABLE_RECORDS, null, "phase IN (?, ?) AND instance_id != ?",
            arrayOf(SmsSendPhase.PREPARED.wire, SmsSendPhase.DISPATCHED.wire, instanceId), null, null, "created_at ASC",
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toRecord() else null }.toList() }
            .filter { SmsSendRecovery.isCandidate(it, instanceId, now) }
        if (candidates.isEmpty()) return

        val elapsedNow = SystemClock.elapsedRealtime()
        val sinceOpenMs = elapsedNow - openedElapsed
        var own = ownSendsLocked(db)
        val rows = if (hasReadSms()) {
            val source = AndroidSmsProviderSource(appContext)
            val since = candidates.minOf { SmsSendRecovery.evidenceFrom(it, now, elapsedNow) }
            SmsSendRecovery.evidence(candidates) { after, limit ->
                source.ownSends(appContext.packageName, since, after, limit)
            }
        } else {
            null
        }
        var retryAt: Long? = null
        candidates.forEach { record ->
            val decision =
                SmsSendRecovery.decide(record, now, elapsedNow, instanceId, sinceOpenMs, rows, own, appContext.packageName)
            when (decision) {
                is SmsRecoveryDecision.Wait -> retryAt = minOf(retryAt ?: decision.atMs, decision.atMs)
                is SmsRecoveryDecision.Resolve -> {
                    val rowDate = rows?.firstOrNull { it.id == decision.providerId }?.date
                    db.inTransaction {
                        writeLocked(db, decision.record)
                        decision.providerId?.let { recordOwnSendLocked(db, it, record.id, rowDate) }
                    }
                    decision.providerId?.let { own = own.plus(it, clock(), rowDate) }
                    if (decision.record.state != record.state) SmsBridgeSendStates.changed(decision.record)
                    decision.recheckAt?.let { at -> retryAt = minOf(retryAt ?: at, at) }
                }
                SmsRecoveryDecision.None -> Unit
            }
        }
        retryAt?.let { at ->
            executor.schedule(
                Runnable { retry.run("recover", untilDone = true) { recoverInterrupted() } },
                (at - now).coerceAtLeast(0L) + 50L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun hasReadSms(): Boolean = isGranted(appContext, Manifest.permission.READ_SMS)

    /** The date of a send's row, binding its own-send marker to that row; null when it cannot be read. */
    private fun providerRowDate(providerId: Long): Long? =
        runCatching { if (hasReadSms()) AndroidSmsProviderSource(appContext).get(providerId)?.date else null }.getOrNull()

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

    private fun recordOwnSendLocked(db: SQLiteDatabase, providerId: Long, recordId: String?, rowDate: Long?) {
        val values = ContentValues().apply {
            put("provider_id", providerId)
            put("record_id", recordId)
            put("created_at", clock())
            put("row_date", rowDate)
        }
        db.insertWithOnConflict(TABLE_OWN_SENDS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM $TABLE_OWN_SENDS WHERE created_at < ? OR provider_id NOT IN " +
                "(SELECT provider_id FROM $TABLE_OWN_SENDS ORDER BY created_at DESC LIMIT ${SmsStorePolicy.MAX_OWN_SEND_IDS})",
            arrayOf<Any>(clock() - SmsStorePolicy.RECORD_TTL_MS),
        )
    }

    private fun ownSendsLocked(db: SQLiteDatabase): SmsOwnSends =
        db.query(TABLE_OWN_SENDS, arrayOf("provider_id", "created_at", "row_date"), null, null, null, null, null).use { cursor ->
            val recordedAt = HashMap<Long, Long>()
            val rowDates = HashMap<Long, Long>()
            while (cursor.moveToNext()) {
                recordedAt[cursor.getLong(0)] = cursor.getLong(1)
                if (!cursor.isNull(2)) rowDates[cursor.getLong(0)] = cursor.getLong(2)
            }
            SmsOwnSends(recordedAt, rowDates)
        }

    private fun evictLocked(db: SQLiteDatabase) {
        val columns = arrayOf("id", "phase", "state", "created_at", "origin", "last_reported_state", "updated_at")
        val candidates = db.query(TABLE_RECORDS, columns, null, null, null, null, null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SmsStorePolicy.EvictionCandidate(
                                id = cursor.getString(0),
                                terminal = cursor.getString(1) == SmsSendPhase.TERMINAL.wire,
                                createdAt = cursor.getLong(3),
                                awaitingDelivery = cursor.getString(2) == SmsSendState.SENT.wire,
                                unreported = cursor.getString(4) == SmsSendRecord.ORIGIN_RELAY &&
                                    cursor.getString(5) != cursor.getString(2),
                                updatedAt = cursor.getLong(6),
                            ),
                        )
                    }
                }
            }
        // Rolls the insert back rather than drop an outstanding send or a recent unreported state.
        val evicted = SmsStorePolicy.evictions(candidates, clock()) ?: throw SmsStoreFullException()
        evicted.forEach { db.delete(TABLE_RECORDS, "id = ?", arrayOf(it)) }
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
                    ${SEND_STATE_RETRY_COLUMNS.joinToString(", ")},
                    $INSTANCE_ID_COLUMN,
                    $CREATED_ELAPSED_COLUMN,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX send_records_created_at ON $TABLE_RECORDS(created_at)")
            db.execSQL(
                "CREATE TABLE $TABLE_OWN_SENDS (provider_id INTEGER PRIMARY KEY NOT NULL, record_id TEXT, " +
                    "created_at INTEGER NOT NULL, $OWN_ROW_DATE_COLUMN)",
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

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                SEND_STATE_RETRY_COLUMNS.forEach { db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $it") }
            }
            if (oldVersion < 3) db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $INSTANCE_ID_COLUMN")
            if (oldVersion < 4) db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $CREATED_ELAPSED_COLUMN")
            if (oldVersion < 5) db.execSQL("ALTER TABLE $TABLE_OWN_SENDS ADD COLUMN $OWN_ROW_DATE_COLUMN")
        }
    }

    companion object {
        private const val TAG = "SmsStore"
        const val DB_NAME = "mobilerun_sms.db"
        private const val DB_VERSION = 5
        private const val CALL_TIMEOUT_SECONDS = 10L

        const val TABLE_RECORDS = "send_records"
        const val TABLE_OWN_SENDS = "own_send_ids"
        const val TABLE_REPORTED_IDS = "reported_ids"
        const val TABLE_REPORTER_STATE = "reporter_state"
        const val TABLE_PENDING_REPORTS = "pending_reports"

        /** send_records columns added in version 2: retry bookkeeping of the unreported sms/sendState. */
        private val SEND_STATE_RETRY_COLUMNS = listOf(
            "report_state TEXT",
            "report_attempts INTEGER NOT NULL DEFAULT 0",
            "report_unconfirmed INTEGER NOT NULL DEFAULT 0",
            "report_next_attempt_at INTEGER",
            "report_first_queued_at INTEGER",
        )

        /** Added in version 3; existing rows get '' and so count as an earlier process's. */
        private const val INSTANCE_ID_COLUMN = "instance_id TEXT NOT NULL DEFAULT ''"

        /** Added in version 4; existing rows get 0 and so fall back to the wall clock. */
        private const val CREATED_ELAPSED_COLUMN = "created_elapsed INTEGER NOT NULL DEFAULT 0"

        /** own_send_ids column added in version 5; existing markers get NULL and so match by when they were recorded. */
        private const val OWN_ROW_DATE_COLUMN = "row_date INTEGER"

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
            put("instance_id", instanceId)
            put("created_elapsed", createdElapsed)
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

        private fun Cursor.toRetry(): SmsSendStateRetry? {
            val state = string("report_state") ?: return null
            return SmsSendStateRetry(
                state = smsSendStateFromWire(state),
                attempts = int("report_attempts") ?: 0,
                unconfirmed = int("report_unconfirmed") ?: 0,
                nextAttemptAt = long("report_next_attempt_at") ?: return null,
                firstQueuedAt = long("report_first_queued_at") ?: return null,
            )
        }

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
                instanceId = string("instance_id").orEmpty(),
                createdElapsed = long("created_elapsed") ?: 0L,
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
