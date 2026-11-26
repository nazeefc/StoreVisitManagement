package com.example.storevisitmanagement.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.storevisitmanagement.data.SessionManager
import java.util.concurrent.TimeUnit

object SyncManager {
    private const val SYNC_WORK_NAME_PREFIX = "StoreVisitSyncWork_"
    private const val SYNC_WORK_TAG = "store_visit_sync"

    const val KEY_EMPLOYEE_ID = "employee_id"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun enqueueSyncWork(context: Context) {
        init(context)
        val employee = SessionManager.getEmployee() ?: return
        val employeeId = employee.id
        if (SessionManager.getSessionSnapshotForEmployee(employeeId) == null) {
            // Do not schedule work without a valid employee session
            return
        }
        val workName = "$SYNC_WORK_NAME_PREFIX$employeeId"

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Persist only employee_id — never store auth tokens in the WorkManager database
        val inputData = workDataOf(KEY_EMPLOYEE_ID to employeeId)

        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag(SYNC_WORK_TAG)
            .addTag(workName)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncWorkRequest
        )
    }

    fun cancelSyncWorkForEmployee(context: Context, employeeId: String) {
        init(context)
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("$SYNC_WORK_NAME_PREFIX$employeeId")
    }

    fun cancelAllSyncWork(context: Context? = null) {
        val ctx = context?.applicationContext ?: appContext ?: return
        appContext = ctx
        WorkManager.getInstance(ctx).cancelAllWorkByTag(SYNC_WORK_TAG)
    }
}
