package org.hurtorius.mirror.client;


import java.util.ArrayList;
import java.util.List;

/** Pure bounded geometry, independent of the game/render thread and native media runtimes. */
public final class ScreenMesh {
    public static final float ORIGINAL_PIXELS_PER_BLOCK = 128;
    private static final float EPS = .00001f;
    private static final Mesh EMPTY = new Mesh(List.of(), List.of());
    private ScreenMesh() { }

    public record Point(float x, float y) { }
    public record Bounds(float x0, float y0, float x1, float y1) {
        public float width() { return x1 - x0; }
        public float height() { return y1 - y0; }
    }
    public record Vertex(float x, float y, float z, float u, float v,
                         float nx, float ny, float nz, float alpha, float logicalX, float logicalY) { }
    public record Triangle(Vertex a, Vertex b, Vertex c) { }
    public record Mesh(List<Triangle> triangles, List<Vertex> boundary) {
        public Mesh { triangles = List.copyOf(triangles); boundary = List.copyOf(boundary); }
        public boolean empty() { return triangles.isEmpty(); }
    }
    public record Content(Mesh image, Mesh letterbox, Bounds imageBounds) { }
    private record P(float x, float y, float alpha) { }
    private record T(float x, float y, float u, float v, float alpha) { }
    /** A frame-local cap; pathological geometry falls back to a fade instead of allocating without bound. */
    public static final int MAX_TRANSITION_TRIANGLES = 24_000;
    private record Surface(PreviewConfig.Shape shape, float width, float height, float curve, float radius, String customShape) {
        boolean curved() { return shape == PreviewConfig.Shape.CONCAVE || shape == PreviewConfig.Shape.CONVEX || shape == PreviewConfig.Shape.PANORAMA; }
    }

    public static Mesh surface(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                               float cornerRadius, float fadeWidth) {
        return surface(shape, width, height, curveDegrees, cornerRadius, fadeWidth, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Mesh surface(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                               float cornerRadius, float fadeWidth, String customShape) {
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        List<P> outline = outline(s);
        Bounds full = bounds(outline);
        return build(s, outline, full, full, fadeWidth);
    }

    public static Content content(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                                  float cornerRadius, PreviewConfig.Fit fit, int pixelsW, int pixelsH, float fadeWidth) {
        return content(shape, width, height, curveDegrees, cornerRadius, fit, pixelsW, pixelsH, fadeWidth, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Content content(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                                  float cornerRadius, PreviewConfig.Fit fit, int pixelsW, int pixelsH, float fadeWidth, String customShape) {
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        List<P> outline = outline(s);
        Bounds full = bounds(outline);
        if (pixelsW <= 0 || pixelsH <= 0) return new Content(EMPTY, surface(shape, width, height, curveDegrees, cornerRadius, fadeWidth, customShape), full);
        fit = fit == null ? PreviewConfig.Fit.FIT : fit;
        float centerX=(full.x0+full.x1)/2, centerY=(full.y0+full.y1)/2;
        if (fit == PreviewConfig.Fit.FIT && shape == PreviewConfig.Shape.CUSTOM) {
            centerX=0; centerY=0;
            for (P point:outline) { centerX+=point.x/outline.size(); centerY+=point.y/outline.size(); }
        }
        float imageW, imageH;
        if (fit == PreviewConfig.Fit.STRETCH) {
            imageW = full.width(); imageH = full.height();
        } else if (fit == PreviewConfig.Fit.ORIGINAL) {
            imageW = pixelsW / ORIGINAL_PIXELS_PER_BLOCK; imageH = pixelsH / ORIGINAL_PIXELS_PER_BLOCK;
        } else {
            float scale = fit == PreviewConfig.Fit.FILL ? Math.max(full.width() / pixelsW, full.height() / pixelsH)
                    : Math.min(full.width() / pixelsW, full.height() / pixelsH);
            imageW = pixelsW * scale; imageH = pixelsH * scale;
            if (fit == PreviewConfig.Fit.FIT) {
                // The whole image fits inside curved-corner silhouettes, not just their bounding box.
                float low = 0, high = 1;
                for (int i = 0; i < 22; i++) {
                    float candidate = (low + high) / 2;
                    if (rectangleInside(outline, centerX, centerY, imageW * candidate / 2, imageH * candidate / 2)) low = candidate;
                    else high = candidate;
                }
                imageW *= low; imageH *= low;
            }
        }
        Bounds imageBounds = new Bounds(centerX-imageW / 2, centerY-imageH / 2, centerX+imageW / 2, centerY+imageH / 2);
        return content(s, outline, full, imageBounds, fadeWidth);
    }

    /**
     * Video-wall content is fitted in the complete rectangular wall before clipping
     * to this tile's silhouette. A 1x1 selection preserves single-screen fitting,
     * including the inscribed FIT behavior of circles, ovals and rounded corners.
     */
    public static Content content(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                                  float cornerRadius, PreviewConfig.Fit fit, int pixelsW, int pixelsH,
                                  float fadeWidth, ScreenTileLayout.Tile tile) {
        return content(shape, width, height, curveDegrees, cornerRadius, fit, pixelsW, pixelsH, fadeWidth, tile, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Content content(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                                  float cornerRadius, PreviewConfig.Fit fit, int pixelsW, int pixelsH,
                                  float fadeWidth, ScreenTileLayout.Tile tile, String customShape) {
        if (tile == null || !tile.tiled())
            return content(shape, width, height, curveDegrees, cornerRadius, fit, pixelsW, pixelsH, fadeWidth, customShape);
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        List<P> outline = outline(s);
        Bounds full = bounds(outline);
        if (pixelsW <= 0 || pixelsH <= 0)
            return new Content(EMPTY, build(s, outline, full, full, fadeWidth), full);
        Bounds imageBounds = ScreenTileLayout.imageBounds(s.width, s.height, fit, pixelsW, pixelsH, tile);
        return content(s, outline, full, imageBounds, fadeWidth);
    }

    private static Content content(Surface s, List<P> outline, Bounds full, Bounds imageBounds, float fadeWidth) {
        Mesh image = build(s, outline, imageBounds, imageBounds, fadeWidth);
        List<Triangle> margins = new ArrayList<>();
        append(margins, build(s, outline, new Bounds(full.x0, full.y0, full.x1, Math.min(full.y1, imageBounds.y0)), full, fadeWidth));
        append(margins, build(s, outline, new Bounds(full.x0, Math.max(full.y0, imageBounds.y1), full.x1, full.y1), full, fadeWidth));
        float lowY = Math.max(full.y0, imageBounds.y0), highY = Math.min(full.y1, imageBounds.y1);
        append(margins, build(s, outline, new Bounds(full.x0, lowY, Math.min(full.x1, imageBounds.x0), highY), full, fadeWidth));
        append(margins, build(s, outline, new Bounds(Math.max(full.x0, imageBounds.x1), lowY, full.x1, highY), full, fadeWidth));
        return new Content(image, new Mesh(margins, List.of()), imageBounds);
    }

    /**
     * An outline strip; positive offsets expand outwards. Dashed strips support runic borders.
     * Edge UVs are perimeter turns (u) and strip width (v), independent of surface texture UVs.
     * Keeping 0 and 1 at the seam allows smooth flow without rebuilding geometry every frame.
     */
    public static Mesh edge(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                            float cornerRadius, float innerOffset, float outerOffset, boolean dashed) {
        return edge(shape, width, height, curveDegrees, cornerRadius, innerOffset, outerOffset, dashed, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Mesh edge(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                            float cornerRadius, float innerOffset, float outerOffset, boolean dashed, String customShape) {
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        List<P> base = outline(s);
        List<P> inner = offset(base, innerOffset), outer = offset(base, outerOffset);
        List<Triangle> triangles = new ArrayList<>();
        Bounds uv = bounds(base);
        double totalPerimeter = 0;
        for (int i = 0; i < base.size(); i++) {
            P a = base.get(i), b = base.get((i + 1) % base.size());
            totalPerimeter += Math.hypot(b.x - a.x, b.y - a.y);
        }
        double perimeter = 0;
        for (int i = 0; i < base.size(); i++) {
            int next = (i + 1) % base.size();
            P a = base.get(i), b = base.get(next);
            double length = Math.hypot(b.x - a.x, b.y - a.y);
            int segments = Math.max(1, (int) Math.ceil(length / .12f));
            for (int part = 0; part < segments; part++) {
                float t0 = (float) part / segments, t1 = (float) (part + 1) / segments;
                double midpoint = perimeter + length * (t0 + t1) * .5;
                if (dashed && ((int) (midpoint / .11f)) % 4 == 3) continue;
                float u0 = (float) ((perimeter + length * t0) / totalPerimeter);
                float u1 = (float) ((perimeter + length * t1) / totalPerimeter);
                List<T> polygon = List.of(edgePoint(interpolate(inner.get(i), inner.get(next), t0), u0, 0),
                        edgePoint(interpolate(outer.get(i), outer.get(next), t0), u0, 1),
                        edgePoint(interpolate(outer.get(i), outer.get(next), t1), u1, 1),
                        edgePoint(interpolate(inner.get(i), inner.get(next), t1), u1, 0));
                tessellateEdge(s, polygon, triangles);
            }
            perimeter += length;
        }
        return new Mesh(triangles, mappedBoundary(s, outer, uv));
    }

    private static T edgePoint(P point, float u, float v) { return new T(point.x, point.y, u, v, point.alpha); }

    /** The same cylindrical subdivision as surface meshes, retaining interpolated perimeter UVs. */
    private static void tessellateEdge(Surface s, List<T> polygon, List<Triangle> out) {
        if (!s.curved()) { triangulateT(s, polygon, out); return; }
        int columns = Math.clamp((int) Math.ceil(Math.max(s.shape == PreviewConfig.Shape.PANORAMA ? 120 : 45, s.curve) / 5), 12, 64);
        float step = s.width / columns;
        Bounds extent = bounds(polygon.stream().map(p -> new P(p.x, p.y, p.alpha)).toList());
        int first = (int) Math.floor((extent.x0 + s.width / 2) / step);
        int last = (int) Math.floor((extent.x1 + s.width / 2) / step);
        for (int i = first; i <= last; i++) {
            float left = -s.width / 2 + i * step;
            triangulateT(s, clipT(polygon, rectangle(new Bounds(left, extent.y0 - 1, left + step, extent.y1 + 1))), out);
        }
    }

    public static Mesh scanlines(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                                 float cornerRadius, int count, float fadeWidth) {
        return scanlines(shape, width, height, curveDegrees, cornerRadius, count, fadeWidth, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Mesh scanlines(PreviewConfig.Shape shape, float width, float height, float curveDegrees,
                                 float cornerRadius, int count, float fadeWidth, String customShape) {
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        List<P> outline = outline(s);
        Bounds full = bounds(outline);
        List<Triangle> triangles = new ArrayList<>();
        count = Math.clamp(count, 1, 96);
        float step = full.height() / count;
        for (int i = 0; i < count; i++) {
            float y = full.y0 + i * step;
            append(triangles, build(s, outline, new Bounds(full.x0, y, full.x1, y + step * .16f), full, fadeWidth));
        }
        return new Mesh(triangles, List.of());
    }

    /**
     * Transforms and clips each textured triangle in logical panel space, then maps it onto the
     * curved surface. UVs and feather alpha are interpolated at every cut; a moving image can
     * never leak outside a circle, rounded corner, hexagon or curved screen silhouette.
     * Appearance masks use clipToScreen=false so a surrounding frame can reveal with the panel.
     */
    public static Mesh transition(Mesh mesh, PreviewConfig.Shape shape, float width, float height,
                                  float curveDegrees, float cornerRadius, TransitionMath.Plan plan,
                                  boolean clipToScreen, float maskOutset) {
        return transition(mesh, shape, width, height, curveDegrees, cornerRadius, plan, clipToScreen, maskOutset, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Mesh transition(Mesh mesh, PreviewConfig.Shape shape, float width, float height,
                                  float curveDegrees, float cornerRadius, TransitionMath.Plan plan,
                                  boolean clipToScreen, float maskOutset, String customShape) {
        if (mesh.empty() || plan.complete()) return mesh;
        if (plan.progress() <= 0) return EMPTY;
        if (plan.effect() == PreviewConfig.Transition.FADE) return mesh;
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        List<P> silhouette = outline(s);
        Bounds full = bounds(silhouette);
        float outset = clamp(maskOutset, 0, 2);
        Bounds maskBounds = new Bounds(full.x0 - outset, full.y0 - outset, full.x1 + outset, full.y1 + outset);
        List<P> iris = plan.effect() == PreviewConfig.Transition.IRIS ? iris(maskBounds, plan.progress()) : List.of();
        List<Triangle> result = new ArrayList<>();
        for (Triangle triangle : mesh.triangles) {
            List<T> polygon = List.of(transform(triangle.a, plan, full.width()),
                    transform(triangle.b, plan, full.width()), transform(triangle.c, plan, full.width()));
            if (clipToScreen) polygon = clipT(polygon, silhouette);
            if (polygon.size() < 3) continue;
            if (plan.effect() == PreviewConfig.Transition.IRIS) polygon = clipT(polygon, iris);
            if (plan.effect() == PreviewConfig.Transition.DISSOLVE) {
                float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
                for (T v : polygon) { minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); }
                float cellW = maskBounds.width() / TransitionMath.DISSOLVE_COLUMNS;
                float cellH = maskBounds.height() / TransitionMath.DISSOLVE_ROWS;
                int firstX = Math.clamp((int) Math.floor((minX - maskBounds.x0) / cellW), 0, TransitionMath.DISSOLVE_COLUMNS - 1);
                int lastX = Math.clamp((int) Math.floor((maxX - maskBounds.x0) / cellW), 0, TransitionMath.DISSOLVE_COLUMNS - 1);
                int firstY = Math.clamp((int) Math.floor((minY - maskBounds.y0) / cellH), 0, TransitionMath.DISSOLVE_ROWS - 1);
                int lastY = Math.clamp((int) Math.floor((maxY - maskBounds.y0) / cellH), 0, TransitionMath.DISSOLVE_ROWS - 1);
                for (int y = firstY; y <= lastY; y++) for (int x = firstX; x <= lastX; x++) {
                    if (!TransitionMath.cellVisible(x, y, plan.progress())) continue;
                    float x0 = maskBounds.x0 + x * cellW, y0 = maskBounds.y0 + y * cellH;
                    triangulateT(s, clipT(polygon, rectangle(new Bounds(x0, y0, x0 + cellW, y0 + cellH))), result);
                }
            } else triangulateT(s, polygon, result);
            if (result.size() > MAX_TRANSITION_TRIANGLES) return fade(mesh, plan.progress());
        }
        // A mask has multiple/disconnected boundaries. Omit rather than invent a beam rim.
        return new Mesh(result, List.of());
    }

    private static T transform(Vertex v, TransitionMath.Plan plan, float width) {
        return new T(v.logicalX * plan.scaleX() + width * plan.offsetX(), v.logicalY * plan.scaleY(), v.u, v.v, v.alpha);
    }
    private static Mesh fade(Mesh mesh, float alpha) {
        List<Triangle> result = new ArrayList<>(mesh.triangles.size());
        for (Triangle t : mesh.triangles) result.add(new Triangle(fade(t.a, alpha), fade(t.b, alpha), fade(t.c, alpha)));
        return new Mesh(result, List.of());
    }
    private static Vertex fade(Vertex v, float alpha) {
        return new Vertex(v.x, v.y, v.z, v.u, v.v, v.nx, v.ny, v.nz, v.alpha * alpha, v.logicalX, v.logicalY);
    }
    private static List<P> iris(Bounds b, float progress) {
        // Circumscribed 48-gon: at progress=1 its inradius reaches all four bounding corners.
        float radius = (float) (Math.hypot(b.width() / 2, b.height() / 2) / Math.cos(Math.PI / 48)) * progress;
        List<P> result = new ArrayList<>(48);
        for (int i = 0; i < 48; i++) {
            double theta = i * Math.PI / 24;
            result.add(new P((float) Math.cos(theta) * radius, (float) Math.sin(theta) * radius, 1));
        }
        return result;
    }
    private static List<P> rectangle(Bounds b) {
        return List.of(new P(b.x0, b.y0, 1), new P(b.x1, b.y0, 1), new P(b.x1, b.y1, 1), new P(b.x0, b.y1, 1));
    }
    private static List<T> clipT(List<T> polygon, List<P> boundary) {
        List<T> result = polygon;
        for (int i = 0; i < boundary.size() && !result.isEmpty(); i++) {
            P a = boundary.get(i), b = boundary.get((i + 1) % boundary.size());
            List<T> next = new ArrayList<>();
            T previous = result.getLast();
            float oldDistance = cross(a, b, previous);
            for (T point : result) {
                float newDistance = cross(a, b, point);
                if ((newDistance >= 0) != (oldDistance >= 0))
                    next.add(interpolate(previous, point, oldDistance / (oldDistance - newDistance)));
                if (newDistance >= 0) next.add(point);
                previous = point; oldDistance = newDistance;
            }
            result = next;
        }
        return result;
    }
    private static float cross(P a, P b, T p) { return (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x); }
    private static T interpolate(T a, T b, float t) {
        return new T(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                a.u + (b.u - a.u) * t, a.v + (b.v - a.v) * t, a.alpha + (b.alpha - a.alpha) * t);
    }
    private static void triangulateT(Surface s, List<T> polygon, List<Triangle> result) {
        for (int i = 1; i < polygon.size() - 1; i++) {
            T a = polygon.getFirst(), b = polygon.get(i), c = polygon.get(i + 1);
            float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(area) < EPS * EPS) continue;
            if (area < 0) { T temporary = b; b = c; c = temporary; }
            result.add(new Triangle(map(s, a), map(s, b), map(s, c)));
        }
    }
    private static Vertex map(Surface s, T p) {
        Vertex mapped = map(s, new P(p.x, p.y, p.alpha), new Bounds(0, 0, 1, 1));
        return new Vertex(mapped.x, mapped.y, mapped.z, p.u, p.v, mapped.nx, mapped.ny, mapped.nz, p.alpha, p.x, p.y);
    }

    public static Bounds inscribedText(PreviewConfig.Shape shape, float width, float height, float cornerRadius) {
        return inscribedText(shape, width, height, cornerRadius, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Bounds inscribedText(PreviewConfig.Shape shape, float width, float height, float cornerRadius, String customShape) {
        return inscribedText(shape, width, height, 45, cornerRadius, customShape);
    }
    public static Bounds inscribedText(PreviewConfig.Shape shape, float width, float height, float curveDegrees, float cornerRadius) {
        return inscribedText(shape, width, height, curveDegrees, cornerRadius, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static Bounds inscribedText(PreviewConfig.Shape shape, float width, float height, float curveDegrees, float cornerRadius, String customShape) {
        Surface s = settings(shape, width, height, curveDegrees, cornerRadius, customShape);
        if (s.curved()) {
            float angle = radians(s), radius = s.width / angle;
            float halfWidth = radius * (float) Math.sin(Math.min(angle / 2, Math.PI / 3));
            return new Bounds(-halfWidth, -s.height / 2, halfWidth, s.height / 2);
        }
        List<P> outline = outline(s);
        Bounds b = bounds(outline);
        float centerX=0,centerY=0;
        for(P point:outline) { centerX+=point.x/outline.size(); centerY+=point.y/outline.size(); }
        float low = 0, high = 1;
        for (int i = 0; i < 22; i++) {
            float f = (low + high) / 2;
            if (rectangleInside(outline, centerX, centerY, b.width() * f / 2, b.height() * f / 2)) low = f;
            else high = f;
        }
        return new Bounds(centerX-b.width() * low / 2, centerY-b.height() * low / 2, centerX+b.width() * low / 2, centerY+b.height() * low / 2);
    }

    /** Native text remains on a small central flat plane in front of the central arc. */
    public static float textPlaneZ(PreviewConfig.Shape shape, float width, float curveDegrees) {
        Surface s = settings(shape, width, 1, curveDegrees, 0);
        if (!s.curved() || shape == PreviewConfig.Shape.CONVEX) return .022f;
        float angle = radians(s), radius = s.width / angle;
        return radius * (1 - (float) Math.cos(Math.min(angle / 2, Math.PI / 3))) + .022f;
    }
    private static float radians(Surface s) {
        float degrees = s.shape == PreviewConfig.Shape.PANORAMA ? Math.max(120, s.curve) : Math.min(170, s.curve);
        return (float) Math.toRadians(degrees);
    }

    /** Logical silhouette before cylindrical mapping, for deterministic geometric tests. */
    public static List<Point> silhouette(PreviewConfig.Shape shape, float width, float height, float cornerRadius) {
        return silhouette(shape, width, height, cornerRadius, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }

    public static List<Point> silhouette(PreviewConfig.Shape shape, float width, float height, float cornerRadius, String customShape) {
        return outline(settings(shape, width, height, 0, cornerRadius, customShape)).stream().map(p -> new Point(p.x, p.y)).toList();
    }

    private static Surface settings(PreviewConfig.Shape shape, float width, float height, float curve, float radius) {
        return settings(shape, width, height, curve, radius, org.hurtorius.mirror.core.PolygonShape.DEFAULT_CSV);
    }
    private static Surface settings(PreviewConfig.Shape shape, float width, float height, float curve, float radius, String customShape) {
        return new Surface(shape == null ? PreviewConfig.Shape.FLAT : shape, clamp(width, .01f, 64), clamp(height, .01f, 36),
                clamp(curve, 1, 300), clamp(radius, 0, .5f), customShape);
    }
    private static float clamp(float value, float min, float max) { return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min; }
    private static Bounds bounds(List<P> polygon) {
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
        for (P p : polygon) { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); }
        return new Bounds(x0, y0, x1, y1);
    }
    private static List<P> outline(Surface s) {
        float w = s.width / 2, h = s.height / 2;
        List<P> points = new ArrayList<>();
        switch (s.shape) {
            case CUSTOM -> {
                for (var point : org.hurtorius.mirror.core.PolygonShape.parse(s.customShape).points())
                    points.add(new P(point.x() * w, point.y() * h, 1));
            }
            case CIRCLE, OVAL -> {
                if (s.shape == PreviewConfig.Shape.CIRCLE) w = h = Math.min(w, h);
                for (int i = 0; i < 64; i++) {
                    double theta = i * Math.PI / 32;
                    points.add(new P((float) Math.cos(theta) * w, (float) Math.sin(theta) * h, 1));
                }
            }
            case HEXAGON -> {
                points.add(new P(w, 0, 1)); points.add(new P(w / 2, h, 1)); points.add(new P(-w / 2, h, 1));
                points.add(new P(-w, 0, 1)); points.add(new P(-w / 2, -h, 1)); points.add(new P(w / 2, -h, 1));
            }
            case ROUNDED -> {
                float r = Math.min(s.width, s.height) * s.radius;
                if (r < EPS) return rectangle(w, h);
                for (int corner = 0; corner < 4; corner++) {
                    float cx = (corner == 0 || corner == 3) ? w - r : -w + r;
                    float cy = corner < 2 ? h - r : -h + r;
                    for (int i = 0; i <= 12; i++) {
                        double theta = (corner * 90 + i * 7.5) * Math.PI / 180;
                        P point = new P(cx + (float) Math.cos(theta) * r, cy + (float) Math.sin(theta) * r, 1);
                        if (points.isEmpty() || Math.hypot(point.x - points.getLast().x, point.y - points.getLast().y) > EPS) points.add(point);
                    }
                }
            }
            default -> { return rectangle(w, h); }
        }
        if (points.size() > 2 && Math.hypot(points.getFirst().x - points.getLast().x, points.getFirst().y - points.getLast().y) <= EPS)
            points.removeLast();
        return points;
    }
    private static List<P> rectangle(float w, float h) {
        return List.of(new P(-w, -h, 1), new P(w, -h, 1), new P(w, h, 1), new P(-w, h, 1));
    }
    private static boolean rectangleInside(List<P> polygon, float w, float h) { return rectangleInside(polygon,0,0,w,h); }
    private static boolean rectangleInside(List<P> polygon, float x, float y, float w, float h) {
        return inside(polygon, x-w, y-h) && inside(polygon, x+w, y-h) && inside(polygon, x+w, y+h) && inside(polygon, x-w, y+h);
    }
    private static boolean inside(List<P> polygon, float x, float y) {
        for (int i = 0; i < polygon.size(); i++) {
            P a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < -EPS) return false;
        }
        return true;
    }
    private static Mesh build(Surface s, List<P> outline, Bounds clip, Bounds uv, float fadeWidth) {
        if (clip.width() <= EPS || clip.height() <= EPS || uv.width() <= EPS || uv.height() <= EPS) return EMPTY;
        List<Triangle> triangles = new ArrayList<>();
        if (fadeWidth > EPS) {
            float f = 1 - clamp(fadeWidth / (Math.min(s.width, s.height) / 2), 0, .8f);
            List<P> core = outline.stream().map(p -> new P(p.x * f, p.y * f, 1)).toList();
            tessellate(s, clip(core, clip), uv, triangles);
            for (int i = 0; i < outline.size(); i++) {
                int next = (i + 1) % outline.size();
                P a = outline.get(i), b = outline.get(next);
                List<P> strip = List.of(core.get(i), new P(a.x, a.y, 0), new P(b.x, b.y, 0), core.get(next));
                tessellate(s, clip(strip, clip), uv, triangles);
            }
        } else tessellate(s, clip(outline, clip), uv, triangles);
        return new Mesh(triangles, mappedBoundary(s, outline, uv));
    }
    private static void append(List<Triangle> triangles, Mesh mesh) { triangles.addAll(mesh.triangles); }
    private static void tessellate(Surface s, List<P> polygon, Bounds uv, List<Triangle> out) {
        if (polygon.size() < 3) return;
        if (!s.curved()) { triangulate(s, polygon, uv, out); return; }
        int columns = Math.clamp((int) Math.ceil(Math.max(s.shape == PreviewConfig.Shape.PANORAMA ? 120 : 45, s.curve) / 5), 12, 64);
        float step = s.width / columns;
        Bounds extent = bounds(polygon);
        int first = (int) Math.floor((extent.x0 + s.width / 2) / step);
        int last = (int) Math.floor((extent.x1 + s.width / 2) / step);
        for (int i = first; i <= last; i++) {
            float left = -s.width / 2 + i * step;
            triangulate(s, clip(polygon, new Bounds(left, extent.y0 - 1, left + step, extent.y1 + 1)), uv, out);
        }
    }
    private static void triangulate(Surface s, List<P> p, Bounds uv, List<Triangle> out) {
        if (p.size() < 3) return;
        for (int i = 1; i < p.size() - 1; i++) {
            P a = p.getFirst(), b = p.get(i), c = p.get(i + 1);
            float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(area) < EPS * EPS) continue;
            if (area < 0) { P temp = b; b = c; c = temp; }
            out.add(new Triangle(map(s, a, uv), map(s, b, uv), map(s, c, uv)));
        }
    }
    private static List<Vertex> mappedBoundary(Surface s, List<P> outline, Bounds uv) {
        List<Vertex> result = new ArrayList<>();
        for (int i = 0; i < outline.size(); i++) {
            P a = outline.get(i), b = outline.get((i + 1) % outline.size());
            int segments = s.curved() ? Math.max(1, (int) Math.ceil(Math.abs(b.x - a.x) / Math.max(.01f, s.width / 40))) : 1;
            for (int part = 0; part < segments; part++) result.add(map(s, interpolate(a, b, (float) part / segments), uv));
        }
        return result;
    }
    private static Vertex map(Surface s, P p, Bounds uv) {
        float x = p.x, z = 0, nx = 0, nz = 1;
        if (s.curved()) {
            float angle = radians(s), radius = s.width / angle, theta = p.x / radius;
            float sign = s.shape == PreviewConfig.Shape.CONVEX ? -1 : 1;
            x = radius * (float) Math.sin(theta); z = sign * radius * (1 - (float) Math.cos(theta));
            nx = -sign * (float) Math.sin(theta); nz = (float) Math.cos(theta);
        }
        return new Vertex(x, p.y, z, (p.x - uv.x0) / uv.width(), 1 - (p.y - uv.y0) / uv.height(), nx, 0, nz, p.alpha, p.x, p.y);
    }
    private static List<P> offset(List<P> polygon, float distance) {
        List<P> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            P previous = polygon.get((i + polygon.size() - 1) % polygon.size()), p = polygon.get(i), next = polygon.get((i + 1) % polygon.size());
            double aLength = Math.hypot(p.x - previous.x, p.y - previous.y), bLength = Math.hypot(next.x - p.x, next.y - p.y);
            float ax = (float) ((p.y - previous.y) / aLength), ay = (float) ((previous.x - p.x) / aLength);
            float bx = (float) ((next.y - p.y) / bLength), by = (float) ((p.x - next.x) / bLength);
            float mx = ax + bx, my = ay + by, divisor = Math.max(.25f, mx * bx + my * by);
            result.add(new P(p.x + distance * mx / divisor, p.y + distance * my / divisor, 1));
        }
        return result;
    }
    private static List<P> clip(List<P> polygon, Bounds b) {
        if (b.width() <= EPS || b.height() <= EPS) return List.of();
        List<P> result = polygon;
        for (int edge = 0; edge < 4 && !result.isEmpty(); edge++) {
            List<P> next = new ArrayList<>();
            P previous = result.getLast();
            float oldDistance = distance(previous, edge, b);
            for (P point : result) {
                float newDistance = distance(point, edge, b);
                if ((newDistance >= 0) != (oldDistance >= 0))
                    next.add(interpolate(previous, point, oldDistance / (oldDistance - newDistance)));
                if (newDistance >= 0) next.add(point);
                previous = point; oldDistance = newDistance;
            }
            result = next;
        }
        return result;
    }
    private static float distance(P p, int edge, Bounds b) {
        return switch (edge) { case 0 -> p.x - b.x0; case 1 -> b.x1 - p.x; case 2 -> p.y - b.y0; default -> b.y1 - p.y; };
    }
    private static P interpolate(P a, P b, float t) { return new P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.alpha + (b.alpha - a.alpha) * t); }
}
