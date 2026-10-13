package org.hurtorius.mirror;
import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.utils.*;
import net.fabricmc.api.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.*;
import xyz.nucleoid.packettweaker.PacketContext;
public final class InitiatorBlock extends BaseEntityBlock implements PolymerBlock,PolymerKeepModel,PolymerClientDecoded {
    private static final VoxelShape SELECTION=Block.box(1,0,1,15,17,15);
    private static final VoxelShape COLLISION=Block.box(1,0,1,15,12,15);
    public static final BooleanProperty ACTIVE=BooleanProperty.create("active");
    public InitiatorBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(ACTIVE,false));}
    protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(InitiatorBlock::new);}
    protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(ACTIVE);}
    protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return SELECTION;}
    protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return COLLISION;}
    protected boolean isCollisionShapeFullBlock(BlockState state,BlockGetter level,BlockPos pos){return false;}
    public boolean overridePlayerCollisionsWithPolymer(BlockGetter level,BlockPos pos,BlockState state,net.minecraft.server.level.ServerPlayer player){return !NativeCompatibility.supported(PacketContext.create(player));}
    protected boolean hasAnalogOutputSignal(BlockState state){return true;}
    protected int getAnalogOutputSignal(BlockState state,Level level,BlockPos pos,net.minecraft.core.Direction direction){var a=Mirror.SERVER.at(level.dimension().identifier().toString(),pos);return a!=null&&a.finished?15:0;}
    protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    public void setPlacedBy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.LivingEntity placer,net.minecraft.world.item.ItemStack stack){
        super.setPlacedBy(level,pos,state,placer,stack);
        if(placer instanceof net.minecraft.server.level.ServerPlayer player)Mirror.SERVER.placed(player,pos);
    }
    public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new InitiatorBlockEntity(pos,state);}
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){return level.isClientSide()?null:createTickerHelper(type,Mirror.INITIATOR_ENTITY,InitiatorBlockEntity::tick);}
    public BlockState getPolymerBlockState(BlockState state,PacketContext context){return NativeCompatibility.supported(context)?state:Blocks.LODESTONE.defaultBlockState();}
    public BlockState getPolymerBreakEventBlockState(BlockState state,PacketContext context){return getPolymerBlockState(state,context);}
    public boolean canSyncRawToClient(PacketContext context){return NativeCompatibility.supported(context);}
    public boolean canSynchronizeToPolymerClient(PacketContext context){return NativeCompatibility.supported(context);}
    @Environment(EnvType.CLIENT) public boolean shouldDecodePolymer(){return NativeCompatibility.supportedClient();}
}
