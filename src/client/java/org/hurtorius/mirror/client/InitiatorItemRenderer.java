package org.hurtorius.mirror.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.mirror.core.*;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.*;
import java.util.function.Consumer;

/** The same carved base, faceted crystal and finite-depth rings as the placed device. */
public final class InitiatorItemRenderer implements NoDataSpecialModelRenderer {
    public record Unbaked() implements SpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> CODEC=MapCodec.unit(new Unbaked());
        public MapCodec<Unbaked> type(){return CODEC;}
        public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context){return new InitiatorItemRenderer();}
    }
    /** One draw and its material, chosen once at bake time. */
    private record Baked(MirrorRenderer.Draw draw,RenderType type){}
    private final List<Baked> draws=geometry(true,true).stream().map(d->new Baked(d,d.opaque()?RenderTypes.entityCutoutNoCull(d.texture()):RenderTypes.entityTranslucent(d.texture()))).toList();
    /** The carved base is one draw per texture (the atlas), then the crystal and rings. */
    static List<MirrorRenderer.Draw> geometry(boolean active,boolean glow){
        Map<Identifier,List<MirrorRenderer.Quad>> base=new LinkedHashMap<>();
        for(var face:InitiatorModel.MODEL.faces()){
            Vector3f[] p=new Vector3f[4];for(int i=0;i<4;i++){var v=face.points().get(i);p[i]=new Vector3f(v.x(),v.y(),v.z());}
            Vector3f normal=new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0])).normalize();
            Identifier t=Identifier.parse(face.texture());t=Identifier.fromNamespaceAndPath(t.getNamespace(),"textures/"+t.getPath()+".png");
            base.computeIfAbsent(t,k->new ArrayList<>()).add(new MirrorRenderer.Quad(p[0],p[1],p[2],p[3],0,0,1,1,0xffffffff,face.uv(),normal));
        }
        List<MirrorRenderer.Draw> result=new ArrayList<>();
        base.forEach((texture,quads)->result.add(new MirrorRenderer.Draw(List.copyOf(quads),texture,0,true)));
        var pose=InitiatorLifecycle.loaded(active,0).sample(0,true);
        InitiatorDecorationRenderer.append(result,new Device("item",0,0,0,"live",0,true),pose,Vec3.ZERO,0,glow,true);
        return List.copyOf(result);
    }
    public void getExtents(Consumer<Vector3fc> output){for(var baked:draws)for(var q:baked.draw.quads()){output.accept(q.a());output.accept(q.b());output.accept(q.c());output.accept(q.d());}}
    public void submit(ItemDisplayContext context,PoseStack pose,SubmitNodeCollector queue,int light,int overlay,boolean foil,int outline){
        for(var baked:draws){var draw=baked.draw;int vertexLight=draw.light()==0xf000f0?0xf000f0:light;
            queue.order(0).submitCustomGeometry(pose,baked.type,(entry,consumer)->{for(var q:draw.quads()){
                Vector3f[] points={q.a(),q.b(),q.c(),q.d()};float[] uv=q.uv();
                for(int i=0;i<4;i++){var v=points[i];consumer.addVertex(entry,v.x,v.y,v.z).setColor(q.color()).setUv(uv[i*2],uv[i*2+1]).setOverlay(overlay).setLight(vertexLight).setNormal(entry,q.normal().x,q.normal().y,q.normal().z);}
            }});
        }
    }
}
