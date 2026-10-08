package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EditorDefaultsTest {
    @Test void newScreensAreOffAndBlankWithoutAutomaticContentOrDecoration(){var s=new ScreenSpec();s.validate();assertFalse(s.live);assertEquals(ScreenSpec.Source.BLANK,s.source);assertTrue(s.text.isEmpty());assertTrue(s.url.isEmpty());assertTrue(s.playlist.isEmpty());assertEquals(ScreenSpec.Edge.NONE,s.edge);assertEquals(0,s.glow);assertEquals(0,s.floatAmount);assertEquals(0,s.swayDegrees);assertFalse(s.shadow);assertFalse(s.reflection);assertEquals(ScreenSpec.Source.BLANK,s.copy().source);}
    @Test void explicitlySavedContentAndStyleArePreserved(){var s=ScreenSpec.read("{\"source\":\"TEXT\",\"text\":\"My sign\",\"edge\":\"RUNIC\",\"glow\":0.5,\"live\":true}");assertTrue(s.live);assertEquals("My sign",s.text);assertEquals(ScreenSpec.Edge.RUNIC,s.edge);assertEquals(.5,s.glow);}
    @Test void removingAnEarlierItemKeepsTheCurrentMovieSelected(){var s=new ScreenSpec();s.playlist=new ArrayList<>(List.of("a","b","c"));s.item=1;PlaylistEdits.remove(s,"a");assertEquals("b",s.playlist.get(s.item));}
    @Test void removingSelectedLastItemSelectsThePreviousItemAndDropsItsBranch(){var s=new ScreenSpec();s.playlist=new ArrayList<>(List.of("a","b"));s.item=1;s.branches.add(new ScreenSpec.Branch("NORTH","b"));PlaylistEdits.remove(s,"b");assertEquals(0,s.item);assertTrue(s.branches.isEmpty());}
}
