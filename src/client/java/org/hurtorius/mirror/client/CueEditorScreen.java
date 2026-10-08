package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.*;
import java.util.*;

public final class CueEditorScreen extends Screen {
    private final Screen parent;private final ScreenSpec spec;private int index;private final ScreenCoordinates coordinates;
    private final List<String> actions=List.of("summon","play","wait","pause","next","seek","width","height","yaw","pitch","roll","x","y","z","dismiss");
    private int action;private String media="",waitValue="0",numberValue="0",error="";private EditBox seconds,value;
    public CueEditorScreen(Screen parent,ScreenSpec spec,int index,ScreenCoordinates coordinates){super(Component.literal(index<0?"Add a show cue":"Edit show cue "+(index+1)));this.parent=parent;this.spec=spec;this.index=index;this.coordinates=coordinates;if(index>=0){var cue=spec.show.get(index);action=actions.indexOf(cue.action());media=cue.media();waitValue=Double.toString(cue.seconds());numberValue=Double.toString(ScreenCoordinates.isAxis(cue.action())?coordinates.display(cue.action(),cue.value()):cue.value());}}
    protected void init(){int w=Math.min(360,width-24),x=(width-w)/2;
        addRenderableWidget(new MirrorButton(x,34,w,"Action: "+label(actions.get(action)),()->minecraft.setScreen(new ChoiceScreen<>(this,"Show action",actions.stream().map(a->new ChoiceScreen.Option<>(a,label(a),"Choose this action")).toList(),actions.get(action),a->{action=actions.indexOf(a);numberValue=Double.toString(switch(a){case "x"->coordinates.display(a,spec.x);case "y"->coordinates.display(a,spec.y);case "z"->coordinates.display(a,spec.z);case "width"->spec.width;case "height"->spec.height;default->0;});}))));
        List<String> ids=new ArrayList<>(List.of(""));MirrorClient.library.values().stream().filter(m->!m.kind().equals("subtitle")).forEach(m->ids.add(m.id()));var item=MirrorClient.library.get(media);
        var choose=addRenderableWidget(new MirrorButton(x,59,w,item==null?"Current playlist item":item.name(),()->{media=ids.get((Math.max(0,ids.indexOf(media))+1)%ids.size());rebuildWidgets();}));choose.active=actions.get(action).equals("play");
        seconds=addRenderableWidget(new EditBox(font,x,101,w,20,Component.literal("Wait after this cue")));seconds.setValue(waitValue);seconds.setResponder(v->waitValue=v);
        value=addRenderableWidget(new EditBox(font,x,143,w,20,Component.literal("Value")));value.setValue(numberValue);value.setResponder(v->numberValue=v);
        addRenderableWidget(new MirrorButton(x,171,w/2-2,index<0?"Add cue":"Save cue",this::saveCue));
        var remove=addRenderableWidget(new MirrorButton(x+w/2,171,w/2,"Remove cue",()->{spec.show.remove(index);onClose();}));remove.active=index>=0;
        int bw=w/3;var earlier=addRenderableWidget(new MirrorButton(x,height-28,bw-2,"Earlier",()->{Collections.swap(spec.show,index,index-1);index--;rebuildWidgets();}));earlier.active=index>0;
        var later=addRenderableWidget(new MirrorButton(x+bw,height-28,bw-2,"Later",()->{Collections.swap(spec.show,index,index+1);index++;rebuildWidgets();}));later.active=index>=0&&index+1<spec.show.size();
        addRenderableWidget(new MirrorButton(x+bw*2,height-28,w-bw*2,"Back",this::onClose));
    }
    static String label(String action){return switch(action){case "x","y","z"->"Move center "+action.toUpperCase();case "summon"->"Screen on";case "dismiss"->"Screen off";default->Character.toUpperCase(action.charAt(0))+action.substring(1);};}
    private void saveCue(){try{var cue=new ScreenSpec.Cue(actions.get(action),actions.get(action).equals("play")?media:"",Double.parseDouble(waitValue),ScreenCoordinates.isAxis(actions.get(action))?coordinates.stored(actions.get(action),Double.parseDouble(numberValue)):Double.parseDouble(numberValue));ScreenSpec copy=spec.copy();if(index<0)copy.show.add(cue);else copy.show.set(index,cue);copy.validate();spec.show=copy.show;onClose();}catch(Exception e){error=e.getMessage()==null?"Enter valid numbers":e.getMessage();}}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xee101010);g.drawCenteredString(font,getTitle(),width/2,14,0xffffffff);g.drawString(font,"Wait after this cue, seconds",seconds.getX(),87,0xffcccccc);g.drawString(font,ScreenCoordinates.isAxis(actions.get(action))?"Center "+actions.get(action).toUpperCase()+" · world coordinate":"Value for seek, size or angle",value.getX(),129,0xffcccccc);g.drawCenteredString(font,font.plainSubstrByWidth(error,width-20),width/2,height-42,0xffffbb66);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
