package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.ScreenSpec;
import java.util.*;
public final class BranchScreen extends Screen {
    private final Screen parent;private final ScreenSpec spec;
    public BranchScreen(Screen parent,ScreenSpec spec){super(Component.literal("Redstone film choices"));this.parent=parent;this.spec=spec;}
    protected void init(){int w=Math.min(440,width-24),x=(width-w)/2,y=40;for(String side:List.of("NORTH","SOUTH","EAST","WEST","UP","DOWN")){
        String current=spec.branches.stream().filter(b->b.side().equals(side)).map(ScreenSpec.Branch::media).findFirst().orElse("");var item=MirrorClient.library.get(current);
        var button=addRenderableWidget(new MirrorButton(x,y,w,side.toLowerCase(Locale.ROOT)+": "+(item==null?"No choice":item.name()),()->{List<String> choices=new ArrayList<>();choices.add("");choices.addAll(spec.playlist);String next=choices.get((Math.max(0,choices.indexOf(current))+1)%choices.size());spec.branches.removeIf(b->b.side().equals(side));if(!next.isEmpty())spec.branches.add(new ScreenSpec.Branch(side,next));rebuildWidgets();}));button.active=!spec.playlist.isEmpty();y+=24;
    }addRenderableWidget(new MirrorButton(x,height-28,w,"Back",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xee101010);g.drawCenteredString(font,"Redstone choices · use playlist items",width/2,15,0xffffffff);g.drawCenteredString(font,spec.playlist.isEmpty()?"Add films in Source first":"Power a chosen side to play. Enable world triggers in Show.",width/2,height-42,0xffcccccc);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
