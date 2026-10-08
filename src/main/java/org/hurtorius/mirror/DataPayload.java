package org.hurtorius.mirror;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
/** kind: 0 media, 1 picture, 2 PCM; id and epoch are opaque server-issued UUIDs. */
public record DataPayload(int kind,String id,String epoch,long number,int part,int parts,byte[] bytes) implements CustomPacketPayload {
    public static final Type<DataPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mirror","data"));
    public static final StreamCodec<RegistryFriendlyByteBuf,DataPayload> CODEC=StreamCodec.of((b,p)->{b.writeByte(p.kind);b.writeUtf(p.id,36);b.writeUtf(p.epoch,36);b.writeVarLong(p.number);b.writeVarInt(p.part);b.writeVarInt(p.parts);b.writeByteArray(p.bytes);},b->new DataPayload(b.readUnsignedByte(),b.readUtf(36),b.readUtf(36),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readByteArray(28000)));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
