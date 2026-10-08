package org.hurtorius.mirror.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/** Frame-local preview of the same clipped native glyphs; never opens or remembers a source. */
final class NativeTextPreview {
    private NativeTextPreview() { }
    record Point(float x, float y, float depth, float u, float v, int color, int light) { }
    record Triangle(Point a, Point b, Point c) {
        float depth() { return (a.depth+b.depth+c.depth)/3; }
    }

    record Batch(RenderType type,List<Triangle> triangles) { }

    static List<Batch> capture(Font font, PreviewConfig config, DraftPreviewGeometry.Scene scene, String text) {
        List<Batch> result=new ArrayList<>();
        if (config.opacity <= 0 || scene.textSurface().empty() || text == null || text.isEmpty()) return List.of();
        boolean twoSided = config.twoSided && !config.projector && (config.backFace == PreviewConfig.BackFace.MIRROR||config.backFace == PreviewConfig.BackFace.READABLE);
        // A curved panel can expose front-facing wings even when its central normal faces away.
        boolean anyVisible = twoSided || scene.textSurface().triangles().stream().anyMatch(t -> {
            var p=scene.textProjection();
            var a=p.project(t.a().x(),t.a().y(),t.a().z()); var b=p.project(t.b().x(),t.b().y(),t.b().z());
            var c=p.project(t.c().x(),t.c().y(),t.c().z());
            return (b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x()) < 0;
        });
        if (!anyVisible) return List.of();
        text = text.substring(0, Math.min(text.length(), 2048));
        var wrapped=font.split(Component.literal(text),ScreenTextLayout.WRAP_WIDTH);
        var lines=wrapped.subList(0,Math.min(ScreenTextLayout.MAX_LINES,wrapped.size()));
        int widest=1;for(var line:lines)widest=Math.max(widest,font.width(line));
        ScreenTextLayout layout=ScreenTextLayout.of(config.shape,config.width,config.height,config.curveDegrees,
                config.cornerRadius,config.fit,config.customShape,
                new ScreenTileLayout.Tile(config.tileColumns,config.tileRows,config.tileColumn,config.tileRow),widest,lines.size());
        NativeScreenText.Collector collector=new NativeScreenText.Collector(scene.textSurface(),ScreenTextGeometry.Budget.panel());
        if(collector.failed())return List.of();
        boolean reverse=twoSided&&config.backFace==PreviewConfig.BackFace.READABLE&&!scene.textProjection().centerFront();
        Matrix4f matrix=new Matrix4f().translation(layout.centerX(),layout.centerY(),0)
                .scale(reverse?-layout.scaleX():layout.scaleX(),-layout.scaleY(),Math.min(layout.scaleX(),layout.scaleY()));
        Font.GlyphVisitor visitor=new Font.GlyphVisitor(){
            private void capture(TextRenderable glyph){
                if(collector.failed())return;
                RenderType type=glyph.renderType(Font.DisplayMode.NORMAL);
                VertexConsumer sink=collector.getBuffer(type);
                if(collector.failed())return;
                glyph.render(matrix,sink,config.light,false);
            }
            @Override public void acceptGlyph(TextRenderable.Styled glyph){capture(glyph);}
            @Override public void acceptEffect(TextRenderable effect){capture(effect);}
        };
        int color=org.hurtorius.mirror.core.ScreenBrightness.textColor(Math.round(Math.clamp(config.opacity,0,1)*255),config.brightness);
        float y=-lines.size()*ScreenTextLayout.LINE_HEIGHT/2f;
        for(var line:lines){
            if(collector.failed())break;
            font.prepareText(line,-font.width(line)/2f,y,color,false,false,0).visit(visitor);
            y+=ScreenTextLayout.LINE_HEIGHT;
        }
        var captured=collector.finish();
        if(collector.failed())return List.of();
        for(var batch:captured){
            List<Triangle> triangles=new ArrayList<>();
            for(var t:batch.triangles()){
                boolean front=scene.textProjection().front(t);
                if(!twoSided&&!front)continue;
                if(front)triangles.add(new Triangle(project(t.a(),scene),project(t.b(),scene),project(t.c(),scene)));
                else triangles.add(new Triangle(project(ScreenTextGeometry.back(t.c()),scene),
                        project(ScreenTextGeometry.back(t.b()),scene),project(ScreenTextGeometry.back(t.a()),scene)));
            }
            if(triangles.isEmpty())continue;
            triangles.sort(Comparator.comparingDouble(Triangle::depth));
            result.add(new Batch(batch.type(),List.copyOf(triangles)));
        }
        return List.copyOf(result);
    }

    private static Point project(ScreenTextGeometry.Vertex v,DraftPreviewGeometry.Scene scene){
        var p=scene.textProjection().project(v.x(),v.y(),v.z());
        return new Point(p.x(),p.y(),p.z(),v.u(),v.v(),v.color(),v.light());
    }
}
