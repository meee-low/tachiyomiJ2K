package eu.kanade.tachiyomi.util.system

import android.app.ActivityManager
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import com.nononsenseapps.filepicker.FilePickerActivity
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.widget.CustomLayoutPickerActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale
import kotlin.math.max

private const val TABLET_UI_MIN_SCREEN_WIDTH_DP = 720

/**
 * Display a toast in this context.
 *
 * @param resource the text resource.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(@StringRes resource: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resource, duration).show()
}

/**
 * Display a toast in this context.
 *
 * @param text the text to display.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(text: String?, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, text.orEmpty(), duration).show()
}

/**
 * Helper method to create a notification.
 *
 * @param id the channel id.
 * @param func the function that will execute inside the builder.
 * @return a notification to be displayed or updated.
 */
inline fun Context.notification(channelId: String, func: NotificationCompat.Builder.() -> Unit): Notification {
    val builder = NotificationCompat.Builder(this, channelId)
    builder.func()
    return builder.build()
}

/**
 * Helper method to construct an Intent to use a custom file picker.
 * @param currentDir the path the file picker will open with.
 * @return an Intent to start the file picker activity.
 */
fun Context.getFilePicker(currentDir: String): Intent {
    return Intent(this, CustomLayoutPickerActivity::class.java)
        .putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false)
        .putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
        .putExtra(FilePickerActivity.EXTRA_START_PATH, currentDir)
}

/**
 * Checks if the give permission is granted.
 *
 * @param permission the permission to check.
 * @return true if it has permissions.
 */
fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Returns the color for the given attribute.
 *
 * @param resource the attribute.
 */
@ColorInt
fun Context.getResourceColor(@AttrRes resource: Int): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(resource))
    val attrValue = typedArray.getColor(0, 0)
    typedArray.recycle()
    return attrValue
}

fun Context.getResourceDrawable(@AttrRes resource: Int): Drawable? {
    val typedArray = obtainStyledAttributes(intArrayOf(resource))
    val attrValue = typedArray.getDrawable(0)
    typedArray.recycle()
    return attrValue
}

/**
 * Returns the color from ContextCompat
 *
 * @param resource the color.
 */
fun Context.contextCompatColor(@ColorRes resource: Int): Int {
    return ContextCompat.getColor(this, resource)
}

/**
 * Returns the color from ContextCompat
 *
 * @param resource the color.
 */
fun Context.contextCompatDrawable(@DrawableRes resource: Int): Drawable? {
    return ContextCompat.getDrawable(this, resource)
}

/**
 * Converts to dp.
 */
val Int.pxToDp: Int
    get() = (this / Resources.getSystem().displayMetrics.density).toInt()

val Float.pxToDp: Float
    get() = (this / Resources.getSystem().displayMetrics.density)

/**
 * Converts to px.
 */
val Int.dpToPx: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

val Int.spToPx: Int
    get() = (this * Resources.getSystem().displayMetrics.scaledDensity).toInt()

val Float.dpToPx: Float
    get() = (this * Resources.getSystem().displayMetrics.density)

/** Converts to px and takes into account LTR/RTL layout */
fun Float.dpToPxEnd(resources: Resources): Float {
    return this * resources.displayMetrics.density * if (resources.isLTR) 1 else -1
}

val Resources.isLTR
    get() = configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR

fun Context.isTablet() = resources.configuration.smallestScreenWidthDp >= 600

val displayMaxHeightInPx: Int
    get() = Resources.getSystem().displayMetrics.let { max(it.heightPixels, it.widthPixels) }

/** Gets the duration multiplier for general animations on the device
 * @see Settings.Global.ANIMATOR_DURATION_SCALE
 */
val Context.animatorDurationScale: Float
    get() = Settings.Global.getFloat(this.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * Helper method to create a notification builder.
 *
 * @param id the channel id.
 * @param block the function that will execute inside the builder.
 * @return a notification to be displayed or updated.
 */
fun Context.notificationBuilder(
    channelId: String,
    block: (NotificationCompat.Builder.() -> Unit)? = null,
): NotificationCompat.Builder {
    val builder = NotificationCompat.Builder(this, channelId)
        .setColor(ContextCompat.getColor(this, R.color.secondaryTachiyomi))
    if (block != null) {
        builder.block()
    }
    return builder
}

fun Context.prepareSideNavContext(): Context {
    val configuration = resources.configuration
    val expected = when (Injekt.get<PreferencesHelper>().sideNavMode().get()) {
        SideNavMode.ALWAYS.prefValue -> true
        SideNavMode.NEVER.prefValue -> false
        else -> null
    }
    if (expected != null) {
        val overrideConf = Configuration()
        overrideConf.setTo(configuration)
        overrideConf.screenWidthDp = if (expected) {
            overrideConf.screenWidthDp.coerceAtLeast(TABLET_UI_MIN_SCREEN_WIDTH_DP)
        } else {
            overrideConf.screenWidthDp.coerceAtMost(TABLET_UI_MIN_SCREEN_WIDTH_DP - 1)
        }
        return createConfigurationContext(overrideConf)
    }
    return this
}

fun Context.withOriginalWidth(): Context {
    val width = (this as? MainActivity)?.ogWidth ?: resources.configuration.screenWidthDp
    val configuration = resources.configuration
    val overrideConf = Configuration()
    overrideConf.setTo(configuration)
    overrideConf.screenWidthDp = width
    resources.configuration.updateFrom(overrideConf)
    return this
}

fun Context.extensionIntentForText(text: String): Intent? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(text))
    val info = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .firstOrNull {
            try {
                val pkgName = it.activityInfo.packageName
                ExtensionLoader.isPackageNameAnExtension(packageManager, pkgName)
            } catch (_: Exception) {
                false
            }
        } ?: return null
    intent.setClassName(info.activityInfo.packageName, info.activityInfo.name)
    return intent
}

fun Context.isLandscape(): Boolean {
    return resources.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/**
 * Convenience method to acquire a partial wake lock.
 */
fun Context.acquireWakeLock(tag: String? = null, timeout: Long? = null): PowerManager.WakeLock {
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${tag ?: javaClass.name}:WakeLock")
    if (timeout != null) {
        wakeLock.acquire(timeout)
    } else {
        wakeLock.acquire()
    }
    return wakeLock
}

/**
 * Gets document size of provided [Uri]
 *
 * @return document size of [uri] or null if size can't be obtained
 */
fun Context.getUriSize(uri: Uri): Long? {
    return UniFile.fromUri(this, uri).length().takeIf { it >= 0 }
}

/**
 * Returns true if [packageName] is installed.
 */
fun Context.isPackageInstalled(packageName: String): Boolean {
    return try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Property to get the notification manager from the context.
 */
val Context.notificationManager: NotificationManager
    get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

/**
 * Property to get the connectivity manager from the context.
 */
val Context.connectivityManager: ConnectivityManager
    get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

val Context.wifiManager: WifiManager
    get() = getSystemService()!!

/**
 * Property to get the power manager from the context.
 */
val Context.powerManager: PowerManager
    get() = getSystemService()!!

/**
 * Function used to send a local broadcast asynchronous
 *
 * @param intent intent that contains broadcast information
 */
fun Context.sendLocalBroadcast(intent: Intent) {
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(
        intent,
    )
}

/**
 * Function used to send a local broadcast synchronous
 *
 * @param intent intent that contains broadcast information
 */
fun Context.sendLocalBroadcastSync(intent: Intent) {
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcastSync(
        intent,
    )
}

/**
 * Function used to register local broadcast
 *
 * @param receiver receiver that gets registered.
 */
fun Context.registerLocalReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).registerReceiver(
        receiver,
        filter,
    )
}

/**
 * Function used to unregister local broadcast
 *
 * @param receiver receiver that gets unregistered.
 */
fun Context.unregisterLocalReceiver(receiver: BroadcastReceiver) {
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(
        receiver,
    )
}

/**
 * Returns true if device is connected to Wifi.
 */
fun Context.isConnectedToWifi(): Boolean {
    if (!wifiManager.isWifiEnabled) return false

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        @Suppress("DEPRECATION")
        wifiManager.connectionInfo.bssid != null
    }
}

/**
 * Returns true if the given service class is running.
 */
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {
    val className = serviceClass.name
    val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    return manager.getRunningServices(Integer.MAX_VALUE)
        .any { className == it.service.className }
}

fun Context.openInBrowser(url: String, @ColorInt toolbarColor: Int? = null) {
    this.openInBrowser(url.toUri(), toolbarColor)
}

fun Context.openInBrowser(uri: Uri, @ColorInt toolbarColor: Int? = null) {
    try {
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbarColor ?: getResourceColor(R.attr.colorPrimaryVariant))
                    .build(),
            )
            .build()
        intent.launchUrl(this, uri)
    } catch (e: Exception) {
        toast(e.message)
    }
}

/**
 * Opens a URL in a custom tab.
 */
fun Context.openInBrowser(url: String, forceBrowser: Boolean): Boolean {
    try {
        val parsedUrl = url.toUri()
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(getResourceColor(R.attr.colorPrimaryVariant))
                    .build(),
            )
            .build()
        if (forceBrowser) {
            val packages = getCustomTabsPackages().maxByOrNull { it.preferredOrder }
            val processName = packages?.activityInfo?.processName ?: return false
            intent.intent.`package` = processName
        }
        intent.launchUrl(this, parsedUrl)
        return true
    } catch (e: Exception) {
        toast(e.message)
        return false
    }
}

/**
 * Returns a list of packages that support Custom Tabs.
 */
fun Context.getCustomTabsPackages(): ArrayList<ResolveInfo> {
    val pm = packageManager
    // Get default VIEW intent handler.
    val activityIntent = Intent(Intent.ACTION_VIEW, "http://www.example.com".toUri())
    // Get all apps that can handle VIEW intents.
    val resolvedActivityList = pm.queryIntentActivities(activityIntent, 0)
    val packagesSupportingCustomTabs = ArrayList<ResolveInfo>()
    for (info in resolvedActivityList) {
        val serviceIntent = Intent()
        serviceIntent.action = ACTION_CUSTOM_TABS_CONNECTION
        serviceIntent.setPackage(info.activityInfo.packageName)
        // Check if this package also resolves the Custom Tabs service.
        if (pm.resolveService(serviceIntent, 0) != null) {
            packagesSupportingCustomTabs.add(info)
        }
    }
    return packagesSupportingCustomTabs
}

fun Context.isInNightMode(): Boolean {
    val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return currentNightMode == Configuration.UI_MODE_NIGHT_YES
}

fun Context.appDelegateNightMode(): Int {
    return if (isInNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES
    } else {
        AppCompatDelegate.MODE_NIGHT_NO
    }
}

fun Context.isOnline(): Boolean {
    val connectivityManager = this
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    var result = false
    connectivityManager?.let {
        val networkCapabilities = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(networkCapabilities) ?: return false
        val maxTransport = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> NetworkCapabilities.TRANSPORT_LOWPAN
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> NetworkCapabilities.TRANSPORT_WIFI_AWARE
            else -> NetworkCapabilities.TRANSPORT_VPN
        }
        result = (NetworkCapabilities.TRANSPORT_CELLULAR..maxTransport).any(actNw::hasTransport)
    }
    return result
}

fun Context.createFileInCacheDir(name: String): File {
    val file = File(externalCacheDir, name)
    if (file.exists()) {
        file.delete()
    }
    file.createNewFile()
    return file
}

fun Context.getApplicationIcon(pkgName: String): Drawable? {
    return try {
        packageManager.getApplicationIcon(pkgName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

/** Context used for notifications as Appcompat app lang does not support notifications */
val Context.localeContext: Context
    get() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
        val pref = Injekt.get<PreferencesHelper>()
        val prefsLang = if (pref.appLanguage().isSet()) {
            Locale.forLanguageTag(pref.appLanguage().get())
        } else {
            null
        }
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(
            prefsLang
                ?: AppCompatDelegate.getApplicationLocales()[0]
                ?: Locale.getDefault(),
        )
        return createConfigurationContext(configuration)
    }

fun setLocaleByAppCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        AppCompatDelegate.getApplicationLocales().get(0)?.let { Locale.setDefault(it) }
    }
}

val Context.systemLangContext: Context
    get() {
        val configuration = Configuration(resources.configuration)

        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService<LocaleManager>()?.systemLocales?.get(0)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales.get(0)
            } else {
                return this
            } ?: Locale.getDefault()
        }
        configuration.setLocale(systemLocale)
        return createConfigurationContext(configuration)
    }
