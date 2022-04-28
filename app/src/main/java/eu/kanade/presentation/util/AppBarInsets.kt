package eu.kanade.presentation.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

@Stable
class AppBarInsets(
    val insets: WindowInsets,
    val appBarHeight: () -> Float,
    private val density: Density,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = with(density) {
        0.toDp()
    }

    override fun calculateTopPadding() = with(density) {
        insets.getTop(this).toDp() + Dp(appBarHeight())
    }

    override fun calculateRightPadding(layoutDirection: LayoutDirection) = with(density) {
        0.toDp()
    }

    override fun calculateBottomPadding() = with(density) {
        insets.getBottom(this).toDp()
    }

    override fun toString(): String {
        return "InsetsPaddingValues(insets=$insets, density=$density)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is AppBarInsets) {
            return false
        }
        return insets == other.insets && density == other.density
    }

    override fun hashCode(): Int {
        var result = insets.hashCode()
        result = 31 * result + density.hashCode()
        return result
    }
}

@ReadOnlyComposable
@Composable
fun WindowInsets.asAppBarPaddingValues(appBarHeight: () -> Float): PaddingValues =
    AppBarInsets(this, appBarHeight, LocalDensity.current)
