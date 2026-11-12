package org.hurtorius.mirror.client;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Clean Mirror-face output for OBS Browser Source. Loopback only, with a per-run token. */
public final class CameraOutput {
    private static HttpServer server;
    private static ExecutorService executor;
    private static String token,id;
    private static volatile byte[] picture;
    /** Counts pictures; written after picture, so a reader that takes it first never pairs a new count with an old picture. */
    private static volatile long version;
    // Only this player's view reaches the output: private browser previews are keyed browser-<id> and never match.
    private static boolean matches(String screen){if(screen.equals(id))return true;var view=MirrorClient.views.get(id);return view!=null&&screen.equals(view.stream());}
    public static void accept(String screen,byte[] jpeg){if(server!=null&&matches(screen)){picture=jpeg;version++;}}
    public static void invalidate(String screen){if(matches(screen))picture=null;}
    /** OBS polls 24 times a second; a picture it already shows is answered with 304 instead of being sent again. */
    static int status(byte[] frame,String tag,String shown){return frame==null?204:tag.equals(shown)?304:200;}
    public static boolean active(String screen){return server!=null&&screen.equals(id);}
    public static void close(){if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();executor=null;server=null;picture=null;token=null;id=null;}
    public static void open(String screen){
        var allowed=MirrorClient.views.get(screen);if(allowed==null||!allowed.anchor().spec.live||((allowed.anchor().spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.WEB||allowed.anchor().spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.SHARE)&&allowed.epoch().isEmpty())||MirrorClient.emergency||MirrorClient.preferences.hidden){MirrorClient.notice("Enable a public screen before opening its camera output.");return;}
        try{
            // An output already running keeps its address and switches to this screen, so OBS needs no new URL.
            if(server==null){server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);token=UUID.randomUUID().toString();executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Mirror OBS content output");t.setDaemon(true);return t;});server.setExecutor(executor);
                server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();if(!exchange.getRequestMethod().equals("GET")||token==null||!path.startsWith("/"+token+"/")){exchange.sendResponseHeaders(404,-1);return;}exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                    if(path.endsWith("frame.jpg")){long count=version;byte[] frame=picture;String tag="\""+count+"\"";int status=status(frame,tag,exchange.getRequestHeaders().getFirst("If-None-Match"));if(status!=200){exchange.sendResponseHeaders(status,-1);return;}exchange.getResponseHeaders().set("Content-Type","image/jpeg");exchange.getResponseHeaders().set("ETag",tag);exchange.sendResponseHeaders(200,frame.length);exchange.getResponseBody().write(frame);}
                    else{String html="<!doctype html><meta charset=utf-8><style>html,body{margin:0;width:100%;height:100%;background:transparent;overflow:hidden}img{width:100%;height:100%;object-fit:contain}</style><img id=face alt='Mirror camera'><script>const image=document.getElementById('face');let old,tag;async function next(){let delay=42;try{const r=await fetch('frame.jpg',{cache:'no-store',headers:tag?{'If-None-Match':tag}:{}});if(r.status===204){image.style.display='none';tag=null}else if(r.ok){tag=r.headers.get('ETag');const url=URL.createObjectURL(await r.blob());image.src=url;image.style.display='block';if(old)URL.revokeObjectURL(old);old=url}}catch(e){image.style.display='none';tag=null;delay=250}setTimeout(next,delay)}next()</script>";byte[] b=html.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html;charset=utf-8");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; img-src 'self' blob:; connect-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);}
                }finally{exchange.close();}});server.start();}
            id=screen;picture=null;var spec=allowed.anchor().spec;TextureFrame current=MirrorClient.worldFrame(allowed);if(current!=null&&current.latest!=null)accept(screen,current.latest);
            if(spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.BLANK){var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,18,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",bytes);accept(screen,bytes.toByteArray());}
            if(spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.TEXT)TextSource.render(allowed.stream(),spec.text);
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/"+token+"/";Minecraft mc=Minecraft.getInstance();Screen parent=mc.screen;mc.setScreen(new Screen(Component.literal("Mirror OBS content output")){
                protected void init(){int w=Math.min(400,width-24),x=(width-w)/2;addRenderableWidget(new MirrorButton(x,height/2,w,"Copy local OBS address",()->{minecraft.keyboardHandler.setClipboard(url);MirrorClient.notice("OBS address copied. Add it as an OBS Browser Source.");}));addRenderableWidget(new MirrorButton(x,height/2+26,w,"Stop OBS output",()->{CameraOutput.close();minecraft.setScreen(parent);}));addRenderableWidget(new MirrorButton(x,height-28,w,"Back",()->minecraft.setScreen(parent)));}
                public void render(GuiGraphics g,int mx,int my,float delta){g.drawCenteredString(font,"Mirror OBS content output",width/2,18,0xffffffff);g.drawWordWrap(font,Component.literal("Add the copied address as an OBS Browser Source at 1280 × 720. It exports the screen content, without the Minecraft world or player. OBS can send it to its Virtual Camera."),(width-Math.min(400,width-24))/2,45,Math.min(400,width-24),0xffcccccc);super.render(g,mx,my,delta);}public void onClose(){minecraft.setScreen(parent);}public boolean isPauseScreen(){return false;}
            });
        }catch(IOException e){MirrorClient.notice("The local camera output could not start.");close();}
    }
}
