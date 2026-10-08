package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.WorldStore;
import java.util.*;

public final class LibraryScreen extends Screen {
    private final Screen parent;private String query="";private int page;private boolean refresh;
    public void refresh(){refresh=true;}
    public void tick(){if(refresh){refresh=false;boolean focused=getFocused() instanceof EditBox;int cursor=focused?((EditBox)getFocused()).getCursorPosition():0;rebuildWidgets();if(focused)for(var child:children())if(child instanceof EditBox box){setInitialFocus(box);box.setCursorPosition(Math.min(cursor,box.getValue().length()));break;}}}
    public LibraryScreen(Screen parent){super(Component.literal("World media library"));this.parent=parent;}
    protected void init(){
        int w=Math.min(620,width-20),x=(width-w)/2;
        EditBox search=addRenderableWidget(new EditBox(font,x,34,w,20,Component.literal("Search media")));
        search.setValue(query);search.setResponder(v->{query=v;page=0;});
        addRenderableWidget(new MirrorButton(x,59,w,"Search names and folders",this::rebuildWidgets));
        List<WorldStore.Media> items=MirrorClient.library.values().stream().filter(m->(m.name()+" "+m.folder()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).sorted(Comparator.comparing(WorldStore.Media::folder).thenComparing(WorldStore.Media::name)).toList();
        int perPage=Math.max(1,(height-154)/26);page=Math.clamp(page,0,Math.max(0,(items.size()-1)/perPage));
        for(int i=page*perPage;i<Math.min(items.size(),(page+1)*perPage);i++){
            var item=items.get(i);int y=86+(i-page*perPage)*26;
            addRenderableWidget(new MirrorButton(x,y,w-64,item.name()+" · "+item.folder(),()->minecraft.setScreen(new MediaDetailsScreen(this,item))));
            addRenderableWidget(new MirrorButton(x+w-60,y,60,"Remove",()->minecraft.setScreen(new ConfirmScreen(yes->{minecraft.setScreen(this);if(yes)MirrorClient.send("DELETE_MEDIA","media",item.id());},Component.literal("Remove "+item.name()+"?"),Component.literal("Only unused media can be removed. This removes it from the world.")))));
        }
        addRenderableWidget(new MirrorButton(x,height-62,65,"Previous",()->{page--;rebuildWidgets();}));
        addRenderableWidget(new MirrorButton(x+69,height-62,65,"Next",()->{page++;rebuildWidgets();}));
        addRenderableWidget(new MirrorButton(x+138,height-62,Math.max(90,w-138),"Tidy unused media…",()->minecraft.setScreen(new ConfirmScreen(yes->{minecraft.setScreen(this);if(yes)MirrorClient.send("TIDY");},Component.literal("Remove all unused world media?"),Component.literal("Save your playlists and shows first. Referenced media is kept. This frees disk space.")))));
        addRenderableWidget(new MirrorButton(x,height-28,w,"Back",this::onClose));
    }
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xe0101010);g.drawCenteredString(font,"World media: "+MirrorClient.worldBytes/(1024*1024)+" / "+MirrorClient.quota/(1024*1024)+" MB",width/2,14,MirrorClient.worldBytes>MirrorClient.quota*.8?0xffffbb66:0xffffffff);g.drawString(font,font.plainSubstrByWidth(MirrorClient.notice,width-20),10,height-40,0xffffcc77);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
