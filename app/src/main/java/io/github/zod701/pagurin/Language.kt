package io.github.zod701.pagurin

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

// The app's language: "" follows the system, else a language tag with its own strings. An activity applies it to its base context, so every string it reads follows.
// Following the system means Korean when that is the system's first language, else English: left to itself Android would also take Korean from further down the person's language list
object Language {
    private const val PREFS = "pagurin"
    private const val KEY = "language"

    fun get(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun set(context: Context, tag: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit() }   // commit: the activity is recreated right after, and reads it

    fun wrap(base: Context): Context {
        val tag = get(base).ifEmpty { if (Resources.getSystem().configuration.locales[0].language == "ko") "ko" else "en" }
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }
}
