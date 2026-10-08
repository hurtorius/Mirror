package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.List;

/** CPU-only native-glyph clipping against the exact (possibly masked) screen mesh. */
public final class ScreenTextGeometry {
    public static final int MAX_INPUT_VERTICES = 65_536, MAX_BATCHES = 128;
    public static final int MAX_TRIANGLES = 24_000, MAX_WORK = 262_144;
    public static final int FRAME_VERTICES = 262_144, FRAME_TRIANGLES = 96_000, FRAME_WORK = 1_048_576;
    private static final int GRID = 32, MAX_INDEX_REFERENCES = 131_072;
    private static final double EPS = 1e-10;
    private ScreenTextGeometry() { }

    /** Packed color/light remain attributes, never identifiers for font or source content. */
    public record Input(float x, float y, float depth, float u, float v, int color, int light) {
        boolean finite() { return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(depth)
                && Float.isFinite(u) && Float.isFinite(v); }
    }
    public record Vertex(float x, float y, float z, float logicalX, float logicalY,
                         float u, float v, int color, int light, float nx, float ny, float nz, float offset) { }
    public record Triangle(Vertex a, Vertex b, Vertex c) { }

    /** Numeric accounting only; a frame never retains glyphs, strings, meshes or render states. */
    public static final class Budget {
        private int vertices, triangles, work;
        public Budget(int vertices, int triangles, int work) {
            this.vertices = Math.max(0, vertices); this.triangles = Math.max(0, triangles); this.work = Math.max(0, work);
        }
        public static Budget panel() { return new Budget(MAX_INPUT_VERTICES, MAX_TRIANGLES, MAX_WORK); }
        public static Budget frame() { return new Budget(FRAME_VERTICES, FRAME_TRIANGLES, FRAME_WORK); }
        public int remainingVertices() { return vertices; }
        public int remainingTriangles() { return triangles; }
        public int remainingWork() { return work; }
    }

    /** A hard failure discards this panel's output, with no unmasked or repeated-text fallback. */
    public static final class Projector {
        private final Budget panel = Budget.panel(), frame;
        private final ScreenMesh.Mesh surface;
        private final List<Integer>[] cells;
        private final int[] visited;
        private int generation;
        private float minX, minY, spanX, spanY;
        private boolean failed;

        @SuppressWarnings("unchecked")
        public Projector(ScreenMesh.Mesh surface, Budget frame) {
            this.surface = surface; this.frame = frame;
            cells = (List<Integer>[]) new List<?>[GRID * GRID];
            if (surface == null || surface.triangles().size() > ScreenMesh.MAX_TRANSITION_TRIANGLES) {
                visited = new int[0]; failed = true; return;
            }
            visited = new int[surface.triangles().size()];
            if (surface.empty()) return;
            minX = minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
            for (var triangle : surface.triangles()) for (var p : List.of(triangle.a(), triangle.b(), triangle.c())) {
                if (!Float.isFinite(p.logicalX()) || !Float.isFinite(p.logicalY()) || !Float.isFinite(p.x())
                        || !Float.isFinite(p.y()) || !Float.isFinite(p.z()) || !Float.isFinite(p.alpha())
                        || !Float.isFinite(p.nx()) || !Float.isFinite(p.ny()) || !Float.isFinite(p.nz())) {
                    failed = true; return;
                }
                minX = Math.min(minX, p.logicalX()); minY = Math.min(minY, p.logicalY());
                maxX = Math.max(maxX, p.logicalX()); maxY = Math.max(maxY, p.logicalY());
            }
            spanX = Math.max(.000001f, maxX - minX); spanY = Math.max(.000001f, maxY - minY);
            int references = 0;
            for (int i = 0; i < surface.triangles().size(); i++) {
                var t = surface.triangles().get(i);
                float x0 = Math.min(t.a().logicalX(), Math.min(t.b().logicalX(), t.c().logicalX()));
                float x1 = Math.max(t.a().logicalX(), Math.max(t.b().logicalX(), t.c().logicalX()));
                float y0 = Math.min(t.a().logicalY(), Math.min(t.b().logicalY(), t.c().logicalY()));
                float y1 = Math.max(t.a().logicalY(), Math.max(t.b().logicalY(), t.c().logicalY()));
                for (int y = cellY(y0); y <= cellY(y1); y++) for (int x = cellX(x0); x <= cellX(x1); x++) {
                    if (!work()) return;
                    float left=minX+x*spanX/GRID, bottom=minY+y*spanY/GRID;
                    if (!overlaps(t,left,bottom,left+spanX/GRID,bottom+spanY/GRID)) continue;
                    if (++references > MAX_INDEX_REFERENCES) { failed = true; return; }
                    int cell = y * GRID + x;
                    if (cells[cell] == null) cells[cell] = new ArrayList<>();
                    cells[cell].add(i);
                }
            }
        }

        public boolean failed() { return failed; }
        public void fail() { failed = true; }
        public boolean vertex() {
            if (failed || panel.vertices <= 0 || frame.vertices <= 0) { failed = true; return false; }
            panel.vertices--; frame.vertices--; return true;
        }
        private boolean work() {
            if (failed || panel.work <= 0 || frame.work <= 0) { failed = true; return false; }
            panel.work--; frame.work--; return true;
        }
        private boolean triangle() {
            if (failed || panel.triangles <= 0 || frame.triangles <= 0) { failed = true; return false; }
            panel.triangles--; frame.triangles--; return true;
        }
        private int cellX(float x) { return Math.clamp((int) ((x - minX) / spanX * GRID), 0, GRID - 1); }
        private int cellY(float y) { return Math.clamp((int) ((y - minY) / spanY * GRID), 0, GRID - 1); }

        public List<Triangle> project(Input a, Input b, Input c, Input d) {
            if (failed || surface.empty()) return List.of();
            if (!a.finite() || !b.finite() || !c.finite() || !d.finite()) { failed = true; return List.of(); }
            List<Triangle> out = new ArrayList<>();
            // Ordinary glyphs/effects are affine quads with constant color/light. Clip those
            // once, avoiding duplicate work on their invisible internal diagonal. Unusual
            // non-affine data keeps the native QUADS diagonal and its exact interpolation.
            if (affine(a,b,c,d)) projectPolygon(List.of(a,b,c,d), out);
            else { projectPolygon(List.of(a,b,c), out); projectPolygon(List.of(a,c,d), out); }
            return failed ? List.of() : List.copyOf(out);
        }

        private void projectPolygon(List<Input> source, List<Triangle> out) {
            Input a=source.get(0),b=source.get(1),c=source.get(2);
            if (failed || Math.abs(cross(a.x, a.y, b.x, b.y, c.x, c.y)) < EPS) return;
            float x0 = Math.min(a.x, Math.min(b.x, c.x)), x1 = Math.max(a.x, Math.max(b.x, c.x));
            float y0 = Math.min(a.y, Math.min(b.y, c.y)), y1 = Math.max(a.y, Math.max(b.y, c.y));
            if(source.size()==4){Input d=source.get(3);x0=Math.min(x0,d.x);x1=Math.max(x1,d.x);y0=Math.min(y0,d.y);y1=Math.max(y1,d.y);}
            if (x1 < minX || x0 > minX + spanX || y1 < minY || y0 > minY + spanY) return;
            int stamp = ++generation; // Input vertices cap keeps this well below integer overflow.
            for (int y = cellY(y0); y <= cellY(y1); y++) for (int x = cellX(x0); x <= cellX(x1); x++) {
                List<Integer> candidates = cells[y * GRID + x];
                if (candidates == null) continue;
                for (int index : candidates) {
                    if (!work()) return; // Count duplicate candidates too: CPU work is bounded.
                    if (visited[index] == stamp) continue;
                    visited[index] = stamp;
                    ScreenMesh.Triangle t = surface.triangles().get(index);
                    if (x1 < Math.min(t.a().logicalX(), Math.min(t.b().logicalX(), t.c().logicalX()))
                            || x0 > Math.max(t.a().logicalX(), Math.max(t.b().logicalX(), t.c().logicalX()))
                            || y1 < Math.min(t.a().logicalY(), Math.min(t.b().logicalY(), t.c().logicalY()))
                            || y0 > Math.max(t.a().logicalY(), Math.max(t.b().logicalY(), t.c().logicalY()))) continue;
                    double area = cross(t.a().logicalX(), t.a().logicalY(), t.b().logicalX(), t.b().logicalY(), t.c().logicalX(), t.c().logicalY());
                    if (Math.abs(area) < EPS) continue;
                    List<Input> polygon = source;
                    ScreenMesh.Vertex[] corners = {t.a(), t.b(), t.c()};
                    for (int edge = 0; edge < 3 && polygon.size() >= 3; edge++) {
                        if (!work()) return;
                        polygon = clip(polygon, corners[edge], corners[(edge + 1) % 3], Math.signum(area));
                    }
                    for (int i = 1; i < polygon.size() - 1; i++) {
                        Input p = polygon.getFirst(), q = polygon.get(i), r = polygon.get(i + 1);
                        double piece = cross(p.x, p.y, q.x, q.y, r.x, r.y);
                        if (Math.abs(piece) < EPS) continue;
                        if (!triangle()) return;
                        Vertex pa = map(p, t, area), pb = map(q, t, area), pc = map(r, t, area);
                        out.add(piece > 0 ? new Triangle(pa, pb, pc) : new Triangle(pa, pc, pb));
                    }
                }
            }
        }
    }

    private static boolean affine(Input a,Input b,Input c,Input d){
        return a.color==b.color&&a.color==c.color&&a.color==d.color&&a.light==b.light&&a.light==c.light&&a.light==d.light
                &&sameSum(a.x,c.x,b.x,d.x)&&sameSum(a.y,c.y,b.y,d.y)&&sameSum(a.depth,c.depth,b.depth,d.depth)
                &&sameSum(a.u,c.u,b.u,d.u)&&sameSum(a.v,c.v,b.v,d.v);
    }
    private static boolean sameSum(float a,float b,float c,float d){
        double first=(double)a+b,second=(double)c+d;
        return Math.abs(first-second)<=1e-7*Math.max(1,Math.max(Math.abs(first),Math.abs(second)));
    }

    private static List<Input> clip(List<Input> polygon, ScreenMesh.Vertex a, ScreenMesh.Vertex b, double sign) {
        List<Input> out = new ArrayList<>(6);
        Input previous = polygon.getLast();
        double old = cross(a.logicalX(), a.logicalY(), b.logicalX(), b.logicalY(), previous.x, previous.y) * sign;
        for (Input next : polygon) {
            double distance = cross(a.logicalX(), a.logicalY(), b.logicalX(), b.logicalY(), next.x, next.y) * sign;
            if ((distance >= 0) != (old >= 0)) out.add(interpolate(previous, next, (float) (old / (old - distance))));
            if (distance >= 0) out.add(next);
            previous = next; old = distance;
        }
        return out;
    }

    /** Triangle-vs-cell separating axes avoid huge false candidate lists for radial fans. */
    private static boolean overlaps(ScreenMesh.Triangle triangle,float x0,float y0,float x1,float y1) {
        ScreenMesh.Vertex[] points={triangle.a(),triangle.b(),triangle.c()};
        double sign=Math.signum(cross(points[0].logicalX(),points[0].logicalY(),points[1].logicalX(),
                points[1].logicalY(),points[2].logicalX(),points[2].logicalY()));
        if(sign==0)return false;
        for(int i=0;i<3;i++){
            var a=points[i];var b=points[(i+1)%3];
            double dx=(b.logicalX()-a.logicalX())*sign,dy=(b.logicalY()-a.logicalY())*sign;
            float x=dy>0?x0:x1,y=dx>0?y1:y0;
            if(cross(a.logicalX(),a.logicalY(),b.logicalX(),b.logicalY(),x,y)*sign < -EPS)return false;
        }
        return true;
    }

    private static Vertex map(Input p, ScreenMesh.Triangle t, double area) {
        ScreenMesh.Vertex a = t.a(), b = t.b(), c = t.c();
        float wa = (float) (cross(b.logicalX(), b.logicalY(), c.logicalX(), c.logicalY(), p.x, p.y) / area);
        float wb = (float) (cross(c.logicalX(), c.logicalY(), a.logicalX(), a.logicalY(), p.x, p.y) / area);
        float wc = 1 - wa - wb;
        float nx = a.nx() * wa + b.nx() * wb + c.nx() * wc;
        float ny = a.ny() * wa + b.ny() * wb + c.ny() * wc;
        float nz = a.nz() * wa + b.nz() * wb + c.nz() * wc;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > .000001f) { nx /= length; ny /= length; nz /= length; }
        float offset = .022f + p.depth;
        float alpha = Math.clamp(a.alpha() * wa + b.alpha() * wb + c.alpha() * wc, 0, 1);
        int color = (Math.clamp(Math.round((p.color >>> 24) * alpha), 0, 255) << 24) | (p.color & 0xFFFFFF);
        return new Vertex(a.x() * wa + b.x() * wb + c.x() * wc + nx * offset,
                a.y() * wa + b.y() * wb + c.y() * wc + ny * offset,
                a.z() * wa + b.z() * wb + c.z() * wc + nz * offset,
                p.x, p.y, p.u, p.v, color, p.light, nx, ny, nz, offset);
    }
    /** Keep text in front of the selected side of the physical surface. */
    public static Vertex back(Vertex p) {
        return new Vertex(p.x - 2 * p.nx * p.offset, p.y - 2 * p.ny * p.offset,
                p.z - 2 * p.nz * p.offset, p.logicalX, p.logicalY, p.u, p.v, p.color, p.light,
                -p.nx, -p.ny, -p.nz, p.offset);
    }
    private static double cross(float ax, float ay, float bx, float by, float px, float py) {
        return ((double) bx - ax) * ((double) py - ay) - ((double) by - ay) * ((double) px - ax);
    }
    private static Input interpolate(Input a, Input b, float f) {
        return new Input(lerp(a.x, b.x, f), lerp(a.y, b.y, f), lerp(a.depth, b.depth, f),
                lerp(a.u, b.u, f), lerp(a.v, b.v, f), color(a.color, b.color, f),
                channel(a.light & 65535, b.light & 65535, f) | channel(a.light >>> 16, b.light >>> 16, f) << 16);
    }
    private static float lerp(float a, float b, float f) { return (float) (a + ((double) b - a) * f); }
    private static int channel(int a, int b, float f) { return Math.round(a + (b - a) * f); }
    private static int color(int a, int b, float f) {
        return channel(a >>> 24, b >>> 24, f) << 24 | channel((a >>> 16) & 255, (b >>> 16) & 255, f) << 16
                | channel((a >>> 8) & 255, (b >>> 8) & 255, f) << 8 | channel(a & 255, b & 255, f);
    }
}
