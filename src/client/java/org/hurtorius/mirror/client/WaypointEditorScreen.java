package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.ScreenSpec;
import org.hurtorius.mirror.core.ScreenCoordinates;
public final class WaypointEditorScreen extends Screen {
    private final Screen parent;private final ScreenSpec spec;private final int index;private final ScreenCoordinates coordinates;
    private final String[] names={"Center X","Center Y","Center Z","Facing angle","Tilt","Travel seconds"};
    private final String[] values=new String[6];private final EditBox[] fields=new EditBox[6];private String error="";
    public WaypointEditorScreen(Screen parent,ScreenSpec spec,int index,ScreenCoordinates coordinates){super(Component.literal("Flight stop "+(index+1)));this.parent=parent;this.spec=spec;this.index=index;this.coordinates=coordinates;var p=spec.path.get(index);double[] v={coordinates.display("x",p.x()),coordinates.display("y",p.y()),coordinates.display("z",p.z()),p.yaw(),p.pitch(),p.seconds()};for(int i=0;i<6;i++)values[i]=Double.toString(v[i]);}
    protected void init(){int w=Math.min(320,width-24),x=(width-w)/2;for(int i=0;i<6;i++){final int at=i;fields[i]=addRenderableWidget(new EditBox(font,x+110,36+i*24,w-110,20,Component.literal(names[i])));fields[i].setValue(values[i]);fields[i].setResponder(v->values[at]=v);}
        addRenderableWidget(new MirrorButton(x,height-28,w/3-3,"Cancel",this::onClose));
        addRenderableWidget(new MirrorButton(x+w/3,height-28,w/3-3,"Remove",()->{spec.path.remove(index);onClose();}));
        addRenderableWidget(new MirrorButton(x+w*2/3,height-28,w/3,"Save stop",()->{try{double[] n=new double[6];for(int i=0;i<6;i++)n[i]=Double.parseDouble(values[i]);var previous=spec.path.set(index,new ScreenSpec.Waypoint(coordinates.stored("x",n[0]),coordinates.stored("y",n[1]),coordinates.stored("z",n[2]),n[3],n[4],n[5]));try{spec.validate();}catch(Exception e){spec.path.set(index,previous);throw e;}onClose();}catch(Exception e){error=e.getMessage()==null?"Enter valid numbers":e.getMessage();}}));
    }
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xee101010);g.drawCenteredString(font,getTitle(),width/2,14,0xffffffff);for(int i=0;i<6;i++)g.drawString(font,names[i],fields[i].getX()-110,fields[i].getY()+6,0xffcccccc);g.drawCenteredString(font,font.plainSubstrByWidth(error,width-20),width/2,height-42,0xffffbb66);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
