package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.*;
import java.util.*;

/** Search and select world media without filling the main settings form with every file. */
public final class MediaPickerScreen extends Screen {
    private final InitiatorScreen parent;
    private final ScreenSpec draft;
    private final List<MirrorButton> items=new ArrayList<>();
    private List<WorldStore.Media> media=List.of();
    private String query="";
    private int scroll,bottom;
    private boolean refresh;
    public void refresh(){refresh=true;}
    public MediaPickerScreen(InitiatorScreen parent,ScreenSpec draft){super(Component.literal("Choose media"));this.parent=parent;this.draft=draft;}
    protected void init(){
        items.clear();int w=Math.min(520,width-24),x=(width-w)/2;bottom=height-62;
        EditBox search=addRenderableWidget(new EditBox(font,x,30,w,20,Component.literal("Search media")));search.setHint(Component.literal("Search names or folders…"));search.setValue(query);search.setResponder(v->{query=v;scroll=0;layout();});
        media=MirrorClient.library.values().stream().sorted(Comparator.comparing(WorldStore.Media::folder).thenComparing(WorldStore.Media::name)).toList();
        for(var item:media){var button=addRenderableWidget(new MirrorButton(x,0,w,label(item),()->{toggle(item);for(int i=0;i<media.size();i++)items.get(i).setMessage(Component.literal(label(media.get(i))));}));button.setTooltip(Tooltip.create(Component.literal(item.name()+"\n"+item.kind()+(item.folder().isBlank()?"":" · "+item.folder()))));items.add(button);}
        addRenderableWidget(new MirrorButton(x,height-52,w/2-2,"Import a file…",parent::pickFile));
        addRenderableWidget(new MirrorButton(x+w/2+2,height-52,w/2-2,"Arrange playlist…",()->minecraft.setScreen(new PlaylistScreen(this,draft))));
        addRenderableWidget(new MirrorButton(x,height-28,w/2-2,"Back to settings",this::onClose));addRenderableWidget(new MirrorButton(x+w/2+2,height-28,w/2-2,"Play in world",()->{minecraft.setScreen(parent);parent.playSelection(true);}));layout();
    }
    private String label(WorldStore.Media item){boolean chosen=item.kind().equals("subtitle")?draft.subtitle.equals(item.id()):draft.playlist.contains(item.id());return (chosen?"✓ ":"+ ")+item.name()+" · "+item.kind();}
    private void toggle(WorldStore.Media item){
        if(item.kind().equals("subtitle")){draft.subtitle=draft.subtitle.equals(item.id())?"":item.id();return;}
        if(draft.playlist.contains(item.id()))PlaylistEdits.remove(draft,item.id());else draft.playlist.add(item.id());
    }
    private void layout(){List<Integer> matching=new ArrayList<>();String q=query.toLowerCase(Locale.ROOT);for(int i=0;i<media.size();i++){var m=media.get(i);items.get(i).visible=false;if((m.name()+" "+m.folder()+" "+m.kind()).toLowerCase(Locale.ROOT).contains(q))matching.add(i);}scroll=Math.clamp(scroll,0,Math.max(0,matching.size()*24-(bottom-58)));for(int n=0;n<matching.size();n++){var b=items.get(matching.get(n));int y=58+n*24-scroll;b.setY(y);b.visible=y>=58&&y+20<=bottom;}}
    public void tick(){if(refresh||media.size()!=MirrorClient.library.size()){refresh=false;boolean focus=getFocused() instanceof EditBox;int cursor=focus?((EditBox)getFocused()).getCursorPosition():0;rebuildWidgets();if(focus)for(var child:children())if(child instanceof EditBox search){setInitialFocus(search);search.setCursorPosition(Math.min(cursor,search.getValue().length()));break;}}}
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll-=(int)(dy*24);layout();return true;}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xf0202020);g.drawCenteredString(font,"Choose media · "+draft.playlist.size()+" selected",width/2,12,0xffffffff);if(items.stream().noneMatch(b->b.visible))g.drawCenteredString(font,media.isEmpty()?"Import a file to get started":"No matching media",width/2,72,0xffaaaaaa);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
