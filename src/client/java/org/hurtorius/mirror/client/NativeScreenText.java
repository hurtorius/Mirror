package org.hurtorius.mirror.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Public native-font bridge. No atlas copies, source history, new pipeline or world access. */
final class NativeScreenText {
    private static ScreenTextGeometry.Budget frame = ScreenTextGeometry.Budget.frame();
    private NativeScreenText() { }
    static void endWorldFrame() { frame = ScreenTextGeometry.Budget.frame(); }

    static void submit(Font font, List<FormattedCharSequence> lines, ScreenTextLayout layout,
                       ScreenMesh.Mesh surface, int color, boolean twoSided, Vec3 camera,
                       PoseStack pose, MultiBufferSource buffers, int light,boolean readableBack) {
        submit(font,lines,layout,surface,color,twoSided,camera,pose,buffers,
                -Math.min(ScreenTextLayout.MAX_LINES,lines.size())*ScreenTextLayout.LINE_HEIGHT/2f,
                ScreenTextLayout.LINE_HEIGHT,0,6,light,readableBack);
    }
    static void overlay(Font font,List<FormattedCharSequence> lines,float y,float scale,ScreenMesh.Mesh surface,
                        int color,int background,Vec3 camera,PoseStack pose,MultiBufferSource buffers) {
        submit(font,lines,new ScreenTextLayout(0,y,scale,scale),surface,color,false,camera,pose,buffers,
                -(lines.size()-1)*10,10,background,7,LightTexture.FULL_BRIGHT,false);
    }
    private static void submit(Font font,List<FormattedCharSequence> lines,ScreenTextLayout layout,
                               ScreenMesh.Mesh surface,int color,boolean twoSided,Vec3 camera,
                               PoseStack pose,MultiBufferSource buffers,float firstY,int lineHeight,int background,int order,int light,boolean readableBack) {
        if (lines.isEmpty() || layout == null || surface.empty() || (color >>> 24) == 0) return;
        if (!twoSided && surface.triangles().stream().noneMatch(t -> front(t, camera))) return;
        Collector collector = new Collector(surface, frame);
        if (collector.failed()) return;
        Matrix4f transform = new Matrix4f().translation(layout.centerX(), layout.centerY(), 0)
                .scale(twoSided && readableBack && camera.z < 0 ? -layout.scaleX() : layout.scaleX(),
                        -layout.scaleY(), Math.min(layout.scaleX(), layout.scaleY()));
        float y = firstY;
        for (int i = 0; i < Math.min(ScreenTextLayout.MAX_LINES, lines.size()) && !collector.failed(); i++) {
            FormattedCharSequence line = lines.get(i);
            font.drawInBatch(line, -font.width(line) / 2f, y, color, false, transform,
                    collector, Font.DisplayMode.NORMAL, background, light);
            y += lineHeight;
        }
        List<Batch> batches = collector.finish();
        // Only detached attributes and the existing native font RenderTypes enter callbacks.
        for (Batch batch : batches) { var entry=pose.last();var consumer=buffers.getBuffer(batch.type());
            for (ScreenTextGeometry.Triangle triangle : batch.triangles()) {
                boolean front = front(triangle, camera);
                if (!twoSided && !front) continue;
                if (front) {
                    emit(consumer, entry, triangle.a()); emit(consumer, entry, triangle.b());
                    emit(consumer, entry, triangle.c()); emit(consumer, entry, triangle.c());
                } else {
                    var a = ScreenTextGeometry.back(triangle.a()); var b = ScreenTextGeometry.back(triangle.b());
                    var c = ScreenTextGeometry.back(triangle.c());
                    emit(consumer, entry, c); emit(consumer, entry, b); emit(consumer, entry, a); emit(consumer, entry, a);
                }
            }
        }
    }

    record Batch(RenderType type, List<ScreenTextGeometry.Triangle> triangles) {
        Batch { triangles = List.copyOf(triangles); }
    }

    /** Streaming capture: four completed vertices are projected, then released immediately. */
    static final class Collector implements MultiBufferSource {
        private final ScreenTextGeometry.Projector projector;
        private final Map<RenderType, Sink> sinks = new LinkedHashMap<>();
        private Sink active;
        private final Sink discard;
        Collector(ScreenMesh.Mesh surface, ScreenTextGeometry.Budget budget) {
            projector = new ScreenTextGeometry.Projector(surface, budget);
            discard = new Sink(projector);
        }
        boolean failed() { return projector.failed(); }
        @Override public VertexConsumer getBuffer(RenderType type) {
            if (active != null) active.flush();
            if (projector.failed()) return discard;
            if (type == null || type.mode() != VertexFormat.Mode.QUADS) {
                projector.fail(); return discard;
            }
            Sink sink = sinks.get(type);
            if (sink == null) {
                if (sinks.size() >= ScreenTextGeometry.MAX_BATCHES) { projector.fail(); return discard; }
                sink = new Sink(projector); sinks.put(type, sink);
            }
            active = sink;
            return sink;
        }
        List<Batch> finish() {
            for (Sink sink : sinks.values()) {
                sink.flush();
                if (sink.count != 0) projector.fail();
            }
            if (projector.failed()) return List.of();
            List<Batch> result = new ArrayList<>();
            sinks.forEach((type, sink) -> { if (!sink.triangles.isEmpty()) result.add(new Batch(type, sink.triangles)); });
            return List.copyOf(result);
        }
    }

    /** Package visible for attribute-capture tests that need no actual Font, atlas or GPU. */
    static final class Sink implements VertexConsumer {
        private final ScreenTextGeometry.Projector projector;
        private final ScreenTextGeometry.Input[] quad = new ScreenTextGeometry.Input[4];
        private final List<ScreenTextGeometry.Triangle> triangles = new ArrayList<>();
        private int count;
        private boolean pending;
        private float x, y, z, u, v;
        private int color, light;
        Sink(ScreenTextGeometry.Projector projector) { this.projector = projector; }
        void flush() {
            if (!pending) return;
            pending = false;
            if (projector.failed()) return;
            quad[count++] = new ScreenTextGeometry.Input(x, y, z, u, v, color, light);
            if (count == 4) {
                triangles.addAll(projector.project(quad[0], quad[1], quad[2], quad[3]));
                count = 0;
                java.util.Arrays.fill(quad, null);
            }
        }
        List<ScreenTextGeometry.Triangle> finish() {
            flush();
            if (count != 0) projector.fail();
            return projector.failed() ? List.of() : List.copyOf(triangles);
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            if (!projector.vertex()) return this;
            this.x = x; this.y = y; this.z = z; u = v = 0; color = 0xFFFFFFFF; light = 0;
            pending = true; return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            color = (alpha & 255) << 24 | (red & 255) << 16 | (green & 255) << 8 | blue & 255; return this;
        }
        @Override public VertexConsumer setColor(int argb) { color = argb; return this; }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { light = (u & 65535) | (v & 65535) << 16; return this; }
        // Native world-font pipelines contain position, color, UV and light only. Unexpected
        // extra attributes are rejected rather than silently producing incorrect geometry.
        @Override public VertexConsumer setUv1(int u, int v) { projector.fail(); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { projector.fail(); return this; }
        @Override public VertexConsumer setLineWidth(float width) { projector.fail(); return this; }
    }

    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, ScreenTextGeometry.Vertex p) {
        consumer.addVertex(pose, p.x(), p.y(), p.z()).setColor(p.color()).setUv(p.u(), p.v()).setLight(p.light());
    }
    private static boolean front(ScreenTextGeometry.Triangle triangle, Vec3 camera) {
        var a = triangle.a(); var b = triangle.b(); var c = triangle.c();
        double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
        double acx = c.x() - a.x(), acy = c.y() - a.y(), acz = c.z() - a.z();
        return (aby * acz - abz * acy) * (camera.x - a.x())
                + (abz * acx - abx * acz) * (camera.y - a.y())
                + (abx * acy - aby * acx) * (camera.z - a.z()) >= 0;
    }
    private static boolean front(ScreenMesh.Triangle triangle, Vec3 camera) {
        var a=triangle.a();var b=triangle.b();var c=triangle.c();
        double abx=b.x()-a.x(),aby=b.y()-a.y(),abz=b.z()-a.z();
        double acx=c.x()-a.x(),acy=c.y()-a.y(),acz=c.z()-a.z();
        return (aby*acz-abz*acy)*(camera.x-a.x())+(abz*acx-abx*acz)*(camera.y-a.y())
                +(abx*acy-aby*acx)*(camera.z-a.z())>=0;
    }
}
