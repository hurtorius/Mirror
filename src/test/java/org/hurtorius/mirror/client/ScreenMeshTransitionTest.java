package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig.Fit;
import org.hurtorius.mirror.client.PreviewConfig.Shape;
import org.hurtorius.mirror.client.PreviewConfig.Transition;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ScreenMeshTransitionTest {
    private static Stream<ScreenMesh.Vertex> vertices(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().flatMap(t -> Stream.of(t.a(), t.b(), t.c()));
    }
    private static double area(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().mapToDouble(t -> Math.abs(
                (t.b().logicalX() - t.a().logicalX()) * (t.c().logicalY() - t.a().logicalY())
                - (t.b().logicalY() - t.a().logicalY()) * (t.c().logicalX() - t.a().logicalX())) / 2).sum();
    }
    private static boolean inside(List<ScreenMesh.Point> polygon, float x, float y) {
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            if ((b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x()) < -.0001) return false;
        }
        return true;
    }
    private static ScreenMesh.Mesh transition(ScreenMesh.Mesh mesh, Shape shape, Transition effect, float progress) {
        return ScreenMesh.transition(mesh, shape, 4, 2.25f, 140, .18f,
                TransitionMath.content(effect, progress, false), true, 0);
    }

    @ParameterizedTest @EnumSource(Shape.class)
    void everyTransitionClipsAgainstTheActualSilhouetteAndPreservesValidUvs(Shape shape) {
        var mesh = ScreenMesh.content(shape, 4, 2.25f, 140, .18f, Fit.FILL, 1200, 1000, .04f).image();
        var outline = ScreenMesh.silhouette(shape, 4, 2.25f, .18f);
        for (Transition effect : Transition.values()) for (float p : new float[] {.01f, .23f, .5f, .91f}) {
            var moved = transition(mesh, shape, effect, p);
            assertTrue(moved.triangles().size() <= ScreenMesh.MAX_TRANSITION_TRIANGLES);
            vertices(moved).forEach(v -> {
                assertTrue(inside(outline, v.logicalX(), v.logicalY()), effect + " leaked beyond " + shape);
                assertTrue(v.u() >= -.0001 && v.u() <= 1.0001 && v.v() >= -.0001 && v.v() <= 1.0001);
                assertTrue(Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z()));
                assertTrue(v.alpha() >= -.0001 && v.alpha() <= 1.0001);
                assertEquals(1, Math.hypot(v.nx(), v.nz()), .00001);
            });
        }
    }
    @ParameterizedTest @EnumSource(Transition.class)
    void endpointsNeverInventOrRetainAFrame(Transition effect) {
        var source = ScreenMesh.surface(Shape.ROUNDED, 4, 2.25f, 140, .18f, 0);
        assertSame(source, transition(source, Shape.ROUNDED, effect, 1));
        var start = transition(source, Shape.ROUNDED, effect, 0);
        if (effect == Transition.NONE) assertSame(source, start); else assertTrue(start.empty());
        assertSame(source, ScreenMesh.transition(source, Shape.ROUNDED, 4, 2.25f, 140, .18f,
                TransitionMath.content(effect, .2f, true), true, 0));
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void movingLayersKeepAffineTextureCoordinatesAfterClipping(Shape shape) {
        var original = ScreenMesh.content(shape, 4, 2.25f, 140, .18f, Fit.FILL, 1200, 1000, 0);
        var bounds = original.imageBounds();
        float width = shape == Shape.CIRCLE ? 2.25f : 4;
        for (Transition effect : new Transition[] {Transition.SLIDE, Transition.ZOOM, Transition.UNFOLD}) {
            var plan = TransitionMath.content(effect, .61f, false);
            var moved = transition(original.image(), shape, effect, .61f);
            assertFalse(moved.empty());
            vertices(moved).forEach(v -> {
                float sourceX = bounds.x0() + bounds.width() * v.u();
                float sourceY = bounds.y0() + bounds.height() * (1 - v.v());
                assertEquals(sourceX * plan.scaleX() + width * plan.offsetX(), v.logicalX(), .0001);
                assertEquals(sourceY * plan.scaleY(), v.logicalY(), .0001);
            });
        }
    }
    @Test void rectangularDissolveMatchesExactCellAreaWithoutOverlappingTriangles() {
        var full = ScreenMesh.surface(Shape.FLAT, 4, 2.25f, 140, .18f, 0);
        for (int count : new int[] {1, 7, 24, 48, 79, 95}) {
            float p = (count + .0001f) / TransitionMath.DISSOLVE_CELLS;
            assertEquals(9d * count / TransitionMath.DISSOLVE_CELLS,
                    area(transition(full, Shape.FLAT, Transition.DISSOLVE, p)), .0001);
        }
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void irisIsMonotonicAndImageAndLetterboxKeepTheSameMask(Shape shape) {
        var full = ScreenMesh.surface(shape, 4, 2.25f, 140, .18f, 0);
        var content = ScreenMesh.content(shape, 4, 2.25f, 140, .18f, Fit.FIT, 400, 1200, 0);
        double previous = 0;
        for (float p : new float[] {.1f, .25f, .5f, .75f, .99f, 1}) {
            double next = area(transition(full, shape, Transition.IRIS, p));
            assertTrue(next >= previous - .0001); previous = next;
            assertEquals(next, area(transition(content.image(), shape, Transition.IRIS, p))
                    + area(transition(content.letterbox(), shape, Transition.IRIS, p)), .0002);
        }
        assertEquals(area(full), previous, .0002);
    }
    @Test void appearanceMaskUsesMovedLogicalCoordinatesRatherThanReconstructingThemFromUvs() {
        var original = ScreenMesh.content(Shape.FLAT, 4, 2.25f, 140, .18f, Fit.STRETCH, 100, 100, 0).image();
        var slide = transition(original, Shape.FLAT, Transition.SLIDE, .5f);
        var masked = transition(slide, Shape.FLAT, Transition.IRIS, .35f);
        assertFalse(masked.empty());
        vertices(masked).forEach(v -> {
            assertTrue(v.logicalX() >= -.00001);
            assertEquals(v.u() * 4, v.logicalX(), .0001);
        });
    }
    @ParameterizedTest @EnumSource(Shape.class)
    void frameContoursAndMaximumScanlinesRemainBoundedThroughMasks(Shape shape) {
        var frame = ScreenMesh.edge(shape, 16, 12, 300, .49f, 0, 1, false);
        var grooves = ScreenMesh.edge(shape, 16, 12, 300, .49f, .42f, .62f, true);
        var scans = ScreenMesh.scanlines(shape, 16, 12, 300, .49f, 96, .25f);
        assertFalse(frame.empty()); assertFalse(grooves.empty());
        for (var mesh : new ScreenMesh.Mesh[] {frame, grooves, scans}) for (Transition effect : new Transition[] {Transition.IRIS, Transition.DISSOLVE}) {
            var result = ScreenMesh.transition(mesh, shape, 16, 12, 300, .49f,
                    TransitionMath.appearance(effect, .6f, false), false, 2);
            assertTrue(result.triangles().size() <= ScreenMesh.MAX_TRANSITION_TRIANGLES);
            vertices(result).forEach(v -> assertTrue(Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z())));
        }
    }
}
