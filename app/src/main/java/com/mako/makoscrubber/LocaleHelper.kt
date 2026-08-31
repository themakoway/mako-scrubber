package com.mako.makoscrubber

import android.content.Context
import android.content.res.Configuration
import java.text.Collator
import java.util.Locale

/**
 * Per-app language override, applied manually (no androidx.appcompat).
 *
 * The chosen BCP-47 tag lives in the existing "mako_prefs" SharedPreferences so it can be
 * read synchronously from [android.app.Activity.attachBaseContext] — before the activity
 * exists, which rules out the async DataStore in [MakoSettings]. An empty/absent value means
 * "follow the system", which is the default. The setting is the single source of truth and
 * survives restarts and app updates; there is no system-settings ("App languages") integration
 * on Android 13+ by design, since that path needs the framework to own the locale.
 */
object LocaleHelper {

    private const val PREFS = "mako_prefs"
    private const val KEY_APP_LOCALE = "app_locale"

    /** A language the app ships translations for. [tag] "" is the system-default sentinel. */
    data class AppLanguage(val tag: String, val autonym: String)

    /** The 10 shipped locales, keyed to their values-* resource folders. */
    val LANGUAGES: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("it", "Italiano"),
        AppLanguage("ja", "日本語"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("pt", "Português"),
        AppLanguage("pt-BR", "Português (Brasil)"),
        AppLanguage("zh", "中文"),
    )

    /** [LANGUAGES] sorted by autonym with a locale-aware collator; CJK names fall at the end. */
    val languagesSorted: List<AppLanguage> by lazy {
        LANGUAGES.sortedWith(compareBy(Collator.getInstance()) { it.autonym })
    }

    /** The persisted tag, or "" when following the system. */
    fun getPersistedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_LOCALE, "") ?: ""

    /** Persist [tag] ("" to follow the system). Caller is responsible for recreating the UI. */
    fun persistTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_APP_LOCALE, tag).apply()
    }

    /**
     * Wrap [base] so its resources resolve in the chosen language. Returns [base] unchanged when
     * following the system. Also sets [Locale.setDefault] so formatting (dates, numbers,
     * String.format) matches the UI.
     */
    fun wrap(base: Context): Context {
        val tag = getPersistedTag(base)
        if (tag.isEmpty()) return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
