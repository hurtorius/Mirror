package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectorGeometryTest {
    private static final double EPS = 2e-6;

    private static ScreenMesh.Vertex vertex(float x, float y) {
        // Position and normals deliberately disagree with logical XY, as on a curved screen.
        return new ScreenMesh.Vertex(x + 17, y - 9, 4 + x * .2f, 2 + x * .3f, -.25f + y * .7f,
                .6f, 0, .8f, .5f + .05f * x + .08f * y, x, y);
    }

    private static ScreenMesh.Mesh rectangle(float x0, float y0, float x1, float y1) {
        var a = vertex(x0, y0); var b = vertex(x1, y0); var c = vertex(x1, y1); var d = vertex(x0, y1);
        return new ScreenMesh.Mesh(List.of(new ScreenMesh.Triangle(a, b, c), new ScreenMesh.Triangle(a, c, d)), List.of(a, b, c, d));
    }

    private static List<ScreenMesh.Point> mask(float x0, float y0, float x1, float y1) {
        return List.of(new ScreenMesh.Point(x0, y0), new ScreenMesh.Point(x1, y0),
                new ScreenMesh.Point(x1, y1), new ScreenMesh.Point(x0, y1));
    }

    private static Stream<ScreenMesh.Vertex> vertices(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().flatMap(t -> Stream.of(t.a(), t.b(), t.c()));
    }

    private static double area(ScreenMesh.Mesh mesh) {
        return mesh.triangles().stream().mapToDouble(t -> Math.abs(
                ((double) t.b().logicalX() - t.a().logicalX()) * (t.c().logicalY() - t.a().logicalY())
                        - ((double) t.b().logicalY() - t.a().logicalY()) * (t.c().logicalX() - t.a().logicalX())) / 2).sum();
    }

    private static void assertAttributes(ScreenMesh.Mesh mesh) {
        assertTrue(mesh.boundary().isEmpty());
        vertices(mesh).forEach(v -> {
            assertEquals(v.logicalX(), v.x(), 0); assertEquals(v.logicalY(), v.y(), 0);
            assertEquals(0, v.z(), 0); assertEquals(0, v.nx(), 0); assertEquals(0, v.ny(), 0); assertEquals(1, v.nz(), 0);
            assertEquals(2 + v.logicalX() * .3f, v.u(), EPS);
            assertEquals(-.25f + v.logicalY() * .7f, v.v(), EPS);
            assertEquals(.5f + .05f * v.logicalX() + .08f * v.logicalY(), v.alpha(), EPS);
        });
    }

    @Test void finiteReceiverEdgesAndAnUncoveredHoleClipTheWholeSurface() {
        var receivers = List.of(mask(-1.5f, -1.5f, 1.5f, -.5f), mask(-1.5f, .5f, 1.5f, 1.5f),
                mask(-1.5f, -.5f, -.5f, .5f), mask(.5f, -.5f, 1.5f, .5f));
        var budget = ProjectorGeometry.Budget.panel();
        var output = ProjectorGeometry.clip(rectangle(-2, -2, 2, 2), receivers, budget);
        assertFalse(budget.failed()); assertEquals(8, area(output), EPS); assertAttributes(output);
        vertices(output).forEach(v -> {
            assertTrue(v.x() >= -1.5f - EPS && v.x() <= 1.5f + EPS);
            assertTrue(v.y() >= -1.5f - EPS && v.y() <= 1.5f + EPS);
        });
        for (var t : output.triangles()) {
            float x = (t.a().x() + t.b().x() + t.c().x()) / 3, y = (t.a().y() + t.b().y() + t.c().y()) / 3;
            assertTrue(Math.abs(x) >= .5f - EPS || Math.abs(y) >= .5f - EPS, "No triangle may bridge the hole");
        }
    }

    @Test void rolledReceiverAndBothWindingsPreserveInterpolatedUvAlphaAndLogicalCoordinates() {
        var diamond = List.of(new ScreenMesh.Point(0, -1), new ScreenMesh.Point(1, 0),
                new ScreenMesh.Point(0, 1), new ScreenMesh.Point(-1, 0));
        var original = rectangle(-2, -2, 2, 2);
        var reversed = new ScreenMesh.Mesh(original.triangles().stream()
                .map(t -> new ScreenMesh.Triangle(t.a(), t.c(), t.b())).toList(), List.of());
        for (var mesh : List.of(original, reversed)) for (var polygon : List.of(diamond, diamond.reversed())) {
            var budget = ProjectorGeometry.Budget.panel();
            var output = ProjectorGeometry.clip(mesh, List.of(polygon), budget);
            assertFalse(budget.failed()); assertEquals(2, area(output), EPS); assertAttributes(output);
            vertices(output).forEach(v -> assertTrue(Math.abs(v.x()) + Math.abs(v.y()) <= 1 + EPS));
        }
    }

    @Test void receiverCutsRetainSourceFitAndTileUvsWithoutRenormalizing() {
        var source = ScreenMesh.content(PreviewConfig.Shape.FLAT, 4, 2, 0, 0, PreviewConfig.Fit.FILL,
                1920, 1080, 0, new ScreenTileLayout.Tile(3, 2, 1, 0)).image();
        var output = ProjectorGeometry.clip(source, List.of(mask(-.4f, -.3f, .6f, .5f)), ProjectorGeometry.Budget.panel());
        assertFalse(output.empty()); assertEquals(.8, area(output), EPS);
        // All source triangles share one affine UV mapping across this rectangular tile.
        var a = source.triangles().getFirst().a();
        var uMin = vertices(source).min(java.util.Comparator.comparingDouble(ScreenMesh.Vertex::logicalX)).orElseThrow();
        var uMax = vertices(source).max(java.util.Comparator.comparingDouble(ScreenMesh.Vertex::logicalX)).orElseThrow();
        var vMin = vertices(source).min(java.util.Comparator.comparingDouble(ScreenMesh.Vertex::logicalY)).orElseThrow();
        var vMax = vertices(source).max(java.util.Comparator.comparingDouble(ScreenMesh.Vertex::logicalY)).orElseThrow();
        vertices(output).forEach(v -> {
            double tx = (v.logicalX() - uMin.logicalX()) / (uMax.logicalX() - uMin.logicalX());
            double ty = (v.logicalY() - vMin.logicalY()) / (vMax.logicalY() - vMin.logicalY());
            assertEquals(uMin.u() + tx * (uMax.u() - uMin.u()), v.u(), EPS);
            assertEquals(vMin.v() + ty * (vMax.v() - vMin.v()), v.v(), EPS);
            assertEquals(a.alpha(), v.alpha(), EPS);
        });
    }

    @Test void curvedPanelsFlattenTheirLogicalFootprintBeforeClipping() {
        for (var shape : List.of(PreviewConfig.Shape.CONCAVE, PreviewConfig.Shape.CONVEX, PreviewConfig.Shape.PANORAMA)) {
            var source = ScreenMesh.surface(shape, 4, 2, 160, .2f, 0);
            assertTrue(vertices(source).anyMatch(v -> Math.abs(v.x() - v.logicalX()) > .1f));
            var budget = ProjectorGeometry.Budget.panel();
            var output = ProjectorGeometry.clip(source, List.of(mask(1.7f, -1, 2, 1)), budget);
            assertFalse(budget.failed(), shape.name()); assertEquals(.6, area(output), EPS, shape.name());
            vertices(output).forEach(v -> {
                assertTrue(v.x() >= 1.7f - EPS); assertEquals(v.logicalX(), v.x(), 0);
                assertEquals(v.logicalY(), v.y(), 0); assertEquals(0, v.z(), 0); assertEquals(1, v.nz(), 0);
            });
        }
    }

    @Test void appearanceScaleAndSlideAreAppliedOnlyToTheReceiverTest() {
        var budget = ProjectorGeometry.Budget.panel();
        var output = ProjectorGeometry.clip(rectangle(-2, -2, 2, 2), List.of(mask(-.5f, -1, .5f, 1)), .5f, 2, .75f, budget);
        assertFalse(budget.failed()); assertEquals(1.5, area(output), EPS); assertAttributes(output);
        vertices(output).forEach(v -> {
            assertTrue(v.x() >= -2 - EPS && v.x() <= -.5f + EPS);
            assertTrue(v.x() * .5f + .75f >= -.5f - EPS && v.x() * .5f + .75f <= .5f + EPS);
            assertTrue(Math.abs(v.y() * 2) <= 1 + EPS);
        });
        var reflected = ProjectorGeometry.clip(rectangle(-2, -2, 2, 2),
                List.of(mask(-1, -.5f, 0, .5f)), -.5f, .5f, -.5f, ProjectorGeometry.Budget.panel());
        assertEquals(4, area(reflected), EPS); assertAttributes(reflected);
    }

    @Test void emptyReceiversOutsideTrianglesAndCollapsedAppearanceRemainEmptyWithoutFailure() {
        var source = rectangle(-2, -2, 2, 2);
        var budget = ProjectorGeometry.Budget.panel();
        assertTrue(ProjectorGeometry.clip(source, List.of(), budget).empty());
        assertTrue(ProjectorGeometry.clip(source, List.of(mask(10, 10, 11, 11)), budget).empty());
        assertTrue(ProjectorGeometry.clip(source, List.of(mask(-2, -2, 2, 2)), 0, 1, 0, budget).empty());
        assertTrue(ProjectorGeometry.clip(source, List.of(mask(-2, -2, 2, 2)), 1, 0, 0, budget).empty());
        assertFalse(budget.failed());
    }

    @Test void inputOutputAndWorkCapsFailClosedAndStayNonnegative() {
        var source = rectangle(-1, -1, 1, 1); var receivers = List.of(mask(-2, -2, 2, 2));
        var input = new ProjectorGeometry.Budget(1, 100, 10000);
        assertTrue(ProjectorGeometry.clip(source, receivers, input).empty()); assertTrue(input.failed());
        assertEquals(1, input.remainingInputTriangles());
        var output = new ProjectorGeometry.Budget(10, 1, 10000);
        assertTrue(ProjectorGeometry.clip(source, receivers, output).empty()); assertTrue(output.failed());
        assertEquals(0, output.remainingTriangles());
        var work = new ProjectorGeometry.Budget(10, 100, 10);
        assertTrue(ProjectorGeometry.clip(source, receivers, work).empty()); assertTrue(work.failed());
        assertEquals(0, work.remainingWork());
        for (var budget : List.of(input, output, work)) {
            assertTrue(ProjectorGeometry.clip(source, receivers, budget).empty());
            assertTrue(budget.remainingInputTriangles() >= 0 && budget.remainingTriangles() >= 0 && budget.remainingWork() >= 0);
        }
    }

    @Test void aSharedPanelBudgetInvalidatesTheBundleWhenALaterLayerFails() {
        var source = rectangle(-1, -1, 1, 1); var receivers = List.of(mask(-2, -2, 2, 2));
        var budget = new ProjectorGeometry.Budget(3, 100, 10000);
        assertFalse(ProjectorGeometry.clip(source, receivers, budget).empty()); assertFalse(budget.failed());
        assertTrue(ProjectorGeometry.clip(source, receivers, budget).empty()); assertTrue(budget.failed());
        assertTrue(ProjectorGeometry.clip(source, receivers, budget).empty());
        var explicit = ProjectorGeometry.Budget.panel(); explicit.fail();
        assertTrue(ProjectorGeometry.clip(source, receivers, explicit).empty());
    }

    @Test void everyNonfiniteSourceAttributeAndMalformedMaskFailClosed() {
        for (int attribute = 0; attribute < 12; attribute++) {
            float[] fields = {0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0};
            // Vertex has eleven fields; the last pass checks infinity as well as NaN.
            fields[Math.min(attribute, 10)] = attribute == 11 ? Float.POSITIVE_INFINITY : Float.NaN;
            var bad = new ScreenMesh.Vertex(fields[0], fields[1], fields[2], fields[3], fields[4],
                    fields[5], fields[6], fields[7], fields[8], fields[9], fields[10]);
            var source = new ScreenMesh.Mesh(List.of(new ScreenMesh.Triangle(bad, vertex(1, 0), vertex(0, 1))), List.of());
            var budget = ProjectorGeometry.Budget.panel();
            assertTrue(ProjectorGeometry.clip(source, List.of(mask(-2, -2, 2, 2)), budget).empty()); assertTrue(budget.failed());
        }
        var malformed = List.of(List.<ScreenMesh.Point>of(),
                List.of(new ScreenMesh.Point(0, 0), new ScreenMesh.Point(1, 1)),
                List.of(new ScreenMesh.Point(0, 0), new ScreenMesh.Point(1, 0), new ScreenMesh.Point(Float.NaN, 1)),
                List.of(new ScreenMesh.Point(0, 0), new ScreenMesh.Point(2, 0), new ScreenMesh.Point(1, .5f), new ScreenMesh.Point(2, 2), new ScreenMesh.Point(0, 2)),
                List.of(new ScreenMesh.Point(0, 1), new ScreenMesh.Point(.588f, -.809f), new ScreenMesh.Point(-.951f, .309f),
                        new ScreenMesh.Point(.951f, .309f), new ScreenMesh.Point(-.588f, -.809f)),
                List.of(new ScreenMesh.Point(0, 0), new ScreenMesh.Point(1, 0), new ScreenMesh.Point(1, 0), new ScreenMesh.Point(0, 1)));
        for (var polygon : malformed) {
            var budget = ProjectorGeometry.Budget.panel();
            assertTrue(ProjectorGeometry.clip(rectangle(-2, -2, 2, 2), List.of(polygon), budget).empty()); assertTrue(budget.failed());
        }
        var budget = ProjectorGeometry.Budget.panel();
        assertTrue(ProjectorGeometry.clip(rectangle(-1, -1, 1, 1), List.of(mask(-2, -2, 2, 2)), Float.NaN, 1, 0, budget).empty());
        assertTrue(budget.failed());
    }

    private static ProjectorGeometry.Point point(double x, double y, double z) { return new ProjectorGeometry.Point(x, y, z); }
    private static ProjectorGeometry.Box box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new ProjectorGeometry.Box(x0, y0, z0, x1, y1, z1);
    }
    private static ProjectorGeometry.Patch wall() {
        return new ProjectorGeometry.Patch(List.of(point(-2, -2, 10), point(2, -2, 10), point(2, 2, 10), point(-2, 2, 10)));
    }

    @Test void coneUsesPerspectiveAndDetectsThinOffAxisBlockers() {
        var source = point(0, 0, 0); var wall = wall();
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall, box(-.1, -.1, 4, .1, .1, 5)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(source, wall, box(3, 3, 4, 4, 4, 5)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(source, wall, box(1.1, -.1, 4, 1.2, .1, 4.1)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall, box(.78, -.01, 3.9, .82, .01, 4.1)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall, box(.75, .2, 4.9999999, .75000001, .20000001, 5.0000001)));
        var offset = point(3, -2, 0);
        assertTrue(ProjectorGeometry.potentiallyBlocked(offset, wall, box(1.49, -1.01, 4.99, 1.51, -.99, 5.01)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(offset, wall, box(-1.9, -.1, 4.9, -1.8, .1, 5.1)));
    }

    @Test void receiverAndSourcePlanesExcludeOnlyBoxesEntirelyOutsideTheOpenSlab() {
        var source = point(0, 0, 0); var wall = wall();
        assertFalse(ProjectorGeometry.potentiallyBlocked(source, wall, box(-1, -1, 10, 1, 1, 12)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(source, wall, box(-1, -1, 10, 1, 1, 10)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(source, wall, box(-1, -1, -2, 1, 1, 0)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall, box(-.1, -.1, 10 - 1e-12, .1, .1, 10 + 1e-12)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall, box(-.1, -.1, -1e-12, .1, .1, 1e-12)));
        var rearSource = point(0, 0, 20);
        assertTrue(ProjectorGeometry.potentiallyBlocked(rearSource, wall, box(-.1, -.1, 14, .1, .1, 15)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(rearSource, wall, box(-1, -1, 8, 1, 1, 10)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(rearSource, wall, box(-1, -1, 20, 1, 1, 22)));
    }

    @Test void coneSupportsRotatedAndSlantedWorldPlanes() {
        var side = new ProjectorGeometry.Patch(List.of(point(10, -2, -2), point(10, 2, -2), point(10, 2, 2), point(10, -2, 2)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(point(0, 0, 0), side, box(4, -.1, -.1, 5, .1, .1)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(point(0, 0, 0), side, box(10, -1, -1, 12, 1, 1)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(point(0, 0, 0), side, box(-2, -1, -1, 0, 1, 1)));
        var slanted = new ProjectorGeometry.Patch(List.of(point(8, -2, 2), point(12, -2, -2), point(12, 2, -2), point(8, 2, 2)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(point(0, 0, 0), slanted, box(4.9, -.1, -.1, 5.1, .1, .1)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(point(0, 0, 0), slanted, box(12, -.1, 0, 13, .1, 1)));
        assertFalse(ProjectorGeometry.potentiallyBlocked(point(0, 0, 0), slanted, box(-2, -.1, -2, -1, .1, -1)));
    }

    @Test void everySampledRayIntersectionIsConservativelyRetainedIncludingLargeWorldCoordinates() {
        for (double origin : new double[]{0, 30_000_000}) {
            var source = point(origin + .7, -.2, -3);
            var corners = List.of(point(origin - 2, -2, 7), point(origin + 2, -2, 7), point(origin + 2, 2, 7), point(origin - 2, 2, 7));
            for (var ordered : List.of(corners, corners.reversed())) {
                var patch = new ProjectorGeometry.Patch(ordered);
                for (double x : new double[]{-2, -.7, 0, 1.3, 2}) for (double y : new double[]{-2, -.4, 0, .8, 2})
                    for (double t : new double[]{.1, .5, .9}) {
                        double px = source.x() * (1 - t) + (origin + x) * t;
                        double py = source.y() * (1 - t) + y * t, pz = source.z() * (1 - t) + 7 * t;
                        assertTrue(ProjectorGeometry.potentiallyBlocked(source, patch,
                                box(px - 1e-7, py - 1e-7, pz - 1e-7, px + 1e-7, py + 1e-7, pz + 1e-7)),
                                "A box containing a source-to-receiver ray must never pass through");
                    }
            }
        }
    }

    @Test void malformedConeInputsConservativelyBlock() {
        var valid = box(-1, -1, 4, 1, 1, 5); var source = point(0, 0, 0);
        assertTrue(ProjectorGeometry.potentiallyBlocked(point(Double.NaN, 0, 0), wall(), valid));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall(), box(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, wall(), box(1, 0, 0, 0, 1, 1)));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, new ProjectorGeometry.Patch(List.of()), valid));
        assertTrue(ProjectorGeometry.potentiallyBlocked(point(0, 0, 10), wall(), valid));
        var nonplanar = new ArrayList<>(wall().corners()); nonplanar.set(3, point(-2, 2, 10.1));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, new ProjectorGeometry.Patch(nonplanar), valid));
        var malformed = new ArrayList<>(wall().corners()); malformed.set(1, point(Double.NaN, 0, 10));
        assertTrue(ProjectorGeometry.potentiallyBlocked(source, new ProjectorGeometry.Patch(malformed), valid));
    }
}
