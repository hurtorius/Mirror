package org.hurtorius.mirror.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.hurtorius.mirror.core.FrameAssembler;
import org.hurtorius.mirror.core.ScreenSpec;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One waiting frame and one decoded frame per texture; only GPU upload runs on Minecraft's thread. */
public final class TextureFrame implements AutoCloseable {
    private static final ExecutorService DECODERS=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Mirror picture decoder");t.setDaemon(true);return t;});
    private static final LongAdder decodeNanos=new LongAdder(),uploadNanos=new LongAdder(),decodedCount=new LongAdder(),uploadedCount=new LongAdder(),droppedCount=new LongAdder();
    public final Identifier id;
    public int width=1280,height=720;
    public long updated,version;
    public byte[] latest;
    public boolean browserPixels;
    private DynamicTexture texture;
    private final AtomicReference<byte[]> pending=new AtomicReference<>();
    private final AtomicReference<Request> waiting=new AtomicReference<>();
    private final AtomicReference<NativeImage> prepared=new AtomicReference<>();
    private final AtomicBoolean decoding=new AtomicBoolean();
    private volatile boolean closed,reportedFailure;
    private record Request(byte[] bytes,int resolution,double brightness,double contrast,double saturation){}

    public TextureFrame(String id){this.id=Identifier.fromNamespaceAndPath("mirror","frames/"+id);}
    public void accept(byte[] jpeg){
        if(closed)return;
        browserPixels=false;latest=jpeg;
        if(pending.getAndSet(jpeg)!=null)droppedCount.increment();
        CameraOutput.accept(id.getPath().substring("frames/".length()),jpeg);
    }
    public void upload(){upload(null);}
    public void upload(ScreenSpec style){
        if(closed)return;
        byte[] bytes=pending.getAndSet(null);
        if(bytes!=null){
            Request request=new Request(bytes,MirrorClient.preferences.resolution,style==null?1:style.brightness,style==null?1:style.contrast,style==null?1:style.saturation);
            if(waiting.getAndSet(request)!=null)droppedCount.increment();
            startDecoder();
        }
        NativeImage pixels=prepared.getAndSet(null);
        if(pixels==null)return;
        long started=System.nanoTime();
        try{
            width=pixels.getWidth();height=pixels.getHeight();
            if(texture!=null&&(texture.getPixels().getWidth()!=width||texture.getPixels().getHeight()!=height)){
                Minecraft.getInstance().getTextureManager().release(id);texture=null;
            }
            if(texture==null){texture=new DynamicTexture(()->"Mirror shared frame",pixels);Minecraft.getInstance().getTextureManager().register(id,texture);}
            else{texture.setPixels(pixels);texture.upload();}
            pixels=null;updated=System.currentTimeMillis();version++;uploadedCount.increment();
        }finally{if(pixels!=null)pixels.close();uploadNanos.add(System.nanoTime()-started);}
    }
    private void startDecoder(){if(!closed&&decoding.compareAndSet(false,true))DECODERS.execute(this::decode);}
    private void decode(){
        try{
            Request request=waiting.getAndSet(null);
            if(request==null||closed)return;
            long started=System.nanoTime();NativeImage image=null;
            try{
                image=decodeJpeg(request.bytes);
                if(image.getWidth()>request.resolution){
                    NativeImage scaled=new NativeImage(request.resolution,Math.max(1,image.getHeight()*request.resolution/image.getWidth()),false);
                    image.resizeSubRectTo(0,0,image.getWidth(),image.getHeight(),scaled);image.close();image=scaled;
                }
                if(request.brightness!=1||request.contrast!=1||request.saturation!=1)style(image,request);
                synchronized(this){if(!closed){NativeImage stale=prepared.getAndSet(image);image=null;if(stale!=null){stale.close();droppedCount.increment();}}}
                decodedCount.increment();reportedFailure=false;
            }catch(Exception e){if(!closed&&!reportedFailure){reportedFailure=true;org.slf4j.LoggerFactory.getLogger("mirror").warn("Shared picture decode failed",e);MirrorClient.notice("A Mirror picture could not be decoded. The next valid frame will retry.");}}
            finally{if(image!=null)image.close();decodeNanos.add(System.nanoTime()-started);}
        }finally{decoding.set(false);if(waiting.get()!=null)startDecoder();}
    }
    private static NativeImage decodeJpeg(byte[] jpeg)throws IOException{
        if(!FrameAssembler.validJpeg(jpeg))throw new IOException("Invalid frame dimensions");
        ByteBuffer input=MemoryUtil.memAlloc(jpeg.length),rgba=null;
        try(MemoryStack stack=MemoryStack.stackPush()){
            input.put(jpeg).flip();var w=stack.mallocInt(1);var h=stack.mallocInt(1);var channels=stack.mallocInt(1);
            rgba=STBImage.stbi_load_from_memory(input,w,h,channels,4);
            if(rgba==null)throw new IOException("JPEG decoding failed: "+STBImage.stbi_failure_reason());
            if(w.get(0)<16||h.get(0)<16||w.get(0)>1920||h.get(0)>1920||(long)w.get(0)*h.get(0)>2073600)throw new IOException("Frame dimensions exceeded the limit");
            NativeImage image=new NativeImage(w.get(0),h.get(0),false);
            MemoryUtil.memCopy(MemoryUtil.memAddress(rgba),image.getPointer(),(long)w.get(0)*h.get(0)*4);
            return image;
        }finally{if(rgba!=null)STBImage.stbi_image_free(rgba);MemoryUtil.memFree(input);}
    }
    private static void style(NativeImage image,Request style){
        if(style.saturation==1){
            int[] table=new int[256];for(int i=0;i<256;i++)table[i]=clamp(((i-127.5)*style.contrast+127.5)*style.brightness);
            long start=image.getPointer(),end=start+(long)image.getWidth()*image.getHeight()*4;
            for(long at=start;at<end;at+=4){int c=MemoryUtil.memGetInt(at);MemoryUtil.memPutInt(at,(c&0xff000000)|(table[(c>>16)&255]<<16)|(table[(c>>8)&255]<<8)|table[c&255]);}
            return;
        }
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int c=image.getPixel(x,y);double r=(c>>16)&255,g=(c>>8)&255,b=c&255,luma=.2126*r+.7152*g+.0722*b;
            r=(((luma+(r-luma)*style.saturation)-127.5)*style.contrast+127.5)*style.brightness;
            g=(((luma+(g-luma)*style.saturation)-127.5)*style.contrast+127.5)*style.brightness;
            b=(((luma+(b-luma)*style.saturation)-127.5)*style.contrast+127.5)*style.brightness;
            image.setPixel(x,y,(c&0xff000000)|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b));
        }
    }
    private static int clamp(double value){return Math.max(0,Math.min(255,(int)value));}
    public static Map<String,Long> diagnostics(){return Map.of("decoded",decodedCount.sum(),"uploaded",uploadedCount.sum(),"decodeNanos",decodeNanos.sum(),"uploadNanos",uploadNanos.sum(),"superseded",droppedCount.sum());}
    public boolean available(){return texture!=null;}
    public synchronized void close(){
        closed=true;pending.set(null);waiting.set(null);latest=null;
        NativeImage image=prepared.getAndSet(null);if(image!=null)image.close();
        if(texture!=null){Minecraft.getInstance().getTextureManager().release(id);texture=null;}
    }
}
