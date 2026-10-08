package org.hurtorius.mirror.client;


import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Native, local-only layout preview. The caller supplies an already authorized current frame.
 * This helper cannot open sources, request media, publish pixels, or keep stale source textures.
 */
public final class DraftScreenPreview {
    private final DraftPreviewGeometry geometry = new DraftPreviewGeometry();
    private DraftPreviewGeometry.Camera camera = DraftPreviewGeometry.Camera.reset();
    private int sceneX, sceneY, sceneWidth, sceneHeight;
    private boolean dragging, initialized;
    private static int nextTexture;
    private TextureFrame styled;
    private PictureStyleKey styleKey;
    private final org.hurtorius.mirror.core.ScreenSpec style=new org.hurtorius.mirror.core.ScreenSpec();
    public void close(){if(styled!=null)styled.close();styled=null;styleKey=null;}
    private TextureFrame styled(TextureFrame source,PreviewConfig c){
        if(source==null||c.source==PreviewConfig.Source.BLANK||c.source==PreviewConfig.Source.TEXT||c.brightness==1&&c.contrast==1&&c.saturation==1){close();return source;}
        var key=new PictureStyleKey(source,source.version,MirrorClient.preferences.resolution,c.brightness,c.contrast,c.saturation);
        if(styleKey!=null&&styleKey.source()!=source)close();
        if(source.latest!=null&&!key.equals(styleKey)){
            if(styled==null)styled=new TextureFrame("preview-style-"+(nextTexture++));
            style.brightness=c.brightness;style.contrast=c.contrast;style.saturation=c.saturation;
            styled.accept(source.latest);styleKey=key;
        }
        if(styled!=null){styled.upload(style);if(styled.available())return styled;}
        return source;
    }
    private float screenYaw,screenPitch;

    /** Add these vanilla buttons during Screen.init, at panel x + 4 and panel y + 16. */
    public List<Button> controls(int x, int y, int width) {
        int gap=2, usable=Math.max(4,width);
        boolean wide=usable>=238, tiny=usable<120;
        String[] labels=wide?new String[]{"Front","Side","Turn","Fit","−","+"}
                :new String[]{tiny?">":"Turn",tiny?"R":"Fit","−","+"};
        Runnable front=()->camera=new DraftPreviewGeometry.Camera(screenYaw,-screenPitch,camera.zoom());
        Runnable side=()->camera=new DraftPreviewGeometry.Camera(screenYaw+90,18,camera.zoom());
        Runnable turn=()->orbit(30,0),reset=()->camera=new DraftPreviewGeometry.Camera(screenYaw,-screenPitch);
        Runnable[] actions=wide?new Runnable[]{front,side,turn,reset,()->zoom(-1),()->zoom(1)}
                :new Runnable[]{turn,reset,()->zoom(-1),()->zoom(1)};
        String[] help=wide?new String[]{"View the screen from the front","View the screen from the side","Turn the view 30 degrees","Fit the screen and reset the view","Zoom out","Zoom in"}
                :new String[]{"Turn the view 30 degrees","Fit the screen and reset the view","Zoom out","Zoom in"};
        int zoomWidth=Math.min(24,Math.max(12,usable/6));
        int buttonWidth=Math.max(1,(usable-gap*(labels.length-1)-zoomWidth*2)/(labels.length-2));
        List<Button> buttons=new ArrayList<>();
        for(int i=0;i<labels.length;i++) {
            Runnable action=actions[i];
            int w=i<labels.length-2?buttonWidth:zoomWidth;
            buttons.add(Button.builder(Component.literal(labels[i]),button->action.run()).bounds(x,y,w,18)
                    .tooltip(Tooltip.create(Component.literal(help[i]))).build());
            x+=w+gap;
        }
        return buttons;
    }

    public boolean beginDrag(double x,double y,int button) {
        if(button!=0||!contains(x,y))return false;
        dragging=true;return true;
    }
    public boolean drag(int button,double dx,double dy) {
        if(!dragging||button!=0)return false;
        orbit((float)dx*.6f,(float)dy*.5f);return true;
    }
    public boolean endDrag(int button) {
        if(button!=0||!dragging)return false;
        dragging=false;return true;
    }
    public void cancelDrag() { dragging=false;sceneWidth=sceneHeight=0; }
    public boolean scroll(double x,double y,double amount) {
        if(!contains(x,y))return false;
        if(Double.isFinite(amount))zoom((float)Math.clamp(amount,-8,8));
        return true;
    }
    private boolean contains(double x,double y) {return x>=sceneX&&x<sceneX+sceneWidth&&y>=sceneY&&y<sceneY+sceneHeight;}
    private void orbit(float yaw,float elevation) {camera=new DraftPreviewGeometry.Camera(camera.yaw()+yaw,camera.elevation()+elevation,camera.zoom());}
    private void zoom(float steps) {camera=new DraftPreviewGeometry.Camera(camera.yaw(),camera.elevation(),camera.zoom()*(float)Math.pow(1.2,steps));}

    public void render(GuiGraphics g, Font font, PreviewConfig c, TextureFrame currentTexture,
                       String missingSourceText, int x, int y, int width, int height) {
        if(width<1||height<1)return;
        screenYaw=c.yaw;screenPitch=c.pitch;
        if(!initialized){camera=new DraftPreviewGeometry.Camera(screenYaw,-screenPitch);initialized=true;}
        if(c.facing==PreviewConfig.Facing.EACH_VIEWER||c.facing==PreviewConfig.Facing.BILLBOARD){c.yaw=camera.yaw();if(c.facing==PreviewConfig.Facing.EACH_VIEWER)c.pitch=-camera.elevation();}
        g.fill(x,y,x+width,y+height,0xE010141C);
        g.drawCenteredString(font,"Preview",x+width/2,y+4,0xFFBED6E6);
        sceneX=x;sceneY=y+36;sceneWidth=width;sceneHeight=Math.max(1,height-36);
        // No identifier, pixels, texture handle, or controller survives this call in preview state.
        currentTexture=styled(currentTexture,c);
        boolean picture=currentTexture!=null&&currentTexture.available()&&c.source!=PreviewConfig.Source.TEXT&&c.source!=PreviewConfig.Source.BLANK;
        var scene=geometry.project(c,camera,width,sceneHeight,picture?currentTexture.width:0,picture?currentTexture.height:0,MirrorClient.preferences.glow);
        g.enableScissor(x,sceneY,x+width,sceneY+sceneHeight);
        var text=c.source==PreviewConfig.Source.TEXT?NativeTextPreview.capture(font,c,scene,c.text):List.<NativeTextPreview.Batch>of();
        g.guiRenderState.submitPicturesInPictureState(new DraftPreviewState(x,sceneY,x+width,sceneY+sceneHeight,
                g.scissorStack.peek(),scene,picture?currentTexture.id:null,c.enabled,MirrorClient.preferences.glow,text,c.light));
        g.disableScissor();
    }

}
