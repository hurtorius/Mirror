package org.hurtorius.mirror.client;

import org.hurtorius.mirror.core.PolygonShape;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenTextLayoutTest {
    private static ScreenTextLayout layout(int columns,int rows,int column,int row,PreviewConfig.Fit fit){
        return ScreenTextLayout.of(PreviewConfig.Shape.FLAT,4,2,90,.2f,fit,PolygonShape.DEFAULT_CSV,
                new ScreenTileLayout.Tile(columns,rows,column,row),240,6);
    }
    @Test void adjacentWallCellsShareOneCanvasAndScale(){
        for(var fit:PreviewConfig.Fit.values()){
            var left=layout(3,2,0,0,fit);var right=layout(3,2,1,0,fit);var bottom=layout(3,2,0,1,fit);
            assertEquals(left.scaleX(),right.scaleX());assertEquals(left.scaleY(),bottom.scaleY());
            assertEquals(4,left.centerX()-right.centerX(),.00001);assertEquals(2,bottom.centerY()-left.centerY(),.00001);
            for(float pixel:new float[]{-120,0,120})assertEquals(left.x(pixel)-4,right.x(pixel),.00001);
        }
    }
    @Test void originalNativePixelsAndStretchHaveExplicitSemantics(){
        var original=layout(8,8,7,7,PreviewConfig.Fit.ORIGINAL);
        assertEquals(1/128f,original.scaleX(),.000001);assertEquals(1/128f,original.scaleY(),.000001);
        var stretch=layout(3,2,0,0,PreviewConfig.Fit.STRETCH);
        assertNotEquals(stretch.scaleX(),stretch.scaleY());
    }
    @Test void readableRearReflectionKeepsCellTranslation(){
        var left=layout(2,1,0,0,PreviewConfig.Fit.FIT);var right=layout(2,1,1,0,PreviewConfig.Fit.FIT);
        float pixel=80;
        float rearLeft=left.centerX()-pixel*left.scaleX(),rearRight=right.centerX()-pixel*right.scaleX();
        assertEquals(rearLeft-4,rearRight,.00001);
        assertEquals(left.centerX(),left.x(0));
    }
    @Test void offCenterCustomShapeFitsTheActualAuthoredRegion(){
        var custom=ScreenTextLayout.of(PreviewConfig.Shape.CUSTOM,4,2,90,.2f,PreviewConfig.Fit.FIT,
                ".2,-1;1,-1;1,1;.2,1",new ScreenTileLayout.Tile(1,1,0,0),240,6);
        assertTrue(custom.centerX()>.4f);assertTrue(custom.scaleX()>0);assertEquals(custom.scaleX(),custom.scaleY(),.000001);
    }
}
