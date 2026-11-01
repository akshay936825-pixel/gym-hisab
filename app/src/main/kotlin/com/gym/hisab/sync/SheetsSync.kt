package com.gym.hisab.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gym.hisab.BuildConfig
import com.gym.hisab.data.local.HisabDb
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object SheetsSyncConfig {
    /**
     * MASTER SWITCH. Stays false until the owner has approved the migration/reconciliation report.
     * While false, nothing is scheduled, nothing is sent, and any work queued by an older build is cancelled.
     */
    val ENABLED: Boolean = false

    val json = Json { encodeDefaults = true; explicitNulls = false }

    // Injected at build time (gradle property or environment variable). Never committed to source.
    val ENDPOINT: String get() = BuildConfig.SHEETS_ENDPOINT
    val TOKEN: String get() = BuildConfig.SHEETS_TOKEN

    const val PERIODIC_WORK = "gym_hisab_sheets_periodic"
    const val IMMEDIATE_WORK = "gym_hisab_sheets_immediate"
}

object SheetsSyncScheduler {
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!SheetsSyncConfig.ENABLED) {
            wm.cancelUniqueWork(SheetsSyncConfig.PERIODIC_WORK)
            wm.cancelUniqueWork(SheetsSyncConfig.IMMEDIATE_WORK)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<SheetsSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints()).build()
        wm.enqueueUniquePeriodicWork(SheetsSyncConfig.PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
        requestNow(context)
    }

    fun requestNow(context: Context) {
        if (!SheetsSyncConfig.ENABLED) return
        val request = OneTimeWorkRequestBuilder<SheetsSyncWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SheetsSyncConfig.IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request
        )
    }
}

/**
 * Legacy full-snapshot upload, kept only so it can be re-enabled after approval.
 * NOT safe for the existing Sheet as written (see docs/MIGRATION_PLAN.md): do not enable it.
 */
class SheetsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SheetsSyncConfig.ENABLED) return Result.success()
        if (SheetsSyncConfig.ENDPOINT.isBlank() || SheetsSyncConfig.TOKEN.isBlank()) return Result.failure()
        return try {
            val database = HisabDb.create(applicationContext)
            val dao = database.dao()
            val payload = buildJsonObject {
                put("action", "syncSnapshot")
                put("token", SheetsSyncConfig.TOKEN)
                put("data", buildJsonObject {
                    put("plans", SheetsSyncConfig.json.encodeToJsonElement(dao.allPlans()))
                    put("members", SheetsSyncConfig.json.encodeToJsonElement(dao.allMembers()))
                    put("memberships", SheetsSyncConfig.json.encodeToJsonElement(dao.allMemberships()))
                    put("payments", SheetsSyncConfig.json.encodeToJsonElement(dao.allPayments()))
                    put("expenses", SheetsSyncConfig.json.encodeToJsonElement(dao.allExpenses()))
                    put("attendance", SheetsSyncConfig.json.encodeToJsonElement(dao.allAttendance()))
                })
            }
            database.close()
            val connection = (URL(SheetsSyncConfig.ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val body = Json.encodeToString(payload)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (status !in 200..299) return if (status >= 500 || status == 429) Result.retry() else Result.failure()
            val result = try { Json.parseToJsonElement(response).jsonObject } catch (_: Exception) { null }
            val ok = result?.get("ok")?.jsonPrimitive?.content == "true" &&
                result?.get("protocol")?.jsonPrimitive?.content == "gym-hisab-v2"
            if (ok) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
