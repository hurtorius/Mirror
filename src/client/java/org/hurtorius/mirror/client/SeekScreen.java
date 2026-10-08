package org.hurtorius.mirror.client;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public final class SeekScreen extends Screen {
    private final Screen parent;private final String id;private EditBox time;private String error="";
    public SeekScreen(Screen parent,String id){super(Component.literal("Jump to a time"));this.parent=parent;this.id=id;}
    protected void init(){int w=Math.min(300,width-24),x=(width-w)/2;time=addRenderableWidget(new EditBox(font,x,65,w,20,Component.literal("Playback time in seconds")));time.setValue("0");addRenderableWidget(new MirrorButton(x,95,w,"Jump to this time",()->{try{double seconds=Double.parseDouble(time.getValue());org.hurtorius.mirror.core.ScreenSpec.range(seconds,0,86400,"Playback time");MirrorClient.send("SEEK","id",id,"seconds",seconds);minecraft.setScreen(parent);}catch(Exception e){error="Enter a time from 0 to 86,400 seconds.";}}));addRenderableWidget(new MirrorButton(x,height-28,w,"Back",()->minecraft.setScreen(parent)));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xff14243c);g.drawCenteredString(font,"Playback time in seconds",width/2,40,0xffd6bdf7);g.drawString(font,error,12,height-44,0xfff3bd80);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
