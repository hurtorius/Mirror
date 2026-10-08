package org.hurtorius.mirror.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
public final class PreviewScreen extends Screen {
    private final InitiatorScreen parent;private final DraftScreenPreview preview=new DraftScreenPreview();
    public PreviewScreen(InitiatorScreen parent){super(Component.literal("Screen preview"));this.parent=parent;}
    protected void init(){for(var b:preview.controls(16,32,width-32))addRenderableWidget(b);addRenderableWidget(new MirrorButton(width/2-70,height-28,140,"Back to settings",this::onClose));}
    public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xff10141c);TextureFrame frame=parent.previewFrame();preview.render(g,font,parent.previewConfig(),frame!=null&&frame.available()?frame:null,"Choose a source",10,16,width-20,height-54);super.render(g,mx,my,delta);}
    public boolean mouseClicked(MouseButtonEvent e,boolean twice){return super.mouseClicked(e,twice)||preview.beginDrag(e.x(),e.y(),e.button());}
    public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){return preview.drag(e.button(),dx,dy)||super.mouseDragged(e,dx,dy);}
    public boolean mouseReleased(MouseButtonEvent e){return preview.endDrag(e.button())||super.mouseReleased(e);}
    public boolean mouseScrolled(double x,double y,double dx,double dy){return preview.scroll(x,y,dy)||super.mouseScrolled(x,y,dx,dy);}
    public void removed(){preview.close();}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
