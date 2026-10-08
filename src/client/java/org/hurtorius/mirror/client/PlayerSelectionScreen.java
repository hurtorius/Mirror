package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

public final class PlayerSelectionScreen extends Screen {
    private final Screen parent;
    private final List<String> selected;
    private final List<MirrorButton> buttons=new ArrayList<>();
    private List<Map.Entry<String,String>> players;
    private String query="";
    private int scroll;
    public PlayerSelectionScreen(Screen parent,List<String> selected,String title){super(Component.literal(title));this.parent=parent;this.selected=selected;}
    protected void init(){int w=Math.min(400,width-24),x=(width-w)/2;buttons.clear();players=new ArrayList<>(MirrorClient.players.entrySet());players.sort(Map.Entry.comparingByValue());var search=addRenderableWidget(new EditBox(font,x,30,w,20,Component.literal("Search players")));search.setHint(Component.literal("Search players…"));search.setValue(query);search.setResponder(v->{query=v;scroll=0;layout();});for(var p:players){var b=addRenderableWidget(new MirrorButton(x,0,w,label(p),()->{if(!selected.remove(p.getKey()))selected.add(p.getKey());for(int i=0;i<players.size();i++)buttons.get(i).setMessage(Component.literal(label(players.get(i))));}));buttons.add(b);}addRenderableWidget(new MirrorButton(x,height-28,w,"Use selection",this::onClose));layout();}
    private String label(Map.Entry<String,String> p){return (selected.contains(p.getKey())?"✓ ":"+ ")+p.getValue();}
    private void layout(){List<Integer> match=new ArrayList<>();for(int i=0;i<players.size();i++){buttons.get(i).visible=false;if(players.get(i).getValue().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))match.add(i);}scroll=Math.clamp(scroll,0,Math.max(0,match.size()*24-(height-96)));for(int n=0;n<match.size();n++){var b=buttons.get(match.get(n));int y=58+n*24-scroll;b.setY(y);b.visible=y>=58&&y+20<=height-38;}}
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll-=(int)(dy*24);layout();return true;}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xf0202020);g.drawCenteredString(font,title,width/2,12,0xffffffff);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
