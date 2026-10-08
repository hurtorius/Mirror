package org.cef.browser;

import org.cef.*;
import org.cef.handler.*;
import org.cef.callback.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JPanel;

/** CPU offscreen rendering; no JOGL window or Minecraft launch flags required. */
public final class MirrorBrowser extends CefBrowser_N implements CefRenderHandler {
    private final JPanel component=new JPanel();
    private volatile int width,height;
    private final Consumer<BufferedImage> paint;
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> listeners=new CopyOnWriteArrayList<>();
    private BufferedImage base,popup;
    private Rectangle popupRect=new Rectangle();
    public MirrorBrowser(CefClient client,String url,int width,int height,Consumer<BufferedImage> paint){
        super(client,url,null,null,null,new CefBrowserSettings());this.width=width;this.height=height;this.paint=paint;
        component.setSize(width,height);
    }
    public synchronized void resize(int w,int h){width=w;height=h;base=null;popup=null;component.setSize(w,h);wasResized(w,h);}
    public void createImmediately(){createBrowser(getClient(),0,getUrl(),true,false,component,getRequestContext());wasResized(width,height);}
    public synchronized java.util.concurrent.CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution){return java.util.concurrent.CompletableFuture.completedFuture(base);}
    public Component getUIComponent(){return component;}
    public CefRenderHandler getRenderHandler(){return this;}
    protected CefBrowser_N createDevToolsBrowser(CefClient c,String u,CefRequestContext r,CefBrowser_N p,Point q){throw new UnsupportedOperationException("Developer tools are disabled in Mirror.");}
    public Rectangle getViewRect(CefBrowser b){return new Rectangle(0,0,width,height);}
    public Point getScreenPoint(CefBrowser b,Point p){return new Point(p);}
    public boolean getScreenInfo(CefBrowser b,CefScreenInfo info){info.Set(1,32,8,false,getViewRect(b),getViewRect(b));return true;}
    public synchronized void onPopupShow(CefBrowser b,boolean show){if(!show)popup=null;}
    public void onPopupSize(CefBrowser b,Rectangle rect){popupRect=new Rectangle(rect);}
    public synchronized void onPaint(CefBrowser b,boolean isPopup,Rectangle[] dirty,ByteBuffer buffer,int w,int h){
        if(w<1||h<1||w>1920||h>1920||(long)w*h>2073600||buffer.remaining()<w*h*4)return;
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);int[] rgb=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
        ByteBuffer pixels=buffer.duplicate();for(int i=0;i<rgb.length;i++){int blue=pixels.get()&255,green=pixels.get()&255,red=pixels.get()&255;pixels.get();rgb[i]=(red<<16)|(green<<8)|blue;}
        if(isPopup)popup=image;else base=image;
        if(base!=null){BufferedImage composed=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=composed.createGraphics();g.drawImage(base,0,0,width,height,null);if(popup!=null)g.drawImage(popup,popupRect.x,popupRect.y,null);g.dispose();paint.accept(composed);}
    }
    public void addOnPaintListener(Consumer<CefPaintEvent> listener){listeners.add(listener);}
    public void setOnPaintListener(Consumer<CefPaintEvent> listener){listeners.clear();listeners.add(listener);}
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener){listeners.remove(listener);}
    public boolean onCursorChange(CefBrowser b,int cursor){return false;}
    public boolean startDragging(CefBrowser b,CefDragData data,int mask,int x,int y){return false;}
    public void updateDragCursor(CefBrowser b,int operation){}
    public void pointer(int event,int x,int y,int button,int mods){int awtButton=button==0?MouseEvent.BUTTON1:button==1?MouseEvent.BUTTON3:MouseEvent.BUTTON2;sendMouseEvent(new MouseEvent(component,event,System.currentTimeMillis(),mods,x,y,1,false,event==MouseEvent.MOUSE_MOVED?0:awtButton));}
    public void wheel(int x,int y,int amount,int mods){sendMouseWheelEvent(new MouseWheelEvent(component,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),mods,x,y,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,amount));}
    public void key(int event,int code,char character,int mods){sendKeyEvent(new KeyEvent(component,event,System.currentTimeMillis(),mods,code,character));}
}
