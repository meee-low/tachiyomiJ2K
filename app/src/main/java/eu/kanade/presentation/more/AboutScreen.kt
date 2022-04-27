package eu.kanade.presentation.more

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import eu.kanade.presentation.components.LinkIcon
import eu.kanade.presentation.components.PreferenceRow
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.util.CrashLogUtil
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(
    nestedScrollInterop: NestedScrollConnection,
    checkVersion: () -> Unit,
    getFormattedBuildTime: () -> String,
    onClickLicenses: () -> Unit,
    topPadding: Int,
    scrolled: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    class SnackbarVisualsWithError(
        override val message: String,
        val isError: Boolean
    ) : SnackbarVisuals {
        override val actionLabel: String
            get() = if (isError) "Error" else "OK"
        override val withDismissAction: Boolean
            get() = false
        override val duration: SnackbarDuration
            get() = SnackbarDuration.Long
    }

    Scaffold(
        snackbarHost = {
            // reuse default SnackbarHost to have default animation and timing handling
            SnackbarHost(snackbarHostState) { data ->
                // custom snackbar with the custom action button color and border
                val isError = (data.visuals as? SnackbarVisualsWithError)?.isError ?: false
                val buttonColor = if (isError) {
                    ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inversePrimary
                    )
                }

                Snackbar(
                    modifier = Modifier
                        .border(2.dp, MaterialTheme.colorScheme.secondary)
                        .padding(12.dp),
                    action = {
                        TextButton(
                            onClick = { if (isError) data.dismiss() else data.performAction() },
                            colors = buttonColor
                        ) { Text(data.visuals.actionLabel ?: "") }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        },
        content = {
            LazyColumn(
                modifier = Modifier.nestedScroll(nestedScrollInterop),
                state = listState,
                contentPadding = PaddingValues(top = topPadding.dp)
            ) {
                item {
                    LogoHeader()
                }

                item {
                    PreferenceRow(
                        title = stringResource(R.string.version),
                        subtitle = when {
                            BuildConfig.DEBUG -> {
                                "Debug ${BuildConfig.COMMIT_SHA} (${getFormattedBuildTime()})"
                            }
                            else -> {
                                "Stable ${BuildConfig.VERSION_NAME} (${getFormattedBuildTime()})"
                            }
                        },
                        onClick = {
                            val deviceInfo = CrashLogUtil(context).getDebugInfo()
                            val clipboard = context.getSystemService<ClipboardManager>()!!
                            val appInfo = context.getString(R.string.app_info)
                            clipboard.setPrimaryClip(ClipData.newPlainText(appInfo, deviceInfo))
                            if (Build.VERSION.SDK_INT + Build.VERSION.PREVIEW_SDK_INT < 33) {
                                coroutineScope.launch { // using the `coroutineScope` to `launch` showing the snackbar
                                    // taking the `snackbarHostState` from the attached `scaffoldState`
                                    snackbarHostState.showSnackbar(
                                        SnackbarVisualsWithError(
                                            context.getString(R.string._copied_to_clipboard, appInfo),
                                            false
                                        )
                                    )
                                }
                            }
                        },
                    )
                }

                if (BuildConfig.INCLUDE_UPDATER) {
                    item {
                        PreferenceRow(
                            title = stringResource(R.string.check_for_updates),
                            onClick = checkVersion,
                        )
                    }
                }
                item {
                    PreferenceRow(
                        title = stringResource(R.string.whats_new),
                        onClick = {
                            val url = if (BuildConfig.DEBUG) {
                                "https://github.com/Jays2Kings/tachiyomiJ2K/commits/master"
                            } else {
                                RELEASE_URL
                            }
                            uriHandler.openUri(url)
                        },
                    )
                }

                item {
                    PreferenceRow(
                        title = stringResource(R.string.help_translate),
                        onClick = { uriHandler.openUri("https://hosted.weblate.org/projects/tachiyomi/tachiyomi-j2k/") },
                    )
                }

                item {
                    PreferenceRow(
                        title = stringResource(R.string.helpful_translation_links),
                        onClick = { uriHandler.openUri("https://tachiyomi.org/help/contribution/#translation") },
                    )
                }

                item {
                    PreferenceRow(
                        title = stringResource(R.string.open_source_licenses),
                        onClick = onClickLicenses,
                    )
                }

//                item {
//                    PreferenceRow(
//                        title = stringResource(R.string.pr),
//                        onClick = { uriHandler.openUri("https://tachiyomi.org/privacy") },
//                    )
//                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkIcon(
                            label = stringResource(R.string.website),
                            painter = rememberVectorPainter(Icons.Outlined.Public),
                            url = "https://tachiyomi.org",
                        )
                        LinkIcon(
                            label = "Discord",
                            painter = painterResource(R.drawable.ic_discord_24dp),
                            url = "https://discord.gg/tachiyomi",
                        )
                        LinkIcon(
                            label = "Twitter",
                            painter = painterResource(R.drawable.ic_twitter_24dp),
                            url = "https://twitter.com/tachiyomiorg",
                        )
                        LinkIcon(
                            label = "Facebook",
                            painter = painterResource(R.drawable.ic_facebook_24dp),
                            url = "https://facebook.com/tachiyomiorg",
                        )
                        LinkIcon(
                            label = "Reddit",
                            painter = painterResource(R.drawable.ic_reddit_24dp),
                            url = "https://www.reddit.com/r/Tachiyomi",
                        )
                        LinkIcon(
                            label = "GitHub",
                            painter = painterResource(R.drawable.ic_github_24dp),
                            url = "https://github.com/tachiyomiorg",
                        )
                    }
                }
            }
        }
    )

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect {
                scrolled(it.first, it.second)
            }
    }
}
