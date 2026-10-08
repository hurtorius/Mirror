package org.hurtorius.mirror;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.world.InteractionResult;

public final class Mirror implements ModInitializer {
    public static final MirrorServer SERVER=new MirrorServer();
    public static final InitiatorBlock INITIATOR=new InitiatorBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,net.minecraft.resources.Identifier.parse("mirror:initiator"))).strength(3.5f,3600000).sound(net.minecraft.world.level.block.SoundType.AMETHYST).noOcclusion().dynamicShape());
    public static final InitiatorItem INITIATOR_ITEM=new InitiatorItem(new net.minecraft.world.item.Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,net.minecraft.resources.Identifier.parse("mirror:initiator"))));
    public static final net.minecraft.world.level.block.entity.BlockEntityType<InitiatorBlockEntity> INITIATOR_ENTITY=net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(InitiatorBlockEntity::new,INITIATOR).build();

    public void onInitialize(){
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK,net.minecraft.resources.Identifier.parse("mirror:initiator"),INITIATOR);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,net.minecraft.resources.Identifier.parse("mirror:initiator"),INITIATOR_ITEM);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE,net.minecraft.resources.Identifier.parse("mirror:initiator"),INITIATOR_ENTITY);
        NativeCompatibility.register();

        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("mirror","mirror"),
            net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
                .title(net.minecraft.network.chat.Component.literal("Mirror"))
                .icon(MirrorServer::initiator)
                .displayItems((parameters,output)->{if(parameters.hasPermissions())output.accept(MirrorServer.initiator());}).build());
        PayloadTypeRegistry.playC2S().registerLarge(MessagePayload.TYPE,MessagePayload.CODEC,96000);
        PayloadTypeRegistry.playS2C().registerLarge(MessagePayload.TYPE,MessagePayload.CODEC,96000);
        PayloadTypeRegistry.playC2S().register(DataPayload.TYPE,DataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DataPayload.TYPE,DataPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MessagePayload.TYPE,(p,c)->SERVER.message(c.player(),p.json()));
        ServerPlayNetworking.registerGlobalReceiver(DataPayload.TYPE,(p,c)->SERVER.data(c.player(),p));
        ServerLifecycleEvents.SERVER_STARTED.register(SERVER::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(SERVER::stop);
        ServerTickEvents.END_SERVER_TICK.register(SERVER::tick);
        ServerPlayConnectionEvents.JOIN.register((h,s,server)->SERVER.join(h.player));
        ServerPlayConnectionEvents.DISCONNECT.register((h,server)->SERVER.leave(h.player));
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            boolean item=MirrorServer.isInitiator(player.getItemInHand(hand));
            boolean anchor=level.getBlockState(hit.getBlockPos()).is(INITIATOR)||SERVER.at(level.dimension().identifier().toString(),hit.getBlockPos())!=null;
            if(!item&&!anchor)return InteractionResult.PASS;
            if(level.isClientSide())return InteractionResult.SUCCESS;
            if(player instanceof net.minecraft.server.level.ServerPlayer p)SERVER.interact(p,hand,hit,item);
            return InteractionResult.SUCCESS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level,player,pos,state,entity)->{
            var a=SERVER.at(level.dimension().identifier().toString(),pos);
            return a==null||player instanceof net.minecraft.server.level.ServerPlayer p&&SERVER.mayEdit(p,a);
        });
        PlayerBlockBreakEvents.AFTER.register((level,player,pos,state,entity)->SERVER.broken(level.dimension().identifier().toString(),pos));
    }
}
