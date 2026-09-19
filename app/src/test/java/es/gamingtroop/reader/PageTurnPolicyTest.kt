package es.gamingtroop.reader

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PageTurnPolicyTest {
    private fun swipe(dx: Float = -200f, dy: Float = 0f, rtl: Boolean = false, multi: Boolean = false,
        zoom: Boolean = false, left: Boolean = false, right: Boolean = false, mode: String = "swipe") =
        PageTurnPolicy.swipe(mode, dx, dy, 1f, rtl, multi, zoom, left, right)
    @Test fun edgeTapsUseScreenEdgesAndRespectRtl() {
        for(rtl in listOf(false,true)) {
            assertEquals(if(rtl) -1 else 1, PageTurnPolicy.tap("edges",950f,1000,rtl))
            assertEquals(if(rtl) 1 else -1, PageTurnPolicy.tap("edges",50f,1000,rtl))
            assertEquals(0,PageTurnPolicy.tap("edges",500f,1000,rtl))
        }
        assertEquals(0,PageTurnPolicy.tap("edges",100f,0,false))
        assertEquals(0,PageTurnPolicy.tap("swipe",950f,1000,false))
    }
    @Test fun zoomedPanDoesNotTurnUntilANewSwipeStartsAtTheMatchingBoundary() {
        assertEquals(0,swipe(zoom=true,left=true,right=true))
        assertEquals(0,swipe(200f,zoom=true,left=true,right=false))
        assertEquals(1,swipe(zoom=true,left=true,right=false))
        assertEquals(-1,swipe(200f,zoom=true,left=false,right=true))
        assertEquals(-1,swipe(zoom=true,left=true,right=false,rtl=true))
        assertEquals(1,swipe(200f,zoom=true,left=false,right=true,rtl=true))
    }
    @Test fun edgeModeNeverTurnsByPanningOrSwiping() {
        assertEquals(0,swipe(mode="edges"))
        assertEquals(0,swipe(200f,zoom=true,mode="edges"))
    }
    @Test fun pinchesVerticalAndShortDragsNeverNavigate() {
        assertEquals(0,swipe(multi=true));assertEquals(0,swipe(zoom=true,multi=true))
        assertEquals(0,swipe(-50f));assertEquals(0,swipe(-80f,100f))
        assertEquals(1,swipe());assertEquals(-1,swipe(200f))
    }
    @Test fun missingSettingsUseEdgesAndSerializationKeepsUserChoice() {
        val old=codec.decodeFromString<ReadingSettings>("""{"rtl":true,"wifiOnly":false,"preferLocalChanges":true}""")
        assertEquals("edges",old.pageTurnMode);assertTrue(old.rtl);assertTrue(old.preferLocalChanges)
        val changed=codec.decodeFromString<ReadingSettings>(codec.encodeToString(old.copy(pageTurnMode="swipe")))
        assertEquals("swipe",changed.pageTurnMode);assertTrue(changed.rtl);assertFalse(changed.wifiOnly)
        assertEquals(1,swipe(mode="future"))
    }

    @Test fun nativeEdgeRoundingDoesNotTrapZoomedReadingButInteriorStillPans() {
        assertFalse(PageTurnPolicy.canPan(998,2000,1000,1,5))
        assertFalse(PageTurnPolicy.canPan(2,2000,1000,-1,5))
        assertTrue(PageTurnPolicy.canPan(990,2000,1000,1,5))
        assertTrue(PageTurnPolicy.canPan(10,2000,1000,-1,5))
        assertFalse(PageTurnPolicy.canPan(0,800,1000,1,5))
    }
}
