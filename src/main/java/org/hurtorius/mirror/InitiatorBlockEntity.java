package org.hurtorius.mirror;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
public final class InitiatorBlockEntity extends BlockEntity {
    /** Screens are found by position, so a block needs binding once per load; block entities are rebuilt on every load, so this is never saved. */
    private boolean adopted;
    public InitiatorBlockEntity(BlockPos pos,BlockState state){super(Mirror.INITIATOR_ENTITY,pos,state);}
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup){return new CompoundTag();}
    public static void tick(Level level,BlockPos pos,BlockState state,InitiatorBlockEntity entity){if(!entity.adopted&&level instanceof net.minecraft.server.level.ServerLevel server)entity.adopted=Mirror.SERVER.adopt(server,entity);}
}
