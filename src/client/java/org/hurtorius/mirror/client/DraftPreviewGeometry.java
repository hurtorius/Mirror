package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.joml.Matrix3d;
import org.joml.Vector3d;

/** Pure orthographic editor geometry. No world, media, GPU or native-runtime access. */
public final class DraftPreviewGeometry {
    public static final int MAX_TRIANGLES = 24_000;
    public record Camera(float yaw, float elevation, float zoom) {
        public Camera {
            yaw = finite(yaw, 0) % 360;
            elevation = Math.clamp(finite(elevation, 0), -80, 80);
            zoom = Math.clamp(finite(zoom, 1), .5f, 4);
        }
        public Camera(float yaw, float elevation) { this(yaw, elevation, 1); }
        public static Camera reset() { return new Camera(28, 18); }
    }
    public record Point(float x, float y, float z) { }
    public record Vertex(float x, float y, float depth, float u, float v, int color) { }
    public record Triangle(Vertex a, Vertex b, Vertex c, boolean textured) {
        public float depth() { return (a.depth + b.depth + c.depth) / 3; }
    }
    public record Line(Point a, Point b, int color) { }
    /** Content-free affine projection shared with clipped native glyph geometry. */
    public record TextProjection(Point origin, Point right, Point up, Point forward) {
        public Point project(float x, float y, float z) {
            return new Point(origin.x + (right.x-origin.x)*x + (up.x-origin.x)*y + (forward.x-origin.x)*z,
                    origin.y + (right.y-origin.y)*x + (up.y-origin.y)*y + (forward.y-origin.y)*z,
                    origin.z + (right.z-origin.z)*x + (up.z-origin.z)*y + (forward.z-origin.z)*z);
        }

        public boolean centerFront() { return forward.z >= origin.z; }
        public boolean front(ScreenTextGeometry.Triangle t) {var a=project(t.a().x(),t.a().y(),t.a().z());var b=project(t.b().x(),t.b().y(),t.b().z());var c=project(t.c().x(),t.c().y(),t.c().z());return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)<0;}
    }
    public record Scene(List<Triangle> triangles, List<Line> grid, Point origin, Point center,
                        float pixelsPerBlock, boolean limited,
                        ScreenMesh.Mesh textSurface, TextProjection textProjection, TextProjection deviceProjection) {
        public Scene { triangles = List.copyOf(triangles); grid = List.copyOf(grid); }
    }
    private record Key(PreviewConfig.Shape shape, PreviewConfig.Fit fit, float width, float height,
                       float curve, float corner, String polygon, PreviewConfig.EdgeStyle edge,
                       float edgeWidth, boolean scans, int pixelWidth, int pixelHeight, ScreenTileLayout.Tile tile, float thickness) { }
    /** The edge meshes follow ScreenMeshCache.edgeLayers; the plain (unfaded) surface carries text, lines and sides as in the world. */
    private record Geometry(ScreenMesh.Mesh surface, ScreenMesh.Mesh plain, ScreenMesh.Mesh image, ScreenMesh.Mesh sides,
                            List<ScreenMesh.Mesh> edges, ScreenMesh.Mesh scans) { }
    private record Raw(Point point, float u, float v, float alpha) { }
    private record Face(Raw a, Raw b, Raw c, int color, boolean textured) { }
    private static final ScreenMesh.Mesh EMPTY = new ScreenMesh.Mesh(List.of(), List.of());
    private record ProjectionKey(Key mesh, Camera camera, int width, int height, float x, float y, float z,
                                 float yaw, float pitch, float roll, float opacity, int background, int color,
                                 int backColor, float thickness, boolean twoSided, PreviewConfig.BackFace backFace, boolean projector, float glow,PreviewConfig.Source source,int imageStyle) { }
    /** The screen rotation and offset and the camera orbit, combined once per projection instead of per point. */
    private static final class View {
        final Matrix3d orbit, combined; final Vector3d offset;
        View(PreviewConfig c, Camera camera) {
            orbit = new Matrix3d().rotationX(Math.toRadians(camera.elevation())).rotateY(-Math.toRadians(camera.yaw()));
            combined = new Matrix3d(orbit).mul(rotation(c));
            offset = orbit.transform(new Vector3d(finite(c.offsetX,0), finite(c.offsetY,2), finite(c.offsetZ,0)));
        }
        /** Camera space of a world point (the Initiator is the origin). */
        Point view(float x, float y, float z) { return point(orbit.transform(new Vector3d(x, y, z))); }
        /** Camera space of a point on the screen. */
        Point screen(float x, float y, float z) { return point(combined.transform(new Vector3d(x, y, z)).add(offset)); }
        /** Whether a screen-space direction points toward the camera. */
        boolean toward(float x, float y, float z) { return combined.m02 * x + combined.m12 * y + combined.m22 * z >= 0; }
    }
    private Key key;
    private Geometry geometry;
    private ProjectionKey projectionKey;
    private Scene cachedScene;

    /** Only immutable, non-content meshes are cached. Source pixels and identifiers never enter this class. */
    public Scene project(PreviewConfig c, Camera camera, int width, int height, int pixelWidth, int pixelHeight) {
        return project(c, camera, width, height, pixelWidth, pixelHeight, true);
    }
    public Scene project(PreviewConfig c, Camera camera, int width, int height, int pixelWidth, int pixelHeight, boolean viewerGlow) {
        width = Math.clamp(width, 1, 8192); height = Math.clamp(height, 1, 8192);
        PreviewConfig.EdgeStyle edge = c.border ? c.edgeStyle : PreviewConfig.EdgeStyle.NONE;
        Key next = new Key(c.shape, c.fit, c.width, c.height, c.curveDegrees, c.cornerRadius, c.customShape,
                edge, c.edgeWidth, c.scanlines, Math.clamp(pixelWidth, 0, 32768), Math.clamp(pixelHeight, 0, 32768),
                new ScreenTileLayout.Tile(c.tileColumns, c.tileRows, c.tileColumn, c.tileRow), c.thickness);
        ProjectionKey nextProjection = new ProjectionKey(next,camera,width,height,c.offsetX,c.offsetY,c.offsetZ,
                c.yaw,c.pitch,c.roll,c.opacity,c.background,c.color,c.backColor,c.thickness,c.twoSided,c.backFace,c.projector,viewerGlow?c.edgeGlowIntensity():0,c.source,ReflectionStyle.color(255,c.brightness,c.contrast,c.saturation));
        if (nextProjection.equals(projectionKey)) return cachedScene;
        if (!next.equals(key)) { geometry = build(next); key = next; }
        View view = new View(c, camera);
        List<Face> faces = new ArrayList<>();
        float opacity = Math.clamp(finite(c.opacity, 1), 0, 1);
        // The source surface has the exact shape, fit, UV clipping and depth used in-world.
        append(faces, geometry.surface, c, view, 0, argb(c.background, opacity), false, false, false, true);
        if (!c.projector && (c.backFace == PreviewConfig.BackFace.SOLID || twoSided(c)))
            append(faces, geometry.surface, c, view, 0, argb(c.backColor, opacity), false, true);
        if(!c.projector&&c.thickness>0)append(faces,geometry.sides,c,view,0,argb(c.backColor,opacity),false,false,true);
        // Every picture carries its style in vertex color; the GPU applies it exactly as in the world.
        append(faces, geometry.image, c, view, .009f, ReflectionStyle.color(Math.round(opacity*255),c.brightness,c.contrast,c.saturation), true, false);
        // The world's own edge recipe, so the preview cannot drift from it.
        var layers = ScreenMeshCache.edgeLayers(edge, c.edgeWidth, c.color, nextProjection.glow);
        for (int i = 0; i < layers.size(); i++) { var layer = layers.get(i); append(faces, geometry.edges.get(i), c, view, layer.offset(), argb(layer.rgb(), opacity * layer.alpha()), false, false); }
        append(faces, geometry.scans, c, view, ScreenMeshCache.SCANLINE_OFFSET, argb(0, opacity * ScreenMeshCache.SCANLINE_ALPHA/255f), false, false);
        boolean limited = faces.size() >= MAX_TRIANGLES - 12;
        // The native Initiator assembly is depth-rendered in the preview at this origin.
        List<Line> grid = new ArrayList<>();
        for (int i = -4; i <= 4; i++) {
            grid.add(new Line(view.view(i, 0, -4), view.view(i, 0, 4), i == 0 ? 0xFF50749A : 0x553E536A));
            grid.add(new Line(view.view(-4, 0, i), view.view(4, 0, i), i == 0 ? 0xFF8E635D : 0x553E536A));
        }
        Point origin = view.view(0, 0, 0), center = view.screen(0, 0, 0);
        grid.add(new Line(origin, view.view(0, 2, 0), 0xAA77B999));
        float minX = center.x, maxX = center.x, minY = center.y, maxY = center.y;
        for (Face face : faces) for (Raw vertex : List.of(face.a, face.b, face.c)) {
            minX = Math.min(minX, vertex.point.x); maxX = Math.max(maxX, vertex.point.x);
            minY = Math.min(minY, vertex.point.y); maxY = Math.max(maxY, vertex.point.y);
        }
        // Frame independently of visibility/source readiness: opacity and hiding a rear face
        // must not resize the scene or make the source disappear into the origin marker.
        List<ScreenMesh.Mesh> framed = new ArrayList<>(geometry.edges); framed.add(geometry.surface);
        for (ScreenMesh.Mesh mesh : framed) for (ScreenMesh.Vertex v : mesh.boundary()) {
            Point point = view.screen(v.x(), v.y(), v.z());
            minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
        }
        float spanX=2*Math.max(center.x-minX,maxX-center.x),spanY=2*Math.max(center.y-minY,maxY-center.y);
        float scale=Math.min(Math.max(1,width-16)/(spanX+.8f),Math.max(1,height-16)/(spanY+.8f))*camera.zoom;
        float dx=width/2f-center.x*scale,dy=height/2f+center.y*scale;
        List<Triangle> projected = new ArrayList<>(faces.size());
        for (Face face : faces) {
            Vertex a = vertex(face.a, face.color, scale, dx, dy), b = vertex(face.b, face.color, scale, dx, dy), d = vertex(face.c, face.color, scale, dx, dy);
            if (Math.abs((b.x-a.x)*(d.y-a.y)-(b.y-a.y)*(d.x-a.x)) > .00001f)
                projected.add(new Triangle(a,b,d,face.textured));
        }
        projected.sort(Comparator.comparingDouble(Triangle::depth));
        List<Line> projectedGrid = new ArrayList<>();
        for (Line line : grid) projectedGrid.add(new Line(screen(line.a, scale, dx, dy), screen(line.b, scale, dx, dy), line.color));
        boolean front = view.toward(0, 0, 1);
        float back = front ? 0 : c.thickness;
        cachedScene = new Scene(projected, projectedGrid, screen(origin,scale,dx,dy), screen(center,scale,dx,dy), scale, limited,
                geometry.plain, new TextProjection(screen(view.screen(0,0,-back),scale,dx,dy),
                        screen(view.screen(1,0,-back),scale,dx,dy),
                        screen(view.screen(0,1,-back),scale,dx,dy),
                        screen(view.screen(0,0,1-back),scale,dx,dy)),
                new TextProjection(screen(origin,scale,dx,dy),
                        screen(view.view(1,0,0),scale,dx,dy),
                        screen(view.view(0,1,0),scale,dx,dy),
                        screen(view.view(0,0,1),scale,dx,dy)));
        projectionKey = nextProjection;
        return cachedScene;
    }

    private static Geometry build(Key k) {
        float fade = k.edge == PreviewConfig.EdgeStyle.FADE ? Math.max(.001f,k.edgeWidth) : 0;
        ScreenMesh.Mesh surface = ScreenMesh.surface(k.shape,k.width,k.height,k.curve,k.corner,fade,k.polygon);
        ScreenMesh.Mesh plain = fade > 0 ? ScreenMesh.surface(k.shape,k.width,k.height,k.curve,k.corner,0,k.polygon) : surface;
        ScreenMesh.Mesh image = ScreenMesh.image(k.shape,k.width,k.height,k.curve,k.corner,k.fit,k.pixelWidth,k.pixelHeight,fade,k.tile,k.polygon);
        // Every possible layer is built, the halo included, so turning glow off never reframes the preview.
        List<ScreenMesh.Mesh> edges = new ArrayList<>();
        for (var layer : ScreenMeshCache.edgeLayers(k.edge, k.edgeWidth, 0, 1))
            edges.add(ScreenMesh.edge(k.shape,k.width,k.height,k.curve,k.corner,layer.inner(),layer.outer(),layer.dashed(),k.polygon));
        ScreenMesh.Mesh scans = k.scans ? ScreenMesh.scanlines(k.shape,k.width,k.height,k.curve,k.corner,96,0,k.polygon) : EMPTY;
        return new Geometry(surface,plain,image,k.thickness>0?ScreenShell.sides(plain,k.thickness):EMPTY,List.copyOf(edges),scans);
    }

    private static void append(List<Face> faces, ScreenMesh.Mesh mesh, PreviewConfig c, View view, float offset, int color, boolean textured, boolean rearOnly) {
        append(faces,mesh,c,view,offset,color,textured,rearOnly,false);
    }
    private static void append(List<Face> faces, ScreenMesh.Mesh mesh, PreviewConfig c, View view, float offset, int color, boolean textured, boolean rearOnly,boolean positioned) {
        append(faces,mesh,c,view,offset,color,textured,rearOnly,positioned,false);
    }
    private static void append(List<Face> faces, ScreenMesh.Mesh mesh, PreviewConfig c, View view, float offset, int color, boolean textured, boolean rearOnly,boolean positioned,boolean frontOnly) {
        if ((color >>> 24) == 0) return;
        for (ScreenMesh.Triangle triangle : mesh.triangles()) {
            if (faces.size() >= MAX_TRIANGLES - 12) return;
            // Averaged analytic mesh normals determine the visible side, including wraparound arcs.
            boolean front = view.toward(triangle.a().nx()+triangle.b().nx()+triangle.c().nx(),triangle.a().ny()+triangle.b().ny()+triangle.c().ny(),triangle.a().nz()+triangle.b().nz()+triangle.c().nz());
            if (frontOnly&&!front || (rearOnly ? front : (!front && (!twoSided(c) || (textured && c.backFace != PreviewConfig.BackFace.MIRROR && c.backFace != PreviewConfig.BackFace.READABLE))))) continue;
            float visibleOffset = !front && !rearOnly ? -offset : offset;
            boolean readableRear=textured&&!front&&c.backFace==PreviewConfig.BackFace.READABLE;
            float rearDepth=!front&&!positioned?c.thickness:0;
            faces.add(new Face(raw(triangle.a(),c,view,visibleOffset,readableRear,rearDepth),raw(triangle.b(),c,view,visibleOffset,readableRear,rearDepth),raw(triangle.c(),c,view,visibleOffset,readableRear,rearDepth),color,textured));
        }
    }
    private static Raw raw(ScreenMesh.Vertex v, PreviewConfig c, View view, float offset,boolean readableRear,float rearDepth) {
        float u=ScreenSurfaceSide.imageU(v.u(),readableRear?-1:1,readableRear);
        return new Raw(view.screen(v.x()+v.nx()*offset,v.y()+v.ny()*offset,v.z()+v.nz()*offset-rearDepth),c.source==PreviewConfig.Source.MIRROR?1-u:u,c.source==PreviewConfig.Source.MIRROR?1-v.v():v.v(),v.alpha());
    }
    /** Mirrors the world renderer's Y * X * Z rotation order exactly. */
    private static Matrix3d rotation(PreviewConfig c) {
        return new Matrix3d().rotationYXZ(Math.toRadians(finite(c.yaw,0)), Math.toRadians(finite(c.pitch,0)), Math.toRadians(finite(c.roll,0)));
    }
    static Point rotate(Point p, PreviewConfig c) { return point(rotation(c).transform(new Vector3d(p.x, p.y, p.z))); }
    static Point world(Point p, PreviewConfig c) {
        Point r = rotate(p,c);
        return new Point(r.x+finite(c.offsetX,0),r.y+finite(c.offsetY,2),r.z+finite(c.offsetZ,0));
    }
    private static Point point(Vector3d v) { return new Point((float)v.x,(float)v.y,(float)v.z); }
    private static Point screen(Point p, float scale, float dx, float dy) { return new Point(dx+p.x*scale,dy-p.y*scale,p.z); }
    private static Vertex vertex(Raw v, int color, float scale, float dx, float dy) {
        Point p = screen(v.point,scale,dx,dy);
        return new Vertex(p.x,p.y,p.z,v.u,v.v,((Math.round((color>>>24)*v.alpha)&255)<<24)|(color&0xFFFFFF));
    }
    private static boolean twoSided(PreviewConfig c) { return c.twoSided && !c.projector && c.backFace != PreviewConfig.BackFace.HIDDEN; }
    private static float finite(float value, float fallback) { return Float.isFinite(value)?value:fallback; }
    private static int argb(int rgb, float alpha) { return (Math.round(Math.clamp(alpha,0,1)*255)<<24)|(rgb&0xFFFFFF); }
}
