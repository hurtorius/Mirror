package org.hurtorius.mirror.client;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.*;
import java.util.*;
public final class OutlineScreen extends Screen {
    private final Screen parent;private final ScreenSpec spec;private final List<Outline.Point> points=new ArrayList<>();private int x,y,w,h;private String error="";
    public OutlineScreen(Screen parent,ScreenSpec spec){super(Component.literal("Draw a screen outline"));this.parent=parent;this.spec=spec;points.addAll(spec.outline);}
    protected void init(){w=Math.min(width-24,(height-85)*16/9);h=w*9/16;x=(width-w)/2;y=45;addRenderableWidget(new MirrorButton(10,height-28,80,"Clear points",()->points.clear()));addRenderableWidget(new MirrorButton(95,height-28,80,"Back",()->minecraft.setScreen(parent)));addRenderableWidget(new MirrorButton(width-95,height-28,85,"Use outline",()->{try{List<Outline.Point> shape=Outline.hull(points);Outline.validate(shape);spec.outline=shape;spec.shape=ScreenSpec.Shape.CUSTOM;minecraft.setScreen(parent);}catch(IllegalArgumentException e){error=e.getMessage();}}));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xff14243c);g.drawCenteredString(font,"Click to place the outer corners of your screen",width/2,15,0xffd6bdf7);g.fill(x,y,x+w,y+h,0xff263a52);List<Outline.Point> shape=Outline.hull(points);for(int i=0;i<shape.size();i++){var a=shape.get(i);var b=shape.get((i+1)%shape.size());int ax=x+(int)(a.x()*w),ay=y+(int)(a.y()*h),bx=x+(int)(b.x()*w),by=y+(int)(b.y()*h);int n=Math.max(Math.abs(ax-bx),Math.abs(ay-by));for(int step=0;step<=n;step++){int px=n==0?ax:ax+(bx-ax)*step/n,py=n==0?ay:ay+(by-ay)*step/n;g.fill(px,py,px+1,py+1,0xff86e1db);}g.fill(ax-2,ay-2,ax+3,ay+3,0xffd6bdf7);}g.drawString(font,font.plainSubstrByWidth(error,width-24),12,height-44,0xfff3bd80);super.render(g,mx,my,delta);}
    public boolean mouseClicked(MouseButtonEvent e,boolean twice){if(e.x()>=x&&e.x()<=x+w&&e.y()>=y&&e.y()<=y+h){if(points.size()<64)points.add(new Outline.Point((e.x()-x)/w,(e.y()-y)/h));return true;}return super.mouseClicked(e,twice);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
