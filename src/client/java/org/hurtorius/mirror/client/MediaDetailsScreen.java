package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.WorldStore;
public final class MediaDetailsScreen extends Screen {
    private final Screen parent;private final WorldStore.Media media;private EditBox name,folder;
    public MediaDetailsScreen(Screen parent,WorldStore.Media media){super(Component.literal("Media details"));this.parent=parent;this.media=media;}
    protected void init(){int w=Math.min(340,width-24),x=(width-w)/2;name=addRenderableWidget(new EditBox(font,x,56,w,20,Component.literal("Name")));name.setMaxLength(128);name.setValue(media.name());folder=addRenderableWidget(new EditBox(font,x,100,w,20,Component.literal("Folder")));folder.setMaxLength(64);folder.setValue(media.folder());addRenderableWidget(new MirrorButton(x,height-54,w,"Save name and folder",()->{MirrorClient.send("MEDIA_EDIT","media",media.id(),"name",name.getValue(),"folder",folder.getValue());onClose();}));addRenderableWidget(new MirrorButton(x,height-28,w,"Back",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xe0101010);g.drawCenteredString(font,"Media details",width/2,16,0xffffffff);g.drawString(font,"Name",name.getX(),42,0xffcccccc);g.drawString(font,"Folder",folder.getX(),86,0xffcccccc);g.drawCenteredString(font,media.kind()+" · "+media.bytes()/1024+" KB",width/2,135,0xffcccccc);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
