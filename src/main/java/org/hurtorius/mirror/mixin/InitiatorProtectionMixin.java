package org.hurtorius.mirror.mixin;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.core.*;
import org.hurtorius.mirror.Mirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PistonBaseBlock.class)
public abstract class InitiatorProtectionMixin {
    @Inject(method="isPushable",at=@At("HEAD"),cancellable=true)
    private static void mirrorAnchor(BlockState state,Level level,BlockPos pos,Direction direction,boolean destroy,Direction piston,CallbackInfoReturnable<Boolean> result){if(Mirror.SERVER.at(level.dimension().identifier().toString(),pos)!=null)result.setReturnValue(false);}
}
