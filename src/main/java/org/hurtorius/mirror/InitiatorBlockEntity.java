package org.hurtorius.mirror;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
public final class InitiatorBlockEntity extends BlockEntity {
    private long legacyRevision;
    private boolean legacyEffective,legacyPreviousGate;
    private String anchorId="",legacyConfig="",legacyOwner="";
    public InitiatorBlockEntity(BlockPos pos,BlockState state){super(Mirror.INITIATOR_ENTITY,pos,state);}
    public void fresh(String id){legacyConfig=legacyOwner="";anchorId=id;setChanged();}
    public String legacyConfig(){return legacyConfig;}
    public String legacyOwner(){return legacyOwner;}
    public void bind(String id){if(!id.equals(anchorId)){anchorId=id;setChanged();}}
    protected void loadAdditional(ValueInput input){super.loadAdditional(input);anchorId=input.getStringOr("MirrorAnchorId","");legacyConfig=input.getStringOr("MirrorConfig","");legacyOwner=input.getStringOr("Owner","");legacyRevision=input.getLongOr("Revision",0);legacyEffective=input.getBooleanOr("Effective",false);legacyPreviousGate=input.getBooleanOr("PreviousGate",false);}
    protected void saveAdditional(ValueOutput output){super.saveAdditional(output);output.putString("MirrorAnchorId",anchorId);if(!legacyConfig.isEmpty()){output.putString("MirrorConfig",legacyConfig);output.putLong("Revision",legacyRevision);output.putBoolean("Effective",legacyEffective);output.putBoolean("PreviousGate",legacyPreviousGate);}if(!legacyOwner.isEmpty())output.putString("Owner",legacyOwner);}
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup){return new CompoundTag();}
    public static void tick(Level level,BlockPos pos,BlockState state,InitiatorBlockEntity entity){if(level instanceof net.minecraft.server.level.ServerLevel server)Mirror.SERVER.adopt(server,entity);}
}
