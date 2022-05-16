package eu.kanade.tachiyomi.ui.base.activity

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.BuildCompat
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.getThemeWithExtras
import eu.kanade.tachiyomi.util.system.setThemeByPref
import nucleus.view.NucleusAppCompatActivity
import uy.kohesive.injekt.injectLazy
import java.util.Locale

abstract class BaseRxActivity<P : BasePresenter<*>> : NucleusAppCompatActivity<P>() {

    val scope = lifecycleScope
    private val preferences by injectLazy<PreferencesHelper>()
    private var updatedTheme: Resources.Theme? = null

    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!BuildCompat.isAtLeastT()) {
            AppCompatDelegate.getApplicationLocales().get(0)?.let { Locale.setDefault(it) }
        }
        updatedTheme = null
        setThemeByPref(preferences)
        super.onCreate(savedInstanceState)
        SecureActivityDelegate.setSecure(this)
    }

    override fun onResume() {
        super.onResume()
        SecureActivityDelegate.promptLockIfNeeded(this)
    }

    override fun getTheme(): Resources.Theme {
        val newTheme = getThemeWithExtras(super.getTheme(), preferences, updatedTheme)
        updatedTheme = newTheme
        return newTheme
    }
}
