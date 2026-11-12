package org.hurtorius.mirror.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.hurtorius.mirror.core.FrameAssembler;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One waiting frame and one decoded frame per texture; only GPU upload runs on Minecraft's thread. */
public final class TextureFrame implements AutoCloseable {
    private static final ExecutorService DECODERS=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Mirror picture decoder");t.setDaemon(true);return t;});
    public final Identifier id;
    public int width=1280,height=720;
    public byte[] latest;
    public boolean browserPixels;
    private DynamicTexture texture;
    private boolean external;
    private final AtomicReference<byte[]> pending=new AtomicReference<>();
    private final AtomicReference<Request> waiting=new AtomicReference<>();
    private final AtomicReference<NativeImage> prepared=new AtomicReference<>();
    private final AtomicBoolean decoding=new AtomicBoolean();
    private volatile boolean closed,reportedFailure;
    private record Request(byte[] bytes,int resolution){}

    public TextureFrame(String id){this.id=Identifier.fromNamespaceAndPath("mirror","frames/"+id);}
    public TextureFrame(String id,com.mojang.blaze3d.pipeline.RenderTarget target){
        this(id);external=true;width=target.width;height=target.height;
        // A reflection spans its texture exactly: clamp so no opposite edge bleeds into the border, filter so distance does not shimmer.
        Minecraft.getInstance().getTextureManager().register(this.id,new net.minecraft.client.renderer.texture.AbstractTexture(){
            {texture=target.getColorTexture();textureView=target.getColorTextureView();sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);}
            @Override public void close(){texture=null;textureView=null;}
        });
    }
    public void accept(byte[] jpeg){
        if(closed)return;
        browserPixels=false;latest=jpeg;
        pending.set(jpeg);
        CameraOutput.accept(id.getPath().substring("frames/".length()),jpeg);
    }
    public void upload(){
        if(external)return;
        if(closed)return;
        byte[] bytes=pending.getAndSet(null);
        if(bytes!=null){
            Request request=new Request(bytes,MirrorClient.preferences.resolution);
            waiting.set(request);
            startDecoder();
        }
        NativeImage pixels=prepared.getAndSet(null);
        if(pixels==null)return;
        try{
            width=pixels.getWidth();height=pixels.getHeight();
            if(texture!=null&&(texture.getPixels().getWidth()!=width||texture.getPixels().getHeight()!=height)){
                Minecraft.getInstance().getTextureManager().release(id);texture=null;
            }
            // Pages and videos are minified onto the screen: filter linearly and never wrap the opposite edge in.
            if(texture==null){texture=new DynamicTexture(()->"Mirror shared frame",pixels){{sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);}};Minecraft.getInstance().getTextureManager().register(id,texture);}
            else{texture.setPixels(pixels);texture.upload();}
            pixels=null;
        }finally{if(pixels!=null)pixels.close();}
    }
    private void startDecoder(){if(!closed&&decoding.compareAndSet(false,true))DECODERS.execute(this::decode);}
    private void decode(){
        try{
            Request request=waiting.getAndSet(null);
            if(request==null||closed)return;
            NativeImage image=null;
            try{
                image=decodeJpeg(request.bytes);
                if(image.getWidth()>request.resolution){
                    NativeImage scaled=new NativeImage(request.resolution,Math.max(1,image.getHeight()*request.resolution/image.getWidth()),false);
                    image.resizeSubRectTo(0,0,image.getWidth(),image.getHeight(),scaled);image.close();image=scaled;
                }
                synchronized(this){if(!closed){NativeImage stale=prepared.getAndSet(image);image=null;if(stale!=null)stale.close();}}
                reportedFailure=false;
            }catch(Exception e){if(!closed&&!reportedFailure){reportedFailure=true;org.slf4j.LoggerFactory.getLogger("mirror").warn("Shared picture decode failed",e);MirrorClient.notice("A Mirror picture could not be decoded. The next valid frame will retry.");}}
            finally{if(image!=null)image.close();}
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
            // stb's RGBA output already has NativeImage's layout; the image adopts and later frees it.
            NativeImage image=new NativeImage(NativeImage.Format.RGBA,w.get(0),h.get(0),true,MemoryUtil.memAddress(rgba));
            rgba=null;return image;
        }finally{if(rgba!=null)STBImage.stbi_image_free(rgba);MemoryUtil.memFree(input);}
    }
    public boolean available(){return !closed&&(external||texture!=null);}
    public synchronized void close(){
        closed=true;pending.set(null);waiting.set(null);latest=null;
        NativeImage image=prepared.getAndSet(null);if(image!=null)image.close();
        if(texture!=null||external){Minecraft.getInstance().getTextureManager().release(id);texture=null;}
        ReflectionRenderType.remove(id);
    }
}
