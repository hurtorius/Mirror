package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.ScreenSpec;

/** A user-initiated, private route through a site's normal browser sign-in. */
final class BrowserAccountScreen extends Screen {
    private final InitiatorScreen parent;
    private final String id;
    private final EngineProcess engine;
    private final ScreenSpec spec;
    private boolean returning;
    BrowserAccountScreen(InitiatorScreen parent,String id,EngineProcess engine){
        super(Component.literal("Browser account"));this.parent=parent;this.id=id;this.engine=engine;
        MirrorClient.stopCapturesForAccount();parent.makeBrowserPrivate();parent.syncBrowserAddress();spec=parent.preview().copy();spec.live=false;
        MirrorClient.clearBrowserPreview(id);engine.send("SIGN_IN","spec",spec);
    }
    protected void init(){addRenderableWidget(new MirrorButton(width/2-90,height-30,180,"Return to Mirror",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xff141414);g.drawString(font,"Mirror • Hurtorius",12,12,0xffffffff);
        g.drawWordWrap(font,Component.literal("Complete sign-in in the Chrome window, then return here. This uses this Mirror's browser profile. Its picture and sound are not being shared."),18,45,width-36,0xffdddddd);
        super.render(g,mx,my,delta);
    }
    public void onClose(){if(returning)return;returning=true;MirrorClient.clearBrowserPreview(id);engine.send("BROWSER","spec",spec);engine.send("RATE","fps",spec.browserFps);minecraft.setScreen(new BrowserControlScreen(parent,id,engine));}
    public boolean isPauseScreen(){return false;}
}
