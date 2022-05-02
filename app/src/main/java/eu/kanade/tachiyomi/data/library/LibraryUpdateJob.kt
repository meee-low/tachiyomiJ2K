package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class LibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    val notifier = LibraryUpdateNotifier(context)

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<PreferencesHelper>()
        if (requiresWifiConnection(preferences) && !context.isConnectedToWifi()) {
            Result.failure()
        }

        setForegroundAsync(
            ForegroundInfo(
                Notifications.ID_LIBRARY_PROGRESS,
                notifier.progressNotificationBuilder.build(),
            ),
        )
        val category = inputData.getInt("cats", -1)
        val mangaList = inputData.getLongArray("savedManga")

        LibraryUpdateManager().start(
            context,
            LibraryUpdateManager.Target.CHAPTERS,
            notifier,
            mangaList?.toTypedArray(),
            categoryId = category,
        )

        return Result.success()
//        return if (LibraryUpdateService.start(context)) {
//            Result.success()
//        } else {
//            Result.failure()
//        }
    }

    companion object {
        private const val TAG = "LibraryUpdate"
        private const val ONE_TIME_TAG = "LibraryUpdateNow"

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<PreferencesHelper>()
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestriction().get()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(DEVICE_BATTERY_NOT_LOW in restrictions)
                    .build()

                val request = PeriodicWorkRequestBuilder<LibraryUpdateJob>(
                    15,
                    TimeUnit.MINUTES,
                    5,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.REPLACE, request)
            } else {
                WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            }
        }

        fun run(context: Context, category: Int = -1, savedManga: List<LibraryManga>? = null) {
            if (LibraryUpdateManager.addedIfRunning(context, LibraryUpdateManager.Target.CHAPTERS, savedManga, category)) {
                return
            }
            val data = Data.Builder()
            data.putInt("cats", category)
            savedManga?.let { mangaList ->
                data.putLongArray("savedManga", mangaList.mapNotNull { it.id }.toLongArray())
            }

            val request = OneTimeWorkRequestBuilder<LibraryUpdateJob>()
                .addTag(ONE_TIME_TAG)
                .setInputData(data.build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun requiresWifiConnection(preferences: PreferencesHelper): Boolean {
            val restrictions = preferences.libraryUpdateDeviceRestriction().get()
            return DEVICE_ONLY_ON_WIFI in restrictions
        }
    }
}
