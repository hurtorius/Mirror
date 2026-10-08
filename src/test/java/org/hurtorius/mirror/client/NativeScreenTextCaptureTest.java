package org.hurtorius.mirror.client;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native VertexConsumer traffic only; never constructs Font, a GPU atlas or client. */
class NativeScreenTextCaptureTest {
    private static NativeScreenText.Sink sink(){
        var surface=ScreenMesh.surface(PreviewConfig.Shape.FLAT,4,2,90,0,0);
        return new NativeScreenText.Sink(new ScreenTextGeometry.Projector(surface,ScreenTextGeometry.Budget.frame()));
    }
    @Test void finalNativeVertexAttributesAreCommittedOnlyAfterSetters(){
        var sink=sink();
        sink.addVertex(-1,1,0).setColor(0x80664422).setUv(0,0).setLight(0x00A000B0);
        sink.addVertex(-1,-1,0).setColor(0x80664422).setUv(0,1).setLight(0x00A000B0);
        sink.addVertex(1,-1,0).setColor(0x80664422).setUv(1,1).setLight(0x00A000B0);
        sink.addVertex(1,1,0).setColor(0x80664422).setUv(1,0).setLight(0x00A000B0);
        var result=sink.finish();assertFalse(result.isEmpty());
        for(var t:result)for(var v:java.util.List.of(t.a(),t.b(),t.c())){
            assertEquals(0x80664422,v.color());assertEquals(0x00A000B0,v.light());
            assertEquals((v.logicalX()+1)/2,v.u(),.00001);assertEquals((1-v.logicalY())/2,v.v(),.00001);
        }
    }
    @Test void incompleteAndUnexpectedNativeFormatsFailClosed(){
        var partial=sink();partial.addVertex(0,0,0).setColor(-1).setUv(0,0).setLight(0);
        assertTrue(partial.finish().isEmpty());
        var extra=sink();extra.addVertex(0,0,0).setNormal(0,0,1);assertTrue(extra.finish().isEmpty());
    }
}
