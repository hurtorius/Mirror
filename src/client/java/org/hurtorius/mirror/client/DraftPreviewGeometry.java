package org.hurtorius.mirror.client;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    /** Text basis is projected from the same inscribed, flat text plane used in the world renderer. */
    public record TextPlane(Point origin, Point right, Point down, float width, float height, boolean visible) { }
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
                        TextPlane text, float pixelsPerBlock, boolean limited,
                        ScreenMesh.Mesh textSurface, TextProjection textProjection, TextProjection deviceProjection) {
        public Scene { triangles = List.copyOf(triangles); grid = List.copyOf(grid); }
    }
    private record Key(PreviewConfig.Shape shape, PreviewConfig.Fit fit, float width, float height,
                       float curve, float corner, String polygon, PreviewConfig.EdgeStyle edge,
                       float edgeWidth, boolean scans, int pixelWidth, int pixelHeight, ScreenTileLayout.Tile tile) { }
    private record Geometry(ScreenMesh.Mesh surface, ScreenMesh.Mesh image, ScreenMesh.Mesh edge,
                            ScreenMesh.Mesh detail, ScreenMesh.Mesh trim, ScreenMesh.Mesh scans,
                            ScreenMesh.Mesh glowInner, ScreenMesh.Mesh glowOuter,
                            ScreenMesh.Bounds text, float textZ) { }
    private record Raw(Point point, float u, float v, float alpha) { }
    private record Face(Raw a, Raw b, Raw c, int color, boolean textured) { }
    private static final ScreenMesh.Mesh EMPTY = new ScreenMesh.Mesh(List.of(), List.of());
    private record ProjectionKey(Key mesh, Camera camera, int width, int height, float x, float y, float z,
                                 float yaw, float pitch, float roll, float opacity, int background, int color,
                                 int backColor, boolean twoSided, PreviewConfig.BackFace backFace, boolean projector, float glow) { }
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
                new ScreenTileLayout.Tile(c.tileColumns, c.tileRows, c.tileColumn, c.tileRow));
        ProjectionKey nextProjection = new ProjectionKey(next,camera,width,height,c.offsetX,c.offsetY,c.offsetZ,
                c.yaw,c.pitch,c.roll,c.opacity,c.background,c.color,c.backColor,c.twoSided,c.backFace,c.projector,viewerGlow?c.edgeGlowIntensity():0);
        if (nextProjection.equals(projectionKey)) return cachedScene;
        if (!next.equals(key)) { geometry = build(next); key = next; }
        List<Face> faces = new ArrayList<>();
        float opacity = Math.clamp(finite(c.opacity, 1), 0, 1);
        // The source surface has the exact shape, fit, UV clipping and depth used in-world.
        append(faces, geometry.surface, c, camera, 0, argb(c.background, opacity), false, false);
        if (!c.projector && c.backFace == PreviewConfig.BackFace.SOLID)
            append(faces, geometry.surface, c, camera, 0, argb(c.backColor, opacity), false, true);
        append(faces, geometry.image, c, camera, .009f, argb(0xFFFFFF, opacity), true, false);
        float glow = nextProjection.glow;

        append(faces, geometry.glowInner, c, camera, .005f, argb(c.color, opacity * 32/255f * glow), false, false);
        int frameColor = edge == PreviewConfig.EdgeStyle.TV ? 0x17202A : edge == PreviewConfig.EdgeStyle.CARVED ? 0x754A2D : c.color;
        append(faces, geometry.edge, c, camera, .012f, argb(frameColor, opacity), false, false);
        append(faces, geometry.detail, c, camera, .016f, argb(0xd6bdf7, opacity), false, false);
        append(faces, geometry.trim, c, camera, .015f, argb(edge == PreviewConfig.EdgeStyle.TV ? 0x53606F : 0xB48750, opacity), false, false);
        append(faces, geometry.scans, c, camera, .01f, argb(0, opacity * 40/255f), false, false);
        boolean limited = faces.size() >= MAX_TRIANGLES - 12;
        // The native Initiator assembly is depth-rendered in the preview at this origin.
        List<Line> grid = new ArrayList<>();
        for (int i = -4; i <= 4; i++) {
            grid.add(new Line(view(new Point(i, 0, -4), camera), view(new Point(i, 0, 4), camera), i == 0 ? 0xFF50749A : 0x553E536A));
            grid.add(new Line(view(new Point(-4, 0, i), camera), view(new Point(4, 0, i), camera), i == 0 ? 0xFF8E635D : 0x553E536A));
        }
        Point origin = view(new Point(0, 0, 0), camera), center = view(world(new Point(0, 0, 0), c), camera);
        grid.add(new Line(origin, view(new Point(0, 2, 0), camera), 0xAA77B999));
        float minX = center.x, maxX = center.x, minY = center.y, maxY = center.y;
        for (Face face : faces) for (Raw vertex : List.of(face.a, face.b, face.c)) {
            minX = Math.min(minX, vertex.point.x); maxX = Math.max(maxX, vertex.point.x);
            minY = Math.min(minY, vertex.point.y); maxY = Math.max(maxY, vertex.point.y);
        }
        // Frame independently of visibility/source readiness: opacity and hiding a rear face
        // must not resize the scene or make the source disappear into the origin marker.
        for (ScreenMesh.Mesh mesh : List.of(geometry.surface, geometry.edge, geometry.glowOuter)) for (ScreenMesh.Vertex v : mesh.boundary()) {
            Point point = view(world(new Point(v.x(),v.y(),v.z()),c),camera);
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
        boolean front = view(rotate(new Point(0,0,1), c), camera).z >= 0;
        boolean textVisible = front || (twoSided(c) && (c.backFace == PreviewConfig.BackFace.MIRROR||c.backFace == PreviewConfig.BackFace.READABLE));
        float sign = front ? 1 : -1;
        ScreenMesh.Bounds bounds = geometry.text;
        Point textCenter = new Point((bounds.x0()+bounds.x1())*.5f*sign, (bounds.y0()+bounds.y1())*.5f, geometry.textZ*sign);
        Point textOrigin = screen(view(world(textCenter,c),camera),scale,dx,dy);
        Point textRight = screen(view(world(new Point(textCenter.x+sign,textCenter.y,textCenter.z),c),camera),scale,dx,dy);
        Point textDown = screen(view(world(new Point(textCenter.x,textCenter.y-1,textCenter.z),c),camera),scale,dx,dy);
        cachedScene = new Scene(projected, projectedGrid, screen(origin,scale,dx,dy), screen(center,scale,dx,dy),
                new TextPlane(textOrigin,textRight,textDown,bounds.width(),bounds.height(),textVisible), scale, limited,
                geometry.surface, new TextProjection(screen(center,scale,dx,dy),
                        screen(view(world(new Point(1,0,0),c),camera),scale,dx,dy),
                        screen(view(world(new Point(0,1,0),c),camera),scale,dx,dy),
                        screen(view(world(new Point(0,0,1),c),camera),scale,dx,dy)),
                new TextProjection(screen(origin,scale,dx,dy),
                        screen(view(new Point(1,0,0),camera),scale,dx,dy),
                        screen(view(new Point(0,1,0),camera),scale,dx,dy),
                        screen(view(new Point(0,0,1),camera),scale,dx,dy)));
        projectionKey = nextProjection;
        return cachedScene;
    }

    private static Geometry build(Key k) {
        float fade = k.edge == PreviewConfig.EdgeStyle.FADE ? Math.max(.001f,k.edgeWidth) : 0;
        ScreenMesh.Mesh surface = ScreenMesh.surface(k.shape,k.width,k.height,k.curve,k.corner,fade,k.polygon);
        ScreenMesh.Mesh image = k.pixelWidth > 0 && k.pixelHeight > 0 ? ScreenMesh.content(k.shape,k.width,k.height,k.curve,k.corner,
                k.fit,k.pixelWidth,k.pixelHeight,fade,k.tile,k.polygon).image() : EMPTY;
        boolean outline = k.edge != PreviewConfig.EdgeStyle.NONE && k.edge != PreviewConfig.EdgeStyle.FADE && k.edgeWidth > 0;
        boolean frame = k.edge == PreviewConfig.EdgeStyle.TV || k.edge == PreviewConfig.EdgeStyle.CARVED;
        float thick = frame ? k.edgeWidth*4 : k.edgeWidth;
        ScreenMesh.Mesh edge = outline ? ScreenMesh.edge(k.shape,k.width,k.height,k.curve,k.corner,0,thick,false,k.polygon) : EMPTY;
        ScreenMesh.Mesh detail = outline && (frame||k.edge==PreviewConfig.EdgeStyle.RUNIC) ? ScreenMesh.edge(k.shape,k.width,k.height,k.curve,k.corner,thick*.25f,thick*.6f,k.edge != PreviewConfig.EdgeStyle.TV,k.polygon) : EMPTY;
        ScreenMesh.Mesh trim = EMPTY;
        ScreenMesh.Mesh scans = k.scans ? ScreenMesh.scanlines(k.shape,k.width,k.height,k.curve,k.corner,96,fade,k.polygon) : EMPTY;
        boolean glowing = outline && (k.edge == PreviewConfig.EdgeStyle.NEON || k.edge == PreviewConfig.EdgeStyle.RUNIC);
        ScreenMesh.Mesh inner = glowing ? ScreenMesh.edge(k.shape,k.width,k.height,k.curve,k.corner,-thick*.5f,thick*2,false,k.polygon) : EMPTY;
        ScreenMesh.Mesh outer = inner;
        return new Geometry(surface,image,edge,detail,trim,scans,inner,outer,ScreenMesh.inscribedText(k.shape,k.width,k.height,k.curve,k.corner,k.polygon),ScreenMesh.textPlaneZ(k.shape,k.width,k.curve));
    }

    private static void append(List<Face> faces, ScreenMesh.Mesh mesh, PreviewConfig c, Camera camera, float offset, int color, boolean textured, boolean rearOnly) {
        if ((color >>> 24) == 0) return;
        for (ScreenMesh.Triangle triangle : mesh.triangles()) {
            if (faces.size() >= MAX_TRIANGLES - 12) return;
            // Averaged analytic mesh normals determine the visible side, including wraparound arcs.
            Point normal = new Point(triangle.a().nx()+triangle.b().nx()+triangle.c().nx(),triangle.a().ny()+triangle.b().ny()+triangle.c().ny(),triangle.a().nz()+triangle.b().nz()+triangle.c().nz());
            boolean front = view(rotate(normal,c),camera).z >= 0;
            if (rearOnly ? front : (!front && (!twoSided(c) || (textured && c.backFace != PreviewConfig.BackFace.MIRROR && c.backFace != PreviewConfig.BackFace.READABLE)))) continue;
            float visibleOffset = !front && !rearOnly ? -offset : offset;
            boolean readableRear=textured&&!front&&c.backFace==PreviewConfig.BackFace.READABLE;
            faces.add(new Face(raw(triangle.a(),c,camera,visibleOffset,readableRear),raw(triangle.b(),c,camera,visibleOffset,readableRear),raw(triangle.c(),c,camera,visibleOffset,readableRear),color,textured));
        }
    }
    private static Raw raw(ScreenMesh.Vertex v, PreviewConfig c, Camera camera, float offset,boolean readableRear) {
        return new Raw(view(world(new Point(v.x()+v.nx()*offset,v.y()+v.ny()*offset,v.z()+v.nz()*offset),c),camera),ScreenSurfaceSide.imageU(v.u(),readableRear?-1:1,readableRear),v.v(),v.alpha());
    }
    /** Mirrors the world renderer's Y * X * Z rotation order exactly. */
    static Point rotate(Point p, PreviewConfig c) {
        double roll = Math.toRadians(finite(c.roll,0)), pitch = Math.toRadians(finite(c.pitch,0)), yaw = Math.toRadians(finite(c.yaw,0));
        double x = p.x*Math.cos(roll)-p.y*Math.sin(roll), y = p.x*Math.sin(roll)+p.y*Math.cos(roll);
        double py = y*Math.cos(pitch)-p.z*Math.sin(pitch), pz = y*Math.sin(pitch)+p.z*Math.cos(pitch);
        return new Point((float)(x*Math.cos(yaw)+pz*Math.sin(yaw)),(float)py,(float)(-x*Math.sin(yaw)+pz*Math.cos(yaw)));
    }
    static Point world(Point p, PreviewConfig c) {
        Point r = rotate(p,c);
        return new Point(r.x+finite(c.offsetX,0),r.y+finite(c.offsetY,2),r.z+finite(c.offsetZ,0));
    }
    private static Point view(Point p, Camera camera) {
        double yaw = Math.toRadians(camera.yaw), elevation = Math.toRadians(camera.elevation);
        double x = p.x*Math.cos(yaw)-p.z*Math.sin(yaw), z = p.x*Math.sin(yaw)+p.z*Math.cos(yaw);
        return new Point((float)x,(float)(p.y*Math.cos(elevation)-z*Math.sin(elevation)),(float)(p.y*Math.sin(elevation)+z*Math.cos(elevation)));
    }
    private static Point screen(Point p, float scale, float dx, float dy) { return new Point(dx+p.x*scale,dy-p.y*scale,p.z); }
    private static Vertex vertex(Raw v, int color, float scale, float dx, float dy) {
        Point p = screen(v.point,scale,dx,dy);
        return new Vertex(p.x,p.y,p.z,v.u,v.v,((Math.round((color>>>24)*v.alpha)&255)<<24)|(color&0xFFFFFF));
    }
    private static boolean twoSided(PreviewConfig c) { return c.twoSided && !c.projector && c.backFace != PreviewConfig.BackFace.HIDDEN; }
    private static Raw solid(Point p) { return new Raw(p,0,0,1); }
    private static float finite(float value, float fallback) { return Float.isFinite(value)?value:fallback; }
    private static int argb(int rgb, float alpha) { return (Math.round(Math.clamp(alpha,0,1)*255)<<24)|(rgb&0xFFFFFF); }
}
