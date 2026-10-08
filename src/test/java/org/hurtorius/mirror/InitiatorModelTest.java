package org.hurtorius.mirror;
import org.hurtorius.mirror.core.InitiatorModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InitiatorModelTest {
    @Test void baseUsesRecoveredThreePartOriginalAtlas(){
        var m=InitiatorModel.MODEL;assertEquals(3,m.bounds().size());assertEquals(18,m.faces().size());
        assertTrue(m.faces().stream().allMatch(f->f.texture().equals("mirror:block/initiator_atlas")));
        assertEquals(2/16f,m.bounds().getFirst().x0());assertEquals(14/16f,m.bounds().getFirst().x1());
        assertEquals(4.5f/16,m.bounds().getLast().y1());
    }
    @Test void baseAtlasUvsStayInItsAuthoredQuarter(){
        for(var f:InitiatorModel.MODEL.faces())for(float uv:f.uv())assertTrue(uv>=0&&uv<=.5f);
    }
}
