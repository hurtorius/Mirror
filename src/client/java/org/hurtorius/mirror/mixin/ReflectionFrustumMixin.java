package org.hurtorius.mirror.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.hurtorius.mirror.client.ReflectionPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Frustum.class)
public abstract class ReflectionFrustumMixin {
    @Inject(method="offsetToFullyIncludeCameraCube",at=@At("HEAD"),cancellable=true)
    private void mirror$keepClippedFrustum(int size,CallbackInfoReturnable<Frustum> ci){
        // An off-axis mirror deliberately excludes its virtual camera. Vanilla's
        // unbounded backward-offset loop cannot always fit that cube into this cone.
        if(ReflectionPass.active())ci.setReturnValue((Frustum)(Object)this);
    }
}
