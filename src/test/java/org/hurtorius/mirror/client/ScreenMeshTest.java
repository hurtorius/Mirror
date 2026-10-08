package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig.Fit;
import org.hurtorius.mirror.client.PreviewConfig.Shape;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ScreenMeshTest {
    private static Stream<ScreenMesh.Vertex> vertices(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().flatMap(t -> Stream.of(t.a(), t.b(), t.c()));
    }
    private static double area(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().mapToDouble(t -> Math.abs((t.b().x() - t.a().x()) * (t.c().y() - t.a().y())
                - (t.b().y() - t.a().y()) * (t.c().x() - t.a().x())) / 2).sum();
    }
    private static boolean inside(List<ScreenMesh.Point> polygon, float x, float y) {
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            if ((b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x()) < -.0001) return false;
        }
        return true;
    }
    private static boolean curved(Shape shape) { return shape == Shape.CONCAVE || shape == Shape.CONVEX || shape == Shape.PANORAMA; }

    @ParameterizedTest @EnumSource(Shape.class)
    void allShapesProduceFiniteBoundedMeshes(Shape shape) {
        var mesh = ScreenMesh.surface(shape, 4, 2.25f, 140, .12f, 0);
        assertFalse(mesh.empty()); assertTrue(mesh.triangles().size() < 10000);
        vertices(mesh).forEach(v -> {
            assertTrue(Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z()));
            assertTrue(Math.abs(v.x()) <= 2.001 && Math.abs(v.y()) <= 1.126 && Math.abs(v.z()) < 10);
            assertEquals(1, Math.sqrt(v.nx() * v.nx() + v.ny() * v.ny() + v.nz() * v.nz()), .00001);
            assertTrue(v.u() >= -.00001 && v.u() <= 1.00001 && v.v() >= -.00001 && v.v() <= 1.00001);
        });
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void fitKeepsAllImageCornersInsideSilhouette(Shape shape) {
        for (int[] size : new int[][] {{1600,900},{100,1000},{2000,100}}) {
            var content = ScreenMesh.content(shape, 4, 2.25f, 60, .2f, Fit.FIT, size[0], size[1], 0);
            var bounds = content.imageBounds(); var silhouette = ScreenMesh.silhouette(shape, 4, 2.25f, .2f);
            for (float x : new float[] {bounds.x0(),bounds.x1()})
                for (float y : new float[] {bounds.y0(),bounds.y1()}) assertTrue(inside(silhouette,x,y));
            assertEquals((double)size[0]/size[1],bounds.width()/bounds.height(),.0001);
            assertFalse(content.image().empty());
        }
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void fitAndLetterboxPartitionTheFlatSilhouette(Shape shape) {
        if (curved(shape)) return;
        var full = ScreenMesh.surface(shape, 4, 2.25f, 60, .17f, 0);
        for (Fit fit : Fit.values()) {
            var content = ScreenMesh.content(shape,4,2.25f,60,.17f,fit,600,1000,0);
            assertEquals(area(full),area(content.image())+area(content.letterbox()),.0002);
        }
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void croppedTextureCoordinatesRemainValid(Shape shape) {
        for (Fit fit : Fit.values()) {
            var content = ScreenMesh.content(shape,4,2.25f,120,.15f,fit,2048,100,0);
            vertices(content.image()).forEach(v -> {
                assertTrue(v.u() >= -.00001 && v.u() <= 1.00001, "u="+v.u());
                assertTrue(v.v() >= -.00001 && v.v() <= 1.00001, "v="+v.v());
            });
        }
    }
    @Test void fillCropsAndStretchUsesAllTextureCoordinates() {
        var fill = ScreenMesh.content(Shape.FLAT,4,2,45,.08f,Fit.FILL,100,100,0);
        assertEquals(-2,fill.imageBounds().y0()); assertEquals(2,fill.imageBounds().y1());
        assertTrue(fill.letterbox().empty());
        assertEquals(.25,vertices(fill.image()).mapToDouble(ScreenMesh.Vertex::v).min().orElseThrow(),.00001);
        assertEquals(.75,vertices(fill.image()).mapToDouble(ScreenMesh.Vertex::v).max().orElseThrow(),.00001);
        var stretch = ScreenMesh.content(Shape.FLAT,4,2,45,.08f,Fit.STRETCH,100,100,0);
        assertEquals(4,stretch.imageBounds().width()); assertEquals(2,stretch.imageBounds().height());
        assertEquals(0,vertices(stretch.image()).mapToDouble(ScreenMesh.Vertex::v).min().orElseThrow());
        assertEquals(1,vertices(stretch.image()).mapToDouble(ScreenMesh.Vertex::v).max().orElseThrow());
    }
    @Test void originalMeans128PixelsPerWorldBlock() {
        var original=ScreenMesh.content(Shape.FLAT,4,2,45,.08f,Fit.ORIGINAL,256,128,0);
        assertEquals(2,original.imageBounds().width()); assertEquals(1,original.imageBounds().height());
        assertEquals(2,area(original.image()),.00001); assertEquals(6,area(original.letterbox()),.00001);
    }
    @Test void circleUsesSmallerDiameterAndOvalUsesBothDimensions() {
        var circle=ScreenMesh.surface(Shape.CIRCLE,4,2,45,.08f,0);
        assertEquals(Math.PI,area(circle),.006);
        var oval=ScreenMesh.surface(Shape.OVAL,4,2,45,.08f,0);
        assertEquals(2*Math.PI,area(oval),.012);
        vertices(circle).forEach(v -> assertTrue(v.x()*v.x()+v.y()*v.y()<=1.0001));
    }
    @Test void concaveAndConvexHaveOppositeDepthAndTessellation() {
        var concave=ScreenMesh.surface(Shape.CONCAVE,4,2,100,.08f,0);
        var convex=ScreenMesh.surface(Shape.CONVEX,4,2,100,.08f,0);
        assertTrue(concave.triangles().size()>10);
        assertTrue(vertices(concave).allMatch(v->v.z()>=-.00001));
        assertTrue(vertices(convex).allMatch(v->v.z()<=.00001));
        assertEquals(vertices(concave).mapToDouble(ScreenMesh.Vertex::z).max().orElseThrow(),
                -vertices(convex).mapToDouble(ScreenMesh.Vertex::z).min().orElseThrow(),.00001);
        var panorama=ScreenMesh.surface(Shape.PANORAMA,4,2,300,.08f,0);
        assertTrue(panorama.triangles().size()>=100);
        assertTrue(vertices(panorama).anyMatch(v->v.nz()<0));
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void fadeRetainsOpaqueCoreAndTransparentBoundary(Shape shape) {
        var fade=ScreenMesh.surface(shape,4,2.25f,120,.12f,.05f);
        assertTrue(vertices(fade).anyMatch(v->v.alpha()==0));
        assertTrue(vertices(fade).anyMatch(v->v.alpha()==1));
        assertTrue(vertices(fade).allMatch(v->v.alpha()>=0&&v.alpha()<=1));
        if(!curved(shape)) assertEquals(area(ScreenMesh.surface(shape,4,2.25f,120,.12f,0)),area(fade),.0002);
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void bordersAndScanlinesRemainBounded(Shape shape) {
        var border=ScreenMesh.edge(shape,16,12,300,.49f,0,.25f,false);
        var dashed=ScreenMesh.edge(shape,16,12,300,.49f,0,.25f,true);
        assertFalse(border.empty()); assertTrue(dashed.triangles().size()<border.triangles().size());
        assertTrue(border.triangles().size()<10000);
        vertices(border).forEach(v->assertTrue(Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z())));
        var scans=ScreenMesh.scanlines(shape,16,12,300,.49f,500,0);
        assertTrue(scans.triangles().size()<20000);
    }
    @Test void panoramicNativeTextFitsTheCentralArcRatherThanTheWholeUnrolledWidth() {
        var text = ScreenMesh.inscribedText(Shape.PANORAMA, 16, 9, 300, .08f);
        assertTrue(text.width() < 6 && text.width() > 5);
        assertEquals(9, text.height());
        assertTrue(ScreenMesh.textPlaneZ(Shape.PANORAMA, 16, 300) < 1.6f);
        assertEquals(.022f, ScreenMesh.textPlaneZ(Shape.CONVEX, 16, 120));
    }
    @Test void degenerateInputsAndFullyRoundedSquareRemainFinite() {
        var rounded=ScreenMesh.edge(Shape.ROUNDED,2,2,45,.5f,0,.02f,false);
        vertices(rounded).forEach(v->assertTrue(Float.isFinite(v.x())&&Float.isFinite(v.y())));
        var invalid=ScreenMesh.surface(null,Float.NaN,Float.POSITIVE_INFINITY,Float.NaN,Float.NaN,.01f);
        vertices(invalid).forEach(v->assertTrue(Float.isFinite(v.x())&&Float.isFinite(v.y())&&Float.isFinite(v.z())));
        assertTrue(ScreenMesh.content(Shape.FLAT,4,2,45,.08f,Fit.FIT,0,0,0).image().empty());
    }
}
