package org.hurtorius.mirror.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InitiatorDecorationGeometryTest {
    private static final double EPSILON = 1e-6;

    @Test void meshesAreCachedDeeplyImmutableAndAlwaysQuadShaped() {
        assertSame(InitiatorDecorationGeometry.crystal(), InitiatorDecorationGeometry.crystal());
        assertEquals(8, InitiatorDecorationGeometry.crystal().size());
        assertThrows(UnsupportedOperationException.class, () -> InitiatorDecorationGeometry.crystal().clear());
        for (var facet : InitiatorDecorationGeometry.crystal()) {
            assertEquals(4, facet.quad().vertices().size());
            assertThrows(UnsupportedOperationException.class, () -> facet.quad().vertices().clear());
        }
        for (int index = 0; index < InitiatorVisualProfile.RINGS.size(); index++) {
            assertSame(InitiatorDecorationGeometry.ring(index), InitiatorDecorationGeometry.ring(index));
            assertEquals(48, InitiatorDecorationGeometry.ring(index).size());
            int ringIndex = index;
            assertThrows(UnsupportedOperationException.class, () -> InitiatorDecorationGeometry.ring(ringIndex).clear());
            for (var quad : InitiatorDecorationGeometry.ring(index)) {
                assertEquals(4, quad.vertices().size());
                assertThrows(UnsupportedOperationException.class, () -> quad.vertices().clear());
            }
        }
        var vertices = new ArrayList<>(InitiatorDecorationGeometry.crystal().getFirst().quad().vertices());
        var copied = new InitiatorDecorationGeometry.Quad(vertices);
        vertices.clear();
        assertEquals(4, copied.vertices().size());
        assertThrows(IllegalArgumentException.class, () -> new InitiatorDecorationGeometry.Quad(List.of()));
        assertThrows(IndexOutOfBoundsException.class, () -> InitiatorDecorationGeometry.ring(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> InitiatorDecorationGeometry.ring(2));
    }

    @Test void crystalUsesTheAuthoredRimOrderUvsAndFacetPaletteIndices() {
        for (int index = 0; index < 4; index++) {
            var top = InitiatorDecorationGeometry.crystal().get(index * 2);
            var bottom = InitiatorDecorationGeometry.crystal().get(index * 2 + 1);
            assertEquals(InitiatorVisualProfile.TOP_MIX_TO, top.mixTarget());
            assertEquals(InitiatorVisualProfile.TOP_MIX_AMOUNTS.get(index), top.mixAmount());
            assertEquals(InitiatorVisualProfile.BOTTOM_MIX_TO, bottom.mixTarget());
            assertEquals(InitiatorVisualProfile.BOTTOM_MIX_AMOUNTS.get(index), bottom.mixAmount());
            assertTriangle(top.quad(), InitiatorVisualProfile.CRYSTAL_TOP, (index + 1) % 4, index, InitiatorVisualProfile.TOP_UV);
            assertTriangle(bottom.quad(), -InitiatorVisualProfile.CRYSTAL_BOTTOM, index, (index + 1) % 4, InitiatorVisualProfile.BOTTOM_UV);
        }
    }

    @Test void everyNormalIsFiniteUnitLengthAndMatchesOutwardFaceWinding() {
        for (var facet : InitiatorDecorationGeometry.crystal()) {
            assertFace(facet.quad());
            var a = facet.quad().vertices().getFirst();
            assertTrue(dot(normal(a), centroid(facet.quad())) > 0, "Crystal normal must point away from its center");
        }
        for (int ringIndex = 0; ringIndex < InitiatorVisualProfile.RINGS.size(); ringIndex++) {
            var ring = InitiatorDecorationGeometry.ring(ringIndex);
            for (int segment = 0; segment < InitiatorVisualProfile.SEGMENTS; segment++) {
                var back = ring.get(segment * 6);
                double[] center = centroid(back);
                center[2] = 0;
                for (int face = 0; face < 6; face++) {
                    var quad = ring.get(segment * 6 + face);
                    assertFace(quad);
                    assertTrue(dot(normal(quad.vertices().getFirst()), subtract(centroid(quad), center)) > 0,
                            "Each prism face must point away from the segment interior");
                }
            }
        }
    }

    @Test void ringsHaveExactRadialWidthDepthJointGapsAndUntransformedBounds() {
        for (int ringIndex = 0; ringIndex < InitiatorVisualProfile.RINGS.size(); ringIndex++) {
            var profile = InitiatorVisualProfile.RINGS.get(ringIndex);
            var quads = InitiatorDecorationGeometry.ring(ringIndex);
            double inner = profile.radius() - profile.width() / 2, outer = profile.radius() + profile.width() / 2;
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int segment = 0; segment < InitiatorVisualProfile.SEGMENTS; segment++) {
                double start = segment * Math.PI / 4 + InitiatorVisualProfile.GAP;
                double end = (segment + 1) * Math.PI / 4 - InitiatorVisualProfile.GAP;
                for (int face = 0; face < 6; face++) for (var vertex : quads.get(segment * 6 + face).vertices()) {
                    double radius = Math.hypot(vertex.x(), vertex.y());
                    assertTrue(Math.abs(radius - inner) < EPSILON || Math.abs(radius - outer) < EPSILON);
                    assertEquals(profile.depth() / 2, Math.abs(vertex.z()), EPSILON);
                    double angle = Math.atan2(vertex.y(), vertex.x());
                    if (angle < 0) angle += Math.PI * 2;
                    assertTrue(Math.abs(angle - start) < EPSILON || Math.abs(angle - end) < EPSILON);
                    assertEquals(2.5 / 16, vertex.u(), 0);
                    assertEquals(1.5 / 16, vertex.v(), 0);
                    double[] p = position(vertex);
                    for (int axis = 0; axis < 3; axis++) { min[axis] = Math.min(min[axis], p[axis]); max[axis] = Math.max(max[axis], p[axis]); }
                }
            }
            double extent = outer * Math.cos(InitiatorVisualProfile.GAP);
            assertArrayEquals(new double[]{-extent, -extent, -profile.depth() / 2}, min, EPSILON);
            assertArrayEquals(new double[]{extent, extent, profile.depth() / 2}, max, EPSILON);
            assertTrue(profile.depth() > 0);
        }
    }

    @Test void everyRingSegmentIsClosedWithOppositelyWoundSharedEdges() {
        for (int ringIndex = 0; ringIndex < InitiatorVisualProfile.RINGS.size(); ringIndex++) {
            var ring = InitiatorDecorationGeometry.ring(ringIndex);
            for (int segment = 0; segment < InitiatorVisualProfile.SEGMENTS; segment++) {
                Map<String, Integer> directedEdges = new HashMap<>();
                for (int face = 0; face < 6; face++) {
                    var vertices = ring.get(segment * 6 + face).vertices();
                    for (int edge = 0; edge < 4; edge++) directedEdges.merge(key(vertices.get(edge)) + "/" + key(vertices.get((edge + 1) % 4)), 1, Integer::sum);
                }
                assertEquals(24, directedEdges.size());
                for (var edge : directedEdges.entrySet()) {
                    assertEquals(1, edge.getValue());
                    String[] ends = edge.getKey().split("/");
                    assertEquals(1, directedEdges.get(ends[1] + "/" + ends[0]));
                }
            }
        }
    }

    @Test void ringsRemainRotationallyAndDepthSymmetric() {
        for (int ringIndex = 0; ringIndex < InitiatorVisualProfile.RINGS.size(); ringIndex++) {
            var vertices = InitiatorDecorationGeometry.ring(ringIndex).stream().flatMap(q -> q.vertices().stream()).toList();
            for (var vertex : vertices) {
                assertTrue(vertices.stream().anyMatch(v -> samePosition(v, -vertex.y(), vertex.x(), vertex.z())));
                assertTrue(vertices.stream().anyMatch(v -> samePosition(v, vertex.x(), vertex.y(), -vertex.z())));
            }
        }
    }

    @Test void finalAuthoredCrystalMeshMatchesRuntimePositionsWindingAndUvTiles() throws IOException {
        JsonObject crystal = referenceMesh("Live crystal");
        JsonObject positions = crystal.getAsJsonObject("vertices"), faces = crystal.getAsJsonObject("faces");
        assertEquals(8, faces.size());
        for (int index = 0; index < faces.size(); index++) {
            JsonObject face = faces.getAsJsonObject("f" + index);
            JsonArray ids = face.getAsJsonArray("vertices");
            var actual = InitiatorDecorationGeometry.crystal().get(index).quad().vertices();
            assertEquals(3, ids.size());
            for (int vertex = 0; vertex < ids.size(); vertex++) {
                String id = ids.get(vertex).getAsString();
                JsonArray expected = positions.getAsJsonArray(id), uv = face.getAsJsonObject("uv").getAsJsonArray(id);
                assertEquals(expected.get(0).getAsDouble() / 16 - .5, actual.get(vertex).x(), EPSILON);
                assertEquals(expected.get(1).getAsDouble() / 16 - InitiatorVisualProfile.ACTIVE_Y, actual.get(vertex).y(), EPSILON);
                assertEquals(expected.get(2).getAsDouble() / 16 - .5, actual.get(vertex).z(), EPSILON);
                assertEquals((uv.get(0).getAsDouble() - 32 - index / 2 * 6) / 16, actual.get(vertex).u(), EPSILON);
                assertEquals(uv.get(1).getAsDouble() / 16, actual.get(vertex).v(), EPSILON);
            }
        }
    }

    @Test void finalAuthoredRingsMatchRuntimePositionsAndMaterialNormalsAfterOnlyDeclaredTransforms() throws IOException {
        for (int ringIndex = 0; ringIndex < InitiatorVisualProfile.RINGS.size(); ringIndex++) {
            JsonObject reference = referenceMesh("Live orbit " + (ringIndex + 1));
            JsonObject positions = reference.getAsJsonObject("vertices"), faces = reference.getAsJsonObject("faces");
            var profile = InitiatorVisualProfile.RINGS.get(ringIndex);
            var actual = InitiatorDecorationGeometry.ring(ringIndex);
            double cos = Math.cos(Math.toRadians(profile.tiltDegrees())), sin = Math.sin(Math.toRadians(profile.tiltDegrees()));
            assertEquals(actual.size(), faces.size());
            for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
                JsonArray ids = faces.getAsJsonObject("f" + faceIndex).getAsJsonArray("vertices");
                assertEquals(4, ids.size());
                double[][] expectedPoints = new double[4][];
                for (int vertex = 0; vertex < ids.size(); vertex++) {
                    JsonArray p = positions.getAsJsonArray(ids.get(vertex).getAsString());
                    expectedPoints[vertex] = new double[]{p.get(0).getAsDouble() / 16, p.get(1).getAsDouble() / 16, p.get(2).getAsDouble() / 16};
                    var v = actual.get(faceIndex).vertices().get(vertex);
                    assertArrayEquals(expectedPoints[vertex], new double[]{.5 + v.x(), InitiatorVisualProfile.ACTIVE_Y + profile.yOffset() + v.y() * cos - v.z() * sin, .5 + v.y() * sin + v.z() * cos}, EPSILON);
                }
                double[] expectedNormal = cross(subtract(expectedPoints[1], expectedPoints[0]), subtract(expectedPoints[2], expectedPoints[0]));
                var n = actual.get(faceIndex).vertices().getFirst();
                double[] transformedNormal = {n.nx(), n.ny() * cos - n.nz() * sin, n.ny() * sin + n.nz() * cos};
                assertEquals(1, dot(expectedNormal, transformedNormal) / Math.sqrt(dot(expectedNormal, expectedNormal)), EPSILON);
            }
        }
    }

    private static void assertTriangle(InitiatorDecorationGeometry.Quad quad, float height, int second, int third, List<InitiatorVisualProfile.Uv> uv) {
        var vertices = quad.vertices();
        assertTrue(samePosition(vertices.getFirst(), 0, height, 0));
        assertRim(vertices.get(1), second);
        assertRim(vertices.get(2), third);
        assertEquals(vertices.get(2), vertices.get(3));
        for (int index = 0; index < 3; index++) {
            assertEquals(uv.get(index).u(), vertices.get(index).u());
            assertEquals(uv.get(index).v(), vertices.get(index).v());
        }
    }

    private static void assertRim(InitiatorDecorationGeometry.Vertex vertex, int index) {
        assertTrue(samePosition(vertex, InitiatorVisualProfile.CRYSTAL_RADIUS * Math.cos(index * Math.PI / 2), 0,
                InitiatorVisualProfile.CRYSTAL_RADIUS * Math.sin(index * Math.PI / 2)));
    }

    private static void assertFace(InitiatorDecorationGeometry.Quad quad) {
        var vertices = quad.vertices();
        double[] a = position(vertices.get(0)), b = position(vertices.get(1)), c = position(vertices.get(2));
        double[] cross = cross(subtract(b, a), subtract(c, a));
        double area = Math.sqrt(dot(cross, cross));
        assertTrue(area > 1e-8, "The first triangle must have positive area");
        for (var vertex : vertices) {
            for (float value : new float[]{vertex.x(), vertex.y(), vertex.z(), vertex.u(), vertex.v(), vertex.nx(), vertex.ny(), vertex.nz()}) assertTrue(Float.isFinite(value));
            double[] normal = normal(vertex);
            assertEquals(1, dot(normal, normal), EPSILON);
            assertEquals(1, dot(normal, cross) / area, EPSILON);
            assertEquals(0, dot(normal, subtract(position(vertex), a)), EPSILON);
            assertTrue(vertex.u() >= 0 && vertex.u() <= 1 && vertex.v() >= 0 && vertex.v() <= 1);
        }
    }

    private static JsonObject referenceMesh(String namePrefix) throws IOException {
        var root = JsonParser.parseString(Files.readString(Path.of("art/initiator/initiator-live-reference.bbmodel"))).getAsJsonObject();
        for (var element : root.getAsJsonArray("elements")) {
            JsonObject mesh = element.getAsJsonObject();
            if (mesh.get("name").getAsString().startsWith(namePrefix)) return mesh;
        }
        throw new AssertionError("Missing reference mesh " + namePrefix);
    }

    private static boolean samePosition(InitiatorDecorationGeometry.Vertex v, double x, double y, double z) {
        return Math.abs(v.x() - x) < EPSILON && Math.abs(v.y() - y) < EPSILON && Math.abs(v.z() - z) < EPSILON;
    }
    private static String key(InitiatorDecorationGeometry.Vertex v) { return v.x() + "," + v.y() + "," + v.z(); }
    private static double[] position(InitiatorDecorationGeometry.Vertex v) { return new double[]{v.x(), v.y(), v.z()}; }
    private static double[] normal(InitiatorDecorationGeometry.Vertex v) { return new double[]{v.nx(), v.ny(), v.nz()}; }
    private static double[] centroid(InitiatorDecorationGeometry.Quad quad) {
        int count = quad.vertices().get(2).equals(quad.vertices().get(3)) ? 3 : 4;
        double[] center = new double[3];
        for (int index = 0; index < count; index++) {
            double[] point = position(quad.vertices().get(index));
            for (int axis = 0; axis < 3; axis++) center[axis] += point[axis] / count;
        }
        return center;
    }
    private static double[] subtract(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
}
