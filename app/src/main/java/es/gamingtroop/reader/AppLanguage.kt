package es.gamingtroop.reader

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import java.util.Locale

/** Device preference, deliberately outside account data and reading backups. */
object AppLanguage {
    @Volatile private var resources: Resources? = null
    @Volatile var code: String = "es"
        private set

    val locale: Locale get() = Locale.forLanguageTag(code)

    fun selected(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) return if (locales[0].language == "en") "en" else "es"
        }
        return context.getSharedPreferences("app_language", Context.MODE_PRIVATE).getString("language", "es")
            ?.takeIf { it == "en" || it == "es" } ?: "es"
    }
    fun wrap(context: Context): Context {
        val language = selected(context)
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(config)
    }
    fun initialize(context: Context) {
        code = selected(context)
        resources = wrap(context).resources
        // Update existing channel labels without changing their IDs or user settings.
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        mapOf("downloads" to R.string.tr_597, "app-updates" to R.string.tr_022,
            "reading-voice" to R.string.tr_145, "new-chapters" to R.string.tr_087).forEach { (channelId, text) ->
            manager.getNotificationChannel(channelId)?.let { channel ->
                channel.name = tr(text); manager.createNotificationChannel(channel)
            }
        }
    }
    fun select(context: Context, language: String) {
        require(language == "es" || language == "en")
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("language", language).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        }
        initialize(context)
    }
    internal fun template(@StringRes id: Int): String = resources?.getString(id) ?: defaultTexts.getValue(id)
}

// Only numbered placeholders are substituted. Literal percentages and any %/$
// in user/server data remain untouched; arguments are never reinterpreted.
internal fun formatText(template: String, args: Array<out Any?>): String =
    Regex("%([1-9][0-9]*)\\\$s").replace(template) { match ->
        val index = match.groupValues[1].toInt() - 1
        require(index in args.indices) { "Missing text argument" }
        args[index].toString()
    }
fun tr(@StringRes id: Int, vararg args: Any?): String = formatText(AppLanguage.template(id), args)
internal fun canonicalText(@StringRes id: Int, vararg args: Any?): String = formatText(defaultTexts.getValue(id), args)

/** Translate only known, app-generated status messages, never arbitrary titles/content. */
fun localizedStatus(value: String): String {
    for ((id, template) in defaultTexts) {
        if (template == value) return tr(id)
    }
    // Download counters are persisted by earlier versions in Spanish.
    Regex("^Descargando ([0-9]+)/([0-9]+)$").matchEntire(value)?.let {
        return tr(R.string.tr_610, it.groupValues[1], it.groupValues[2])
    }
    return value
}

@Composable fun LanguageSettings() {
    val context = LocalContext.current
    Column {
        Text(context.getString(R.string.app_language), style = MaterialTheme.typography.titleMedium)
        Row {
            listOf("es" to "Español", "en" to "English").forEach { (code, name) ->
                DisplayChip(AppLanguage.code == code, {
                    if (AppLanguage.code != code) {
                        AppLanguage.select(context, code)
                        // Changing only from login/home settings means no active WebView
                        // reading position is discarded. rememberSaveable restores UI state.
                        (context as? android.app.Activity)?.recreate()
                    }
                }, { Text(name) }, modifier = Modifier.testTag("language-$code"))
            }
        }
        Text(context.getString(R.string.app_language_help), style = MaterialTheme.typography.bodySmall)
    }
}
