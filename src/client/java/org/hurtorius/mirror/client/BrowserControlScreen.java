package org.hurtorius.mirror.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.BrowserAddress;
import java.awt.event.MouseEvent;

/** Browser input and publication stay together inside Minecraft. */
public final class BrowserControlScreen extends Screen {
    private final InitiatorScreen parent;
    private final String id;
    private final EngineProcess engine;
    private EditBox address;
    private MirrorButton visibility,done,sound,accountButton;
    private int px,py,pw,ph;
    private org.hurtorius.mirror.core.BrowserViewport viewport;
    private boolean focused;
    private String feedback="";
    public BrowserControlScreen(InitiatorScreen parent,String id,EngineProcess engine){super(Component.literal("Mirror browser"));this.parent=parent;this.id=id;this.engine=engine;}
    protected void init(){
        viewport=org.hurtorius.mirror.core.BrowserViewport.of(parent.preview());int availableH=height-87,availableW=width-20;double scale=Math.min(availableW/(double)viewport.width(),availableH/(double)viewport.height());pw=Math.max(1,(int)(viewport.width()*scale));ph=Math.max(1,(int)(viewport.height()*scale));px=(width-pw)/2;py=42;
        addRenderableWidget(new MirrorButton(10,12,28,"←",()->engine.send("BACK")));
        addRenderableWidget(new MirrorButton(42,12,28,"→",()->engine.send("FORWARD")));
        addRenderableWidget(new MirrorButton(74,12,48,"Reload",this::reload));
        sound=addRenderableWidget(new MirrorButton(126,12,40,"Mute",parent::toggleBrowserSound));
        address=addRenderableWidget(new EditBox(font,170,12,width-240,20,Component.literal("Address or search")));
        address.setMaxLength(2048);address.setValue(parent.browserAddress());address.setCursorPosition(0);address.setHint(Component.literal("Website or search"));
        addRenderableWidget(new MirrorButton(width-66,12,56,"Go",this::navigate));
        boolean account=System.getProperty("os.name","").startsWith("Windows");
        int cell=(width-(account?36:32))/(account?5:4);
        addRenderableWidget(new MirrorButton(10,height-28,cell,"Back",this::onClose));
        visibility=addRenderableWidget(new MirrorButton(14+cell,height-28,cell,"Screen: Off",()->{if(MirrorClient.isBrowserPublished(id))parent.makeBrowserPrivate();else parent.showCurrentBrowser(false);}));
        addRenderableWidget(new MirrorButton(account?26+cell*4:22+cell*3,height-28,cell,"Reports",()->minecraft.setScreen(new ReportsScreen(this))));
        if(account){accountButton=addRenderableWidget(new MirrorButton(22+cell*3,height-28,cell,"Account",this::openAccount));accountButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Open Chrome privately for this Mirror’s sign-in. The screen turns off first.")));}
        done=addRenderableWidget(new MirrorButton(18+cell*2,height-28,cell,"Done",parent::doneBrowsing));
    }
    private void openAccount(){if(parent.isSaving()||MirrorClient.browserShowPending(id))return;try{var active=MirrorClient.hasBrowser(id)?engine:MirrorClient.openBrowser(id,parent.preview());minecraft.setScreen(new BrowserAccountScreen(parent,id,active));}catch(IllegalArgumentException error){feedback=error.getMessage();}}
    private void reload(){if(MirrorClient.hasBrowser(id))engine.send("RELOAD");else parent.browseAt(address.getValue());}
    private void navigate(){try{String url=BrowserAddress.resolve(address.getValue());if(!MirrorClient.hasBrowser(id)){parent.browseAt(url);return;}engine.send("NAVIGATE","url",url);address.setValue(url);address.setFocused(false);setFocused(null);focused=true;feedback="";}catch(IllegalArgumentException e){feedback=e.getMessage();}}
    public void tick(){
        boolean live=MirrorClient.isBrowserPublished(id),pending=MirrorClient.browserShowPending(id);
        visibility.setMessage(Component.literal(pending?"Starting…":live?"Screen: On":"Screen: Off"));
        done.setMessage(Component.literal("Done"));if(accountButton!=null)accountButton.active=!pending&&!parent.isSaving();
        visibility.active=!pending&&!parent.isSaving()&&MirrorClient.browserPreviewReady(id);done.active=!pending&&!parent.isSaving();sound.setMessage(Component.literal(parent.previewVolume()>0?"Mute":"Sound"));sound.active=!parent.isSaving();
    }
    public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xff000000);TextureFrame frame=MirrorClient.browserFrame(id);
        if(frame!=null&&frame.available())g.blit(RenderPipelines.GUI_TEXTURED,frame.id,px,py,0,0,pw,ph,frame.width,frame.height,frame.width,frame.height);
        else g.drawCenteredString(font,!MirrorClient.hasBrowser(id)?"Browser stopped. Choose Reload.":MirrorClient.status.isBlank()?"Opening browser…":MirrorClient.status,width/2,height/2,0xffffffff);
        String message=feedback;

        g.drawString(font,font.plainSubstrByWidth(message,width-20),10,height-42,0xffcccccc);super.render(g,mx,my,delta);
    }
    public void addressChanged(String screen,String url){if(id.equals(screen)&&address!=null&&!address.isFocused()){address.setValue(url);address.setCursorPosition(0);}}
    private boolean within(double x,double y){return x>=px&&x<px+pw&&y>=py&&y<py+ph;}
    private int x(double x){return Math.clamp((int)((x-px)*viewport.width()/pw),0,viewport.width()-1);}
    private int y(double y){return Math.clamp((int)((y-py)*viewport.height()/ph),0,viewport.height()-1);}
    public boolean mouseClicked(MouseButtonEvent e,boolean twice){
        if(within(e.x(),e.y())){focused=true;address.setFocused(false);setFocused(null);engine.send("POINTER","event",MouseEvent.MOUSE_PRESSED,"x",x(e.x()),"y",y(e.y()),"button",e.button(),"mods",mods(e.modifiers()));return true;}
        focused=false;return super.mouseClicked(e,twice);
    }
    public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){if(focused){engine.send("POINTER","event",MouseEvent.MOUSE_MOVED,"x",x(e.x()),"y",y(e.y()),"button",e.button(),"mods",mods(e.modifiers()));return true;}return super.mouseDragged(e,dx,dy);}
    public boolean mouseReleased(MouseButtonEvent e){if(focused){engine.send("POINTER","event",MouseEvent.MOUSE_RELEASED,"x",x(e.x()),"y",y(e.y()),"button",e.button(),"mods",mods(e.modifiers()));return true;}return super.mouseReleased(e);}
    public void mouseMoved(double x,double y){if(within(x,y))engine.send("POINTER","event",MouseEvent.MOUSE_MOVED,"x",x(x),"y",y(y),"button",0,"mods",0);}
    public boolean mouseScrolled(double x,double y,double dx,double dy){if(within(x,y)){engine.send("WHEEL","x",x(x),"y",y(y),"amount",-dy,"mods",0);return true;}return super.mouseScrolled(x,y,dx,dy);}
    public boolean keyPressed(KeyEvent e){
        if((e.modifiers()&2)!=0&&e.key()==76){focused=false;setFocused(address);address.setFocused(true);address.setCursorPosition(address.getValue().length());address.setHighlightPos(0);return true;}
        if(e.key()==257&&address.isFocused()){navigate();return true;}
        if(e.key()==256&&!focused){onClose();return true;}
        if(!focused)return super.keyPressed(e);
        if((e.modifiers()&2)!=0&&e.key()==86){String text=minecraft.keyboardHandler.getClipboard();if(text.length()>4096)text=text.substring(0,4096);engine.send("TEXT","text",text);return true;}
        int code=awt(e.key());if(code!=0)engine.send("KEY","event",java.awt.event.KeyEvent.KEY_PRESSED,"code",code,"mods",mods(e.modifiers()));
        if(e.key()==256)focused=false;return true;
    }
    public boolean keyReleased(KeyEvent e){if(focused){int code=awt(e.key());if(code!=0)engine.send("KEY","event",java.awt.event.KeyEvent.KEY_RELEASED,"code",code,"mods",mods(e.modifiers()));return true;}return super.keyReleased(e);}
    public boolean charTyped(CharacterEvent e){if(focused){engine.send("KEY","event",java.awt.event.KeyEvent.KEY_TYPED,"code",0,"character",e.codepoint(),"mods",mods(e.modifiers()));return true;}return super.charTyped(e);}
    private static int mods(int glfw){return ((glfw&1)!=0?java.awt.event.InputEvent.SHIFT_DOWN_MASK:0)|((glfw&2)!=0?java.awt.event.InputEvent.CTRL_DOWN_MASK:0)|((glfw&4)!=0?java.awt.event.InputEvent.ALT_DOWN_MASK:0);}
    private static int awt(int glfw){if(glfw>=32&&glfw<=90)return glfw;return switch(glfw){case 256->27;case 257->10;case 258->9;case 259->8;case 261->127;case 262->39;case 263->37;case 264->40;case 265->38;case 268->36;case 269->35;case 266->33;case 267->34;default->0;};}
    public void onClose(){parent.syncBrowserAddress();minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
