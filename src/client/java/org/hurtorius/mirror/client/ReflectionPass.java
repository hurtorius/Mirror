package org.hurtorius.mirror.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.*;
import net.minecraft.world.level.material.FogType;
import org.hurtorius.mirror.mixin.*;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import java.util.*;

/** Local GPU-only mirror views. The viewer display limit bounds cached views; only one extra world pass runs per frame. */
public final class ReflectionPass {
    private static final Map<String,Target> targets=new HashMap<>();
    /** Fabulous transients sized to reflection targets survive between captures, not between real frames. */
    private static final CrossFrameResourcePool resources=new CrossFrameResourcePool(4);
    /** Sodium (and with it Iris) replaces vanilla terrain culling; only the vanilla renderer can share the main section graph. */
    private static final boolean SODIUM=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
    private static boolean active;
    private static String capturing;
    private static Matrix4f projection;
    private static net.minecraft.world.phys.Vec3 viewerPosition;
    private static long nextCapture,lastFrame;
    private static double captureCost,frameInterval;
    private static final class Target implements AutoCloseable {
        final TextureTarget buffer;
        final TextureFrame frame;
        long updated,due;
        Target(String id,int width,int height){buffer=new TextureTarget("Mirror reflection",width,height,true);frame=new TextureFrame("reflection-"+id,buffer);}
        public void close(){frame.close();buffer.destroyBuffers();}
    }
    private static final class ReflectedCamera extends Camera {
        private final Camera original;
        ReflectedCamera(Camera original,ReflectionView.View view,float partial){
            this.original=original;var mc=Minecraft.getInstance();setup(mc.level,original.entity(),true,false,partial);
            var angles=view.rotation().getEulerAnglesYXZ(new Vector3f());setRotation(180-(float)Math.toDegrees(angles.y),-(float)Math.toDegrees(angles.x));
            rotation().set(view.rotation());
            ((Vector3f)forwardVector()).set(0,0,-1).rotate(rotation());
            ((Vector3f)upVector()).set(0,1,0).rotate(rotation());
            ((Vector3f)leftVector()).set(-1,0,0).rotate(rotation());
            setPosition(view.eye().x,view.eye().y,view.eye().z);
        }
        @Override public FogType getFluidInCamera(){return original.getFluidInCamera();}
        @Override public net.minecraft.world.attribute.EnvironmentAttributeProbe attributeProbe(){return original.attributeProbe();}
    }
    public static boolean active(){return active;}
    /** A vanilla capture filters the main eye's omnidirectional section graph instead of re-centering and rebuilding it. */
    public static boolean sharesTerrain(){return active&&!SODIUM;}
    /** The mirror being rendered into: its own panel never enters its reflection. */
    public static String capturing(){return capturing;}
    public static net.minecraft.world.phys.Vec3 viewerPosition(){return viewerPosition;}
    public static Matrix4f projection(){return new Matrix4f(projection);}
    public static TextureFrame frame(String id){var t=targets.get(id);return t==null||t.updated==0?null:t.frame;}
    public static void reset(){targets.values().forEach(Target::close);targets.clear();resources.clear();ReflectionRenderType.reset();}
    public static void capture(DeltaTracker time){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||MirrorClient.preferences.hidden||MirrorClient.emergency){reset();return;}
        Camera camera=mc.gameRenderer.getMainCamera();if(!camera.isInitialized())return;
        var pos=camera.position();long now=System.nanoTime();
        if(lastFrame!=0){long frame=Math.min(now-lastFrame,100_000_000L);frameInterval=frameInterval==0?frame:frameInterval*.9+frame*.1;}lastFrame=now;
        // Every mirror within the display limit keeps its target and last picture; only visible facing ones capture, plus the edited one.
        var kept=MirrorRenderer.reflectionSurfaces().stream().sorted(Comparator.comparingDouble(s->new Vector3d(s.center()).distanceSquared(pos.x,pos.y,pos.z)/Math.max(.1,s.width()*s.height())))
          .limit(MirrorClient.preferences.maxScreens).toList();
        Set<String> wanted=new HashSet<>();for(var s:kept)wanted.add(s.id());
        targets.entrySet().removeIf(e->{if(wanted.contains(e.getKey()))return false;e.getValue().close();return true;});
        // Pool entries only age when a capture ends; with no mirror left, nothing would ever free them.
        if(targets.isEmpty())resources.clear();
        String edited=MirrorClient.editor==null?null:MirrorClient.editor.anchorId();
        var candidates=kept.stream().filter(s->captures(s,pos,edited)).toList();
        var next=candidates.stream().filter(s->{var t=targets.get(s.id());return t==null||now+2_000_000L>=t.due;})
            .min(Comparator.comparingLong(s->{var t=targets.get(s.id());return t==null?0:t.updated;})).orElse(null);
        if(next==null||now+2_000_000L<nextCapture)return;
        Target target=targets.get(next.id());
        // Nothing moves in a paused world, so the last picture is still exact.
        if(mc.isPaused()&&target!=null&&target.updated!=0)return;
        var view=ReflectionView.of(next.center(),next.rotation(),new Vector3d(pos.x,pos.y,pos.z),next.width(),next.height(),(float)next.spec().thickness,mc.options.getEffectiveRenderDistance()*64);
        if(view==null)return;
        int[] size=size(next.spec().width,next.spec().height,MirrorClient.preferences.resolution);
        if(target==null||target.buffer.width!=size[0]||target.buffer.height!=size[1]){if(target!=null)target.close();target=new Target(next.id(),size[0],size[1]);targets.put(next.id(),target);}
        RenderTarget original=mc.getMainRenderTarget();var pool=((ReflectionGameRendererAccess)mc.gameRenderer).mirror$resources();boolean cull=mc.smartCull;
        try(var shaderScope=IrisReflectionScope.begin()){
            viewerPosition=pos;active=true;capturing=next.id();projection=view.projection();mc.smartCull=false;
            ((ReflectionMinecraftAccess)mc).mirror$target(target.buffer);((ReflectionGameRendererAccess)mc.gameRenderer).mirror$resources(resources);
            Camera reflected=new ReflectedCamera(camera,view,time.getGameTimeDeltaPartialTick(true));
            ((ReflectionGameRendererAccess)mc.gameRenderer).mirror$camera(reflected);
            // Vanilla terrain reads camera origin from this once-per-frame UBO.
            // A second view must replace it too, not only GameRenderer's Camera.
            updateGlobals(reflected,target.buffer.width,target.buffer.height,time);
            mc.gameRenderer.renderLevel(time);resources.endFrame();target.updated=now;target.due=Math.max(target.due+33_333_333L,now+8_333_333L);
            long cost=System.nanoTime()-now;captureCost=captureCost==0?Math.min(cost,20_000_000L):captureCost*.85+cost*.15;long interval=interval(candidates.size(),captureCost,frameInterval);nextCapture=Math.max(nextCapture+interval,now+interval/4);
        }finally{
            ((ReflectionGameRendererAccess)mc.gameRenderer).mirror$camera(camera);((ReflectionGameRendererAccess)mc.gameRenderer).mirror$resources(pool);
            ((ReflectionMinecraftAccess)mc).mirror$target(original);mc.smartCull=cull;active=false;capturing=null;projection=null;
            updateGlobals(camera,mc.getWindow().getWidth(),mc.getWindow().getHeight(),time);
        }
    }
    /** A kept mirror captures while it is in view, or open in the editor, and its viewer stands on a reflecting side. */
    static boolean captures(MirrorRenderer.ReflectionSurface s,net.minecraft.world.phys.Vec3 pos,String edited){
        var local=new Quaternionf(s.rotation()).conjugate().transform(new Vector3d(pos.x,pos.y,pos.z).sub(s.center()));
        return (s.visible()||s.id().equals(edited))&&(local.z>0||s.spec().doubleSided&&s.spec().backVisible);
    }
    /** The target keeps the saved aspect: unfolding and resizing only change the frustum its stretched picture covers. */
    static int[] size(double width,double height,int resolution){
        float aspect=(float)(width/height);resolution=Math.min(512,resolution);
        return aspect>=1?new int[]{resolution,Math.max(2,Math.round(resolution/aspect))}:new int[]{Math.max(2,Math.round(resolution*aspect)),resolution};
    }
    /**
     * Each mirror refreshes at most 30 times a second, slower when captures cost more CPU, and never sooner than every
     * other real frame: the GPU runs every capture as a second world render.
     */
    static long interval(int mirrors,double captureCost,double frameInterval){
        return (long)Math.max(Math.max(1_000_000_000L/(30*Math.max(1,mirrors)),Math.min(100_000_000L,captureCost*3)),2*frameInterval);
    }
    private static void updateGlobals(Camera camera,int width,int height,DeltaTracker time){
        var mc=Minecraft.getInstance();mc.gameRenderer.getGlobalSettingsUniform().update(width,height,mc.options.glintStrength().get(),mc.level.getGameTime(),time,mc.options.getMenuBackgroundBlurriness(),camera,mc.options.textureFiltering().get()==TextureFilteringMethod.RGSS);
    }
}
