package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
public final class ViewerSettingsScreen extends Screen {
    private final List<AbstractWidget> options=new ArrayList<>();
    private int scroll;
    private final Screen parent;
    public ViewerSettingsScreen(Screen parent){super(Component.literal("Your Mirror settings"));this.parent=parent;}
    protected void init(){options.clear();int w=Math.min(340,width-24),x=(width-w)/2;ClientPreferences p=MirrorClient.preferences;
        if(MirrorClient.sharing())option(new MirrorButton(x,0,w,"Stop sharing",()->{MirrorClient.stopSharing();rebuildWidgets();}));
        option(new MirrorButton(x,0,w,p.hidden?"Show screens":"Hide and mute screens",()->{p.hidden=!p.hidden;changed();}));
        option(new AbstractSliderButton(x,0,w,20,Component.literal("Volume: "+Math.round(p.volume*100)+"%"),p.volume){protected void updateMessage(){setMessage(Component.literal("Volume: "+Math.round(value*100)+"%"));}protected void applyValue(){p.volume=value;}});
        option(new MirrorButton(x,0,w,"Screen limit: "+p.maxScreens+"…",()->minecraft.setScreen(new ChoiceScreen<>(this,"Nearby screen limit",List.of(new ChoiceScreen.Option<>(1,"1 screen","Lowest rendering load"),new ChoiceScreen.Option<>(2,"2 screens","A small display area"),new ChoiceScreen.Option<>(4,"4 screens","Several nearby displays"),new ChoiceScreen.Option<>(8,"8 screens","More rendering work")),p.maxScreens,n->{p.maxScreens=n;MirrorClient.savePreferences();}))));
        option(new MirrorButton(x,0,w,"Picture quality: "+p.resolution+" px…",()->minecraft.setScreen(new ChoiceScreen<>(this,"Picture quality",List.of(new ChoiceScreen.Option<>(640,"Low · 640 px","Lower texture memory"),new ChoiceScreen.Option<>(960,"Medium · 960 px","Balanced picture detail"),new ChoiceScreen.Option<>(1280,"High · 1280 px","Most picture detail")),p.resolution,n->{p.resolution=n;MirrorClient.savePreferences();}))));
        option(new MirrorButton(x,0,w,"Reduced motion: "+(p.reducedMotion?"On":"Off"),()->{p.reducedMotion=!p.reducedMotion;changed();}));
        option(new MirrorButton(x,0,w,"Glow: "+(p.glow?"On":"Off"),()->{p.glow=!p.glow;changed();}));
        option(new MirrorButton(x,0,w,"Positional sound: "+(p.positional?"On":"Off"),()->{p.positional=!p.positional;changed();}));
        option(new MirrorButton(x,0,w,"Reports…",()->minecraft.setScreen(new ReportsScreen(this))));
        option(new MirrorButton(x,0,w,"Clear local media cache",MirrorClient::clearCache));
        addRenderableWidget(new MirrorButton(x,height-28,w,"Done",this::onClose));layout();
    }
    private void option(AbstractWidget widget){options.add(addRenderableWidget(widget));}
    private void layout(){scroll=Math.clamp(scroll,0,Math.max(0,options.size()*24-(height-76)));for(int i=0;i<options.size();i++){var b=options.get(i);int y=36+i*24-scroll;b.setY(y);b.visible=y>=36&&y+20<=height-40;}}
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll-=(int)(dy*24);layout();return true;}
    private void changed(){MirrorClient.savePreferences();rebuildWidgets();}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xf0202020);g.drawCenteredString(font,"Your Mirror settings",width/2,14,0xffffffff);super.render(g,mx,my,delta);}
    public void onClose(){MirrorClient.savePreferences();minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
