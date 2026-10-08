package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.ScreenSpec;
import java.util.Collections;
public final class PlaylistScreen extends Screen {
    private final Screen parent;private final ScreenSpec spec;private int page;
    public PlaylistScreen(Screen parent,ScreenSpec spec){super(Component.literal("Playlist order"));this.parent=parent;this.spec=spec;}
    protected void init(){int w=Math.min(540,width-20),x=(width-w)/2,count=Math.max(1,(height-100)/26);page=Math.clamp(page,0,Math.max(0,(spec.playlist.size()-1)/count));for(int i=page*count;i<Math.min(spec.playlist.size(),(page+1)*count);i++){int n=i,y=38+(i-page*count)*26;var item=MirrorClient.library.get(spec.playlist.get(i));String name=item==null?"Missing media":item.name();addRenderableWidget(new MirrorButton(x,y,w-105,(i==spec.item?"▶ ":"")+(i+1)+" · "+name,()->{spec.item=n;rebuildWidgets();}));var up=addRenderableWidget(new MirrorButton(x+w-101,y,31,"↑",()->{Collections.swap(spec.playlist,n,n-1);if(spec.item==n)spec.item--;else if(spec.item==n-1)spec.item++;rebuildWidgets();}));up.active=n>0;var down=addRenderableWidget(new MirrorButton(x+w-66,y,31,"↓",()->{Collections.swap(spec.playlist,n,n+1);if(spec.item==n)spec.item++;else if(spec.item==n+1)spec.item--;rebuildWidgets();}));down.active=n+1<spec.playlist.size();addRenderableWidget(new MirrorButton(x+w-31,y,31,"−",()->{org.hurtorius.mirror.core.PlaylistEdits.remove(spec,spec.playlist.get(n));rebuildWidgets();}));}
        addRenderableWidget(new MirrorButton(x,height-56,w/2-2,"Previous page",()->{page--;rebuildWidgets();}));addRenderableWidget(new MirrorButton(x+w/2,height-56,w/2,"Next page",()->{page++;rebuildWidgets();}));addRenderableWidget(new MirrorButton(x,height-28,w,"Back to settings",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xee101010);g.drawCenteredString(font,"Playlist · click an item to select it",width/2,16,0xffffffff);if(spec.playlist.isEmpty())g.drawCenteredString(font,"No media selected. Go back and choose media first.",width/2,48,0xffaaaaaa);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
