package org.hurtorius.mirror;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerKeepModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import xyz.nucleoid.packettweaker.PacketContext;
public final class InitiatorItem extends BlockItem implements PolymerItem,PolymerKeepModel {
    public InitiatorItem(Properties settings){super(Mirror.INITIATOR,settings);}
    public Item getPolymerItem(ItemStack stack,PacketContext context){return NativeCompatibility.supported(context)?this:Items.LODESTONE;}
    public Identifier getPolymerItemModel(ItemStack stack,PacketContext context){return NativeCompatibility.supported(context)?Identifier.parse("mirror:initiator"):null;}
    public boolean canSyncRawToClient(PacketContext context){return NativeCompatibility.supported(context);}
    public boolean canSynchronizeToPolymerClient(PacketContext context){return NativeCompatibility.supported(context);}
    public ItemStack getPolymerItemStack(ItemStack stack,TooltipFlag tooltip,PacketContext context){
        if(NativeCompatibility.supported(context)){var safe=stack.copy();safe.remove(DataComponents.BLOCK_ENTITY_DATA);safe.remove(DataComponents.CUSTOM_DATA);return safe;}
        var safe=new ItemStack(Items.LODESTONE,stack.getCount());safe.set(DataComponents.CUSTOM_NAME,Component.literal("Mirror Initiator"));return safe;
    }
    // Use vanilla placement/collision/sound/stat rules after checking Mirror authority. The server decides; a client that may place
    // places at once like any block (hand swing and sound), and the server corrects it if the world's limit was reached meanwhile.
    public InteractionResult place(BlockPlaceContext context){
        if(!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player))return context.getPlayer()!=null&&Mirror.clientMayPlace.test(context.getPlayer())?super.place(context):InteractionResult.FAIL;
        if(!Mirror.SERVER.canPlace(player))return InteractionResult.FAIL;
        return super.place(context);
    }
}
