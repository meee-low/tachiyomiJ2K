package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.os.PowerManager
import androidx.work.NetworkType
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.toMangaInfo
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.toSChapter
import eu.kanade.tachiyomi.source.model.toSManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.acquireWakeLock
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * This class will take care of updating the chapters of the manga from the library. It can be
 * started calling the [start] method. If it's already running, it won't do anything.
 * While the library is updating, a [PowerManager.WakeLock] will be held until the update is
 * completed, preventing the device from going to sleep mode. A notification will display the
 * progress of the update, and if case of an unexpected error, this service will be silently
 * destroyed.
 */
class LibraryUpdateManager(
    val db: DatabaseHelper = Injekt.get(),
    val coverCache: CoverCache = Injekt.get(),
    val sourceManager: SourceManager = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    val downloadManager: DownloadManager = Injekt.get(),
    val trackManager: TrackManager = Injekt.get(),
    private val mangaShortcutManager: MangaShortcutManager = Injekt.get(),
) {

    /**
     * Wake lock that will be held until the service is destroyed.
     */
    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var notifier: LibraryUpdateNotifier

    private var job: Job? = null

    private val mangaToUpdate = mutableListOf<LibraryManga>()

    private val mangaToUpdateMap = mutableMapOf<Long, List<LibraryManga>>()

    private val categoryIds = mutableSetOf<Int>()

    // List containing new updates
    private val newUpdates = mutableMapOf<LibraryManga, Array<Chapter>>()

    // List containing failed updates
    private val failedUpdates = mutableMapOf<Manga, String?>()

    // List containing skipped updates
    private val skippedUpdates = mutableMapOf<Manga, String?>()

    val count = AtomicInteger(0)
    val jobCount = AtomicInteger(0)

    // Boolean to determine if user wants to automatically download new chapters.
    private val downloadNew: Boolean = preferences.downloadNewChapters().get()

    // Boolean to determine if DownloadManager has downloads
    private var hasDownloads = false

    private val requestSemaphore = Semaphore(5)

    // For updates delete removed chapters if not preference is set as well
    private val deleteRemoved by lazy {
        preferences.deleteRemovedChapters().get() != 1
    }

    /**
     * Defines what should be updated within a service execution.
     */
    enum class Target {

        CHAPTERS, // Manga chapters

        DETAILS, // Manga metadata

        TRACKING // Tracking metadata
    }

    /**
     * Method called when the service receives an intent.
     *
     * @param intent the start intent from.
     * @param flags the flags of the command.
     * @param startId the start id of this command.
     * @return the start value of the command.
     */

    suspend fun start(context: Context, target: Target, notifer: LibraryUpdateNotifier? = null, savedMangasList: Array<Long>? = null, categoryId: Int = -1) {
        if (isRunning()) {
            addedIfRunning(context, target, categoryId = categoryId)
            return
        }
        instance = this
        this.notifier = notifer ?: LibraryUpdateNotifier(context)
        wakeLock =
            context.acquireWakeLock(this.javaClass.name, timeout = TimeUnit.MINUTES.toMillis(30))
        val mangaList = (
            if (savedMangasList != null) {
                val mangas = db.getLibraryMangas().executeAsBlocking().filter {
                    it.id in savedMangasList
                }.distinctBy { it.id }
                if (categoryId > -1) categoryIds.add(categoryId)
                mangas
            } else {
                getMangaToUpdate(categoryId)
            }
            ).sortedBy { it.title }
        launchTarget(context, target, mangaList)
    }

    /**
     * Method called when the service is destroyed. It cancels jobs and releases the wake lock.
     */
    fun onDestroy() {
        job?.cancel()
        if (instance == this) {
            instance = null
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        listener?.onUpdateManga()
//        super.onDestroy()
    }

    /**
     * Returns the list of manga to be updated.
     *
     * @param intent the update intent.
     * @param target the target to update.
     * @return a list of manga to update
     */
    private fun getMangaToUpdate(categoryId: Int): List<LibraryManga> {
        val libraryManga = db.getLibraryMangas().executeAsBlocking()

        val listToUpdate = if (categoryId != -1) {
            categoryIds.add(categoryId)
            libraryManga.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate =
                preferences.libraryUpdateCategories().get().map(String::toInt)
            if (categoriesToUpdate.isNotEmpty()) {
                categoryIds.addAll(categoriesToUpdate)
                libraryManga.filter { it.category in categoriesToUpdate }.distinctBy { it.id }
            } else {
                categoryIds.addAll(db.getCategories().executeAsBlocking().mapNotNull { it.id } + 0)
                libraryManga.distinctBy { it.id }
            }
        }

        val categoriesToExclude =
            preferences.libraryUpdateCategoriesExclude().get().map(String::toInt)
        val listToExclude = if (categoriesToExclude.isNotEmpty()) {
            libraryManga.filter { it.category in categoriesToExclude }
        } else {
            emptyList()
        }

        return listToUpdate.minus(listToExclude)
    }

    private suspend fun launchTarget(
        context: Context,
        target: Target,
        mangaToAdd: List<LibraryManga>,
    ) {
//        val handler = CoroutineExceptionHandler { _, exception ->
//            Timber.e(exception)
//            stopSelf(startId)
//        }
        if (target == Target.CHAPTERS) {
            listener?.onUpdateManga(Manga.create(STARTING_UPDATE_SOURCE))
        }
//        job = GlobalScope.launch(handler) {
        job = coroutineScope {
            launch {
                when (target) {
                    Target.CHAPTERS -> updateChaptersJob(context, filterMangaToUpdate(context, mangaToAdd))
                    Target.DETAILS -> updateDetails(context, mangaToAdd)
                    else -> updateTrackings(mangaToAdd)
                }
            }
        }
//        }
        job?.invokeOnCompletion { onDestroy() }

        onDestroy()
    }

    private fun addManga(context: Context, mangaToAdd: List<LibraryManga>) {
        val distinctManga = mangaToAdd.filter { it !in mangaToUpdate }
        mangaToUpdate.addAll(distinctManga)
        checkIfMassiveUpdate()
        distinctManga.groupBy { it.source }.forEach {
            // if added queue items is a new source not in the async list or an async list has
            // finished running
            if (mangaToUpdateMap[it.key].isNullOrEmpty()) {
                mangaToUpdateMap[it.key] = it.value
                jobCount.andIncrement
                val handler = CoroutineExceptionHandler { _, exception ->
                    Timber.e(exception)
                }
                GlobalScope.launch(handler) {
                    val hasDLs = try {
                        requestSemaphore.withPermit { updateMangaInSource(it.key) }
                    } catch (e: Exception) {
                        false
                    }
                    hasDownloads = hasDownloads || hasDLs
                    jobCount.andDecrement
                    finishUpdates(context)
                }
            } else {
                val list = mangaToUpdateMap[it.key] ?: emptyList()
                mangaToUpdateMap[it.key] = (list + it.value)
            }
        }
    }

    private fun addMangaToQueue(context: Context, categoryId: Int, manga: List<LibraryManga>) {
        val mangas = filterMangaToUpdate(context, manga).sortedBy { it.title }
        categoryIds.add(categoryId)
        addManga(context, mangas)
    }

    private fun addCategory(context: Context, categoryId: Int) {
        val mangas = filterMangaToUpdate(context, getMangaToUpdate(categoryId)).sortedBy { it.title }
        categoryIds.add(categoryId)
        addManga(context, mangas)
    }

    private fun filterMangaToUpdate(context: Context, mangaToAdd: List<LibraryManga>): List<LibraryManga> {
        val restrictions = preferences.libraryUpdateMangaRestriction().get()
        return mangaToAdd.filter { manga ->
            return@filter if (MANGA_NON_COMPLETED in restrictions && manga.status == SManga.COMPLETED) {
                skippedUpdates[manga] = context.getString(R.string.skipped_reason_completed)
                false
            } else if (MANGA_HAS_UNREAD in restrictions && manga.unread != 0) {
                skippedUpdates[manga] = context.getString(R.string.skipped_reason_not_caught_up)
                false
            } else if (MANGA_NON_READ in restrictions && manga.totalChapters > 0 && !manga.hasRead) {
                skippedUpdates[manga] = context.getString(R.string.skipped_reason_not_started)
                false
            } else true
        }
    }

    private fun checkIfMassiveUpdate() {
        val largestSourceSize = mangaToUpdate
            .groupBy { it.source }
            .filterKeys { sourceManager.get(it) !is UnmeteredSource }
            .maxOfOrNull { it.value.size } ?: 0
        if (largestSourceSize > MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
            notifier.showQueueSizeWarningNotification()
        }
    }

    private suspend fun updateChaptersJob(context: Context, mangaToAdd: List<LibraryManga>) {
        // Initialize the variables holding the progress of the updates.

        mangaToUpdate.addAll(mangaToAdd)
        mangaToUpdateMap.putAll(mangaToAdd.groupBy { it.source })
        checkIfMassiveUpdate()
        coroutineScope {
            jobCount.andIncrement
            val list = mangaToUpdateMap.keys.map { source ->
                async {
                    try {
                        requestSemaphore.withPermit {
                            updateMangaInSource(source)
                        }
                    } catch (e: Exception) {
                        Timber.e(e)
                        false
                    }
                }
            }
            val results = list.awaitAll()
            hasDownloads = hasDownloads || results.any { it }
            jobCount.andDecrement
            finishUpdates(context)
        }
    }

    private suspend fun finishUpdates(context: Context) {
        if (jobCount.get() != 0) return
        if (newUpdates.isNotEmpty()) {
            notifier.showResultNotification(newUpdates)

            if (preferences.refreshCoversToo().get() && job?.isCancelled == false) {
                updateDetails(context, newUpdates.keys.toList())
                notifier.cancelProgressNotification()
                if (downloadNew && hasDownloads) {
                    DownloadService.start(context)
                }
            } else if (downloadNew && hasDownloads) {
                DownloadService.start(context.applicationContext)
            }
            newUpdates.clear()
        }
        if (skippedUpdates.isNotEmpty() && Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_LIBRARY_SKIPPED)) {
            val skippedFile = writeErrorFile(
                context,
                skippedUpdates,
                "skipped",
                context.getString(R.string.learn_more_at_, LibraryUpdateNotifier.HELP_SKIPPED_URL),
            ).getUriCompat(context)
            notifier.showUpdateSkippedNotification(skippedUpdates.map { it.key.title }, skippedFile)
        }
        if (failedUpdates.isNotEmpty() && Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_LIBRARY_ERROR)) {
            val errorFile = writeErrorFile(context, failedUpdates).getUriCompat(context)
            notifier.showUpdateErrorNotification(failedUpdates.map { it.key.title }, errorFile)
        }
        mangaShortcutManager.updateShortcuts()
        failedUpdates.clear()
        notifier.cancelProgressNotification()
        if (runExtensionUpdatesAfter && !DownloadService.isRunning(context)) {
            ExtensionUpdateJob.runJobAgain(context, NetworkType.CONNECTED)
            runExtensionUpdatesAfter = false
        }
    }

    private suspend fun updateMangaInSource(source: Long): Boolean {
        if (mangaToUpdateMap[source] == null) return false
        var count = 0
        var hasDownloads = false
        while (count < mangaToUpdateMap[source]!!.size) {
            val manga = mangaToUpdateMap[source]!![count]
            val shouldDownload = manga.shouldDownloadNewChapters(db, preferences)
            if (updateMangaChapters(manga, this.count.andIncrement, shouldDownload)) {
                hasDownloads = true
            }
            count++
        }
        mangaToUpdateMap[source] = emptyList()
        return hasDownloads
    }

    private suspend fun updateMangaChapters(
        manga: LibraryManga,
        progress: Int,
        shouldDownload: Boolean,
    ):
        Boolean {
        try {
            var hasDownloads = false
            if (job?.isCancelled == true) {
                return false
            }
            notifier.showProgressNotification(manga, progress, mangaToUpdate.size)
            val source = sourceManager.get(manga.source) as? HttpSource ?: return false
            val fetchedChapters = withContext(Dispatchers.IO) {
                source.getChapterList(manga.toMangaInfo()).map { it.toSChapter() }
            }
            if (fetchedChapters.isNotEmpty()) {
                val newChapters = syncChaptersWithSource(db, fetchedChapters, manga, source)
                if (newChapters.first.isNotEmpty()) {
                    if (shouldDownload) {
                        downloadChapters(manga, newChapters.first.sortedBy { it.chapter_number })
                        hasDownloads = true
                    }
                    newUpdates[manga] =
                        newChapters.first.sortedBy { it.chapter_number }.toTypedArray()
                }
                if (deleteRemoved && newChapters.second.isNotEmpty()) {
                    val removedChapters = newChapters.second.filter {
                        downloadManager.isChapterDownloaded(it, manga)
                    }
                    if (removedChapters.isNotEmpty()) {
                        downloadManager.deleteChapters(removedChapters, manga, source)
                    }
                }
                if (newChapters.first.size + newChapters.second.size > 0) listener?.onUpdateManga(
                    manga,
                )
            }
            return hasDownloads
        } catch (e: Exception) {
            if (e !is CancellationException) {
                failedUpdates[manga] = e.message
                Timber.e("Failed updating: ${manga.title}: $e")
            }
            return false
        }
    }

    private fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadChapters(manga, chapters, false)
    }

    /**
     * Method that updates the details of the given list of manga. It's called in a background
     * thread, so it's safe to do heavy operations or network calls here.
     *
     * @param mangaToUpdate the list to update
     */
    suspend fun updateDetails(context: Context, mangaToUpdate: List<LibraryManga>) = coroutineScope {
        // Initialize the variables holding the progress of the updates.
        val count = AtomicInteger(0)
        val asyncList = mangaToUpdate.groupBy { it.source }.values.map { list ->
            async {
                requestSemaphore.withPermit {
                    list.forEach { manga ->
                        if (job?.isCancelled == true) {
                            return@async
                        }
                        val source = sourceManager.get(manga.source) as? HttpSource ?: return@async
                        notifier.showProgressNotification(
                            manga,
                            count.andIncrement,
                            mangaToUpdate.size,
                        )

                        val networkManga = try {
                            source.getMangaDetails(manga.toMangaInfo()).toSManga()
                        } catch (e: java.lang.Exception) {
                            Timber.e(e)
                            null
                        }
                        if (networkManga != null) {
                            val thumbnailUrl = manga.thumbnail_url
                            manga.copyFrom(networkManga)
                            manga.initialized = true
                            if (thumbnailUrl != manga.thumbnail_url) {
                                coverCache.deleteFromCache(thumbnailUrl)
                                // load new covers in background
                                val request =
                                    ImageRequest.Builder(context).data(manga)
                                        .memoryCachePolicy(CachePolicy.DISABLED).build()
                                Coil.imageLoader(context).execute(request)
                            } else {
                                val request =
                                    ImageRequest.Builder(context).data(manga)
                                        .memoryCachePolicy(CachePolicy.DISABLED)
                                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                                        .build()
                                Coil.imageLoader(context).execute(request)
                            }
                            db.insertManga(manga).executeAsBlocking()
                        }
                    }
                }
            }
        }
        asyncList.awaitAll()
        notifier.cancelProgressNotification()
    }

    /**
     * Method that updates the metadata of the connected tracking services. It's called in a
     * background thread, so it's safe to do heavy operations or network calls here.
     */

    private suspend fun updateTrackings(mangaToUpdate: List<LibraryManga>) {
        // Initialize the variables holding the progress of the updates.
        var count = 0

        val loggedServices = trackManager.services.filter { it.isLogged }

        mangaToUpdate.forEach { manga ->
            notifier.showProgressNotification(manga, count++, mangaToUpdate.size)

            val tracks = db.getTracks(manga).executeAsBlocking()

            tracks.forEach { track ->
                val service = trackManager.getService(track.sync_id)
                if (service != null && service in loggedServices) {
                    try {
                        val newTrack = service.refresh(track)
                        db.insertTrack(newTrack).executeAsBlocking()

                        if (service is EnhancedTrackService) {
                            syncChaptersWithTrackServiceTwoWay(db, db.getChapters(manga).executeAsBlocking(), track, service)
                        }
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                }
            }
        }
        notifier.cancelProgressNotification()
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(context: Context, errors: Map<Manga, String?>, fileName: String = "errors", additionalInfo: String? = null): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("tachiyomi_update_$fileName.txt")
                file.bufferedWriter().use { out ->
                    additionalInfo?.let { out.write("$it\n") }
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.toList().groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }

    companion object {

        /**
         * Key for category to update.
         */
        const val KEY_CATEGORY = "category"
        const val STARTING_UPDATE_SOURCE = -5L

        fun categoryInQueue(id: Int?) = instance?.categoryIds?.contains(id) ?: false
        private var instance: LibraryUpdateManager? = null

        /**
         * Key that defines what should be updated.
         */
        const val KEY_TARGET = "target"

        /**
         * Key for list of manga to be updated. (For dynamic categories)
         */
        const val KEY_MANGAS = "mangas"

        var runExtensionUpdatesAfter = false

        /**
         * Returns the status of the service.
         *
         * @return true if the service is running, false otherwise.
         */
        fun isRunning() = instance != null

        fun addedIfRunning(context: Context, target: Target, mangaToUse: List<LibraryManga>? = null, categoryId: Int = -1): Boolean {
            if (isRunning()) {
                if (target == Target.CHAPTERS) {
                    if (mangaToUse != null) instance?.addMangaToQueue(context, categoryId, mangaToUse)
                    else instance?.addCategory(context, categoryId)
                    return true
                }
            }
            return false
        }

        /**
         * Stops the service.
         *
         * @param context the application context.
         */
        fun stop(context: Context) {
            instance?.job?.cancel()
            GlobalScope.launch {
                instance?.jobCount?.set(0)
                instance?.finishUpdates(context)
            }
        }

        private var listener: LibraryServiceListener? = null

        fun setListener(listener: LibraryServiceListener) {
            this.listener = listener
        }

        fun removeListener(listener: LibraryServiceListener) {
            if (this.listener == listener) this.listener = null
        }

        fun callListener(manga: Manga) {
            listener?.onUpdateManga(manga)
        }
    }
}

interface LibraryServiceListener {
    fun onUpdateManga(manga: Manga? = null)
}

const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60
