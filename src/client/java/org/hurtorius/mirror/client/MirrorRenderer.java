package org.hurtorius.mirror.client;

import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.*;
import org.hurtorius.mirror.core.*;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.util.*;

/** Extraction prepares immutable geometry; drawing only consumes that geometry. */
public final class MirrorRenderer {
    public record ReflectionSurface(String id,org.joml.Vector3d center,Quaternionf rotation,float width,float height,ScreenSpec spec,boolean visible){}
    private static final Map<String,ReflectionSurface> reflectionSurfaces=new HashMap<>();
    public static Collection<ReflectionSurface> reflectionSurfaces(){return List.copyOf(reflectionSurfaces.values());}
    private static final Identifier WHITE=Identifier.fromNamespaceAndPath("mirror","textures/white.png"),SCANLINES=Identifier.fromNamespaceAndPath("mirror","textures/scanlines.png");
    /** Captions and floor shadows: a flat, fitted, single-cell plane. */
    private static final PreviewConfig PLANE=new PreviewConfig();
    private static final Map<String,Motion> motions=new HashMap<>();
    private static final Map<String,ContentTransition> transitions=new HashMap<>();
    private static final class ContentTransition {TextureFrame old;long start=0,created;byte[] initialPicture;}
    private static volatile List<Draw> draws=List.of();
    private static volatile List<Bundle> screens=List.of();
    private static Set<TextureFrame> sampled=new HashSet<>(),wanted=new HashSet<>();
    private static Vec3 lastCamera=Vec3.ZERO;
    static Vec3 viewPosition(){return lastCamera;}
    record TextDraw(String key,List<FormattedCharSequence> lines,ScreenTextLayout layout,Vector3f origin,Quaternionf rotation,int color,ScreenMesh.Mesh surface,int light,boolean twoSided,boolean readableBack,float thickness){}
    /** A projector's receivers, validated once per wall or pose change, and the layers already clipped against them. */
    static final class Projection {
        final ProjectorSurface.Result result;final Vec3 point;final float roll;final List<List<ScreenMesh.Point>> masks;ProjectorGeometry.Receivers receivers;
        private Map<ScreenMesh.Mesh,ScreenMesh.Mesh> used=new IdentityHashMap<>(),kept=new IdentityHashMap<>();
        Projection(ProjectorSurface.Result result,ProjectorSurface.Hit hit,float roll){this.result=result;point=hit.point();this.roll=roll;masks=ProjectorSurface.local(result,hit,roll);}
        boolean matches(ProjectorSurface.Result result,ProjectorSurface.Hit hit,float roll){return this.result==result&&this.roll==roll&&point.equals(hit.point());}
        /** Like the screen meshes: only the layers clipped since the previous main frame, mirror passes included, are kept. */
        void begin(){var previous=kept;kept=used;used=previous;used.clear();}
        ScreenMesh.Mesh find(ScreenMesh.Mesh mesh){var clipped=used.get(mesh);if(clipped==null&&(clipped=kept.get(mesh))!=null)used.put(mesh,clipped);return clipped;}
        void keep(ScreenMesh.Mesh mesh,ScreenMesh.Mesh clipped){used.put(mesh,clipped);}
    }
    private static Projection projection;
    private static ProjectorGeometry.Budget projectionBudget;
    private static final Map<String,Vec3> soundPositions=new HashMap<>();
    public static Vec3 soundPosition(String id){return soundPositions.get(id);}
    record Quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u0,float v0,float u1,float v1,int color,float[] uv,Vector3f normal){
        private static final Vector3f UP=new Vector3f(0,1,0);
        Quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u0,float v0,float u1,float v1,int color){this(a,b,c,d,u0,v0,u1,v1,color,new float[]{u0,v1,u1,v1,u1,v0,u0,v0},UP);}
    }
    /** Initiator decoration: camera-relative quads, lit like entities unless they are glowing effects. */
    record Draw(List<Quad> quads,Identifier texture,int light,boolean lit,boolean opaque){
        Draw(List<Quad> quads,Identifier texture,int light,boolean lit){this(quads,texture,light,lit,quads.stream().allMatch(q->(q.color>>>24)>=250));}
        Draw(List<Quad> quads,Identifier texture){this(quads,texture,0xf000f0,false);}
    }
    /** One screen layer: a local mesh placed by its own pose and emitted vertex by vertex, allocating nothing per triangle. */
    record MeshDraw(ScreenMesh.Mesh mesh,Vector3f origin,Quaternionf rotation,int color,float depth,float u0,float v0,float u1,float v1,boolean twoSided,boolean readableBack,float thickness,Identifier texture,int light){}
    /** One screen's layers and then its text, at the squared camera distance of the panel itself rather than its Initiator. */
    record Bundle(double distance,List<MeshDraw> layers,List<TextDraw> text){}
    private record Layout(String text,PreviewConfig config,float width,float height,List<FormattedCharSequence> lines,ScreenTextLayout layout){}
    private static final class Motion {double x,y,z,yaw,pitch,roll,width,height,show;long last;boolean ready;ScreenSpec spec;PreviewConfig config;Layout text,caption,status;Projection projection;final ScreenMeshCache meshes=new ScreenMeshCache();}
    private static final int[] FORWARD={0,1,2,3},REVERSE={3,2,1,0};
    public static void remove(String id){soundPositions.remove(id);motions.remove(id);clearTransition(id);NativeScreenText.forget(id);}
    public static void clearTransition(String id){ContentTransition t=transitions.remove(id);if(t!=null)t.old.close();}
    public static void contentChanged(String id,TextureFrame previous,ScreenSpec style){clearTransition(id);if(style.source==ScreenSpec.Source.WEB||style.source==ScreenSpec.Source.SHARE||previous==null||previous.latest==null||previous.browserPixels||MirrorClient.preferences.reducedMotion)return;ContentTransition t=new ContentTransition();t.old=new TextureFrame("transition-"+id);t.old.accept(previous.latest);t.old.upload();t.initialPicture=previous.latest;t.created=System.currentTimeMillis();transitions.put(id,t);}
    public static void reset(){ScreenOverlay.reset();reflectionSurfaces.clear();ReflectionPass.reset();ProjectorSurface.clear();soundPositions.clear();for(ContentTransition t:transitions.values())t.old.close();transitions.clear();motions.clear();NativeScreenText.clear();sampled.clear();wanted.clear();draws=List.of();screens=List.of();}
    /** A dismissed screen keeps its last picture while it folds away. */
    static boolean fading(String stream){for(var view:MirrorClient.views.values()){Motion m=motions.get(view.anchor().id);if(m!=null&&folding(view.anchor().spec,m.show)&&view.stream().equals(stream))return true;}return false;}
    /** Dismissed but not yet back in the crystal. A live screen outside the display limit is resting, not folding, and keeps no picture. */
    static boolean folding(ScreenSpec s,double show){return !s.live&&show>.005;}
    /** Progress text stands in only while a live media or board screen has no picture at all, not while its first one decodes. */
    static boolean awaitsPicture(ScreenSpec s,TextureFrame frame){return frame==null&&s.live&&(s.source==ScreenSpec.Source.MEDIA||s.source==ScreenSpec.Source.WHITEBOARD);}
    /** Pictures selected by a world or mirror pass since the previous frame; only these need fresh uploads. */
    static boolean wanted(TextureFrame frame){return wanted.contains(frame);}
    /** Font reloads and option changes move glyphs within their atlases and change their advances. */
    public static void fontsChanged(){NativeScreenText.clear();for(Motion m:motions.values())m.text=m.caption=m.status=null;}
    public static void extract(WorldExtractionContext context){
        // Mirror captures re-enter here inside the main frame: they read motion, surfaces and pictures, never advance them.
        boolean main=!ReflectionPass.active();
        if(main){reflectionSurfaces.clear();var previous=wanted;wanted=sampled;sampled=previous;sampled.clear();MirrorClient.renderPictures();}
        NativeScreenText.endWorldFrame();projection=null;projectionBudget=null;ProjectorSurface.endFrame();
        long now=System.currentTimeMillis();Vec3 camera=context.camera().position();lastCamera=camera;ClientLevel level=context.world();
        // A mirror sees what the main view cannot; shader packs also draw Initiator shadows from outside the view.
        Frustum frustum=main?context.frustum():null,deviceFrustum=IrisReflectionScope.shaderPack()?null:frustum;
        List<Draw> devices=new ArrayList<>();
        for(Device device:MirrorClient.devices.values())initiator(devices,device,level,camera,deviceFrustum);
        for(var ghost:InitiatorVisuals.ghosts())if(near(ghost.device(),camera,deviceFrustum))InitiatorDecorationRenderer.append(devices,ghost.device(),ghost.pose(),camera,light(level,ghost.device()),MirrorClient.preferences.glow);
        draws=List.copyOf(devices);
        if(MirrorClient.preferences.hidden||MirrorClient.emergency){screens=List.of();return;}
        // The screens whose streams tick activated, plus any still folding away, which never take a slot.
        List<MirrorClient.View> chosen=new ArrayList<>();
        for(String id:MirrorClient.liveScreens){var view=MirrorClient.views.get(id);if(view!=null)chosen.add(view);}
        for(var view:MirrorClient.views.values()){Motion m=motions.get(view.anchor().id);if(m!=null&&folding(view.anchor().spec,m.show)&&!MirrorClient.liveScreens.contains(view.anchor().id))chosen.add(view);}
        // Nearest Initiators first, so projector budgets favour them; drawing order comes from each panel's own distance.
        chosen.sort(Comparator.comparingDouble(v->camera.distanceToSqr(v.anchor().x,v.anchor().y,v.anchor().z)));
        List<Bundle> bundles=new ArrayList<>();
        for(MirrorClient.View view:chosen){projection=null;projectionBudget=null;ProjectorSurface.MeshBudget allocated=null;Anchor a=view.anchor();ScreenSpec s=a.spec;
            // The mirror being captured never enters its own reflection.
            if(!main&&a.id.equals(ReflectionPass.capturing()))continue;
            soundPositions.remove(a.id);
            if(s==null)continue;Motion m=motions.computeIfAbsent(a.id,k->new Motion());double dt=m.last==0?.05:Math.min(.2,(now-m.last)/1000.0);if(main)m.last=now;
            var pose=ScreenPose.sample(a,s,level,main?camera:ReflectionPass.viewerPosition(),now+MirrorClient.serverOffset,MirrorClient.preferences.reducedMotion);
            double x=pose.x(),y=pose.y(),z=pose.z(),yaw=pose.yaw(),pitch=pose.pitch(),roll=pose.roll();
            ProjectorSurface.Hit projectorHit=null;ProjectorSurface.Result receivers=null;
            if(s.projector){
                var block=new BlockPos(a.x,a.y,a.z);
                projectorHit=ProjectorSurface.ray(level,block,(float)yaw,(float)pitch,(float)s.projectionDepth);
                if(projectorHit==null)continue;
                receivers=ProjectorSurface.receivers(level,block,projectorHit,(float)roll,(float)s.width,(float)s.height,(float)s.edgeWidth,TransitionMath.content(PreviewConfig.Transition.NONE,1,true));
                if(receivers.empty())continue;
                Vec3 point=projectorHit.center();x=point.x;y=point.y;z=point.z;yaw=projectorHit.yaw();pitch=projectorHit.pitch();if(main)m.ready=false;
            }
            double target=s.live?1:0;
            if(main){
                double ease=MirrorClient.preferences.reducedMotion||s.easeSeconds==0?1:1-Math.exp(-dt*5/s.easeSeconds);
                if(!m.ready){m.x=x;m.y=y;m.z=z;m.yaw=yaw;m.pitch=pitch;m.roll=roll;m.width=s.width;m.height=s.height;m.ready=true;}
                m.x=lerp(m.x,x,ease);m.y=lerp(m.y,y,ease);m.z=lerp(m.z,z,ease);m.yaw=lerpAngle(m.yaw,yaw,ease);m.pitch=lerp(m.pitch,pitch,ease);m.roll=lerp(m.roll,roll,ease);m.width=lerp(m.width,s.width,ease);m.height=lerp(m.height,s.height,ease);m.show=lerp(m.show,target,Math.min(1,dt/(MirrorClient.preferences.reducedMotion?.01:Math.max(.1,s.easeSeconds))));
                // Exponential easing never arrives by itself; settle so the size, and every mesh built from it, stops changing.
                if(Math.abs(m.show-target)<1e-3)m.show=target;if(Math.abs(m.width-s.width)<1e-4)m.width=s.width;if(Math.abs(m.height-s.height)<1e-4)m.height=s.height;
                if(s.facing==ScreenSpec.Facing.EACH_VIEWER||s.facing==ScreenSpec.Facing.BILLBOARD){m.yaw=yaw;m.pitch=pitch;}
            }
            if(m.show<.005)continue;
            double entrance=s.entrance==ScreenSpec.Entrance.POP?1:m.show;double w=m.width,h=m.height;if(s.entrance==ScreenSpec.Entrance.UNFOLD)w*=entrance;else if(s.entrance==ScreenSpec.Entrance.IRIS){w*=entrance;h*=entrance;}
            boolean keep=w==s.width&&h==s.height;float fw=(float)w,fh=(float)h;
            double elev=m.y;if(!s.projector&&(s.entrance==ScreenSpec.Entrance.RISE||s.entrance==ScreenSpec.Entrance.UNFOLD))elev=lerp(a.y+1.3,m.y,entrance);
            Vector3f origin=new Vector3f((float)(m.x-camera.x),(float)(elev-camera.y),(float)(m.z-camera.z));Quaternionf rotation=rotation(m.yaw,m.pitch,m.roll);
            soundPositions.put(a.id,new Vec3(m.x,elev,m.z));
            boolean visible=frustum==null||frustum.isVisible(s.projector?projected(projectorHit.source(),projectorHit.center(),s):bounds(m.x,elev,m.z,w,h,s,rotation,a.y));
            if(s.source==ScreenSpec.Source.MIRROR&&main)reflectionSurfaces.put(a.id,new ReflectionSurface(a.id,new org.joml.Vector3d(m.x,elev,m.z),new Quaternionf(rotation),fw,fh,s,visible));
            // Rotated every main frame, seen or not, so a projector only a mirror sees cannot grow its memo while it eases.
            if(main&&m.projection!=null)m.projection.begin();
            // A cross-fade nobody watched is over: turning back shows the current picture, not a replay from an old one.
            if(!visible){if(main)clearTransition(a.id);continue;}
            if(receivers!=null){
                allocated=ProjectorSurface.geometryBudget();projectionBudget=allocated.budget();
                if(m.projection==null||!m.projection.matches(receivers,projectorHit,(float)roll))m.projection=new Projection(receivers,projectorHit,(float)roll);
                projection=m.projection;if(projection.receivers==null)projection.receivers=ProjectorGeometry.prepare(projection.masks,projectionBudget);
            }
            double f=s.opacity*m.show;int alpha=(int)(255*f);
            TextureFrame frame=s.source==ScreenSpec.Source.MIRROR?(main?ReflectionPass.frame(a.id):null):s.source==ScreenSpec.Source.BLANK||s.source==ScreenSpec.Source.TEXT||s.source==ScreenSpec.Source.MEDIA&&s.playlist.isEmpty()||!FrameVisibility.publicPicture(s.source,view.epoch())?null:MirrorClient.worldFrame(view);
            if(frame!=null)sampled.add(frame);
            if(s.source==ScreenSpec.Source.MIRROR)clearTransition(a.id);
            int screenLight=s.emissive?0xf000f0:LevelRenderer.getLightColor(level,BlockPos.containing(m.x,elev,m.z));
            PreviewConfig p=config(m,s);ScreenMeshCache meshes=m.meshes;if(main)meshes.begin();
            float fade=p.edgeStyle==PreviewConfig.EdgeStyle.FADE?p.edgeWidth:0;boolean twoSided=s.doubleSided&&s.backVisible&&!s.projector;
            List<MeshDraw> out=new ArrayList<>();List<TextDraw> text=new ArrayList<>();
            out.add(new MeshDraw(clip(meshes.surface(p,fw,fh,fade,keep)),origin,rotation,alpha<<24,0,0,0,1,1,false,false,0,WHITE,screenLight));
            if(!s.projector){
                if(s.backVisible)out.add(new MeshDraw(meshes.back(p,fw,fh,fade,keep),origin,rotation,(alpha<<24)|s.backColor,0,0,0,1,1,false,false,0,WHITE,screenLight));
                if(s.thickness>0)out.add(new MeshDraw(meshes.sides(p,fw,fh,keep),origin,rotation,(alpha<<24)|s.backColor,0,0,0,1,1,true,false,0,WHITE,screenLight));
            }
            if(frame!=null&&frame.available()){
                ContentTransition transition=transitions.get(a.id);double progress=1;
                if(transition!=null){if(transition.start==0&&(frame.latest!=transition.initialPicture||now-transition.created>1000))transition.start=now;progress=transition.start==0?0:Math.min(1,(now-transition.start)/600.0);if(progress>=1){clearTransition(a.id);transition=null;}}
                if(transition!=null){
                    transition.old.upload();
                    if(transition.old.available())out.add(picture(meshes,p,s,fw,fh,origin,rotation,transition.old,(int)(alpha*(1-progress)),.004f,null,keep,twoSided,screenLight));
                }
                TransitionMath.Plan plan=transition==null?null:TransitionMath.content(PreviewConfig.Transition.valueOf(s.transition.name()),(float)progress,MirrorClient.preferences.reducedMotion);
                out.add(picture(meshes,p,s,fw,fh,origin,rotation,frame,alpha,.009f,plan,keep,twoSided,screenLight));
                if(s.reflection)out.add(picture(meshes,p,s,fw,fh*.4f,new Vector3f(origin.x,(float)(a.y+.015-camera.y),origin.z),rotation(m.yaw,90,0),frame,(int)(0x28*f),.002f,null,keep,twoSided,screenLight));
            }else if(awaitsPicture(s,frame)){
                // Until the first picture, viewers see this client's own download or first-time preparation.
                String progress=MirrorClient.progress(view);
                if(progress!=null){m.status=layout(m.status,progress,p,fw,fh);text.add(new TextDraw(a.id+"#status",m.status.lines(),m.status.layout(),origin,rotation,ScreenBrightness.textColor(alpha,1),clip(meshes.surface(p,fw,fh,0,keep)),screenLight,twoSided,!s.backMirrored,(float)s.thickness));}
            }
            edge(out,meshes,p,s,fw,fh,origin,rotation,f,keep,twoSided);
            if(s.source==ScreenSpec.Source.TEXT&&!s.text.isBlank()){
                m.text=layout(m.text,s.text,p,fw,fh);
                text.add(new TextDraw(a.id,m.text.lines(),m.text.layout(),origin,rotation,ScreenBrightness.textColor(alpha,s.brightness),clip(meshes.surface(p,fw,fh,0,keep)),screenLight,twoSided,!s.backMirrored,(float)s.thickness));
            }
            String label=MirrorClient.subtitle(a);
            // Nobody is ever surprised by a shared screen: it always names whose screen it is.
            if(s.source==ScreenSpec.Source.SHARE&&!view.publisher().isBlank())label="Live: "+view.publisher()+"'s screen"+(label.isBlank()?"":"\n"+label);
            if(!label.isBlank()&&s.source!=ScreenSpec.Source.BLANK){
                Vector3f caption=new Vector3f(origin).add(rotation.transform(new Vector3f(0,-fh/2-.25f,.02f)));
                out.add(new MeshDraw(meshes.surface(PLANE,fw,.4f,0,keep),caption,rotation,((int)(0xee*f))<<24,0,0,0,1,1,true,false,0,WHITE,0xf000f0));
                m.caption=layout(m.caption,label,PLANE,fw,.35f);
                text.add(new TextDraw(a.id+"#caption",m.caption.lines(),m.caption.layout(),caption,rotation,ScreenBrightness.textColor(alpha,s.brightness),meshes.surface(PLANE,fw,.35f,0,keep),screenLight,false,false,0));
            }
            // Old-TV lines are a nearest-filtered 96-line texture on the plain surface, not 96 strips of geometry.
            if(s.scanlines)out.add(new MeshDraw(clip(meshes.surface(p,fw,fh,0,keep)),origin,rotation,(((int)(ScreenMeshCache.SCANLINE_ALPHA*f))<<24)|0xffffff,ScreenMeshCache.SCANLINE_OFFSET,0,0,1,1,twoSided,false,(float)s.thickness,SCANLINES,0xf000f0));
            if(s.shadow&&!s.projector)out.add(new MeshDraw(meshes.surface(PLANE,fw*1.1f,1.4f,0,keep),new Vector3f(origin.x,(float)(a.y+.01-camera.y),origin.z),rotation(m.yaw,90,0),((int)(0x30*f))<<24,0,0,0,1,1,true,false,0,WHITE,0xf000f0));
            // The beam is translucent: emitted last, it tints the picture it reaches instead of hiding it.
            if(projectorHit!=null){Vec3 source=projectorHit.source();out.add(new MeshDraw(beam(rotation.transformInverse(new Vector3f((float)(source.x-m.x),(float)(source.y-elev),(float)(source.z-m.z))),(float)s.width,(float)s.height),origin,rotation,(((int)(0x30*f))<<24)|0x86e1db,0,0,0,1,1,true,false,0,WHITE,0xf000f0));}
            if(allocated!=null){ProjectorSurface.consume(allocated);if(projectionBudget.failed()){out.clear();text.clear();soundPositions.remove(a.id);}}
            if(!out.isEmpty()||!text.isEmpty())bundles.add(new Bundle(origin.lengthSquared(),List.copyOf(out),List.copyOf(text)));
        }
        projection=null;projectionBudget=null;
        screens=farthestFirst(bundles);
    }
    /**
     * Translucent layers and text write depth, so a farther panel, its text included, is drawn whole before any nearer one.
     * Panels can float far from their Initiators, so they are ordered by where they are, not by where their Initiators stand.
     */
    static List<Bundle> farthestFirst(List<Bundle> bundles){var result=new ArrayList<>(bundles);result.sort(Comparator.comparingDouble(Bundle::distance).reversed());return List.copyOf(result);}
    /** World box of the panel in its own orientation: edges and glow, caption, curve and back, plus its floor layers when drawn. */
    static AABB bounds(double x,double y,double z,double w,double h,ScreenSpec s,Quaternionf rotation,int floor){
        boolean curved=s.shape==ScreenSpec.Shape.CONCAVE||s.shape==ScreenSpec.Shape.CONVEX||s.shape==ScreenSpec.Shape.PANORAMA;
        double margin=16*s.edgeWidth+.6,ex=w/2+margin,ey=h/2+.5+margin,ez=(curved?w/2:0)+s.thickness+margin;var r=rotation.get(new Matrix3f());
        double hx=Math.abs(r.m00)*ex+Math.abs(r.m10)*ey+Math.abs(r.m20)*ez,hy=Math.abs(r.m01)*ex+Math.abs(r.m11)*ey+Math.abs(r.m21)*ez,hz=Math.abs(r.m02)*ex+Math.abs(r.m12)*ey+Math.abs(r.m22)*ez;
        AABB box=new AABB(x-hx,y-hy,z-hz,x+hx,y+hy,z+hz);
        if(!s.shadow&&!s.reflection)return box;
        double reach=.55*w+.2*h+.7;return box.minmax(new AABB(x-reach,floor,z-reach,x+reach,floor+.1,z+reach));
    }
    /** A projected picture lies on its receiving blocks around the hit, edges and caption included, and its beam reaches back to the Initiator. */
    static AABB projected(Vec3 source,Vec3 hit,ScreenSpec s){return new AABB(source,hit).inflate((s.width+s.height)/2+16*s.edgeWidth+.6);}
    private static PreviewConfig config(Motion m,ScreenSpec s){if(m.spec!=s){m.spec=s;m.config=PreviewConfig.from(s);}return m.config;}
    private static Layout layout(Layout cached,String text,PreviewConfig p,float w,float h){
        if(cached!=null&&cached.config==p&&cached.width==w&&cached.height==h&&cached.text.equals(text))return cached;
        var wrapped=NativeScreenText.wrap(Minecraft.getInstance().font,text,p,w,h);return new Layout(text,p,w,h,wrapped.lines(),wrapped.layout());
    }
    /**
     * A layer already clipped against these receivers is replayed: its triangles still count, and a failed budget still
     * discards the whole bundle. Only successful clips are kept.
     */
    private static ScreenMesh.Mesh clip(ScreenMesh.Mesh mesh){
        if(projection==null)return mesh;
        var clipped=projection.find(mesh);if(clipped!=null){projectionBudget.charge(clipped.triangles().size());return clipped;}
        clipped=ProjectorGeometry.clip(mesh,projection.receivers,projectionBudget);if(!projectionBudget.failed())projection.keep(mesh,clipped);return clipped;
    }
    private static MeshDraw picture(ScreenMeshCache meshes,PreviewConfig p,ScreenSpec s,float w,float h,Vector3f origin,Quaternionf rotation,TextureFrame frame,int alpha,float depth,TransitionMath.Plan plan,boolean keep,boolean twoSided,int light){
        ScreenMesh.Mesh mesh=meshes.content(p,w,h,frame.width,frame.height,p.edgeStyle==PreviewConfig.EdgeStyle.FADE?p.edgeWidth:0,new ScreenTileLayout.Tile(p.tileColumns,p.tileRows,p.tileColumn,p.tileRow),keep);
        if(plan!=null){mesh=ScreenMesh.transition(mesh,p.shape,w,h,p.curveDegrees,p.cornerRadius,plan,true,0,p.customShape);alpha=(int)(alpha*plan.alpha());}
        boolean mirror=s.source==ScreenSpec.Source.MIRROR;
        // The picture shader decodes brightness, contrast and saturation from every vertex color.
        // A reflection already carries the light of the world it shows, so it is never lit a second time.
        return new MeshDraw(clip(mesh),origin,rotation,ReflectionStyle.color(alpha,s.brightness,s.contrast,s.saturation),depth,mirror?1:0,mirror?1:0,mirror?0:1,mirror?0:1,twoSided,!s.backMirrored,(float)s.thickness,frame.id,mirror?0xf000f0:light);
    }
    private static void edge(List<MeshDraw> out,ScreenMeshCache meshes,PreviewConfig p,ScreenSpec s,float w,float h,Vector3f origin,Quaternionf rotation,double f,boolean keep,boolean twoSided){
        for(var layer:ScreenMeshCache.edgeLayers(p.edgeStyle,p.edgeWidth,s.color,MirrorClient.preferences.glow?(float)s.glow:0))if(layer.alpha()>0)
            out.add(new MeshDraw(clip(meshes.edge(p,w,h,layer.inner(),layer.outer(),layer.dashed(),keep)),origin,rotation,((int)(255*f*layer.alpha())<<24)|layer.rgb(),layer.offset(),0,0,1,1,twoSided,false,(float)s.thickness,WHITE,0xf000f0));
    }
    private static void initiator(List<Draw> output,Device device,ClientLevel level,Vec3 camera,Frustum frustum){
        var pos=new BlockPos(device.x(),device.y(),device.z());
        // Unloaded chunks read as air; a loaded block that is gone means the Initiator broke.
        if(!level.getBlockState(pos).is(org.hurtorius.mirror.Mirror.INITIATOR)){if(level.hasChunkAt(pos))InitiatorVisuals.vanish(device);return;}
        // Sample even when unseen, so wakes and dismissals keep server time.
        var pose=InitiatorVisuals.pose(device);
        if(near(device,camera,frustum))InitiatorDecorationRenderer.append(output,device,pose,camera,light(level,device),MirrorClient.preferences.glow);
    }
    /** Inside vanilla's block-entity view distance and, when the view applies, inside it, ripple and raised rings included. */
    private static boolean near(Device d,Vec3 camera,Frustum frustum){return camera.distanceToSqr(d.x()+.5,d.y()+.5,d.z()+.5)<=64*64&&(frustum==null||frustum.isVisible(new AABB(d.x()-1.2,d.y(),d.z()-1.2,d.x()+2.2,d.y()+1.6,d.z()+2.2)));}
    private static int light(ClientLevel level,Device d){return LevelRenderer.getLightColor(level,new BlockPos(d.x(),d.y(),d.z()).above());}
    private static ScreenMesh.Mesh beam(Vector3f source,float width,float height){
        float w=width/2,h=height/2;float[][] corners={{-w,-h},{w,-h},{w,h},{-w,h}};var from=vertex(source.x,source.y,source.z);List<ScreenMesh.Triangle> sides=new ArrayList<>(4);
        for(int i=0;i<4;i++)sides.add(new ScreenMesh.Triangle(from,vertex(corners[i][0],corners[i][1],0),vertex(corners[(i+1)%4][0],corners[(i+1)%4][1],0)));
        return new ScreenMesh.Mesh(sides,List.of());
    }
    private static ScreenMesh.Vertex vertex(float x,float y,float z){return new ScreenMesh.Vertex(x,y,z,0,0,0,0,1,1,x,y);}
    private static Quaternionf rotation(double yaw,double pitch,double roll){return new Quaternionf().rotationYXZ((float)Math.toRadians(yaw),(float)Math.toRadians(pitch),(float)Math.toRadians(roll));}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double lerpAngle(double a,double b,double t){double delta=((b-a+540)%360)-180;return a+delta*t;}
    public static void render(WorldRenderContext context){
        if(ReflectionPass.active()){draw(context.matrices(),context.consumers(),draws,screens);return;}
        draw(context.matrices(),context.consumers(),draws,List.of());
        if(!IrisReflectionScope.shadowPass())ScreenOverlay.prepare(context.matrices(),screens);
    }
    static void draw(PoseStack pose,MultiBufferSource consumers,List<Draw> devices,List<Bundle> bundles){
        for(Draw draw:devices){VertexConsumer v=consumers.getBuffer(draw.lit?(draw.opaque?RenderTypes.entityCutoutNoCull(draw.texture):RenderTypes.entityTranslucent(draw.texture)):ReflectionRenderType.of(draw.texture));for(Quad q:draw.quads){
            // The native unlit text material culls back faces; face each unlit quad toward the viewer while preserving its UVs.
            for(int index:!draw.lit&&away(q.a,q.b,q.c)?REVERSE:FORWARD)vertex(v,pose,index==0?q.a:index==1?q.b:index==2?q.c:q.d,q.uv[index*2],q.uv[index*2+1],q.color,draw.light,q.normal,draw.lit);
        }}
        var font=Minecraft.getInstance().font;
        for(Bundle bundle:bundles){
            for(MeshDraw draw:bundle.layers){
                VertexConsumer v=consumers.getBuffer(ReflectionRenderType.of(draw.texture));Vector3f camera=draw.rotation.transformInverse(-draw.origin.x,-draw.origin.y,-draw.origin.z,new Vector3f());
                pose.pushPose();pose.translate(draw.origin.x,draw.origin.y,draw.origin.z);pose.mulPose(draw.rotation);emit(v,pose.last(),draw,camera.x,camera.y,camera.z);pose.popPose();
            }
            for(TextDraw text:bundle.text){var local=text.rotation.transformInverse(-text.origin.x,-text.origin.y,-text.origin.z,new Vector3f());pose.pushPose();var textOrigin=new Vector3f(text.origin);if(local.z<0)textOrigin.add(text.rotation.transform(new Vector3f(0,0,-text.thickness)));pose.translate(textOrigin.x,textOrigin.y,textOrigin.z);pose.mulPose(text.rotation);NativeScreenText.submit(text.key,font,text.lines,text.layout,text.surface,text.color,text.twoSided,new Vec3(local.x,local.y,local.z),pose,consumers,text.light,text.readableBack);pose.popPose();}
        }
    }
    private static boolean away(Vector3f a,Vector3f b,Vector3f c){float ux=b.x-a.x,uy=b.y-a.y,uz=b.z-a.z,vx=c.x-a.x,vy=c.y-a.y,vz=c.z-a.z;return (uy*vz-uz*vy)*-a.x+(uz*vx-ux*vz)*-a.y+(ux*vy-uy*vx)*-a.z<0;}
    /**
     * Local-space emission: the side, the depth offset off the surface, the readable-back U flip and the winding toward the
     * camera (cx,cy,cz in mesh space) are decided per triangle; alpha is per vertex, so faded edges ramp smoothly.
     * Triangles leave as degenerate quads because the text pipelines draw QUADS.
     */
    static void emit(VertexConsumer out,PoseStack.Pose pose,MeshDraw d,float cx,float cy,float cz){
        for(var t:d.mesh.triangles()){
            float side=ScreenSurfaceSide.side(t,cx,cy,cz);if(side<0&&!d.twoSided)continue;
            float offset=d.depth*side,back=side<0?d.thickness:0;var a=t.a();var b=t.b();var c=t.c();
            float ax=a.x()+a.nx()*offset,ay=a.y()+a.ny()*offset,az=a.z()+a.nz()*offset-back,bx=b.x()+b.nx()*offset,by=b.y()+b.ny()*offset,bz=b.z()+b.nz()*offset-back,qx=c.x()+c.nx()*offset,qy=c.y()+c.ny()*offset,qz=c.z()+c.nz()*offset-back;
            float ux=bx-ax,uy=by-ay,uz=bz-az,vx=qx-ax,vy=qy-ay,vz=qz-az;
            if((uy*vz-uz*vy)*(cx-ax)+(uz*vx-ux*vz)*(cy-ay)+(ux*vy-uy*vx)*(cz-az)<0){put(out,pose,qx,qy,qz,c,d,side);put(out,pose,qx,qy,qz,c,d,side);put(out,pose,bx,by,bz,b,d,side);put(out,pose,ax,ay,az,a,d,side);}
            else{put(out,pose,ax,ay,az,a,d,side);put(out,pose,bx,by,bz,b,d,side);put(out,pose,qx,qy,qz,c,d,side);put(out,pose,qx,qy,qz,c,d,side);}
        }
    }
    private static void put(VertexConsumer out,PoseStack.Pose pose,float x,float y,float z,ScreenMesh.Vertex v,MeshDraw d,float side){
        out.addVertex(pose,x,y,z).setColor(((int)((d.color>>>24)*v.alpha())<<24)|(d.color&0xffffff)).setUv(d.u0+(d.u1-d.u0)*ScreenSurfaceSide.imageU(v.u(),side,d.readableBack),d.v0+(d.v1-d.v0)*v.v()).setOverlay(OverlayTexture.NO_OVERLAY).setLight(d.light).setNormal(0,1,0);
    }
    private static void vertex(VertexConsumer v,PoseStack pose,Vector3f p,float u,float texV,int color,int light,Vector3f normal,boolean lit){
        v.addVertex(pose.last(),p).setColor(color).setUv(u,texV).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light);
        if(lit)v.setNormal(pose.last(),normal.x,normal.y,normal.z);else v.setNormal(normal.x,normal.y,normal.z);
    }
}
