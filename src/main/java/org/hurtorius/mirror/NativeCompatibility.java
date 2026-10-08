package org.hurtorius.mirror;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.networking.api.*;
import eu.pb4.polymer.networking.api.client.PolymerClientNetworking;
import net.fabricmc.api.*;
import net.minecraft.network.PacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import xyz.nucleoid.packettweaker.PacketContext;

/** Keep saved registry IDs native; translate only packets for clients without this protocol. */
public final class NativeCompatibility {
    public static final Identifier ID=Identifier.fromNamespaceAndPath("mirror","native_initiator");
    public static final int PROTOCOL=4;
    public static boolean supported(PacketContext context){
        if(context==null||context.getClientConnection()==null)return false;
        if(context.getBackingPacketListener() instanceof PacketListener listener&&listener.flow()==PacketFlow.CLIENTBOUND)return supportedClient();
        return PolymerNetworking.getSupportedVersion(context.getClientConnection(),ID)==PROTOCOL;
    }
    @Environment(EnvType.CLIENT) public static boolean supportedClient(){return PolymerClientNetworking.getSupportedVersion(ID)==PROTOCOL;}
    public static void register(){
        PolymerNetworking.registerCommonSimple(Capability.TYPE,PROTOCOL,Capability.CODEC);
        PolymerBlockUtils.registerBlockEntity(Mirror.INITIATOR_ENTITY,new PolymerSyncedObject<BlockEntityType<?>>() {
            public BlockEntityType<?> getPolymerReplacement(BlockEntityType<?> type,PacketContext context){return supported(context)?type:null;}
            public boolean canSyncRawToClient(PacketContext context){return supported(context);}
            public boolean canSynchronizeToPolymerClient(PacketContext context){return supported(context);}
        });
    }
    public record Capability() implements CustomPacketPayload {
        static final Type<Capability> TYPE=new Type<>(ID);
        static final StreamCodec<ContextByteBuf,Capability> CODEC=StreamCodec.unit(new Capability());
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
