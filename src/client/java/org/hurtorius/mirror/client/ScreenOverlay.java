package org.hurtorius.mirror.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.hurtorius.mirror.mixin.ReflectionMinecraftAccess;
import org.joml.Matrix4f;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** Depth-tested screens composite after world/shader color effects and before the HUD. No readback. */
public final class ScreenOverlay {
    private static List<MirrorRenderer.Bundle> screens=List.of();
    private static Matrix4f pose,modelView;
    private static GpuBufferSlice projection,fog;
    private static ProjectionType projectionType;
    private static DepthTarget visibility;
    private static ByteBufferBuilder storage;
    private static MultiBufferSource.BufferSource buffers;
    private static boolean ready;
    private static final RenderPipeline HAND_MASK=RenderPipeline.builder()
        .withLocation(Identifier.parse("mirror:pipeline/hand_mask"))
        .withVertexShader(Identifier.parse("mirror:core/hand_mask")).withFragmentShader(Identifier.parse("mirror:core/hand_mask"))
        .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.EMPTY,VertexFormat.Mode.TRIANGLES)
        .withColorWrite(false).withDepthWrite(true).withCull(false).build();
    private static final class CompositeTarget extends RenderTarget {
        CompositeTarget(){super("Mirror final screen layer",true);}
        void bind(RenderTarget color,RenderTarget depth){width=color.width;height=color.height;colorTexture=color.getColorTexture();colorTextureView=color.getColorTextureView();depthTexture=depth.getDepthTexture();depthTextureView=depth.getDepthTextureView();}
    }
    /** The saved world depth: the composite takes its color from the main target, so no color texture is allocated. */
    private static final class DepthTarget extends RenderTarget {
        DepthTarget(int width,int height){super("Mirror screen visibility",true);createBuffers(width,height);}
        @Override public void createBuffers(int width,int height){
            RenderSystem.assertOnRenderThread();this.width=width;this.height=height;var device=RenderSystem.getDevice();
            depthTexture=device.createTexture(()->label+" / Depth",15,com.mojang.blaze3d.textures.TextureFormat.DEPTH32,width,height,1,1);depthTextureView=device.createTextureView(depthTexture);
        }
    }
    private static final CompositeTarget target=new CompositeTarget();
    private ScreenOverlay(){}
    public static void beginFrame(){screens=List.of();ready=false;}
    static void prepare(PoseStack matrices,List<MirrorRenderer.Bundle> drawn){
        screens=drawn;pose=new Matrix4f(matrices.last().pose());modelView=new Matrix4f(RenderSystem.getModelViewMatrix());
        projection=RenderSystem.getProjectionMatrixBuffer();projectionType=RenderSystem.getProjectionType();fog=RenderSystem.getShaderFog();
    }
    public static void captureDepth(){
        if(ReflectionPass.active()||screens.isEmpty())return;
        RenderTarget main=Minecraft.getInstance().getMainRenderTarget();
        if(visibility==null||visibility.width!=main.width||visibility.height!=main.height){if(visibility!=null)visibility.destroyBuffers();visibility=new DepthTarget(main.width,main.height);}
        visibility.copyDepthFrom(main);ready=true;
    }
    public static void render(){
        if(!ready||Minecraft.getInstance().level==null)return;ready=false;
        IrisReflectionScope.restoreDrawingState();
        var mc=Minecraft.getInstance();RenderTarget main=mc.getMainRenderTarget();var oldProjection=RenderSystem.getProjectionMatrixBuffer();var oldType=RenderSystem.getProjectionType();var oldFog=RenderSystem.getShaderFog();
        // The world depth was saved before Minecraft cleared it for first-person hands.
        // Merge their silhouette on the GPU so screens cannot paint over held items.
        try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"Mirror hand visibility",main.getColorTextureView(),OptionalInt.empty(),visibility.getDepthTextureView(),OptionalDouble.empty())){
            pass.setPipeline(HAND_MASK);pass.bindTexture("Sampler0",main.getDepthTextureView(),RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST));pass.draw(0,3);
        }
        if(storage==null){storage=new ByteBufferBuilder(786432);buffers=MultiBufferSource.immediate(storage);}
        target.bind(main,visibility);RenderSystem.getModelViewStack().pushMatrix().set(modelView);
        try{
            // The level has already switched to GUI fog; screens fade into the world's fog like everything around them.
            ((ReflectionMinecraftAccess)mc).mirror$target(target);RenderSystem.setProjectionMatrix(projection,projectionType);RenderSystem.setShaderFog(fog);
            PoseStack matrices=new PoseStack();matrices.mulPose(pose);MirrorRenderer.draw(matrices,buffers,List.of(),screens);buffers.endBatch();
        }finally{
            ((ReflectionMinecraftAccess)mc).mirror$target(main);RenderSystem.getModelViewStack().popMatrix();RenderSystem.setProjectionMatrix(oldProjection,oldType);RenderSystem.setShaderFog(oldFog);
        }
    }
    public static void reset(){beginFrame();if(visibility!=null){visibility.destroyBuffers();visibility=null;}if(storage!=null){storage.close();storage=null;buffers=null;}}
}
