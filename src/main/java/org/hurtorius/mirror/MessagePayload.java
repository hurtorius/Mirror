package org.hurtorius.mirror;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public record MessagePayload(String json) implements CustomPacketPayload {
    public static final Type<MessagePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("mirror","message"));
    public static final StreamCodec<RegistryFriendlyByteBuf,MessagePayload> CODEC=StreamCodec.of((buf,p)->buf.writeUtf(p.json,24000),buf->new MessagePayload(buf.readUtf(24000)));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
