package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;

public final class ReportsScreen extends Screen {
    private final Screen parent;
    private int scroll,contentHeight,wrappedVersion=-1,wrappedWidth;
    private List<FormattedCharSequence> lines=List.of();
    private int[] tops={};
    public ReportsScreen(Screen parent){super(Component.literal("Reports"));this.parent=parent;}
    protected void init(){
        addRenderableWidget(new MirrorButton(10,height-28,90,"Clear",()->{Reports.clear();scroll=0;}));
        if(org.hurtorius.mirror.core.RuntimePlatform.current().startsWith("linux-")&&Reports.linuxSetup)addRenderableWidget(new MirrorButton(110,height-28,Math.max(80,width-220),"Browser setup",()->minecraft.setScreen(new LinuxBrowserSetupScreen(this))));
        addRenderableWidget(new MirrorButton(width-100,height-28,90,"Back",this::onClose));
    }
    public void render(GuiGraphics g,int mx,int my,float delta){
        g.drawString(font,"Mirror • Hurtorius",10,10,0xffffffff);
        g.drawString(font,"Reports · newest first",10,27,0xffcccccc);
        if(Reports.version!=wrappedVersion||width!=wrappedWidth){wrappedVersion=Reports.version;wrappedWidth=width;List<FormattedCharSequence> wrapped=new ArrayList<>();List<Integer> at=new ArrayList<>();int y=0;for(String message:Reports.list()){for(var line:font.split(Component.literal(message),width-28)){wrapped.add(line);at.add(y);y+=11;}y+=8;}lines=wrapped;tops=at.stream().mapToInt(Integer::intValue).toArray();contentHeight=y;}
        scroll=Math.clamp(scroll,0,Math.max(0,contentHeight-(height-86)));
        g.enableScissor(10,47,width-10,height-39);
        if(lines.isEmpty())g.drawString(font,"No reports.",10,50,0xffaaaaaa);
        for(int i=0;i<lines.size();i++){int y=47-scroll+tops[i];if(y>=36&&y<=height-39)g.drawString(font,lines.get(i),12,y,0xffdddddd);}
        g.disableScissor();super.render(g,mx,my,delta);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll-=(int)(dy*24);return true;}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
