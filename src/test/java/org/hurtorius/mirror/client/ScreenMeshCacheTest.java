package org.hurtorius.mirror.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenMeshCacheTest {
    @Test void stableGeometryIsReusedAndSizeOrFitChangesInvalidateIt(){
        var p=new PreviewConfig();var tile=new ScreenTileLayout.Tile(1,1,0,0);
        var a=ScreenMeshCache.content(p,6,3.375f,1280,720,0,tile);
        p.brightness=.5;assertSame(a,ScreenMeshCache.content(p,6,3.375f,1280,720,0,tile));
        assertNotSame(a,ScreenMeshCache.content(p,5,6,1280,720,0,tile));
        p.fit=PreviewConfig.Fit.FILL;assertNotSame(a,ScreenMeshCache.content(p,6,3.375f,1280,720,0,tile));
    }
    @Test void continuousResizingCannotGrowTheCacheWithoutBound(){
        var p=new PreviewConfig();p.shape=PreviewConfig.Shape.CONCAVE;
        for(int i=0;i<200;i++)ScreenMeshCache.surface(p,2+i*.03f,4,0);
        assertTrue(ScreenMeshCache.triangles()<=12000);ScreenMeshCache.clear();assertEquals(0,ScreenMeshCache.triangles());
    }
}
