package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.List;

/** Immutable local-space meshes. Animation, ring offsets and rotations belong to the renderer. */
final class InitiatorDecorationGeometry {
    // This authored texel is opaque white, so a ring retains its approved material tint.
    private static final InitiatorVisualProfile.Uv RING_UV = new InitiatorVisualProfile.Uv(2.5f / 16, 1.5f / 16);
    private static final List<Facet> CRYSTAL = buildCrystal();
    private static final List<List<Quad>> RINGS = InitiatorVisualProfile.RINGS.stream()
            .map(InitiatorDecorationGeometry::buildRing).toList();

    record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) { }
    record Quad(List<Vertex> vertices) {
        Quad {
            vertices = List.copyOf(vertices);
            if (vertices.size() != 4) throw new IllegalArgumentException("A quad needs four vertices");
        }
    }
    record Facet(Quad quad, int mixTarget, float mixAmount) { }

    private InitiatorDecorationGeometry() { }

    static List<Facet> crystal() { return CRYSTAL; }
    static List<Quad> ring(int index) { return RINGS.get(index); }

    private static List<Facet> buildCrystal() {
        float radius = InitiatorVisualProfile.CRYSTAL_RADIUS;
        float[][] rim = {{radius, 0, 0}, {0, 0, radius}, {-radius, 0, 0}, {0, 0, -radius}};
        float[] top = {0, InitiatorVisualProfile.CRYSTAL_TOP, 0};
        float[] bottom = {0, -InitiatorVisualProfile.CRYSTAL_BOTTOM, 0};
        List<Facet> facets = new ArrayList<>(8);
        for (int index = 0; index < rim.length; index++) {
            float[] current = rim[index], next = rim[(index + 1) % rim.length];
            facets.add(new Facet(triangle(top, next, current, InitiatorVisualProfile.TOP_UV),
                    InitiatorVisualProfile.TOP_MIX_TO, InitiatorVisualProfile.TOP_MIX_AMOUNTS.get(index)));
            facets.add(new Facet(triangle(bottom, current, next, InitiatorVisualProfile.BOTTOM_UV),
                    InitiatorVisualProfile.BOTTOM_MIX_TO, InitiatorVisualProfile.BOTTOM_MIX_AMOUNTS.get(index)));
        }
        return List.copyOf(facets);
    }

    private static Quad triangle(float[] tip, float[] second, float[] third, List<InitiatorVisualProfile.Uv> uv) {
        float[] normal = normal(tip, second, third);
        Vertex last = vertex(third, uv.get(2), normal);
        return new Quad(List.of(vertex(tip, uv.get(0), normal), vertex(second, uv.get(1), normal), last, last));
    }

    private static List<Quad> buildRing(InitiatorVisualProfile.Ring ring) {
        List<Quad> quads = new ArrayList<>(InitiatorVisualProfile.SEGMENTS * 6);
        double inner = ring.radius() - ring.width() / 2.0, outer = ring.radius() + ring.width() / 2.0;
        // Back, front, start cap, end cap, outer wall, inner wall; each face winds outward.
        int[][] faces = {{3, 2, 1, 0}, {4, 5, 6, 7}, {1, 5, 4, 0}, {7, 6, 2, 3}, {2, 6, 5, 1}, {4, 7, 3, 0}};
        for (int segment = 0; segment < InitiatorVisualProfile.SEGMENTS; segment++) {
            double a = segment * Math.PI * 2 / InitiatorVisualProfile.SEGMENTS + InitiatorVisualProfile.GAP;
            double b = (segment + 1) * Math.PI * 2 / InitiatorVisualProfile.SEGMENTS - InitiatorVisualProfile.GAP;
            float halfDepth = ring.depth() / 2;
            float[][] points = {
                    point(a, inner, -halfDepth), point(a, outer, -halfDepth),
                    point(b, outer, -halfDepth), point(b, inner, -halfDepth),
                    point(a, inner, halfDepth), point(a, outer, halfDepth),
                    point(b, outer, halfDepth), point(b, inner, halfDepth)
            };
            for (int[] face : faces) {
                float[] normal = normal(points[face[0]], points[face[1]], points[face[2]]);
                quads.add(new Quad(List.of(vertex(points[face[0]], RING_UV, normal), vertex(points[face[1]], RING_UV, normal),
                        vertex(points[face[2]], RING_UV, normal), vertex(points[face[3]], RING_UV, normal))));
            }
        }
        return List.copyOf(quads);
    }

    private static float[] point(double angle, double radius, float depth) {
        return new float[]{(float) (Math.cos(angle) * radius), (float) (Math.sin(angle) * radius), depth};
    }

    private static Vertex vertex(float[] point, InitiatorVisualProfile.Uv uv, float[] normal) {
        return new Vertex(point[0], point[1], point[2], uv.u(), uv.v(), normal[0], normal[1], normal[2]);
    }

    private static float[] normal(float[] a, float[] b, float[] c) {
        double abX = b[0] - a[0], abY = b[1] - a[1], abZ = b[2] - a[2];
        double acX = c[0] - a[0], acY = c[1] - a[1], acZ = c[2] - a[2];
        double x = abY * acZ - abZ * acY, y = abZ * acX - abX * acZ, z = abX * acY - abY * acX;
        double length = Math.sqrt(x * x + y * y + z * z);
        return new float[]{(float) (x / length), (float) (y / length), (float) (z / length)};
    }
}
