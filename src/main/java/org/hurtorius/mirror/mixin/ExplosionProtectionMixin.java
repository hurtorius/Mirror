package org.hurtorius.mirror.mixin;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.hurtorius.mirror.Mirror;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.*;
@Mixin(ServerExplosion.class)
public abstract class ExplosionProtectionMixin {
    @Shadow public abstract ServerLevel level();
    @ModifyVariable(method="interactWithBlocks",at=@At("HEAD"),argsOnly=true)
    private List<BlockPos> mirrorAnchor(List<BlockPos> positions){return new ArrayList<>(positions.stream().filter(pos->Mirror.SERVER.at(level().dimension().identifier().toString(),pos)==null).toList());}
}
