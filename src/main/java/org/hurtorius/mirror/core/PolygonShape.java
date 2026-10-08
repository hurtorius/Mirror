package org.hurtorius.mirror.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** A small, strictly convex polygon in normalized screen coordinates (+Y is up). */
public final class PolygonShape {
    public static final int MAX_VERTICES = 64;
    public static final int MAX_TEXT_LENGTH = 2048;
    public static final String DEFAULT_CSV = "-1,-1;1,-1;1,1;-1,1";
    private static final double EPSILON = 0.000001;
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private final List<Point> points;

    public record Point(float x, float y) {
        public Point {
            if (!Float.isFinite(x) || !Float.isFinite(y) || x < -1 || x > 1 || y < -1 || y > 1)
                throw new IllegalArgumentException("Each vertex coordinate must be a finite number from -1 to 1");
            // A stable wire representation, including inputs spelling negative zero.
            if (x == 0) x = 0;
            if (y == 0) y = 0;
        }
    }

    private PolygonShape(List<Point> points) { this.points = List.copyOf(points); }
    public List<Point> points() { return points; }
    public static PolygonShape rectangle() { return parse(DEFAULT_CSV); }

    /**
     * Parses x,y;x,y;... without clamping, repairing, dropping vertices or accepting Java
     * hexadecimal/NaN literals. Clockwise polygons are accepted and normalized to CCW.
     */
    public static PolygonShape parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Enter at least three polygon vertices");
        if (text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Polygon text exceeds " + MAX_TEXT_LENGTH + " characters");
        String[] vertices = text.split(";", -1);
        if (vertices.length < 3 || vertices.length > MAX_VERTICES)
            throw new IllegalArgumentException("A polygon needs 3 to " + MAX_VERTICES + " vertices");
        List<Point> points = new ArrayList<>(vertices.length);
        for (String vertex : vertices) {
            String[] xy = vertex.split(",", -1);
            if (xy.length != 2) throw new IllegalArgumentException("Use x,y pairs separated by semicolons");
            points.add(new Point(coordinate(xy[0]), coordinate(xy[1])));
        }
        return of(points);
    }

    /** Validates a detached polygon and canonicalizes its winding and starting vertex. */
    public static PolygonShape of(List<Point> input) {
        if (input == null || input.size() < 3 || input.size() > MAX_VERTICES)
            throw new IllegalArgumentException("A polygon needs 3 to " + MAX_VERTICES + " vertices");
        if (input.stream().anyMatch(p -> p == null)) throw new IllegalArgumentException("A polygon vertex cannot be null");
        List<Point> points = new ArrayList<>(input);
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            area += (double) a.x * b.y - (double) b.x * a.y;
            for (int j = i + 1; j < points.size(); j++) {
                Point other = points.get(j);
                if (Math.hypot(a.x - other.x, a.y - other.y) <= EPSILON)
                    throw new IllegalArgumentException("Polygon vertices must be distinct");
            }
        }
        if (Math.abs(area) <= EPSILON) throw new IllegalArgumentException("Polygon must have a nonzero area");
        double winding = Math.signum(area);
        // Testing every vertex against every edge also rejects self-crossing stars whose
        // consecutive turns alone can all have the same sign. Work is capped at 32².
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            for (int j = 0; j < points.size(); j++) {
                if (j == i || j == (i + 1) % points.size()) continue;
                Point p = points.get(j);
                double cross = ((double) b.x - a.x) * ((double) p.y - a.y)
                        - ((double) b.y - a.y) * ((double) p.x - a.x);
                if (cross * winding <= EPSILON)
                    throw new IllegalArgumentException("Use a convex outline without crossing, collinear or inward corners");
            }
        }
        if (area < 0) Collections.reverse(points);
        int first = 0;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i), b = points.get(first);
            if (a.x < b.x || a.x == b.x && a.y < b.y) first = i;
        }
        Collections.rotate(points, -first);
        return new PolygonShape(points);
    }

    public String toCsv() {
        StringBuilder result = new StringBuilder();
        for (Point point : points) {
            if (!result.isEmpty()) result.append(';');
            result.append(number(point.x)).append(',').append(number(point.y));
        }
        return result.toString();
    }

    public static String normalize(String text) { return parse(text).toCsv(); }
    private static float coordinate(String token) {
        token = token.strip();
        if (!NUMBER.matcher(token).matches()) throw new IllegalArgumentException("Use finite decimal coordinates from -1 to 1");
        try {
            double value = Double.parseDouble(token);
            if (!Double.isFinite(value) || value < -1 || value > 1) throw new NumberFormatException();
            return (float) value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Each vertex coordinate must be a finite number from -1 to 1"); }
    }
    private static String number(float value) {
        if (value == (int) value) return Integer.toString((int) value);
        return Float.toString(value);
    }
}
