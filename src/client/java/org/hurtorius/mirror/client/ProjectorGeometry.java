package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.List;

/** Bounded, game-independent clipping for a projector's coplanar receiver patches. */
public final class ProjectorGeometry {
    public static final int MAX_INPUT_TRIANGLES = 24_000, MAX_TRIANGLES = 24_000, MAX_WORK = 262_144;
    private static final int MAX_POLYGON_POINTS = 64;
    private static final double EPS = 1e-10;
    private static final ScreenMesh.Mesh EMPTY = new ScreenMesh.Mesh(List.of(), List.of());

    private ProjectorGeometry() { }

    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) { }
    public record Point(double x, double y, double z) { }
    /** Ordered convex world-space corners. Invalid patches conservatively count as blocked. */
    public record Patch(List<Point> corners) {
        public Patch { corners = corners == null ? List.of() : List.copyOf(corners); }
    }

    /**
     * One sticky budget must be shared by every layer of a panel. If failed() becomes true,
     * the caller must discard the entire panel bundle, including previously clipped layers.
     * Counters never become negative; failed budgets cannot produce later geometry.
     */
    public static final class Budget {
        private int inputTriangles, triangles, work;
        private boolean failed;

        public Budget(int inputTriangles, int triangles, int work) {
            this.inputTriangles = Math.max(0, inputTriangles);
            this.triangles = Math.max(0, triangles);
            this.work = Math.max(0, work);
        }

        public static Budget panel() { return new Budget(MAX_INPUT_TRIANGLES, MAX_TRIANGLES, MAX_WORK); }
        public boolean failed() { return failed; }
        public void fail() { failed = true; }
        public int remainingInputTriangles() { return inputTriangles; }
        public int remainingTriangles() { return triangles; }
        public int remainingWork() { return work; }

        private boolean input(int count) {
            if (failed || count < 0 || count > inputTriangles) { fail(); return false; }
            inputTriangles -= count;
            return true;
        }

        private boolean work() {
            if (failed || work == 0) { fail(); return false; }
            work--;
            return true;
        }

        private boolean triangle() {
            if (failed || triangles == 0) { fail(); return false; }
            triangles--;
            return true;
        }
    }

    private record Receiver(List<ScreenMesh.Point> points, double sign,
                            double x0, double y0, double x1, double y1) { }

    public static ScreenMesh.Mesh clip(ScreenMesh.Mesh mesh, List<List<ScreenMesh.Point>> receivers, Budget budget) {
        return clip(mesh, receivers, 1, 1, 0, budget);
    }

    /**
     * Clips against the union of nonoverlapping, convex receiver polygons in panel XY.
     * Tests use (logicalX * scaleX + offsetX, logicalY * scaleY), so a subsequent appearance
     * transform cannot move the image off its receiver. Returned vertices remain before
     * that appearance transform. Curved positions are flattened to interpolated logical XY,
     * z=0, with normal (0,0,1); UVs, alpha and logical coordinates retain their interpolation.
     * The empty boundary is deliberate: projector beams use the separately authored contour.
     */
    public static ScreenMesh.Mesh clip(ScreenMesh.Mesh mesh, List<List<ScreenMesh.Point>> receivers,
                                       float scaleX, float scaleY, float offsetX, Budget budget) {
        if (budget == null || budget.failed()) return EMPTY;
        if (mesh == null || receivers == null || !Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || !Float.isFinite(offsetX)) { budget.fail(); return EMPTY; }
        if (!budget.input(mesh.triangles().size())) return EMPTY;

        List<Receiver> masks = new ArrayList<>();
        for (List<ScreenMesh.Point> polygon : receivers) {
            Receiver receiver = receiver(polygon, budget);
            if (receiver == null) { budget.fail(); return EMPTY; }
            masks.add(receiver);
        }

        List<ScreenMesh.Triangle> result = new ArrayList<>();
        for (ScreenMesh.Triangle triangle : mesh.triangles()) {
            if (!budget.work() || triangle == null || !finite(triangle.a())
                    || !finite(triangle.b()) || !finite(triangle.c())) { budget.fail(); return EMPTY; }
            // Validate even an invisible layer, so malformed input cannot silently pass.
            if (scaleX == 0 || scaleY == 0 || masks.isEmpty()) continue;
            List<ScreenMesh.Vertex> source = List.of(flat(triangle.a()), flat(triangle.b()), flat(triangle.c()));
            double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
            for (ScreenMesh.Vertex vertex : source) {
                double x = transformedX(vertex, scaleX, offsetX), y = transformedY(vertex, scaleY);
                if (!Double.isFinite(x) || !Double.isFinite(y)) { budget.fail(); return EMPTY; }
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
            for (Receiver receiver : masks) {
                if (!budget.work()) return EMPTY;
                if (x1 < receiver.x0 || x0 > receiver.x1 || y1 < receiver.y0 || y0 > receiver.y1) continue;
                List<ScreenMesh.Vertex> polygon = source;
                for (int edge = 0; edge < receiver.points.size() && polygon.size() >= 3; edge++) {
                    if (!budget.work()) return EMPTY;
                    polygon = clipEdge(polygon, receiver.points.get(edge),
                            receiver.points.get((edge + 1) % receiver.points.size()), receiver.sign,
                            scaleX, scaleY, offsetX, budget);
                    if (budget.failed()) return EMPTY;
                }
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    if (!budget.work()) return EMPTY;
                    ScreenMesh.Vertex a = polygon.getFirst(), b = polygon.get(i), c = polygon.get(i + 1);
                    double area = cross(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
                    if (!Double.isFinite(area)) { budget.fail(); return EMPTY; }
                    if (area == 0) continue;
                    if (!budget.triangle()) return EMPTY;
                    result.add(area > 0 ? new ScreenMesh.Triangle(a, b, c) : new ScreenMesh.Triangle(a, c, b));
                }
            }
        }
        return result.isEmpty() ? EMPTY : new ScreenMesh.Mesh(result, List.of());
    }

    private static Receiver receiver(List<ScreenMesh.Point> points, Budget budget) {
        if (!budget.work() || points == null || points.size() < 3 || points.size() > MAX_POLYGON_POINTS) return null;
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, area = 0;
        for (int i = 0; i < points.size(); i++) {
            if (!budget.work()) return null;
            ScreenMesh.Point a = points.get(i), b = points.get((i + 1) % points.size());
            if (!finite(a) || !finite(b) || a.equals(b)) return null;
            area += (double) a.x() * b.y() - (double) a.y() * b.x();
            x0 = Math.min(x0, a.x()); y0 = Math.min(y0, a.y()); x1 = Math.max(x1, a.x()); y1 = Math.max(y1, a.y());
        }
        if (!Double.isFinite(area) || area == 0) return null;
        double sign = Math.signum(area);
        // Checking every corner against every edge also rejects self-intersecting stars.
        for (int i = 0; i < points.size(); i++) {
            ScreenMesh.Point a = points.get(i), b = points.get((i + 1) % points.size());
            for (ScreenMesh.Point point : points) {
                if (!budget.work() || cross(a.x(), a.y(), b.x(), b.y(), point.x(), point.y()) * sign < 0) return null;
            }
        }
        return new Receiver(List.copyOf(points), sign, x0, y0, x1, y1);
    }

    private static List<ScreenMesh.Vertex> clipEdge(List<ScreenMesh.Vertex> polygon, ScreenMesh.Point a,
                                                   ScreenMesh.Point b, double sign, float scaleX,
                                                   float scaleY, float offsetX, Budget budget) {
        List<ScreenMesh.Vertex> out = new ArrayList<>(polygon.size() + 1);
        ScreenMesh.Vertex previous = polygon.getLast();
        double old = distance(a, b, previous, sign, scaleX, scaleY, offsetX);
        for (ScreenMesh.Vertex next : polygon) {
            if (!budget.work()) return List.of();
            double distance = distance(a, b, next, sign, scaleX, scaleY, offsetX);
            if (!Double.isFinite(old) || !Double.isFinite(distance)) { budget.fail(); return List.of(); }
            if ((distance >= 0) != (old >= 0)) {
                double fraction = old / (old - distance);
                ScreenMesh.Vertex cut = interpolate(previous, next, fraction);
                if (!finite(cut)) { budget.fail(); return List.of(); }
                out.add(cut);
            }
            if (distance >= 0) out.add(next);
            previous = next;
            old = distance;
        }
        return out;
    }

    private static double distance(ScreenMesh.Point a, ScreenMesh.Point b, ScreenMesh.Vertex v,
                                   double sign, float scaleX, float scaleY, float offsetX) {
        return cross(a.x(), a.y(), b.x(), b.y(), transformedX(v, scaleX, offsetX), transformedY(v, scaleY)) * sign;
    }

    private static double transformedX(ScreenMesh.Vertex v, float scale, float offset) { return (double) v.logicalX() * scale + offset; }
    private static double transformedY(ScreenMesh.Vertex v, float scale) { return (double) v.logicalY() * scale; }

    private static ScreenMesh.Vertex flat(ScreenMesh.Vertex v) {
        return new ScreenMesh.Vertex(v.logicalX(), v.logicalY(), 0, v.u(), v.v(), 0, 0, 1, v.alpha(), v.logicalX(), v.logicalY());
    }

    private static ScreenMesh.Vertex interpolate(ScreenMesh.Vertex a, ScreenMesh.Vertex b, double t) {
        float x = mix(a.logicalX(), b.logicalX(), t), y = mix(a.logicalY(), b.logicalY(), t);
        return new ScreenMesh.Vertex(x, y, 0, mix(a.u(), b.u(), t), mix(a.v(), b.v(), t),
                0, 0, 1, mix(a.alpha(), b.alpha(), t), x, y);
    }

    private static float mix(float a, float b, double t) { return (float) ((1 - t) * a + t * b); }
    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /**
     * Conservative cone-vs-box half-space test. False proves separation; true may overreject.
     * Boxes wholly behind/touching the receiver, or wholly behind/touching the parallel plane
     * through the source, do not block. No positive slab epsilon erases thin foreground boxes.
     * Side planes use a generous rounding tolerance, retaining uncertain grazing obstacles.
     */
    public static boolean potentiallyBlocked(Point source, Patch patch, Box obstacle) {
        if (!finite(source) || patch == null || !finite(obstacle)
                || patch.corners.size() < 3 || patch.corners.size() > MAX_POLYGON_POINTS) return true;
        List<Point> corners = patch.corners;
        for (Point corner : corners) if (!finite(corner)) return true;
        Point origin = corners.getFirst(), normal = null;
        for (int i = 1; i + 1 < corners.size() && normal == null; i++)
            normal = unitCross(subtract(corners.get(i), origin), subtract(corners.get(i + 1), origin));
        if (normal == null) return true;
        double tolerance = tolerance(source, obstacle, corners);
        if (!Double.isFinite(tolerance)) return true;
        for (Point corner : corners) {
            double distance = dot(normal, subtract(corner, origin));
            if (!Double.isFinite(distance) || Math.abs(distance) > tolerance) return true;
        }
        for (int i = 0; i < corners.size(); i++) {
            Point a = corners.get(i), edge = subtract(corners.get((i + 1) % corners.size()), a);
            Point inward = unitCross(normal, edge);
            if (inward == null) return true;
            for (Point corner : corners) {
                double distance = dot(inward, subtract(corner, a));
                if (!Double.isFinite(distance) || distance < -tolerance) return true;
            }
        }
        double sourceDistance = dot(normal, subtract(source, origin));
        if (!Double.isFinite(sourceDistance) || Math.abs(sourceDistance) <= tolerance) return true;
        if (sourceDistance < 0) normal = multiply(normal, -1); // Receiver toward source.

        double receiverMaximum = support(normal, obstacle, origin, true);
        double sourceMinimum = support(normal, obstacle, source, false);
        if (!Double.isFinite(receiverMaximum) || !Double.isFinite(sourceMinimum)) return true;
        if (receiverMaximum <= 0 || sourceMinimum >= 0) return false;

        Point center = new Point(0, 0, 0);
        for (Point corner : corners) center = add(center, multiply(subtract(corner, source), 1.0 / corners.size()));
        if (!finite(center)) return true;
        for (int i = 0; i < corners.size(); i++) {
            Point side = unitCross(subtract(corners.get(i), source), subtract(corners.get((i + 1) % corners.size()), source));
            if (side == null) return true;
            double inside = dot(side, center);
            if (!Double.isFinite(inside) || Math.abs(inside) <= tolerance) return true;
            if (inside < 0) side = multiply(side, -1);
            double maximum = support(side, obstacle, source, true);
            if (!Double.isFinite(maximum)) return true;
            if (maximum < -tolerance) return false;
        }
        return true;
    }

    private static double support(Point normal, Box box, Point origin, boolean maximum) {
        double x = (normal.x >= 0) == maximum ? box.x1 : box.x0;
        double y = (normal.y >= 0) == maximum ? box.y1 : box.y0;
        double z = (normal.z >= 0) == maximum ? box.z1 : box.z0;
        return normal.x * (x - origin.x) + normal.y * (y - origin.y) + normal.z * (z - origin.z);
    }

    private static double tolerance(Point source, Box box, List<Point> corners) {
        double magnitude = Math.max(maxAbs(source), Math.max(maxAbs(new Point(box.x0, box.y0, box.z0)),
                maxAbs(new Point(box.x1, box.y1, box.z1))));
        for (Point corner : corners) magnitude = Math.max(magnitude, maxAbs(corner));
        return Math.max(EPS, Math.ulp(magnitude) * 64);
    }

    private static Point unitCross(Point a, Point b) {
        a = unit(a); b = unit(b);
        if (a == null || b == null) return null;
        return unit(new Point(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x));
    }

    private static Point unit(Point point) {
        if (!finite(point)) return null;
        double scale = maxAbs(point);
        if (scale == 0) return null;
        Point scaled = multiply(point, 1 / scale);
        // Division handles subnormal scales without overflowing their reciprocal.
        if (!finite(scaled)) scaled = new Point(point.x / scale, point.y / scale, point.z / scale);
        double length = Math.hypot(Math.hypot(scaled.x, scaled.y), scaled.z);
        return length > 0 && Double.isFinite(length) ? multiply(scaled, 1 / length) : null;
    }

    private static double maxAbs(Point p) { return Math.max(Math.abs(p.x), Math.max(Math.abs(p.y), Math.abs(p.z))); }
    private static Point subtract(Point a, Point b) { return new Point(a.x - b.x, a.y - b.y, a.z - b.z); }
    private static Point add(Point a, Point b) { return new Point(a.x + b.x, a.y + b.y, a.z + b.z); }
    private static Point multiply(Point p, double scale) { return new Point(p.x * scale, p.y * scale, p.z * scale); }
    private static double dot(Point a, Point b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
    private static boolean finite(Point p) { return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
    private static boolean finite(Box b) {
        return b != null && Double.isFinite(b.x0) && Double.isFinite(b.y0) && Double.isFinite(b.z0)
                && Double.isFinite(b.x1) && Double.isFinite(b.y1) && Double.isFinite(b.z1)
                && b.x0 <= b.x1 && b.y0 <= b.y1 && b.z0 <= b.z1;
    }
    private static boolean finite(ScreenMesh.Point p) { return p != null && Float.isFinite(p.x()) && Float.isFinite(p.y()); }
    private static boolean finite(ScreenMesh.Vertex v) {
        return v != null && Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z())
                && Float.isFinite(v.u()) && Float.isFinite(v.v()) && Float.isFinite(v.alpha())
                && Float.isFinite(v.nx()) && Float.isFinite(v.ny()) && Float.isFinite(v.nz())
                && Float.isFinite(v.logicalX()) && Float.isFinite(v.logicalY());
    }
}
