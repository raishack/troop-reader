package es.gamingtroop.reader

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PageEffectPolicyTest {
    @Test fun existingAccountsKeepEffectsOffAndBothChoicesRoundTrip() {
        val old=codec.decodeFromString<ReadingSettings>("""{"pageTurnMode":"edges","rtl":true,"preferLocalChanges":true}""")
        assertEquals("none",old.pageTurnEffect)
        for(effect in listOf("curl","none")) {
            val restored=codec.decodeFromString<ReadingSettings>(codec.encodeToString(old.copy(pageTurnEffect=effect)))
            assertEquals(effect,restored.pageTurnEffect)
            assertEquals("edges",restored.pageTurnMode)
            assertTrue(restored.rtl);assertTrue(restored.preferLocalChanges)
        }
    }
    @Test fun disabledSystemAnimationsAndUnknownEffectsNeverAnimate() {
        assertTrue(PageEffectPolicy.enabled("curl",true))
        assertFalse(PageEffectPolicy.enabled("curl",false))
        assertFalse(PageEffectPolicy.enabled("none",true))
        assertFalse(PageEffectPolicy.enabled("future",true))
    }
    @Test fun animationFollowsReadingDirectionAndReversesWhenGoingBack() {
        assertTrue(PageEffectPolicy.toLeft(true,false))
        assertFalse(PageEffectPolicy.toLeft(false,false))
        assertFalse(PageEffectPolicy.toLeft(true,true))
        assertTrue(PageEffectPolicy.toLeft(false,true))
    }
}
