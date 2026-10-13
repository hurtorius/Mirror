package org.hurtorius.mirror.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.hurtorius.mirror.client.ReflectionPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mirror capture reuses the main eye's terrain state instead of moving it to the reflected eye and back. */
@Mixin(LevelRenderer.class)
public abstract class ReflectionLevelRendererMixin {
    @Shadow private double prevCamRotX;
    @Shadow private void applyFrustum(Frustum frustum){}

    // Culling from the reflected eye would re-center the ViewArea and rebuild the section graph twice per capture.
    // The main graph covers every direction, so the capture filters it by its own frustum and the main view re-applies its own next.
    @Inject(method="cullTerrain",at=@At("HEAD"),cancellable=true,require=0)
    private void mirror$mainTerrain(Camera camera,Frustum frustum,boolean spectator,CallbackInfo ci){
        if(ReflectionPass.sharesTerrain()){applyFrustum(frustum);prevCamRotX=Double.NaN;ci.cancel();}
    }
    // Translucent sections stay sorted for the real eye; dirty sections a mirror sees still compile.
    @WrapWithCondition(method="compileSections",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V"),require=0)
    private boolean mirror$mainSort(LevelRenderer renderer,Vec3 eye){return !ReflectionPass.active();}
    // Clouds are rebuilt whenever the eye changes cell and share one per-frame uniform slot, so mirrors show none.
    @Inject(method="addCloudsPass",at=@At("HEAD"),cancellable=true,require=0)
    private void mirror$noClouds(CallbackInfo ci){if(ReflectionPass.active())ci.cancel();}
}
