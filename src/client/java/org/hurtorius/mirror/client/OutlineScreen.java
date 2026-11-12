package org.hurtorius.mirror.client;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.*;
import java.util.*;
public final class OutlineScreen extends Screen {
    private final Screen parent;private final ScreenSpec spec;private final List<Outline.Point> points=new ArrayList<>();private List<Outline.Point> shape;private int x,y,w,h;private String error="";
    public OutlineScreen(Screen parent,ScreenSpec spec){super(Component.literal("Draw a screen outline"));this.parent=parent;this.spec=spec;points.addAll(spec.outline);shape=Outline.hull(points);}
    protected void init(){w=Math.min(width-24,(height-85)*16/9);h=w*9/16;x=(width-w)/2;y=45;addRenderableWidget(new MirrorButton(10,height-28,80,"Clear points",()->{points.clear();shape=List.of();}));addRenderableWidget(new MirrorButton(95,height-28,80,"Back",()->minecraft.setScreen(parent)));addRenderableWidget(new MirrorButton(width-95,height-28,85,"Use outline",()->{try{Outline.validate(shape);spec.outline=shape;spec.shape=ScreenSpec.Shape.CUSTOM;minecraft.setScreen(parent);}catch(IllegalArgumentException e){error=e.getMessage();}}));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.drawCenteredString(font,"Click to place the outer corners of your screen",width/2,15,0xffffffff);g.fill(x,y,x+w,y+h,0xff252525);for(int i=0;i<shape.size();i++){var a=shape.get(i);var b=shape.get((i+1)%shape.size());int ax=x+(int)(a.x()*w),ay=y+(int)(a.y()*h),bx=x+(int)(b.x()*w),by=y+(int)(b.y()*h);g.pose().pushMatrix();g.pose().translate(ax,ay);g.pose().rotate((float)Math.atan2(by-ay,bx-ax));g.fill(0,0,(int)Math.round(Math.hypot(bx-ax,by-ay)),1,0xff86e1db);g.pose().popMatrix();g.fill(ax-2,ay-2,ax+3,ay+3,0xffffffff);}g.drawString(font,font.plainSubstrByWidth(error,width-24),12,height-44,0xffffbb66);super.render(g,mx,my,delta);}
    public boolean mouseClicked(MouseButtonEvent e,boolean twice){if(e.x()>=x&&e.x()<=x+w&&e.y()>=y&&e.y()<=y+h){if(points.size()<64){points.add(new Outline.Point((e.x()-x)/w,(e.y()-y)/h));shape=Outline.hull(points);}return true;}return super.mouseClicked(e,twice);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
