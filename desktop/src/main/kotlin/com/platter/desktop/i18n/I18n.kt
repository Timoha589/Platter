package com.platter.desktop.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The languages the phone app ships (its locale_config.xml), in the same order. [tag] is what settings.json keeps;
 * [nativeName] is how the language names itself, so it can be found in a menu whatever the current language is.
 */
enum class AppLanguage(val tag: String, val nativeName: String, val locale: Locale) {
    English("en", "English", Locale.ENGLISH),
    German("de", "Deutsch", Locale.GERMAN),
    French("fr", "Français", Locale.FRENCH),
    Chinese("zh", "中文 (简体)", Locale.SIMPLIFIED_CHINESE),
    Korean("ko", "한국어", Locale.KOREAN),
    Portuguese("pt", "Português (Brasil)", Locale("pt", "BR")),
    Italian("it", "Italiano", Locale.ITALIAN),
    Russian("ru", "Русский", Locale("ru", "RU"));

    /** Which of a plural's forms [n] takes, as an index into the `|`-separated forms of its translation. */
    internal fun pluralForm(n: Int): Int = when (this) {
        English, German, Italian -> if (n == 1) 0 else 1
        French, Portuguese -> if (n == 0 || n == 1) 0 else 1
        Chinese, Korean -> 0
        Russian -> when {
            n % 10 == 1 && n % 100 != 11 -> 0
            n % 10 in 2..4 && n % 100 !in 12..14 -> 1
            else -> 2
        }
    }

    internal fun words(): Map<String, String> = when (this) {
        English -> emptyMap()
        German -> GERMAN
        French -> FRENCH
        Chinese -> CHINESE
        Korean -> KOREAN
        Portuguese -> PORTUGUESE
        Italian -> ITALIAN
        Russian -> RUSSIAN
    }

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    }
}

/**
 * The language of the interface. The text of the app is written in English and looked up by that English text,
 * the way gettext does: a string with no translation shows as written, so a missing entry is never a blank.
 *
 * [language] is Compose state, so everything that called [t] while composing redraws when it changes.
 */
object I18n {
    /** What the listener picked; null follows the system language, as the phone does by default. */
    var choice: AppLanguage? by mutableStateOf(null)
        private set

    /** The language in force: the choice, else the system's when the app has it, else English. */
    val language: AppLanguage get() = choice ?: system()

    fun choose(tag: String?) {
        choice = AppLanguage.fromTag(tag)
    }

    private fun system(): AppLanguage = AppLanguage.fromTag(Locale.getDefault().language) ?: AppLanguage.English

    internal fun lookup(text: String): String = language.words()[text] ?: text
}

/** [text] in the current language, as is. */
fun t(text: String): String = I18n.lookup(text)

/** [text] in the current language with [args] filled in (`%s`, `%d`, or `%1$s` when a language needs another order). */
fun t(text: String, vararg args: Any?): String = String.format(I18n.language.locale, I18n.lookup(text), *args)

/**
 * A count and the noun that goes with it. [other] is the English plural and the key; [one] is the singular. A
 * translation holds one form per `|`, in the order of [AppLanguage.pluralForm]. `%d` is the count.
 */
fun tn(n: Int, one: String, other: String, vararg args: Any?): String {
    val language = I18n.language
    val translated = language.words()[other]
    val form = when {
        translated == null -> if (n == 1) one else other
        else -> {
            val forms = translated.split('|')
            forms[language.pluralForm(n).coerceAtMost(forms.lastIndex)]
        }
    }
    return String.format(language.locale, form, n, *args)
}
