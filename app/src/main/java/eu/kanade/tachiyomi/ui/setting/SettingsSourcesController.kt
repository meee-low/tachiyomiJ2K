package eu.kanade.tachiyomi.ui.setting

import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.minusAssign
import eu.kanade.tachiyomi.data.preference.plusAssign
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.widget.preference.SwitchPreferenceCategory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeMap

class SettingsSourcesController : SettingsController(), FloatingSearchInterface {
    init {
        setHasOptionsMenu(true)
    }

    private val onlineSources by lazy { Injekt.get<SourceManager>().getOnlineSources() }

    private var query = ""

    private var orderedLangs = listOf<String>()
    private var langPrefs = mutableListOf<Pair<String, SwitchPreferenceCategory>>()
    private var sourcesByLang: TreeMap<String, MutableList<HttpSource>> = TreeMap()
    private var sorting = SourcesSort.Alpha

    override fun getSearchTitle(): String? {
        return view?.context?.getString(R.string.search)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.filter
        sorting = SourcesSort.from(preferences.sourceSorting().get()) ?: SourcesSort.Alpha
        activity?.invalidateOptionsMenu()
        // Get the list of active language codes.
        val activeLangsCodes = preferences.enabledLanguages().get()

        // Get a map of sources grouped by language.
        sourcesByLang = onlineSources.groupByTo(TreeMap()) { it.lang }

        // Order first by active languages, then inactive ones
        orderedLangs = sourcesByLang.keys.filter { it in activeLangsCodes } + sourcesByLang.keys
            .filterNot { it in activeLangsCodes }

        orderedLangs.forEach { lang ->
            // Create a preference group and set initial state and change listener
            langPrefs.add(
                Pair(
                    lang,
                    SwitchPreferenceCategory(context).apply {
                        preferenceScreen.addPreference(this)
                        title = LocaleHelper.getSourceDisplayName(lang, context)
                        isPersistent = false
                        if (lang in activeLangsCodes) {
                            setChecked(true)
                            addLanguageSources(this, sortedSources(sourcesByLang[lang]))
                        }

                        onChange { newValue ->
                            val checked = newValue as Boolean
                            if (!checked) {
                                preferences.enabledLanguages() -= lang
                                removeAll()
                            } else {
                                preferences.enabledLanguages() += lang
                                addLanguageSources(this, sortedSources(sourcesByLang[lang]))
                            }
                            true
                        }
                    },
                ),
            )
        }
    }

    override fun setDivider(divider: Drawable?) {
        super.setDivider(null)
    }

    /**
     * Adds the source list for the given group (language).
     *
     * @param group the language category.
     */
    private fun addLanguageSources(group: PreferenceGroup, sources: List<HttpSource>) {
        val hiddenCatalogues = preferences.hiddenSources().get()

        val selectAllPreference = CheckBoxPreference(group.context).apply {
            title = "\t\t${context.getString(R.string.all_sources)}"
            key = "all_${sources.first().lang}"
            isPersistent = false
            isChecked = sources.all { it.id.toString() !in hiddenCatalogues }
            isVisible = query.isEmpty()

            onChange { newValue ->
                val checked = newValue as Boolean
                val current = preferences.hiddenSources().get().toMutableSet()
                if (checked) {
                    current.removeAll(sources.map { it.id.toString() })
                } else {
                    current.addAll(sources.map { it.id.toString() })
                }
                preferences.hiddenSources().set(current)
                group.removeAll()
                addLanguageSources(group, sortedSources(sources))
                true
            }
        }
        group.addPreference(selectAllPreference)

        sources.forEach { source ->
            val sourcePreference = CheckBoxPreference(group.context).apply {
                val id = source.id.toString()
                title = source.name
                key = getSourceKey(source.id)
                isPersistent = false
                isChecked = id !in hiddenCatalogues
                isVisible = query.isEmpty() || source.name.contains(query, ignoreCase = true)

                val sourceIcon = source.icon()
                if (sourceIcon != null) {
                    icon = sourceIcon
                }

                onChange { newValue ->
                    val checked = newValue as Boolean
                    val current = preferences.hiddenSources().get()

                    preferences.hiddenSources().set(
                        if (checked) {
                            current - id
                        } else {
                            current + id
                        },
                    )

                    group.removeAll()
                    addLanguageSources(group, sortedSources(sources))
                    true
                }
            }

            group.addPreference(sourcePreference)
        }
    }

    private fun getSourceKey(sourceId: Long): String {
        return "source_$sourceId"
    }

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.settings_sources, menu)
        if (sorting == SourcesSort.Alpha) {
            menu.findItem(R.id.action_sort_alpha).isChecked = true
        } else {
            menu.findItem(R.id.action_sort_enabled).isChecked = true
        }

        val useSearchTB = showFloatingBar()
        val searchItem = if (useSearchTB) {
            activityBinding?.searchToolbar?.searchItem
        } else {
            (menu.findItem(R.id.action_search))
        }
        val searchView = if (useSearchTB) {
            activityBinding?.searchToolbar?.searchView
        } else {
            searchItem?.actionView as? SearchView
        }
        if (!useSearchTB) {
            searchView?.maxWidth = Int.MAX_VALUE
        }

        activityBinding?.searchToolbar?.setQueryHint(getSearchTitle(), query.isEmpty())

        if (query.isNotEmpty()) {
            searchItem?.expandActionView()
            searchView?.setQuery(query, true)
            searchView?.clearFocus()
        }

        setOnQueryTextChangeListener(activityBinding?.searchToolbar?.searchView) {
            query = it ?: ""
            drawSources()
            true
        }

        setOnQueryTextChangeListener(searchView) {
            query = it ?: ""
            drawSources()
            true
        }

        if (useSearchTB) {
            // Fixes problem with the overflow icon showing up in lieu of search
            searchItem?.fixExpand(onExpand = { invalidateMenuOnExpand() })
        }
    }

    override fun showFloatingBar() = activityBinding?.appBar?.useLargeToolbar == true

    var expandActionViewFromInteraction = false
    private fun MenuItem.fixExpand(onExpand: ((MenuItem) -> Boolean)? = null, onCollapse: ((MenuItem) -> Boolean)? = null) {
        setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    return onExpand?.invoke(item) ?: true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    activity?.invalidateOptionsMenu()

                    return onCollapse?.invoke(item) ?: true
                }
            },
        )

        if (expandActionViewFromInteraction) {
            expandActionViewFromInteraction = false
            expandActionView()
        }
    }

    private fun invalidateMenuOnExpand(): Boolean {
        return if (expandActionViewFromInteraction) {
            activity?.invalidateOptionsMenu()
            false
        } else {
            true
        }
    }

    private fun drawSources() {
        val activeLangsCodes = preferences.enabledLanguages().get()
        langPrefs.forEach { group ->
            if (group.first in activeLangsCodes) {
                group.second.removeAll()
                addLanguageSources(group.second, sortedSources(sourcesByLang[group.first]))
            }
        }
    }

    private fun sortedSources(sources: List<HttpSource>?): List<HttpSource> {
        val sourceAlpha = sources.orEmpty().sortedBy { it.name }
        return if (sorting == SourcesSort.Enabled) {
            val hiddenCatalogues = preferences.hiddenSources().get()
            sourceAlpha.filter { it.id.toString() !in hiddenCatalogues } +
                sourceAlpha.filterNot { it.id.toString() !in hiddenCatalogues }
        } else {
            sourceAlpha
        }
    }

    /**
     * Called when an option menu item has been selected by the user.
     *
     * @param item The selected item.
     * @return True if this event has been consumed, false if it has not.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        sorting = when (item.itemId) {
            R.id.action_sort_alpha -> SourcesSort.Alpha
            R.id.action_sort_enabled -> SourcesSort.Enabled
            else -> return super.onOptionsItemSelected(item)
        }
        item.isChecked = true
        (activity as? MainActivity)?.let {
            val otherTB = if (it.currentToolbar == it.binding.searchToolbar) {
                it.binding.toolbar
            } else {
                it.binding.searchToolbar
            }
            otherTB.menu.findItem(item.itemId).isChecked = true
        }
        preferences.sourceSorting().set(sorting.value)
        drawSources()
        return true
    }

    enum class SourcesSort(val value: Int) {
        Alpha(0), Enabled(1);

        companion object {
            fun from(i: Int): SourcesSort? = values().find { it.value == i }
        }
    }
}
