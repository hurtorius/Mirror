package org.hurtorius.mirror.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;

/** The world's own clipped native glyphs, projected into the editor preview; never opens or remembers a source. */
final class NativeTextPreview {
    private NativeTextPreview() { }
    record Point(float x, float y, float depth, float u, float v, int color, int light) { }
    record Triangle(Point a, Point b, Point c) {
        float depth() { return (a.depth+b.depth+c.depth)/3; }
    }

    record Batch(RenderType type,List<Triangle> triangles) { }

    /** Whether text can face the preview camera at all. A curved panel can expose front-facing wings while its center faces away. */
    static boolean visible(PreviewConfig config, DraftPreviewGeometry.Scene scene) {
        return twoSided(config) || scene.textSurface().triangles().stream().anyMatch(t -> {
            var p=scene.textProjection();
            var a=p.project(t.a().x(),t.a().y(),t.a().z()); var b=p.project(t.b().x(),t.b().y(),t.b().z());
            var c=p.project(t.c().x(),t.c().y(),t.c().z());
            return (b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x()) < 0;
        });
    }

    static List<Batch> capture(Font font, PreviewConfig config, DraftPreviewGeometry.Scene scene, String text) {
        if (config.opacity <= 0 || scene.textSurface().empty() || text == null || text.isEmpty() || !visible(config, scene)) return List.of();
        boolean twoSided = twoSided(config);
        var wrapped=NativeScreenText.wrap(font,text,config,config.width,config.height);
        boolean reverse=twoSided&&config.backFace==PreviewConfig.BackFace.READABLE&&!scene.textProjection().centerFront();
        int color=org.hurtorius.mirror.core.ScreenBrightness.textColor(Math.round(Math.clamp(config.opacity,0,1)*255),config.brightness);
        // The world's wrap and capture on the same unfaded surface, so preview text ends exactly where world text does.
        var captured=NativeScreenText.build(font,wrapped.lines(),wrapped.layout(),scene.textSurface(),color,config.light,reverse,ScreenTextGeometry.Budget.panel());
        if(captured==null)return List.of();
        List<Batch> result=new ArrayList<>();
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

    private static boolean twoSided(PreviewConfig config) {
        return config.twoSided && !config.projector && (config.backFace == PreviewConfig.BackFace.MIRROR||config.backFace == PreviewConfig.BackFace.READABLE);
    }
    private static Point project(ScreenTextGeometry.Vertex v,DraftPreviewGeometry.Scene scene){
        var p=scene.textProjection().project(v.x(),v.y(),v.z());
        return new Point(p.x(),p.y(),p.z(),v.u(),v.v(),v.color(),v.light());
    }
}
