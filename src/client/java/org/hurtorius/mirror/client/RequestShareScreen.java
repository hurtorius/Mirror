package org.hurtorius.mirror.client;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public final class RequestShareScreen extends Screen {
    private final Screen parent;private final String id;
    public RequestShareScreen(Screen parent,String id){super(Component.literal("Ask a player to share"));this.parent=parent;this.id=id;}
    protected void init(){int y=55,w=Math.min(300,width-24),x=(width-w)/2;for(var e:MirrorClient.players.entrySet()){addRenderableWidget(new MirrorButton(x,y,w,e.getValue(),()->{MirrorClient.send("REQUEST_SHARE","id",id,"player",e.getKey());minecraft.setScreen(parent);}));y+=25;}
        addRenderableWidget(new MirrorButton(x,height-28,w,"Back",()->minecraft.setScreen(parent)));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xf014243c);g.drawCenteredString(font,"Ask a player to share",width/2,16,0xffd6bdf7);g.drawCenteredString(font,"They decide whether and what to share.",width/2,32,0xffa2b1c8);super.render(g,mx,my,delta);}
    public void tick(){if(parent instanceof InitiatorScreen editor){boolean ready=editor.sourceSaved(org.hurtorius.mirror.core.ScreenSpec.Source.SHARE);for(var child:children())if(child instanceof MirrorButton button&&!button.getMessage().getString().equals("Back"))button.active=ready;}}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
