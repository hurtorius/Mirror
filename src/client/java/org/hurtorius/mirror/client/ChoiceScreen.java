package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** A visible list of choices instead of a button that silently cycles values. */
public final class ChoiceScreen<T> extends Screen {
    public record Option<T>(T value,String label,String description) {}
    private final Screen parent;
    private final List<Option<T>> options;
    private final T selected;
    private final Consumer<T> choose;
    private final List<MirrorButton> buttons=new ArrayList<>();
    private EditBox search;
    private String query="";
    private int scroll,top=58,bottom;
    public ChoiceScreen(Screen parent,String title,List<Option<T>> options,T selected,Consumer<T> choose){super(Component.literal(title));this.parent=parent;this.options=List.copyOf(options);this.selected=selected;this.choose=choose;}
    protected void init(){
        buttons.clear();int w=Math.min(420,width-24),x=(width-w)/2;bottom=height-38;
        search=addRenderableWidget(new EditBox(font,x,30,w,20,Component.literal("Filter choices")));search.setHint(Component.literal("Search choices…"));search.setValue(query);
        search.setResponder(value->{query=value;scroll=0;layout();});
        for(var option:options){var b=addRenderableWidget(new MirrorButton(x,0,w,(Objects.equals(option.value,selected)?"✓ ":"")+option.label,()->{choose.accept(option.value);minecraft.setScreen(parent);}));b.setTooltip(Tooltip.create(Component.literal(option.description)));buttons.add(b);}
        addRenderableWidget(new MirrorButton(x,height-28,w,"Back",this::onClose));layout();
    }
    private void layout(){
        String needle=query.toLowerCase(Locale.ROOT);List<Integer> visible=new ArrayList<>();
        for(int i=0;i<options.size();i++){var o=options.get(i);if((o.label+" "+o.description).toLowerCase(Locale.ROOT).contains(needle))visible.add(i);buttons.get(i).visible=false;}
        scroll=Math.clamp(scroll,0,Math.max(0,visible.size()*24-(bottom-top)));
        for(int n=0;n<visible.size();n++){var b=buttons.get(visible.get(n));int y=top+n*24-scroll;b.setY(y);b.visible=y>=top&&y+20<=bottom;}
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll-=(int)(dy*24);layout();return true;}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xf0202020);g.drawCenteredString(font,title,width/2,12,0xffffffff);if(buttons.stream().noneMatch(b->b.visible))g.drawCenteredString(font,"No matching choices",width/2,70,0xffaaaaaa);super.render(g,mx,my,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
