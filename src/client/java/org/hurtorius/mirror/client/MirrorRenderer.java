package org.hurtorius.mirror.client;

import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import org.hurtorius.mirror.core.*;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.util.*;

/** Extraction prepares immutable geometry; drawing only consumes that geometry. */
public final class MirrorRenderer {
    private static final Identifier WHITE=Identifier.fromNamespaceAndPath("mirror","textures/white.png");
    private static final Map<String,Motion> motions=new HashMap<>();
    private static final Map<String,TextureFrame> styles=new HashMap<>(),labels=new HashMap<>();
    private static final Map<String,PictureStyleKey> styleKeys=new HashMap<>();
    private static final Map<String,String> labelKeys=new HashMap<>();
    private static final Map<String,ContentTransition> transitions=new HashMap<>();
    private static final class ContentTransition {TextureFrame old;long start=0,created;byte[] initialPicture;}
    private static volatile List<Draw> draws=List.of();
    private static Vec3 lastCamera=Vec3.ZERO;
    static Vec3 viewPosition(){return lastCamera;}
    private record TextDraw(String text,PreviewConfig config,Vector3f origin,Quaternionf rotation,int color,ScreenMesh.Mesh surface,int light){}
    private static volatile List<TextDraw> textDraws=List.of();
    private static List<List<ScreenMesh.Point>> projectionMasks;
    private static ProjectorGeometry.Budget projectionBudget;
    private static final Map<String,Vec3> soundPositions=new HashMap<>();
    public static Vec3 soundPosition(String id){return soundPositions.get(id);}
    record Quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u0,float v0,float u1,float v1,int color,float[] uv,Vector3f normal){
        Quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u0,float v0,float u1,float v1,int color){this(a,b,c,d,u0,v0,u1,v1,color,null,new Vector3f(0,1,0));}
        Quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u0,float v0,float u1,float v1,int color,float[] uv){this(a,b,c,d,u0,v0,u1,v1,color,uv,new Vector3f(0,1,0));}
    }
    record Draw(List<Quad> quads,Identifier texture,boolean textured,int light,boolean lit){
        Draw(List<Quad> quads,Identifier texture,boolean textured){this(quads,texture,textured,0xf000f0,false);}
        Draw(List<Quad> quads,Identifier texture,boolean textured,int light){this(quads,texture,textured,light,false);}
    }
    private static final class Motion {double x,y,z,yaw,pitch,roll,width,height,show;long last;boolean ready;}
    public static void remove(String id){soundPositions.remove(id);motions.remove(id);clearTransition(id);TextureFrame s=styles.remove(id);if(s!=null)s.close();TextureFrame l=labels.remove(id);if(l!=null)l.close();styleKeys.remove(id);labelKeys.remove(id);}
    public static void clearTransition(String id){ContentTransition t=transitions.remove(id);if(t!=null)t.old.close();}
    public static void contentChanged(String id,TextureFrame previous,ScreenSpec style){clearTransition(id);if(style.source==ScreenSpec.Source.WEB||style.source==ScreenSpec.Source.SHARE||previous==null||previous.latest==null||previous.browserPixels||MirrorClient.preferences.reducedMotion)return;ContentTransition t=new ContentTransition();t.old=new TextureFrame("transition-"+id);t.old.accept(previous.latest);t.old.upload(style);t.initialPicture=previous.latest;t.created=System.currentTimeMillis();transitions.put(id,t);}
    public static void reset(){ScreenMeshCache.clear();ProjectorSurface.clear();soundPositions.clear();for(TextureFrame f:styles.values())f.close();for(TextureFrame f:labels.values())f.close();for(ContentTransition t:transitions.values())t.old.close();transitions.clear();styles.clear();labels.clear();styleKeys.clear();labelKeys.clear();motions.clear();draws=List.of();}
    public static void extract(WorldExtractionContext context){
        textDraws=List.of();NativeScreenText.endWorldFrame();List<TextDraw> textOutput=new ArrayList<>();
        MirrorClient.renderPictures();projectionMasks=null;projectionBudget=null;ProjectorSurface.endFrame();
        long now=System.currentTimeMillis();double time=(now+MirrorClient.serverOffset)/1000.0;Vec3 camera=context.camera().position();lastCamera=camera;List<Draw> output=new ArrayList<>();
        for(Device device:MirrorClient.devices.values())initiator(output,device,camera);
        for(var ghost:InitiatorVisuals.ghosts())InitiatorDecorationRenderer.append(output,ghost.device(),ghost.pose(),camera,0xf000f0,MirrorClient.preferences.glow);
        if(MirrorClient.preferences.hidden||MirrorClient.emergency){draws=List.copyOf(output);return;}
        List<MirrorClient.View> ordered=MirrorClient.views.values().stream().filter(v->v.anchor().spec.live||motions.containsKey(v.anchor().id)&&motions.get(v.anchor().id).show>.005).sorted(Comparator.comparingDouble(v->camera.distanceToSqr(v.anchor().x,v.anchor().y,v.anchor().z))).limit(MirrorClient.preferences.maxScreens).toList();
        for(MirrorClient.View view:ordered){projectionMasks=null;projectionBudget=null;int bundleStart=output.size();ProjectorSurface.MeshBudget allocated=null;Anchor a=view.anchor();ScreenSpec s=a.spec;soundPositions.remove(a.id);
            if(s==null)continue;ScreenSpec spec=s;Motion m=motions.computeIfAbsent(a.id,k->new Motion());double dt=m.last==0?.05:Math.min(.2,(now-m.last)/1000.0);m.last=now;
            var sampled=ScreenPose.sample(a,s,context.world(),camera,now+MirrorClient.serverOffset,MirrorClient.preferences.reducedMotion);
            double x=sampled.x(),y=sampled.y(),z=sampled.z(),yaw=sampled.yaw(),pitch=sampled.pitch(),roll=sampled.roll();
            ProjectorSurface.Hit projectorHit=null;
            if(s.projector){
                var block=new net.minecraft.core.BlockPos(a.x,a.y,a.z);
                projectorHit=ProjectorSurface.ray(context.world(),block,(float)yaw,(float)pitch,(float)s.projectionDepth);
                if(projectorHit==null)continue;
                var receivers=ProjectorSurface.receivers(context.world(),block,projectorHit,(float)roll,(float)s.width,(float)s.height,(float)s.edgeWidth,TransitionMath.content(PreviewConfig.Transition.NONE,1,true));
                if(receivers.empty())continue;
                projectionMasks=ProjectorSurface.local(receivers,projectorHit,(float)roll);allocated=ProjectorSurface.geometryBudget();projectionBudget=allocated.budget();
                Vec3 point=projectorHit.center();x=point.x;y=point.y;z=point.z;yaw=projectorHit.yaw();pitch=projectorHit.pitch();m.ready=false;
                beam(output,new Vector3f((float)(projectorHit.source().x-camera.x),(float)(projectorHit.source().y-camera.y),(float)(projectorHit.source().z-camera.z)),new Vector3f((float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z)),s,rotation(yaw,pitch,roll));
            }

            double ease=MirrorClient.preferences.reducedMotion||s.easeSeconds==0?1:1-Math.exp(-dt*5/s.easeSeconds);
            if(!m.ready){m.x=x;m.y=y;m.z=z;m.yaw=yaw;m.pitch=pitch;m.roll=roll;m.width=s.width;m.height=s.height;m.ready=true;}
            m.x=lerp(m.x,x,ease);m.y=lerp(m.y,y,ease);m.z=lerp(m.z,z,ease);m.yaw=lerpAngle(m.yaw,yaw,ease);m.pitch=lerp(m.pitch,pitch,ease);m.roll=lerp(m.roll,roll,ease);m.width=lerp(m.width,s.width,ease);m.height=lerp(m.height,s.height,ease);m.show=lerp(m.show,s.live?1:0,Math.min(1,dt/(MirrorClient.preferences.reducedMotion?.01:Math.max(.1,s.easeSeconds))));
            if(s.facing==ScreenSpec.Facing.EACH_VIEWER||s.facing==ScreenSpec.Facing.BILLBOARD){m.yaw=yaw;m.pitch=pitch;}
            if(m.show<.005)continue;
            double entrance=s.entrance==ScreenSpec.Entrance.POP?1:m.show;double w=m.width,h=m.height;if(s.entrance==ScreenSpec.Entrance.UNFOLD)w*=entrance;else if(s.entrance==ScreenSpec.Entrance.IRIS){w*=entrance;h*=entrance;}
            double elev=m.y;if(!s.projector&&(s.entrance==ScreenSpec.Entrance.RISE||s.entrance==ScreenSpec.Entrance.UNFOLD))elev=lerp(a.y+1.3,m.y,entrance);
            Vector3f origin=new Vector3f((float)(m.x-camera.x),(float)(elev-camera.y),(float)(m.z-camera.z));Quaternionf rotation=rotation(m.yaw,m.pitch,m.roll);
            soundPositions.put(a.id,new Vec3(m.x,elev,m.z));
            int alpha=(int)(255*s.opacity*m.show),tint=(alpha<<24)|0xffffff;
            TextureFrame frame=s.source==ScreenSpec.Source.BLANK||s.source==ScreenSpec.Source.TEXT||s.source==ScreenSpec.Source.MEDIA&&s.playlist.isEmpty()||!FrameVisibility.publicPicture(s.source,view.epoch())?null:MirrorClient.worldFrame(view);
            if(frame!=null&&frame.latest!=null&&(s.brightness!=1||s.contrast!=1||s.saturation!=1)){
                var key=new PictureStyleKey(frame,frame.version,MirrorClient.preferences.resolution,s.brightness,s.contrast,s.saturation);
                var previous=styleKeys.get(a.id);
                if(previous!=null&&previous.source()!=frame){TextureFrame stale=styles.remove(a.id);if(stale!=null)stale.close();}
                if(!key.equals(previous)){TextureFrame styled=styles.computeIfAbsent(a.id,k->new TextureFrame("style-"+k));styled.accept(frame.latest);styleKeys.put(a.id,key);}
                frame=styles.get(a.id);frame.upload(s);
            }else if(styles.containsKey(a.id)){styles.remove(a.id).close();styleKeys.remove(a.id);}

            int screenLight=s.emissive?0xf000f0:net.minecraft.client.renderer.LevelRenderer.getLightColor(context.world(),net.minecraft.core.BlockPos.containing(m.x,elev,m.z));
            List<Quad> backing=mesh(s,w,h,origin,rotation,0,0,1,1,(alpha<<24)|0x000000,0);output.add(new Draw(backing,WHITE,true,screenLight));
            if(frame!=null&&frame.available()){

                ContentTransition transition=transitions.get(a.id);double progress=1;
                if(transition!=null){if(transition.start==0&&(frame.latest!=transition.initialPicture||now-transition.created>1000))transition.start=now;progress=transition.start==0?0:Math.min(1,(now-transition.start)/600.0);if(progress>=1){clearTransition(a.id);transition=null;}}
                if(transition!=null){
                    transition.old.upload(s);
                    if(transition.old.available())output.add(new Draw(contentMesh(s,w,h,origin,rotation,transition.old,((int)(alpha*(1-progress))<<24)|0xffffff,.004,null),transition.old.id,true,screenLight));
                }
                TransitionMath.Plan plan=transition==null?null:TransitionMath.content(PreviewConfig.Transition.valueOf(s.transition.name()),(float)progress,MirrorClient.preferences.reducedMotion);
                output.add(new Draw(contentMesh(s,w,h,origin,rotation,frame,tint,.009,plan),frame.id,true,screenLight));
                if(s.reflection){Vector3f reflected=new Vector3f(origin.x,(float)(a.y+.015-camera.y),origin.z);output.add(new Draw(contentMesh(s,w,h*.4,reflected,rotation(m.yaw,90,0),frame,0x28ffffff,.002,null),frame.id,true,screenLight));}

            }
            if(s.edge!=ScreenSpec.Edge.NONE)edge(output,s,w,h,origin,rotation,time,alpha);
            if(s.source==ScreenSpec.Source.TEXT&&!s.text.isBlank()){
                PreviewConfig p=PreviewConfig.from(s);p.width=(float)w;p.height=(float)h;
                var surface=ScreenMesh.surface(p.shape,(float)w,(float)h,p.curveDegrees,p.cornerRadius,0,p.customShape);
                if(projectionMasks!=null)surface=ProjectorGeometry.clip(surface,projectionMasks,projectionBudget);
                textOutput.add(new TextDraw(s.text,p,origin,rotation,ScreenBrightness.textColor(alpha,s.brightness),surface,screenLight));
            }
            String label=MirrorClient.subtitle(a);
            if(!label.isBlank()&&s.source!=ScreenSpec.Source.BLANK){
                PreviewConfig p=PreviewConfig.from(new ScreenSpec());p.width=(float)w;p.height=.35f;p.shape=PreviewConfig.Shape.FLAT;
                Vector3f caption=new Vector3f(origin).add(rotation.transform(new Vector3f(0,(float)(-h/2-.25),.02f)));
                output.add(new Draw(List.of(plane(-w/2,-.2,w/2,.2,0,caption,rotation,0,0,1,1,0xee000000)),WHITE,true));
                textOutput.add(new TextDraw(label,p,caption,rotation,ScreenBrightness.textColor(alpha,s.brightness),ScreenMesh.surface(p.shape,p.width,p.height,p.curveDegrees,p.cornerRadius,0,p.customShape),screenLight));
            }
            if(s.scanlines){PreviewConfig p=PreviewConfig.from(s);output.add(new Draw(geometry(ScreenMeshCache.scans(p,(float)w,(float)h),origin,rotation,0x28000000,.01,0,0,1,1,s.doubleSided&&!s.projector),WHITE,true));}

            if(s.shadow&&!s.projector){Vector3f floor=new Vector3f(origin.x,(float)(a.y+.01-camera.y),origin.z);output.add(new Draw(List.of(plane(-w*.55,-.7,w*.55,.7,0,floor,rotation(m.yaw,90,0),0,0,1,1,0x18000000)),WHITE,true));}
            if(allocated!=null){ProjectorSurface.consume(allocated);if(projectionBudget.failed()){output.subList(bundleStart,output.size()).clear();soundPositions.remove(a.id);}}
        }
        projectionMasks=null;projectionBudget=null;
        draws=List.copyOf(output);textDraws=List.copyOf(textOutput);
    }
    private static List<Quad> mesh(ScreenSpec s,double w,double h,Vector3f origin,Quaternionf rotation,float u0,float v0,float u1,float v1,int color,double depth){
        PreviewConfig p=PreviewConfig.from(s);
        // A front-only display still has a black back; sidedness only hides its content.
        return geometry(ScreenMeshCache.surface(p,(float)w,(float)h,p.edgeStyle==PreviewConfig.EdgeStyle.FADE?p.edgeWidth:0),origin,rotation,color,depth,u0,v0,u1,v1,s.doubleSided||!s.projector);
    }
    private static List<Quad> contentMesh(ScreenSpec s,double w,double h,Vector3f origin,Quaternionf rotation,TextureFrame frame,int color,double depth,TransitionMath.Plan plan){
        PreviewConfig p=PreviewConfig.from(s);
        ScreenMesh.Mesh mesh=ScreenMeshCache.content(p,(float)w,(float)h,frame.width,frame.height,p.edgeStyle==PreviewConfig.EdgeStyle.FADE?p.edgeWidth:0,new ScreenTileLayout.Tile(s.wallColumns,s.wallRows,s.wallColumn,s.wallRow));
        if(plan!=null){mesh=ScreenMesh.transition(mesh,p.shape,(float)w,(float)h,p.curveDegrees,p.cornerRadius,plan,true,0,p.customShape);color=((int)((color>>>24)*plan.alpha())<<24)|(color&0xffffff);}
        return geometry(mesh,origin,rotation,color,depth,0,0,1,1,s.doubleSided&&!s.projector,!s.backMirrored);
    }
    private static List<Quad> geometry(ScreenMesh.Mesh mesh,Vector3f origin,Quaternionf rotation,int color,double depth,float u0,float v0,float u1,float v1){
        return geometry(mesh,origin,rotation,color,depth,u0,v0,u1,v1,true);
    }
    private static List<Quad> geometry(ScreenMesh.Mesh mesh,Vector3f origin,Quaternionf rotation,int color,double depth,float u0,float v0,float u1,float v1,boolean twoSided){
        return geometry(mesh,origin,rotation,color,depth,u0,v0,u1,v1,twoSided,false);
    }
    private static List<Quad> geometry(ScreenMesh.Mesh mesh,Vector3f origin,Quaternionf rotation,int color,double depth,float u0,float v0,float u1,float v1,boolean twoSided,boolean readableBack){
        Vector3f camera=new Quaternionf(rotation).conjugate().transform(new Vector3f(origin).negate());
        if(projectionMasks!=null)mesh=ProjectorGeometry.clip(mesh,projectionMasks,projectionBudget);
        List<Quad> result=new ArrayList<>(mesh.triangles().size());
        for(var tri:mesh.triangles()){
            var a=tri.a();var b=tri.b();var c=tri.c();
            double side=ScreenSurfaceSide.side(tri,camera.x,camera.y,camera.z);
            if(side<0&&!twoSided)continue;
            double offset=depth*side;
            Vector3f pa=point(a.x()+a.nx()*offset,a.y()+a.ny()*offset,a.z()+a.nz()*offset,origin,rotation),
                    pb=point(b.x()+b.nx()*offset,b.y()+b.ny()*offset,b.z()+b.nz()*offset,origin,rotation),
                    pc=point(c.x()+c.nx()*offset,c.y()+c.ny()*offset,c.z()+c.nz()*offset,origin,rotation);
            int tint=((int)((color>>>24)*(a.alpha()+b.alpha()+c.alpha())/3)<<24)|(color&0xffffff);
            result.add(new Quad(pa,pb,pc,pc,0,0,1,1,tint,new float[]{u0+(u1-u0)*ScreenSurfaceSide.imageU(a.u(),side,readableBack),v0+(v1-v0)*a.v(),u0+(u1-u0)*ScreenSurfaceSide.imageU(b.u(),side,readableBack),v0+(v1-v0)*b.v(),u0+(u1-u0)*ScreenSurfaceSide.imageU(c.u(),side,readableBack),v0+(v1-v0)*c.v(),u0+(u1-u0)*ScreenSurfaceSide.imageU(c.u(),side,readableBack),v0+(v1-v0)*c.v()}));
        }
        return result;
    }
    private static void edge(List<Draw> output,ScreenSpec s,double w,double h,Vector3f origin,Quaternionf rotation,double time,int alpha){
        PreviewConfig p=PreviewConfig.from(s);float thick=p.edgeWidth*(p.edgeStyle==PreviewConfig.EdgeStyle.TV||p.edgeStyle==PreviewConfig.EdgeStyle.CARVED?4:1);
        if(thick<=0||p.edgeStyle==PreviewConfig.EdgeStyle.FADE)return;
        int tint=(alpha<<24)|(p.edgeStyle==PreviewConfig.EdgeStyle.TV?0x17202a:p.edgeStyle==PreviewConfig.EdgeStyle.CARVED?0x754a2d:s.color);
        output.add(new Draw(geometry(ScreenMeshCache.edge(p,(float)w,(float)h,0,thick,false),origin,rotation,tint,.012,0,0,1,1,s.doubleSided&&!s.projector),WHITE,true));
        if(p.edgeStyle==PreviewConfig.EdgeStyle.RUNIC||p.edgeStyle==PreviewConfig.EdgeStyle.CARVED||p.edgeStyle==PreviewConfig.EdgeStyle.TV)
            output.add(new Draw(geometry(ScreenMeshCache.edge(p,(float)w,(float)h,thick*.25f,thick*.6f,p.edgeStyle!=PreviewConfig.EdgeStyle.TV),origin,rotation,(alpha<<24)|0xd6bdf7,.016,0,0,1,1,s.doubleSided&&!s.projector),WHITE,true));
        if(MirrorClient.preferences.glow&&s.glow>0&&(p.edgeStyle==PreviewConfig.EdgeStyle.NEON||p.edgeStyle==PreviewConfig.EdgeStyle.RUNIC))
            output.add(new Draw(geometry(ScreenMeshCache.edge(p,(float)w,(float)h,-thick*.5f,thick*2,false),origin,rotation,((int)(32*s.glow)<<24)|s.color,.005,0,0,1,1,s.doubleSided&&!s.projector),WHITE,true));
    }
    private static void initiator(List<Draw> output,Device device,Vec3 camera){
        var level=Minecraft.getInstance().level;var pos=new net.minecraft.core.BlockPos(device.x(),device.y(),device.z());
        if(level==null||!level.getBlockState(pos).is(org.hurtorius.mirror.Mirror.INITIATOR))return;
        int light=net.minecraft.client.renderer.LevelRenderer.getLightColor(level,pos.above());
        InitiatorDecorationRenderer.append(output,device,InitiatorVisuals.pose(device),camera,light,MirrorClient.preferences.glow);
    }
    private static void beam(List<Draw> output,Vector3f from,Vector3f to,ScreenSpec spec,Quaternionf rotation){double w=spec.width/2,h=spec.height/2;List<Quad> quads=new ArrayList<>();Vector3f[] corners={point(-w,-h,0,to,rotation),point(w,-h,0,to,rotation),point(w,h,0,to,rotation),point(-w,h,0,to,rotation)};for(int i=0;i<4;i++)quads.add(new Quad(from,corners[i],corners[(i+1)%4],from,0,0,1,1,0x1086e1db));output.add(new Draw(quads,WHITE,true));}
    private static Quad plane(double x0,double y0,double x1,double y1,double z,Vector3f origin,Quaternionf rotation,float u0,float v0,float u1,float v1,int color){return new Quad(point(x0,y0,z,origin,rotation),point(x1,y0,z,origin,rotation),point(x1,y1,z,origin,rotation),point(x0,y1,z,origin,rotation),u0,v0,u1,v1,color);}
    private static Vector3f point(double x,double y,double z,Vector3f origin,Quaternionf rotation){return rotation.transform(new Vector3f((float)x,(float)y,(float)z)).add(origin);}
    private static Quaternionf rotation(double yaw,double pitch,double roll){return new Quaternionf().rotationYXZ((float)Math.toRadians(yaw),(float)Math.toRadians(pitch),(float)Math.toRadians(roll));}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double lerpAngle(double a,double b,double t){double delta=((b-a+540)%360)-180;return a+delta*t;}
    public static void render(WorldRenderContext context){PoseStack pose=context.matrices();for(Draw draw:draws){boolean opaque=draw.quads.stream().allMatch(q->(q.color>>>24)>=250);VertexConsumer v=context.consumers().getBuffer(draw.lit?(opaque?RenderTypes.entityCutoutNoCull(draw.texture):RenderTypes.entityTranslucent(draw.texture)):RenderTypes.text(draw.texture));for(Quad q:draw.quads){
            // The native unlit text material culls back faces; explicitly face each two-sided
            // quad toward the viewer while preserving its UVs, instead of changing materials.
            boolean reverse=!draw.lit&&new Vector3f(q.b).sub(q.a).cross(new Vector3f(q.c).sub(q.a)).dot(new Vector3f(q.a).negate())<0;
            Vector3f[] points={q.a,q.b,q.c,q.d};
            float[] uv=q.uv==null?new float[]{q.u0,q.v1,q.u1,q.v1,q.u1,q.v0,q.u0,q.v0}:q.uv;
            int[] order=reverse?new int[]{3,2,1,0}:new int[]{0,1,2,3};
            for(int index:order)vertex(v,pose,points[index],uv[index*2],uv[index*2+1],q.color,draw.light,q.normal,draw.lit);
        }}
        for(TextDraw text:textDraws){var font=Minecraft.getInstance().font;var all=font.split(net.minecraft.network.chat.Component.literal(text.text),ScreenTextLayout.WRAP_WIDTH);var lines=all.subList(0,Math.min(all.size(),ScreenTextLayout.MAX_LINES));int widest=1;for(var line:lines)widest=Math.max(widest,font.width(line));var p=text.config;var layout=ScreenTextLayout.of(p.shape,p.width,p.height,p.curveDegrees,p.cornerRadius,p.fit,p.customShape,new ScreenTileLayout.Tile(p.tileColumns,p.tileRows,p.tileColumn,p.tileRow),widest,lines.size());var local=new Quaternionf(text.rotation).conjugate().transform(new Vector3f(text.origin).negate());pose.pushPose();pose.translate(text.origin.x,text.origin.y,text.origin.z);pose.mulPose(text.rotation);NativeScreenText.submit(font,lines,layout,text.surface,text.color,p.twoSided&&!p.projector,new Vec3(local.x,local.y,local.z),pose,context.consumers(),text.light,p.backFace==PreviewConfig.BackFace.READABLE);pose.popPose();}
    }
    private static void vertex(VertexConsumer v,PoseStack pose,Vector3f p,float u,float texV,int color,int light,Vector3f normal,boolean lit){
        v.addVertex(pose.last(),p).setColor(color).setUv(u,texV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light);
        if(lit)v.setNormal(pose.last(),normal.x,normal.y,normal.z);else v.setNormal(normal.x,normal.y,normal.z);
    }
}
