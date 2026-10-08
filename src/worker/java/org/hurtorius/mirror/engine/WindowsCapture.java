package org.hurtorius.mirror.engine;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Target-only Windows.Graphics.Capture; leaves the system's capture border enabled. */
final class WindowsCapture implements AutoCloseable {
    interface D3D extends StdCallLibrary {
        int D3D11CreateDevice(Pointer adapter,int driver,Pointer software,int flags,Pointer levels,int count,int sdk,PointerByReference device,IntByReference feature,PointerByReference context);
        int CreateDirect3D11DeviceFromDXGIDevice(Pointer device,PointerByReference result);
    }
    static final D3D D3D=Native.load("d3d11",D3D.class);
    @Structure.FieldOrder({"width","height"}) public static class Size extends Structure {public int width,height;public Size(){}public Size(int w,int h){width=w;height=h;}public static class Value extends Size implements Structure.ByValue {public Value(int w,int h){super(w,h);}}}
    @Structure.FieldOrder({"data","rowPitch","depthPitch"}) public static class Mapped extends Structure {public Pointer data;public int rowPitch,depthPitch;}
    private Pointer device,context,direct,item,pool,session,staging;
    private int width,height;
    WindowsCapture(long handle,boolean monitor){
        WindowsNative.check(WindowsNative.RUNTIME.RoInitialize(1));
        Pointer factory=WindowsNative.factory("Windows.Graphics.Capture.GraphicsCaptureItem","3628e81b-3cac-4c60-b7f4-23ce0e0c3356");
        try{PointerByReference result=new PointerByReference();WindowsNative.check(WindowsNative.call(factory,monitor?4:3,new Pointer(handle),WindowsNative.guid("79c3f95b-31f7-4ec2-a464-632ef5d30760"),result));item=result.getValue();}finally{WindowsNative.release(factory);}
        PointerByReference d=new PointerByReference(),c=new PointerByReference();WindowsNative.check(D3D.D3D11CreateDevice(null,1,null,32,null,0,7,d,new IntByReference(),c));device=d.getValue();context=c.getValue();
        Pointer dxgi=WindowsNative.query(device,"54ec77fa-1377-44e6-8c32-88fd5f44c84c");PointerByReference inspect=new PointerByReference();try{WindowsNative.check(D3D.CreateDirect3D11DeviceFromDXGIDevice(dxgi,inspect));direct=WindowsNative.query(inspect.getValue(),"a37624ab-8d5f-4650-9d3e-9eae3d9bc670");}finally{WindowsNative.release(dxgi);WindowsNative.release(inspect.getValue());}
        Size size=new Size();size.write();WindowsNative.check(WindowsNative.call(item,7,size.getPointer()));size.read();
        Pointer statics=WindowsNative.factory("Windows.Graphics.Capture.Direct3D11CaptureFramePool","589b103f-6bbc-5df5-a991-02e28b3b66d5");
        try{PointerByReference result=new PointerByReference();WindowsNative.check(WindowsNative.call(statics,6,direct,87,2,new Size.Value(size.width,size.height),result));pool=result.getValue();}finally{WindowsNative.release(statics);}
        PointerByReference result=new PointerByReference();WindowsNative.check(WindowsNative.call(pool,10,item,result));session=result.getValue();WindowsNative.check(WindowsNative.call(session,6));
    }
    BufferedImage frame(){
        PointerByReference result=new PointerByReference();WindowsNative.check(WindowsNative.call(pool,7,result));Pointer frame=result.getValue();if(frame==null)return null;Pointer surface=null,access=null,texture=null;
        try{Size content=new Size();content.write();WindowsNative.check(WindowsNative.call(frame,8,content.getPointer()));content.read();if(content.width<1||content.height<1)return null;
            result=new PointerByReference();WindowsNative.check(WindowsNative.call(frame,6,result));surface=result.getValue();access=WindowsNative.query(surface,"a9b3d012-3df2-4ee3-b8d1-8695f457d3c1");result=new PointerByReference();WindowsNative.check(WindowsNative.call(access,3,WindowsNative.guid("6f15aaf2-d208-4e89-9ab4-489535d34f9c"),result));texture=result.getValue();
            Memory desc=new Memory(44);WindowsNative.call(texture,10,desc);int w=desc.getInt(0),h=desc.getInt(4);if(w<1||h<1||w>8192||h>8192)throw new IllegalStateException("The selected source is too large.");
            if(staging==null||width!=w||height!=h){WindowsNative.release(staging);desc.setInt(28,3);desc.setInt(32,0);desc.setInt(36,0x20000);desc.setInt(40,0);result=new PointerByReference();WindowsNative.check(WindowsNative.call(device,5,desc,null,result));staging=result.getValue();width=w;height=h;}
            WindowsNative.call(context,47,staging,texture);Mapped map=new Mapped();map.write();WindowsNative.check(WindowsNative.call(context,14,staging,0,1,0,map.getPointer()));map.read();
            try{int sw=Math.min(w,content.width),sh=Math.min(h,content.height);double scale=Math.min(1,Math.min(1280.0/sw,720.0/sh));int tw=Math.max(16,(int)(sw*scale)),th=Math.max(16,(int)(sh*scale));BufferedImage image=new BufferedImage(tw,th,BufferedImage.TYPE_INT_RGB);int[] rgb=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();for(int y=0;y<th;y++){byte[] row=map.data.getByteArray((long)(y*sh/th)*map.rowPitch,sw*4);for(int x=0;x<tw;x++){int i=x*sw/tw*4;rgb[y*tw+x]=((row[i+2]&255)<<16)|((row[i+1]&255)<<8)|(row[i]&255);}}return image;}
            finally{WindowsNative.call(context,15,staging,0);}
        }finally{WindowsNative.release(texture);WindowsNative.release(access);WindowsNative.release(surface);WindowsNative.close(frame);WindowsNative.release(frame);}
    }
    public void close(){try{WindowsNative.close(session);WindowsNative.close(pool);}finally{for(Pointer p:new Pointer[]{staging,session,pool,item,direct,context,device})WindowsNative.release(p);WindowsNative.RUNTIME.RoUninitialize();}}
}
