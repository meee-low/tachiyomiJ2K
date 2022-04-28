package eu.kanade.tachiyomi.ui.base.controller

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.ComposeControllerBinding
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.TabbedInterface
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.toInt
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.backgroundColor
import eu.kanade.tachiyomi.util.view.fullAppBarHeightAndPadding
import eu.kanade.tachiyomi.util.view.setAppBarBG
import kotlinx.coroutines.flow.distinctUntilChanged
import nucleus.presenter.Presenter
import kotlin.random.Random

/**
 * Compose controller with a Nucleus presenter.
 */
abstract class ComposeController<P : Presenter<*>> : NucleusController<ComposeControllerBinding, P>() {

    override fun createBinding(inflater: LayoutInflater): ComposeControllerBinding =
        ComposeControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        binding.root.setContent {
            val scrollState = rememberScrollState()

            LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collect {
                        activityBinding?.appBar?.updateAppBarAfterY(it.first, it.second)
                    }
            }

            TachiyomiTheme {
                ComposeContent(scrollState)
            }
        }
    }

    @Composable abstract fun ComposeContent(scrollState: ScrollState)
}

/**
 * Basic Compose controller without a presenter.
 */
abstract class BasicComposeController : BaseController<ComposeControllerBinding>() {

    override fun createBinding(inflater: LayoutInflater): ComposeControllerBinding =
        ComposeControllerBinding.inflate(inflater)

    private var isToolbarColor = false
    var toolbarColorAnim: ValueAnimator? = null
    private val randomTag = Random.nextLong()
    var listState: LazyListState? = null

    fun colorToolbar(isColored: Boolean) {
        isToolbarColor = isColored
        val includeTabView = this is TabbedInterface
        toolbarColorAnim?.cancel()
        val floatingBar =
            (this as? FloatingSearchInterface)?.showFloatingBar() == true && !includeTabView
        if (floatingBar) {
            setAppBarBG(isColored.toInt().toFloat(), includeTabView)
            return
        }
        val percent = ImageUtil.getPercentOfColor(
            activityBinding!!.appBar.backgroundColor ?: Color.TRANSPARENT,
            activity!!.getResourceColor(R.attr.colorSurface),
            activity!!.getResourceColor(R.attr.colorPrimaryVariant)
        )
        toolbarColorAnim = ValueAnimator.ofFloat(percent, isColored.toInt().toFloat())
        toolbarColorAnim?.addUpdateListener { valueAnimator ->
            setAppBarBG(valueAnimator.animatedValue as Float, includeTabView)
        }
        toolbarColorAnim?.start()
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        val includeTabView = this is TabbedInterface

        val tabBarHeight = 48.dpToPx
        val atTopOfRecyclerView: (Int) -> Boolean = f@{ offset ->
            if (this is SmallToolbarInterface || activityBinding?.appBar?.useLargeToolbar == false) {
                return@f offset == 0
            }
            val activityBinding = activityBinding ?: return@f true
            return@f offset - fullAppBarHeightAndPadding!! <=
                0 - activityBinding.appBar.paddingTop -
                activityBinding.toolbar.height - if (includeTabView) tabBarHeight else 0
        }

        activityBinding?.appBar?.lockYPos = false
        activityBinding?.appBar?.y = 0f
        activityBinding?.appBar?.useTabsInPreLayout = includeTabView
        activityBinding?.appBar?.setToolbarModeBy(this)

//        var appBarHeight = (
//                if (fullAppBarHeight ?: 0 > 0) fullAppBarHeight!!
//                else activityBinding?.appBar?.preLayoutHeight ?: 0
//                )
//        activityBinding!!.appBar.doOnLayout {
//            if (fullAppBarHeight!! > 0) {
//                appBarHeight = fullAppBarHeight!!
//            }
//        }

        binding.root.setContent {
            val listState = rememberLazyListState()
            this.listState = listState
            var oldOffset = 0
            val childSizesMap = HashMap<Int, Int>()
            activityBinding!!.appBar.y = 0f
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .distinctUntilChanged()
                    .collect { (firstChildPosition, firstOffset) ->
                        val info = listState.layoutInfo.visibleItemsInfo
                        childSizesMap.putAll(info.map { it.index to it.size })
                        val scrolledY: Int = firstOffset +
                            (0 until firstChildPosition).sumOf { childSizesMap[it] ?: 0 }
                        val diff = scrolledY - oldOffset
                        oldOffset = scrolledY
                        activityBinding!!.appBar.y -= diff.toFloat()
                        activityBinding?.appBar?.updateAppBarAfterY(
                            scrolledY,
                            !listState.isScrollInProgress
                        )

                        if (!isToolbarColor && (
                            oldOffset == 0 ||
                                (
                                    activityBinding!!.appBar.y <= -activityBinding!!.appBar.height.toFloat() ||
                                        oldOffset == 0 && activityBinding!!.appBar.y == 0f
                                    )
                            )
                        ) {
                            colorToolbar(true)
                        }
                        val notAtTop = !atTopOfRecyclerView(
                            scrolledY
                        )
                        if (notAtTop != isToolbarColor) colorToolbar(notAtTop)
                    }
            }

            TachiyomiTheme {
                ComposeContent(listState)
            }
        }
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            activityBinding?.appBar?.hideBigView(
                this is SmallToolbarInterface,
                setTitleAlpha = false // this !is MangaDetailsController
            )
            activityBinding?.appBar?.setToolbarModeBy(this)
            activityBinding?.appBar?.useTabsInPreLayout = this is TabbedInterface
            colorToolbar(isToolbarColor)
            activityBinding!!.toolbar.tag = randomTag
            activityBinding!!.toolbar.setOnClickListener {
//                viewScope.launchUI {
//                    listState?.animateScrollToItem(0)
//                }
            }
        } else {
            toolbarColorAnim?.cancel()
            if (activityBinding!!.toolbar.tag == randomTag) activityBinding!!.toolbar.setOnClickListener(null)
        }
    }

    @Composable abstract fun ComposeContent(listState: LazyListState)
}
