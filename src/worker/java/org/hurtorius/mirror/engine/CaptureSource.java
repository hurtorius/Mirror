package org.hurtorius.mirror.engine;

import com.google.gson.JsonObject;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.io.*;
import java.nio.file.*;

public final class CaptureSource implements EngineMain.Source {
    private final Thread thread;
    private volatile boolean alive=true;
    private final EngineMain engine;
    private final int monitor;
    private final WinDef.HWND window;
    private final Robot robot;
    private final long monitorHandle;
    public CaptureSource(EngineMain engine,JsonObject m)throws Exception{
        this.engine=engine;String selection=m.get("selection").getAsString();
        if(selection.startsWith("monitor-win:")&&Platform.isWindows()){monitorHandle=Long.parseUnsignedLong(selection.substring(12));monitor=-1;robot=null;window=null;}
        else if(selection.startsWith("monitor:")&&!Platform.isWindows()){monitorHandle=0;monitor=Integer.parseInt(selection.substring(8));GraphicsDevice[] devices=GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();if(monitor<0||monitor>=devices.length)throw new IllegalArgumentException("The selected display is unavailable.");robot=new Robot(devices[monitor]);window=null;}
        else if(selection.startsWith("window:")&&Platform.isWindows()){monitorHandle=0;long handle=Long.parseUnsignedLong(selection.substring(7));window=new WinDef.HWND(new Pointer(handle));if(!User32.INSTANCE.IsWindow(window)||!User32.INSTANCE.IsWindowVisible(window))throw new IllegalArgumentException("The selected window is unavailable.");var pid=new com.sun.jna.ptr.IntByReference();User32.INSTANCE.GetWindowThreadProcessId(window,pid);if(!m.has("pid")||pid.getValue()!=m.get("pid").getAsInt())throw new IllegalArgumentException("The selected window changed. Choose it again.");monitor=-1;robot=null;}
        else throw new IllegalArgumentException("Choose a display or window to share.");
        thread=new Thread(this::capture,"Mirror selected-source capture");thread.setDaemon(true);thread.start();
    }

    public static void sources(EngineMain engine){
        java.util.List<Map<String,Object>> sources=new ArrayList<>();GraphicsDevice[] devices=GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if(Platform.isWindows())User32.INSTANCE.EnumDisplayMonitors(null,null,(h,dc,rect,data)->{sources.add(Map.of("id","monitor-win:"+Long.toUnsignedString(Pointer.nativeValue(h.getPointer())),"name","Display "+(sources.size()+1)+" · "+(rect.right-rect.left)+" × "+(rect.bottom-rect.top),"pid",0));return 1;},null);
        else for(int i=0;i<devices.length;i++){Rectangle r=devices[i].getDefaultConfiguration().getBounds();sources.add(Map.of("id","monitor:"+i,"name","Display "+(i+1)+" · "+r.width+" × "+r.height,"pid",0));}
        if(Platform.isWindows())User32.INSTANCE.EnumWindows((h,data)->{char[] text=new char[512];User32.INSTANCE.GetWindowText(h,text,text.length);String title=Native.toString(text);if(User32.INSTANCE.IsWindowVisible(h)&&!title.isBlank()&&!title.equals("Program Manager")&&!title.equals("Mirror browser surface")){var pid=new com.sun.jna.ptr.IntByReference();User32.INSTANCE.GetWindowThreadProcessId(h,pid);sources.add(Map.of("id","window:"+Long.toUnsignedString(Pointer.nativeValue(h.getPointer())),"name",title,"pid",pid.getValue()));}return true;},null);
        engine.event("SOURCES","sources",sources);
    }
    private void capture(){while(alive){try{
        if(Platform.isWindows()){captureWindows();return;}
        BufferedImage image;
        if(window==null){Rectangle rect=GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[monitor].getDefaultConfiguration().getBounds();image=robot.createScreenCapture(rect);}
        else {if(!User32.INSTANCE.IsWindow(window)||!User32.INSTANCE.IsWindowVisible(window)){engine.event("CAPTURE_PAUSED","text","The shared window is closed or hidden.");Thread.sleep(300);continue;}image=GDI32Util.getScreenshot(window);}
        engine.image(image);Thread.sleep(83);
    }catch(Exception e){if(alive)engine.error("The selected source cannot be captured. Choose another window or display.");alive=false;}}}
    private void captureWindows()throws Exception{
        long handle=window==null?monitorHandle:Pointer.nativeValue(window.getPointer());
        try(WindowsCapture capture=new WindowsCapture(handle,window==null)){while(alive){if(window!=null&&!User32.INSTANCE.IsWindow(window))break;BufferedImage image=capture.frame();if(image!=null)engine.image(image);Thread.sleep(image==null?10:33);}}
        finally{if(alive)engine.event("CAPTURE_ENDED","text","The selected source stopped providing frames.");}
    }
    public void command(JsonObject m){}
    public void close(){alive=false;thread.interrupt();try{thread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
