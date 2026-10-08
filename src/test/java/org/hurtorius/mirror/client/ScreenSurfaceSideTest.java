package org.hurtorius.mirror.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenSurfaceSideTest {
    @Test void rearPictureIsOutsideItsBackingInsteadOfHiddenBehindIt(){
        var mesh=ScreenMesh.surface(PreviewConfig.Shape.FLAT,6,3,45,.1f,0,"");
        for(var t:mesh.triangles()){
            assertEquals(1,ScreenSurfaceSide.side(t,0,0,8));
            assertEquals(-1,ScreenSurfaceSide.side(t,0,0,-8));
            for(var v:java.util.List.of(t.a(),t.b(),t.c())){
                float offset=.009f*ScreenSurfaceSide.side(t,0,0,-8);
                assertTrue(v.z()+v.nz()*offset<v.z());
            }
        }
    }
    @Test void wraparoundWingsChooseTheirOwnSide(){
        var mesh=ScreenMesh.surface(PreviewConfig.Shape.PANORAMA,6,3,300,.1f,0,"");
        var sides=mesh.triangles().stream().map(t->ScreenSurfaceSide.side(t,8,0,0)).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of(-1f,1f),sides);
    }
}
