package es.gamingtroop.reader

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
class LanguageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun reset() { AppLanguage.select(context, "es") }
    @Test fun placeholdersNeverInterpretTitlesOrPercentCharacters() {
        assertEquals("Book %2\$s 50% · Part 3", formatText("%1\$s · Part %2\$s", arrayOf("Book %2\$s 50%", 3)))
    }
    @Test fun resourcesAndEnumLabelsChangeBothWaysWithoutProcessRestart() {
        AppLanguage.select(context,"es")
        assertEquals("Libros",ReadingCategory.BOOK.label)
        assertEquals("Pantalla normal",DisplayMode.NORMAL.label)
        AppLanguage.select(context,"en")
        assertEquals("Books",ReadingCategory.BOOK.label)
        assertEquals("Standard screen",DisplayMode.NORMAL.label)
        assertEquals("Page 7",tr(R.string.tr_062,7))
        assertEquals("New version available",tr(R.string.tr_036))
        AppLanguage.select(context,"es")
        assertEquals("Libros",ReadingCategory.BOOK.label)
        assertEquals("Página 7",tr(R.string.tr_062,7))
    }
    @Test fun legacyRemovedDownloadsStayRemovedInEitherLanguage() {
        val removed = SavedChapter(Chapter(1,pages=5),Series(1,"A title"),false,state="Eliminado del dispositivo")
        val encoded=codec.encodeToString(SavedChapter.serializer(),removed)
        AppLanguage.select(context,"en")
        assertFalse(removed.inQueue)
        assertFalse(codec.decodeFromString<SavedChapter>(encoded).inQueue)
        assertEquals("Removed from device",localizedStatus(removed.state))
        assertEquals(encoded,codec.encodeToString(SavedChapter.serializer(),removed))
        AppLanguage.select(context,"es")
        assertFalse(removed.inQueue)
    }
    @Test fun devicePreferenceSurvivesReinitializationAndTitlesAreNotTranslated() {
        AppLanguage.select(context,"en");AppLanguage.initialize(context)
        assertEquals("en",AppLanguage.selected(context))
        val chapter=Chapter(1,title="El nombre del viento",displayTitle="El nombre del viento")
        assertEquals("El nombre del viento",chapter.labelFor(Series(1,"Series"),emptyList()))
        assertEquals("Volume 3 · Part 2",localizedGeneratedLabel("Tomo 3 · Parte 2"))
        assertEquals("Título de un libro",localizedGeneratedLabel("Título de un libro"))
    }
    @Test fun everyMigratedResourceHasValidArgumentsInBothLanguages() {
        for(language in listOf("es","en")) {
            AppLanguage.select(context,language)
            for((id, source) in defaultTexts) {
                val expected=Regex("%[0-9]+\\\$s|%\\.[0-9]+f").findAll(source).map { it.value }.toList().sorted()
                val actual=Regex("%[0-9]+\\\$s|%\\.[0-9]+f").findAll(AppLanguage.template(id)).map { it.value }.toList().sorted()
                assertEquals("Placeholder mismatch: $id / $language",expected,actual)
            }
        }
    }
}
