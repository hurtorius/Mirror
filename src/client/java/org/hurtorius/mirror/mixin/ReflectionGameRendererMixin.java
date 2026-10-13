package org.hurtorius.mirror.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.profiling.Profiler;
import org.hurtorius.mirror.client.ReflectionPass;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class ReflectionGameRendererMixin {
    @Inject(method="render",at=@At("HEAD"))
    private void mirror$begin(DeltaTracker time,boolean world,CallbackInfo ci){org.hurtorius.mirror.client.ScreenOverlay.beginFrame();}
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void mirror$depth(DeltaTracker time,CallbackInfo ci){org.hurtorius.mirror.client.ScreenOverlay.captureDepth();}
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void mirror$finalColor(DeltaTracker time,boolean world,CallbackInfo ci){org.hurtorius.mirror.client.ScreenOverlay.render();}

    @Inject(method="renderLevel",at=@At("HEAD"))
    private void mirror$world(DeltaTracker time,CallbackInfo ci){if(!ReflectionPass.active())ReflectionPass.capture(time);}
    @Inject(method="getProjectionMatrix",at=@At("HEAD"),cancellable=true)
    private void mirror$projection(float fov,CallbackInfoReturnable<Matrix4f> ci){if(ReflectionPass.active())ci.setReturnValue(ReflectionPass.projection());}
    @Inject(method={"bobHurt","bobView"},at=@At("HEAD"),cancellable=true)
    private void mirror$steady(com.mojang.blaze3d.vertex.PoseStack pose,float partial,CallbackInfo ci){if(ReflectionPass.active())ci.cancel();}
    @Inject(method="pick",at=@At("HEAD"),cancellable=true)
    private void mirror$pick(float partial,CallbackInfo ci){if(ReflectionPass.active())ci.cancel();}
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(FZLorg/joml/Matrix4f;)V"),cancellable=true)
    private void mirror$noOverlays(DeltaTracker time,CallbackInfo ci){if(ReflectionPass.active()){Profiler.get().pop();ci.cancel();}}
}
