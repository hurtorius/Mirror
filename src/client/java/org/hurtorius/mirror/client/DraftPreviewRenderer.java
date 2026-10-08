package org.hurtorius.mirror.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

/** Render into Minecraft's GUI preview target, with real depth for every vertex. */
final class DraftPreviewRenderer extends PictureInPictureRenderer<DraftPreviewState> {
    private static final Identifier WHITE=Identifier.parse("mirror:textures/white.png");
    private final List<MirrorRenderer.Draw> active=InitiatorItemRenderer.geometry(true,true);
    private final List<MirrorRenderer.Draw> idle=InitiatorItemRenderer.geometry(false,true);
    DraftPreviewRenderer(MultiBufferSource.BufferSource buffers){super(buffers);}
    @Override public Class<DraftPreviewState> getRenderStateClass(){return DraftPreviewState.class;}
    @Override protected String getTextureLabel(){return "Mirror layout preview";}
    @Override protected float getTranslateY(int height,int guiScale){return 0;}
    @Override protected void renderToTexture(DraftPreviewState state,PoseStack pose){
        pose.translate(-(state.x1()-state.x0())/2f,0,0);
        var scene=state.scene();float scale=scene.pixelsPerBlock();
        var white=RenderTypes.text(WHITE);
        for(var line:scene.grid())line(pose,white,line.a(),line.b(),line.color(),scale);
        bufferSource.endBatch();
        // Opaque surfaces establish depth first. Transparent decoration then blends against it.
        for(boolean transparent:new boolean[]{false,true}){
            for(var triangle:scene.triangles()){
                if(transparent!=((triangle.a().color()>>>24)<255))continue;
                Identifier texture=triangle.textured()?state.picture():WHITE;if(texture==null)continue;
                VertexConsumer out=bufferSource.getBuffer(RenderTypes.text(texture));
                var a=triangle.a();var b=triangle.b();var c=triangle.c();
                if((b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x())>0){var swap=b;b=c;c=swap;}
                vertex(out,pose,a,scale,state.light());vertex(out,pose,b,scale,state.light());vertex(out,pose,c,scale,state.light());vertex(out,pose,c,scale,state.light());
            }
            bufferSource.endBatch();
        }
        for(var draw:state.active()?active:idle){
            VertexConsumer out=bufferSource.getBuffer(RenderTypes.text(draw.texture()));
            for(var q:draw.quads()){
                Vector3f[] points={q.a(),q.b(),q.c(),q.d()};
                for(int i=0;i<4;i++){
                    var p=points[i];var projected=scene.deviceProjection().project(p.x-.5f,p.y,p.z-.5f);
                    float shade=.65f+.35f*Math.max(0,q.normal().y);
                    int color=shade(q.color(),shade);
                    emit(out,pose,projected.x(),projected.y(),projected.z(),q.uv()[i*2],q.uv()[i*2+1],color,scale);
                }
            }
        }
        bufferSource.endBatch();
        for(var batch:state.text()){
            VertexConsumer out=bufferSource.getBuffer(batch.type());
            for(var t:batch.triangles())for(var p:List.of(t.a(),t.b(),t.c(),t.c()))
                emit(out,pose,p.x(),p.y(),p.depth(),p.u(),p.v(),p.color(),scale,p.light());
        }
    }
    private static int shade(int color,float shade){return (color&0xff000000)|((int)(((color>>16)&255)*shade)<<16)|((int)(((color>>8)&255)*shade)<<8)|(int)((color&255)*shade);}
    private static void vertex(VertexConsumer out,PoseStack pose,DraftPreviewGeometry.Vertex v,float scale,int light){emit(out,pose,v.x(),v.y(),v.depth(),v.u(),v.v(),v.color(),scale,light);}
    private static void emit(VertexConsumer out,PoseStack pose,float x,float y,float depth,float u,float v,int color,float scale){
        emit(out,pose,x,y,depth,u,v,color,scale,0xf000f0);
    }
    private static void emit(VertexConsumer out,PoseStack pose,float x,float y,float depth,float u,float v,int color,float scale,int light){
        out.addVertex(pose.last(),x,y,-depth*scale).setColor(color).setUv(u,v).setLight(light);
    }
    private void line(PoseStack pose,RenderType type,DraftPreviewGeometry.Point a,DraftPreviewGeometry.Point b,int color,float scale){
        float dx=b.x()-a.x(),dy=b.y()-a.y(),length=(float)Math.hypot(dx,dy);if(length<.001f)return;
        float nx=-dy/length*.3f,ny=dx/length*.3f;VertexConsumer out=bufferSource.getBuffer(type);
        emit(out,pose,a.x()+nx,a.y()+ny,a.z()-.002f,0,0,color,scale);
        emit(out,pose,b.x()+nx,b.y()+ny,b.z()-.002f,0,0,color,scale);
        emit(out,pose,b.x()-nx,b.y()-ny,b.z()-.002f,0,0,color,scale);
        emit(out,pose,a.x()-nx,a.y()-ny,a.z()-.002f,0,0,color,scale);
    }
}
