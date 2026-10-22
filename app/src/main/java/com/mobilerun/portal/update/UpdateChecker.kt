package com.mobilerun.portal.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.mobilerun.portal.BuildConfig
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class UpdateInfo(
    val latestVersion: String,
    val versionCode: Long,
    val packageName: String,
    val apkUrl: String,
    val sha256: String,
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Failed(val message: String, val httpStatus: Int? = null) : UpdateCheckResult()
}

sealed class InstallResult {
    data class Done(val success: Boolean, val message: String) : InstallResult()
    data class SignatureConflict(
        val apkSavedToDownloads: Boolean,
        val apkUrl: String?,
    ) : InstallResult()
}

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val EXPECTED_PACKAGE_NAME = BuildConfig.APPLICATION_ID
    private const val PREFS_NAME = "portal_update"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_CACHED_VERSION = "cached_version"
    private const val KEY_CACHED_VERSION_CODE = "cached_version_code"
    private const val KEY_CACHED_PACKAGE_NAME = "cached_package_name"
    private const val KEY_CACHED_APK_URL = "cached_apk_url"
    private const val KEY_CACHED_SHA256 = "cached_sha256"
    private const val KEY_RELAUNCH_REQUESTED_MS = "relaunch_requested_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val RELAUNCH_REQUEST_TTL_MS = 10 * 60 * 1000L
    const val APK_CACHE_FILENAME = "portal-update.apk"

    @Volatile
    var pendingInstallResult: InstallResult? = null

    @Volatile
    var pendingInstallApkUrl: String? = null

    private val updateInstallInProgress = AtomicBoolean(false)
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val apiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    fun checkOnStartupIfNeeded(
        context: Context,
        callback: (UpdateCheckResult) -> Unit,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = getCachedUpdate(context)
        if (cached != null && now - prefs.getLong(KEY_LAST_CHECK_MS, 0L) < CHECK_INTERVAL_MS) {
            callback(UpdateCheckResult.Available(cached))
            return
        }
        if (now - prefs.getLong(KEY_LAST_CHECK_MS, 0L) < CHECK_INTERVAL_MS) {
            callback(UpdateCheckResult.UpToDate)
            return
        }
        checkForUpdate(context, callback)
    }

    fun checkForUpdate(
        context: Context,
        callback: (UpdateCheckResult) -> Unit,
    ) {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_FEED_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "mobilerun-portal/${getInstalledVersion(context)}")
            .build()

        apiClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    callback(UpdateCheckResult.Failed("Network error: ${e.message}"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        mainHandler.post {
                            callback(UpdateCheckResult.Failed("Check failed: HTTP ${response.code}", response.code))
                        }
                        return
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        mainHandler.post { callback(UpdateCheckResult.Failed("Empty response")) }
                        return
                    }

                    val result = try {
                        val info = parseFeed(
                            body,
                            expectedPackageName = EXPECTED_PACKAGE_NAME,
                            allowLocalHttp = BuildConfig.DEBUG,
                        )
                        if (isNewerThanInstalled(context, info)) {
                            cacheUpdate(context, info)
                            UpdateCheckResult.Available(info)
                        } else {
                            clearCachedUpdate(context)
                            UpdateCheckResult.UpToDate
                        }
                    } catch (e: IllegalArgumentException) {
                        UpdateCheckResult.Failed(e.message ?: "Invalid update feed")
                    } catch (e: Exception) {
                        Log.e(TAG, "Update check failed", e)
                        UpdateCheckResult.Failed("Parse error: ${e.message}")
                    }
                    if (result !is UpdateCheckResult.Failed) {
                        markChecked(context)
                    }
                    mainHandler.post { callback(result) }
                }
            }
        })
    }

    fun getCachedUpdate(context: Context): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_CACHED_VERSION, null) ?: return null
        val packageName = prefs.getString(KEY_CACHED_PACKAGE_NAME, null) ?: return null
        val apkUrl = prefs.getString(KEY_CACHED_APK_URL, null) ?: return null
        val sha256 = prefs.getString(KEY_CACHED_SHA256, null) ?: return null
        val info = UpdateInfo(
            latestVersion = version,
            versionCode = prefs.getLong(KEY_CACHED_VERSION_CODE, -1L),
            packageName = packageName,
            apkUrl = apkUrl,
            sha256 = sha256,
        )
        return if (isNewerThanInstalled(context, info)) info else null
    }

    fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!updateInstallInProgress.compareAndSet(false, true)) {
            return
        }

        downloadExecutor.execute {
            pendingInstallApkUrl = updateInfo.apkUrl
            if (!context.packageManager.canRequestPackageInstalls()) {
                pendingInstallApkUrl = null
                clearActiveInstallState()
                mainHandler.post { onError("Install permission is not enabled") }
                return@execute
            }

            val apkFile = File(context.cacheDir, APK_CACHE_FILENAME)
            try {
                val request = Request.Builder()
                    .url(updateInfo.apkUrl)
                    .header("User-Agent", "mobilerun-portal/${getInstalledVersion(context)}")
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        pendingInstallApkUrl = null
                        clearActiveInstallState()
                        mainHandler.post { onError("Download failed: HTTP ${response.code}") }
                        return@execute
                    }

                    val body = response.body ?: run {
                        pendingInstallApkUrl = null
                        clearActiveInstallState()
                        mainHandler.post { onError("Empty download response") }
                        return@execute
                    }

                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastProgress = -1
                    apkFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        mainHandler.post { onProgress(progress) }
                                    }
                                }
                            }
                        }
                    }

                    if (!matchesSha256(apkFile, updateInfo.sha256)) {
                        apkFile.delete()
                        pendingInstallApkUrl = null
                        clearActiveInstallState()
                        mainHandler.post { onError("Downloaded APK failed checksum verification") }
                        return@execute
                    }

                    val archivePackageName = getArchivePackageName(context, apkFile)
                    if (archivePackageName != EXPECTED_PACKAGE_NAME) {
                        apkFile.delete()
                        pendingInstallApkUrl = null
                        clearActiveInstallState()
                        val errorMessage = if (archivePackageName == null) {
                            "Downloaded APK package could not be verified"
                        } else {
                            "Downloaded APK package mismatch: expected $EXPECTED_PACKAGE_NAME, got $archivePackageName"
                        }
                        mainHandler.post { onError(errorMessage) }
                        return@execute
                    }

                    mainHandler.post { onProgress(100) }
                    commitInstallSession(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update download/install failed", e)
                apkFile.delete()
                pendingInstallApkUrl = null
                clearActiveInstallState()
                clearRelaunchAfterUpdate(context)
                mainHandler.post { onError("Update failed: ${e.message}") }
            }
        }
    }

    fun isUpdateInstallInProgress(): Boolean = updateInstallInProgress.get()

    fun clearActiveInstallState() {
        updateInstallInProgress.set(false)
    }

    fun requestRelaunchAfterUpdate(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_RELAUNCH_REQUESTED_MS, System.currentTimeMillis())
            .apply()
    }

    fun clearRelaunchAfterUpdate(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RELAUNCH_REQUESTED_MS)
            .apply()
    }

    fun consumeRelaunchAfterUpdate(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requestedAt = prefs.getLong(KEY_RELAUNCH_REQUESTED_MS, 0L)
        if (requestedAt == 0L) return false

        prefs.edit().remove(KEY_RELAUNCH_REQUESTED_MS).apply()
        val ageMs = System.currentTimeMillis() - requestedAt
        return ageMs in 0..RELAUNCH_REQUEST_TTL_MS
    }

    fun parseFeed(
        rawJson: String,
        expectedPackageName: String = EXPECTED_PACKAGE_NAME,
        allowLocalHttp: Boolean = false,
    ): UpdateInfo {
        val json = JSONObject(rawJson)
        val version = json.optString("version").trim().removePrefix("v")
        require(version.isNotBlank()) { "Update feed is missing version" }

        val versionCode = json.optLong("versionCode", -1L)
        require(versionCode > 0) { "Update feed is missing versionCode" }

        val packageName = json.optString("packageName").trim()
        require(packageName == expectedPackageName) {
            "Update package mismatch: expected $expectedPackageName, got $packageName"
        }

        val apkUrl = json.optString("apkUrl").trim()
        require(apkUrl.isNotBlank()) { "Update feed is missing apkUrl" }
        require(isAllowedApkUrl(apkUrl, allowLocalHttp)) { "Update APK URL must use HTTPS" }

        val sha256 = json.optString("sha256").trim().lowercase(Locale.US)
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Update feed is missing a valid sha256"
        }

        return UpdateInfo(version, versionCode, packageName, apkUrl, sha256)
    }

    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matchesSha256(file: File, expectedSha256: String): Boolean {
        return sha256Hex(file).equals(expectedSha256, ignoreCase = true)
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun saveCachedApkToDownloads(context: Context): Uri? {
        val cacheFile = File(context.cacheDir, APK_CACHE_FILENAME)
        if (!cacheFile.exists()) return null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "MobilerunPortal-update.apk")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val written = resolver.openOutputStream(uri)?.use { output ->
                    cacheFile.inputStream().use { input -> input.copyTo(output) }
                    true
                } ?: false
                if (!written) {
                    resolver.delete(uri, null, null)
                    return null
                }
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, publishValues, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val output = File(downloadsDir, "MobilerunPortal-update.apk")
                cacheFile.copyTo(output, overwrite = true)
                Uri.fromFile(output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cached APK to Downloads", e)
            null
        }
    }

    private fun commitInstallSession(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)

        packageInstaller.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE,
            )
            requestRelaunchAfterUpdate(context)
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun getArchivePackageName(context: Context, apkFile: File): String? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
        return packageInfo?.packageName
    }

    private fun isAllowedApkUrl(url: String, allowLocalHttp: Boolean): Boolean {
        return try {
            val uri = URI(url)
            when (uri.scheme?.lowercase(Locale.US)) {
                "https" -> true
                "http" -> allowLocalHttp && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isNewerThanInstalled(context: Context, info: UpdateInfo): Boolean {
        val currentVersionCode = getInstalledVersionCode(context)
        if (currentVersionCode > 0 && info.versionCode > 0) {
            return info.versionCode > currentVersionCode
        }
        return isNewerVersion(info.latestVersion, getInstalledVersion(context))
    }

    private fun getInstalledVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun getInstalledVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun cacheUpdate(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CACHED_VERSION, info.latestVersion)
            .putLong(KEY_CACHED_VERSION_CODE, info.versionCode)
            .putString(KEY_CACHED_PACKAGE_NAME, info.packageName)
            .putString(KEY_CACHED_APK_URL, info.apkUrl)
            .putString(KEY_CACHED_SHA256, info.sha256)
            .apply()
    }

    private fun clearCachedUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CACHED_VERSION)
            .remove(KEY_CACHED_VERSION_CODE)
            .remove(KEY_CACHED_PACKAGE_NAME)
            .remove(KEY_CACHED_APK_URL)
            .remove(KEY_CACHED_SHA256)
            .apply()
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
            .apply()
    }
}
