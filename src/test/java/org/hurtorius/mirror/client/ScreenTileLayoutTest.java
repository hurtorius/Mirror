package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig.Fit;
import org.hurtorius.mirror.client.PreviewConfig.Shape;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ScreenTileLayoutTest {
    private static final float EPS = .00002f;

    private static ScreenMesh.Content tile(Shape shape, float width, float height, Fit fit,
                                           int pixelsW, int pixelsH, int columns, int rows, int column, int row) {
        return ScreenMesh.content(shape, width, height, 140, .2f, fit, pixelsW, pixelsH, 0,
                new ScreenTileLayout.Tile(columns, rows, column, row));
    }
    private static Stream<ScreenMesh.Vertex> vertices(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().flatMap(t -> Stream.of(t.a(), t.b(), t.c()));
    }
    private static double area(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().mapToDouble(t -> Math.abs((t.b().x() - t.a().x()) * (t.c().y() - t.a().y())
                - (t.b().y() - t.a().y()) * (t.c().x() - t.a().x())) / 2).sum();
    }
    private static double uvArea(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().mapToDouble(t -> Math.abs((t.b().u() - t.a().u()) * (t.c().v() - t.a().v())
                - (t.b().v() - t.a().v()) * (t.c().u() - t.a().u())) / 2).sum();
    }
    private static void uvBounds(ScreenMesh.Mesh mesh, double u0, double v0, double u1, double v1) {
        assertFalse(mesh.empty());
        assertEquals(u0, vertices(mesh).mapToDouble(ScreenMesh.Vertex::u).min().orElseThrow(), EPS);
        assertEquals(u1, vertices(mesh).mapToDouble(ScreenMesh.Vertex::u).max().orElseThrow(), EPS);
        assertEquals(v0, vertices(mesh).mapToDouble(ScreenMesh.Vertex::v).min().orElseThrow(), EPS);
        assertEquals(v1, vertices(mesh).mapToDouble(ScreenMesh.Vertex::v).max().orElseThrow(), EPS);
    }

    @ParameterizedTest @EnumSource(Shape.class)
    void oneByOneIsExactlyTheExistingSingleScreen(Shape shape) {
        for (Fit fit : Fit.values()) for (int[] dimensions : new int[][] {{1600,900},{64,1024},{0,0}}) {
            var expected = ScreenMesh.content(shape,4,2.25f,140,.2f,fit,dimensions[0],dimensions[1],.04f);
            var actual = ScreenMesh.content(shape,4,2.25f,140,.2f,fit,dimensions[0],dimensions[1],.04f,
                    new ScreenTileLayout.Tile(1,1,0,0));
            assertEquals(expected,actual);
        }
    }

    @Test void twoByTwoCoversTextureExactlyOnceInTopLeftOrderWithoutFlipping() {
        double total = 0;
        for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
            var content = tile(Shape.FLAT,4,2,Fit.STRETCH,1600,900,2,2,column,row);
            uvBounds(content.image(),column*.5,row*.5,(column+1)*.5,(row+1)*.5);
            assertTrue(content.letterbox().empty());
            final int c = column, r = row;
            vertices(content.image()).forEach(v -> {
                assertEquals((c + (v.x()+2)/4)/2,v.u(),EPS);
                assertEquals((r + (1-v.y())/2)/2,v.v(),EPS);
            });
            total += uvArea(content.image());
        }
        assertEquals(1,total,EPS);
    }

    @Test void fitLetterboxesWholeWallRatherThanRepeatingAnImageOnEveryCell() {
        var left = tile(Shape.FLAT,4,2,Fit.FIT,200,200,2,1,0,0);
        var right = tile(Shape.FLAT,4,2,Fit.FIT,200,200,2,1,1,0);
        uvBounds(left.image(),0,0,.5,1); uvBounds(right.image(),.5,0,1,1);
        assertEquals(1,vertices(left.image()).mapToDouble(ScreenMesh.Vertex::x).min().orElseThrow(),EPS);
        assertEquals(2,vertices(left.image()).mapToDouble(ScreenMesh.Vertex::x).max().orElseThrow(),EPS);
        assertEquals(-2,vertices(right.image()).mapToDouble(ScreenMesh.Vertex::x).min().orElseThrow(),EPS);
        assertEquals(-1,vertices(right.image()).mapToDouble(ScreenMesh.Vertex::x).max().orElseThrow(),EPS);
        assertEquals(6,area(left.letterbox()),EPS); assertEquals(6,area(right.letterbox()),EPS);
        assertEquals(8,area(left.image())+area(left.letterbox()),EPS);
    }

    @Test void horizontalLetterboxDoesNotIntroduceInternalRowGutters() {
        var top = tile(Shape.FLAT,2,2,Fit.FIT,400,100,2,2,0,0);
        var bottom = tile(Shape.FLAT,2,2,Fit.FIT,400,100,2,2,0,1);
        uvBounds(top.image(),0,0,.5,.5); uvBounds(bottom.image(),0,.5,.5,1);
        assertEquals(-1,vertices(top.image()).mapToDouble(ScreenMesh.Vertex::y).min().orElseThrow(),EPS);
        assertEquals(-.5,vertices(top.image()).mapToDouble(ScreenMesh.Vertex::y).max().orElseThrow(),EPS);
        assertEquals(.5,vertices(bottom.image()).mapToDouble(ScreenMesh.Vertex::y).min().orElseThrow(),EPS);
        assertEquals(1,vertices(bottom.image()).mapToDouble(ScreenMesh.Vertex::y).max().orElseThrow(),EPS);
        assertEquals(3,area(top.letterbox()),EPS); assertEquals(3,area(bottom.letterbox()),EPS);
    }

    @Test void fillCropsOuterWallEdgesAndKeepsTileSeamsContinuous() {
        for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
            var content = tile(Shape.FLAT,4,2,Fit.FILL,100,100,2,2,column,row);
            uvBounds(content.image(),column*.5,.25+row*.25,(column+1)*.5,.25+(row+1)*.25);
            assertTrue(content.letterbox().empty());
        }
    }

    @Test void nonsquareCellsAndUnequalRowColumnCountsUseWholeWallAspect() {
        // Tall 2x4 cells in a 3x2 wall form a 6x8 canvas, matching this 3:4 image.
        double total = 0;
        for (int row = 0; row < 2; row++) for (int column = 0; column < 3; column++) {
            var content = tile(Shape.FLAT,2,4,Fit.FIT,300,400,3,2,column,row);
            uvBounds(content.image(),column/3.0,row/2.0,(column+1)/3.0,(row+1)/2.0);
            assertTrue(content.letterbox().empty());
            assertEquals(8,area(content.image()),EPS);
            total += uvArea(content.image());
        }
        assertEquals(1,total,EPS);
        // A different tile aspect correctly changes FIT to outer-wall side gutters.
        var wide = tile(Shape.FLAT,4,2,Fit.FIT,300,400,3,2,0,0);
        assertTrue(wide.image().empty());
        assertEquals(8,area(wide.letterbox()),EPS);
    }

    @Test void originalKeeps128PixelsPerBlockAcrossWallAndAllowsBlankTiles() {
        var corner = tile(Shape.FLAT,2,2,Fit.ORIGINAL,128,128,3,3,0,0);
        var middle = tile(Shape.FLAT,2,2,Fit.ORIGINAL,128,128,3,3,1,1);
        assertTrue(corner.image().empty()); assertEquals(4,area(corner.letterbox()),EPS);
        uvBounds(middle.image(),0,0,1,1);
        assertEquals(1,area(middle.image()),EPS); assertEquals(3,area(middle.letterbox()),EPS);
        assertEquals(1,middle.imageBounds().width(),EPS);
        assertEquals(1,middle.imageBounds().height(),EPS);
    }

    @ParameterizedTest @EnumSource(Shape.class)
    void allShapesClipTheSharedWallWithoutRefittingAndRetainFiniteUvs(Shape shape) {
        for (Fit fit : Fit.values()) for (int row = 0; row < 2; row++) for (int column = 0; column < 3; column++) {
            var content = tile(shape,4,2,fit,512,128,3,2,column,row);
            assertEquals(ScreenTileLayout.imageBounds(4,2,fit,512,128,new ScreenTileLayout.Tile(3,2,column,row)),
                    content.imageBounds());
            vertices(content.image()).forEach(v -> {
                assertTrue(Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z()));
                assertTrue(v.u() >= -EPS && v.u() <= 1+EPS);
                assertTrue(v.v() >= -EPS && v.v() <= 1+EPS);
            });
            if (shape != Shape.CONCAVE && shape != Shape.CONVEX && shape != Shape.PANORAMA) {
                var full = ScreenMesh.surface(shape,4,2,140,.2f,0);
                assertEquals(area(full),area(content.image())+area(content.letterbox()),.0001);
            }
        }
    }

    @Test void shapeClippingAndFadeDoNotPullTheImageInFromTileEdges() {
        var flat = tile(Shape.FLAT,4,2,Fit.FIT,800,400,2,2,0,0);
        var round = ScreenMesh.content(Shape.ROUNDED,4,2,140,.3f,Fit.FIT,800,400,.05f,
                new ScreenTileLayout.Tile(2,2,0,0));
        assertEquals(flat.imageBounds(),round.imageBounds());
        assertTrue(round.letterbox().empty());
        assertTrue(vertices(round.image()).anyMatch(v -> v.alpha()==0));
        assertTrue(vertices(round.image()).anyMatch(v -> v.alpha()==1));
        assertEquals(.5,vertices(round.image()).mapToDouble(ScreenMesh.Vertex::u).max().orElseThrow(),EPS);
        assertEquals(.5,vertices(round.image()).mapToDouble(ScreenMesh.Vertex::v).max().orElseThrow(),EPS);
    }

    @Test void eightByEightLargeWallIsNotClampedToOneScreenSize() {
        var last = tile(Shape.FLAT,32,32,Fit.FIT,2048,2048,8,8,7,7);
        uvBounds(last.image(),.875,.875,1,1);
        assertEquals(256,last.imageBounds().width(),EPS);
        assertEquals(256,last.imageBounds().height(),EPS);
    }

    @Test void malformedSelectionsAreBoundedAndMissingPixelsRemainBackground() {
        assertEquals(new ScreenTileLayout.Tile(1,8,0,7),new ScreenTileLayout.Tile(-10,100,100,100));
        assertEquals(new ScreenTileLayout.Tile(8,1,0,0),new ScreenTileLayout.Tile(100,-10,-10,-10));
        var invalid = tile(Shape.CIRCLE,4,2,Fit.FIT,0,-1,8,8,100,-100);
        assertTrue(invalid.image().empty());
        assertEquals(area(ScreenMesh.surface(Shape.CIRCLE,4,2,140,.2f,0)),area(invalid.letterbox()),EPS);
        var bounds = ScreenTileLayout.imageBounds(Float.NaN,Float.POSITIVE_INFINITY,null,1,1,new ScreenTileLayout.Tile(8,8,7,7));
        assertTrue(Float.isFinite(bounds.x0()) && Float.isFinite(bounds.y0()) && bounds.width()>0 && bounds.height()>0);
    }
}
