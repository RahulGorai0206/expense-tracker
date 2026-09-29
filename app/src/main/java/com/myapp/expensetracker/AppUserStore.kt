package com.myapp.expensetracker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The name of the person using the app — the "you" in every split.
 *
 * Stored with the rest of the settings in the "prefs" file, so it travels with
 * both the local JSON backup and the Google Sheets settings backup. [name] is
 * kept current through a preference listener rather than only by [setName]:
 * a restore writes the preference directly, and the UI must follow it.
 */
class AppUserStore(context: Context, private val splitDao: SplitDao) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _name = MutableStateFlow(read())

    /** Blank until the user has set one. */
    val name: StateFlow<String> = _name.asStateFlow()

    // Held in a field: SharedPreferences only keeps a weak reference to its
    // listeners, so a lambda passed inline would be collected and go silent.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEY) _name.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val hasName: Boolean get() = _name.value.isNotBlank()

    /**
     * Saves the name and renames the app user in every existing split event, so
     * "you" reads the same everywhere. A blank name is ignored rather than
     * saved: splits already created under the old name would be left pointing
     * at nobody.
     */
    suspend fun setName(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        prefs.edit().putString(PREF_KEY, clean).apply()
        _name.value = clean
        splitDao.renameAppUserMembers(clean)
    }

    private fun read(): String = prefs.getString(PREF_KEY, "").orEmpty().trim()

    companion object {
        const val PREF_KEY = "app_user_name"
    }
}
