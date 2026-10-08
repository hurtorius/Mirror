package org.hurtorius.mirror.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.hurtorius.mirror.core.Whiteboard;
import java.util.*;
import java.util.concurrent.*;

public final class WhiteboardScreen extends Screen {
    private static final ExecutorService ENCODER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Mirror whiteboard encoder");t.setDaemon(true);return t;});
    private final Screen parent;private final String id;private final Whiteboard board=new Whiteboard(1280,720);
    private int x,y,w,h;private double zoom=1,panX,panY;private boolean drawing,dirty=true,encoding,saving,closing,closed;
    private String savedDrawing="",feedback="";private boolean loadingSaved;
    private TextureFrame preview;
    private EditBox ink,size,text;
    private MirrorButton undo,redo;
    public WhiteboardScreen(Screen parent,String id){super(Component.literal("Mirror whiteboard"));this.parent=parent;this.id=id;if(parent instanceof InitiatorScreen editor)savedDrawing=editor.drawingId();loadingSaved=!savedDrawing.isEmpty();}
    protected void init(){
        if(preview==null)preview=new TextureFrame("drawing-preview-"+id);dirty=true;
        x=10;y=85;w=width-20;h=Math.max(16,height-123);int cell=(width-20)/6;
        addRenderableWidget(new MirrorButton(10,27,cell-3,label(board.tool),()->{board.end();drawing=false;minecraft.setScreen(new ChoiceScreen<>(this,"Drawing tool",Arrays.stream(Whiteboard.Tool.values()).map(t->new ChoiceScreen.Option<>(t,label(t),t==Whiteboard.Tool.SELECT?"Drag a selection, then drag inside it to move it":t==Whiteboard.Tool.PAN?"Drag the canvas; scroll to zoom":label(t))).toList(),board.tool,t->board.tool=t));}));
        undo=addRenderableWidget(new MirrorButton(10+cell,27,cell-3,"Undo",()->{board.undo();dirty=true;}));
        redo=addRenderableWidget(new MirrorButton(10+cell*2,27,cell-3,"Redo",()->{board.redo();dirty=true;}));
        addRenderableWidget(new MirrorButton(10+cell*3,27,cell-3,"Clear",()->{board.clear();loadingSaved=false;dirty=true;}));
        addRenderableWidget(new MirrorButton(10+cell*4,27,cell-3,board.filled?"Filled":"Outline",()->{board.filled=!board.filled;rebuildWidgets();}));
        addRenderableWidget(new MirrorButton(10+cell*5,27,cell-3,"Fit",()->{zoom=1;panX=panY=0;}));
        ink=addRenderableWidget(new EditBox(font,10,59,64,20,Component.literal("Ink color")));ink.setMaxLength(6);ink.setValue(String.format("%06X",board.color));ink.setResponder(v->{try{if(v.length()==6)board.color=Integer.parseInt(v,16);}catch(NumberFormatException ignored){}});
        addRenderableWidget(new MirrorButton(80,59,58,"Colors…",()->{int[] colors={0x202020,0xffffff,0xe53935,0xfb8c00,0xfdd835,0x43a047,0x00acc1,0x1e88e5,0x8e24aa,0xec407a,0x757575};String[] names={"Black","White","Red","Orange","Yellow","Green","Cyan","Blue","Purple","Pink","Gray"};var options=new ArrayList<ChoiceScreen.Option<Integer>>();for(int i=0;i<colors.length;i++)options.add(new ChoiceScreen.Option<>(colors[i],names[i],String.format("#%06X",colors[i])));minecraft.setScreen(new ChoiceScreen<>(this,"Ink color",options,board.color,color->board.color=color));}));
        size=addRenderableWidget(new EditBox(font,144,59,32,20,Component.literal("Stroke size")));size.setMaxLength(2);size.setValue(Integer.toString(board.size));size.setResponder(v->{try{int n=Integer.parseInt(v);if(n>=1&&n<=64)board.size=n;}catch(NumberFormatException ignored){}});
        text=addRenderableWidget(new EditBox(font,182,59,width-192,20,Component.literal("Text to place")));text.setMaxLength(256);text.setValue(board.text);text.setHint(Component.literal("Text tool: type, then click canvas"));text.setResponder(v->board.text=v);
        addRenderableWidget(new MirrorButton(10,height-28,100,"Save drawing",()->save(false)));
        addRenderableWidget(new MirrorButton(width-110,height-28,100,"Back",this::onClose));
    }
    private static String label(Whiteboard.Tool tool){return switch(tool){case PICK_COLOR->"Pick color";case SELECT->"Select / move";default->tool.name().charAt(0)+tool.name().substring(1).toLowerCase(Locale.ROOT);};}
    private double scale(){return Math.min(w/1280.0,h/720.0)*zoom;}
    private double left(){return x+w/2.0-640*scale()+panX;}
    private double top(){return y+h/2.0-360*scale()+panY;}
    private int bx(double px){return (int)((px-left())/scale());}
    private int by(double py){return (int)((py-top())/scale());}
    private boolean within(double px,double py){return px>=x&&px<x+w&&py>=y&&py<y+h&&bx(px)>=0&&bx(px)<1280&&by(py)>=0&&by(py)<720;}
    private void save(boolean close){if(loadingSaved||saving)return;board.end();drawing=false;if(!MirrorClient.isPublishing(id)){feedback="The board is no longer connected. Reopen it from the Initiator.";if(close){Reports.add(feedback);closed=true;minecraft.setScreen(parent);}return;}saving=true;closing=close;flush();}
    private void flush(){
        if(encoding)return;
        if(!dirty){if(saving){MirrorClient.send("BOARD_SAVE","id",id);saving=false;feedback="Drawing sent for saving";if(closing){closed=true;MirrorClient.stopScreenSharing(id);minecraft.setScreen(parent);}}return;}
        var image=board.snapshot();dirty=false;encoding=true;
        ENCODER.execute(()->{byte[] jpeg=null;String error=null;try{jpeg=encode(image);if(!org.hurtorius.mirror.core.FrameAssembler.validJpeg(jpeg))throw new java.io.IOException("Drawing is too detailed to share. Reduce detail and try again.");}catch(Exception e){error=e.getMessage();}byte[] result=jpeg;String failure=error;
            Minecraft.getInstance().execute(()->{encoding=false;if(closed)return;if(failure!=null){dirty=true;saving=false;closing=false;feedback=failure;Reports.add(failure);return;}if(preview!=null)preview.accept(result);MirrorClient.submitDrawingFrame(id,result);if(saving)flush();});
        });
    }
    private static byte[] encode(java.awt.image.BufferedImage image)throws java.io.IOException{return org.hurtorius.mirror.core.FrameEncoder.encode(image,.9f);}
    public void tick(){
        if(loadingSaved){var saved=MirrorClient.loadDrawing(savedDrawing);if(saved!=null){board.load(saved);loadingSaved=false;dirty=true;}}
        undo.active=!saving&&board.canUndo();redo.active=!saving&&board.canRedo();
        if(!loadingSaved&&MirrorClient.isPublishing(id))flush();
        if(preview!=null)preview.upload(new org.hurtorius.mirror.core.ScreenSpec());
    }
    public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xff141414);g.drawString(font,"Mirror • Hurtorius",10,10,0xffffffff);
        g.drawString(font,"Color",10,49,0xffcccccc);g.drawString(font,"Size",144,49,0xffcccccc);g.drawString(font,"Text",182,49,0xffcccccc);
        g.fill(x,y,x+w,y+h,0xff252525);g.enableScissor(x,y,x+w,y+h);
        int left=(int)left(),top=(int)top(),pw=(int)(1280*scale()),ph=(int)(720*scale());
        if(preview!=null&&preview.available())g.blit(RenderPipelines.GUI_TEXTURED,preview.id,left,top,0,0,pw,ph,preview.width,preview.height,preview.width,preview.height);else g.fill(left,top,left+pw,top+ph,0xffffffff);
        if(board.selection!=null&&board.tool==Whiteboard.Tool.SELECT){var r=board.selection;int a=(int)(left+r.x*scale()),b=(int)(top+r.y*scale()),c=(int)(a+r.width*scale()),d=(int)(b+r.height*scale());g.fill(a,b,c,b+1,0xff0088ff);g.fill(a,d-1,c,d,0xff0088ff);g.fill(a,b,a+1,d,0xff0088ff);g.fill(c-1,b,c,d,0xff0088ff);}
        g.disableScissor();String status=saving?"Saving…":loadingSaved?"Loading saved drawing…":feedback;
        if(!status.isBlank())g.drawString(font,font.plainSubstrByWidth(status,Math.max(1,width-235)),118,height-22,0xffcccccc);super.render(g,mx,my,delta);
    }
    public boolean mouseClicked(MouseButtonEvent e,boolean twice){
        if(super.mouseClicked(e,twice))return true;
        if(!saving&&!loadingSaved&&MirrorClient.isPublishing(id)&&within(e.x(),e.y())&&e.button()==0){setFocused(null);drawing=true;board.begin(bx(e.x()),by(e.y()));if(board.tool==Whiteboard.Tool.PICK_COLOR)ink.setValue(String.format("%06X",board.color));dirty=true;return true;}return false;
    }
    public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){if(drawing){if(board.tool==Whiteboard.Tool.PAN){panX+=dx;panY+=dy;boundPan();}else{board.drag(bx(e.x()),by(e.y()));dirty=true;}return true;}return super.mouseDragged(e,dx,dy);}
    public boolean mouseReleased(MouseButtonEvent e){if(drawing){board.end();drawing=false;dirty=true;return true;}return super.mouseReleased(e);}
    public boolean mouseScrolled(double px,double py,double dx,double dy){if(px>=x&&px<x+w&&py>=y&&py<y+h){double before=scale(),cx=(px-left())/before,cy=(py-top())/before;zoom=Math.clamp(zoom*Math.pow(1.2,dy),1,8);panX=px-x-w/2.0+(640-cx)*scale();panY=py-y-h/2.0+(360-cy)*scale();boundPan();return true;}return super.mouseScrolled(px,py,dx,dy);}
    private void boundPan(){panX=Math.clamp(panX,-640*scale(),640*scale());panY=Math.clamp(panY,-360*scale(),360*scale());}
    public boolean keyPressed(KeyEvent e){if(!(getFocused() instanceof EditBox)&&!saving&&(e.modifiers()&2)!=0){if(e.key()==90){if((e.modifiers()&1)!=0)board.redo();else board.undo();dirty=true;return true;}if(e.key()==89){board.redo();dirty=true;return true;}}return super.keyPressed(e);}
    public void removed(){if(preview!=null){preview.close();preview=null;}}
    public void onClose(){if(loadingSaved){closed=true;MirrorClient.stopScreenSharing(id);minecraft.setScreen(parent);}else save(true);}
    public boolean isPauseScreen(){return false;}
}
