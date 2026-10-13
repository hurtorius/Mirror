package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.*;

/** Explicit OS setup guidance; this screen cannot run privileged commands. */
final class LinuxBrowserSetupScreen extends Screen {
    private final Screen parent;
    private String message="This Linux system blocks the browser's sandbox. The setup command gives only this Mirror browser permission to use it. An administrator needs to apply it once for this installation.";
    LinuxBrowserSetupScreen(Screen parent){super(Component.literal("Browser setup"));this.parent=parent;}
    protected void init(){
        addRenderableWidget(new MirrorButton(width/2-104,height-30,100,"Copy command",()->{
            try{Path file=minecraft.gameDirectory.toPath().resolve("mirror-runtime/1.0.0/browser-setup-command.txt");if(Files.size(file)>20000)throw new java.io.IOException();minecraft.keyboardHandler.setClipboard(Files.readString(file));message="Copied. Review the command and run it in your terminal, then reopen Browse. It installs a rule for this browser only; it does not turn off AppArmor or browser sandboxing.";}
            catch(Exception e){message="The setup file is unavailable. Open Browse once to prepare the browser, then return here.";}
        }));
        addRenderableWidget(new MirrorButton(width/2+4,height-30,100,"Back",this::onClose));
    }
    public void render(GuiGraphics g,int x,int y,float delta){g.drawString(font,"Mirror • Hurtorius",12,12,0xffffffff);g.drawWordWrap(font,Component.literal(message),18,48,width-36,0xffcccccc);super.render(g,x,y,delta);}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
