package org.hurtorius.mirror.mixin;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(GameRenderer.class)
public interface ReflectionGameRendererAccess {
    @Mutable @Accessor("mainCamera") void mirror$camera(Camera camera);
    @Accessor("resourcePool") CrossFrameResourcePool mirror$resources();
    @Mutable @Accessor("resourcePool") void mirror$resources(CrossFrameResourcePool pool);
}
