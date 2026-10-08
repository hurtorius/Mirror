package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ReportsScreen extends Screen {
    private final Screen parent;
    private int scroll,contentHeight;
    public ReportsScreen(Screen parent){super(Component.literal("Reports"));this.parent=parent;}
    protected void init(){
        addRenderableWidget(new MirrorButton(10,height-28,90,"Clear",()->{Reports.clear();scroll=0;}));
        addRenderableWidget(new MirrorButton(width-100,height-28,90,"Back",this::onClose));
    }
    public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xff141414);g.drawString(font,"Mirror • Hurtorius",10,10,0xffffffff);
        g.drawString(font,"Reports · newest first",10,27,0xffcccccc);
        var messages=Reports.list();contentHeight=0;for(String message:messages)contentHeight+=font.split(Component.literal(message),width-28).size()*11+8;
        scroll=Math.clamp(scroll,0,Math.max(0,contentHeight-(height-86)));
        g.enableScissor(10,47,width-10,height-39);int y=47-scroll;
        if(messages.isEmpty())g.drawString(font,"No reports.",10,50,0xffaaaaaa);
        for(String message:messages){for(var line:font.split(Component.literal(message),width-28)){g.drawString(font,line,12,y,0xffdddddd);y+=11;}y+=8;}
        g.disableScissor();super.render(g,mx,my,delta);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll-=(int)(dy*24);return true;}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
