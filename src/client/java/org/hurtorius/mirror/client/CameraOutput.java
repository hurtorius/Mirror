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
    private static volatile boolean invalidated;
    private static boolean matches(String screen){if(screen.equals(id))return true;var view=MirrorClient.views.get(id);return view!=null&&screen.equals(view.stream());}
    public static void accept(String screen,byte[] jpeg){if(matches(screen)&&!invalidated)picture=jpeg;}
    public static void invalidate(String screen){if(matches(screen)){invalidated=true;picture=null;}}
    public static boolean active(String screen){return server!=null&&screen.equals(id);}
    public static void close(){if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();executor=null;server=null;picture=null;token=null;id=null;}
    public static void open(String screen){
        var allowed=MirrorClient.views.get(screen);if(allowed==null||!allowed.anchor().spec.live||((allowed.anchor().spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.WEB||allowed.anchor().spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.SHARE)&&allowed.epoch().isEmpty())||MirrorClient.emergency||MirrorClient.preferences.hidden){MirrorClient.notice("Enable a public screen before opening its camera output.");return;}
        close();id=screen;token=UUID.randomUUID().toString();invalidated=false;
        MirrorClient.View view=MirrorClient.views.get(screen);TextureFrame current=view==null?null:MirrorClient.worldFrame(view);if(current!=null&&current.latest!=null)picture=current.latest;
        try{if(allowed.anchor().spec.source==org.hurtorius.mirror.core.ScreenSpec.Source.BLANK){var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,18,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",bytes);picture=bytes.toByteArray();}server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Mirror OBS content output");t.setDaemon(true);return t;});server.setExecutor(executor);
            server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();if(!exchange.getRequestMethod().equals("GET")||token==null||!path.startsWith("/"+token+"/")){exchange.sendResponseHeaders(404,-1);return;}exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                if(path.endsWith("frame.jpg")){byte[] frame=picture;if(frame==null){exchange.sendResponseHeaders(204,-1);return;}exchange.getResponseHeaders().set("Content-Type","image/jpeg");exchange.sendResponseHeaders(200,frame.length);exchange.getResponseBody().write(frame);}
                else{String html="<!doctype html><meta charset=utf-8><style>html,body{margin:0;width:100%;height:100%;background:transparent;overflow:hidden}img{width:100%;height:100%;object-fit:contain}</style><img id=face alt='Mirror camera'><script>const image=document.getElementById('face');let old;async function next(){let delay=42;try{const r=await fetch('frame.jpg',{cache:'no-store'});if(r.status===204){image.style.display='none'}else if(r.ok){const url=URL.createObjectURL(await r.blob());image.src=url;image.style.display='block';if(old)URL.revokeObjectURL(old);old=url}}catch(e){image.style.display='none';delay=250}setTimeout(next,delay)}next()</script>";byte[] b=html.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html;charset=utf-8");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; img-src 'self' blob:; connect-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);}
            }finally{exchange.close();}});server.start();String url="http://127.0.0.1:"+server.getAddress().getPort()+"/"+token+"/";Minecraft mc=Minecraft.getInstance();Screen parent=mc.screen;mc.setScreen(new Screen(Component.literal("Mirror OBS content output")){
                protected void init(){int w=Math.min(400,width-24),x=(width-w)/2;addRenderableWidget(new MirrorButton(x,height/2,w,"Copy local OBS address",()->{minecraft.keyboardHandler.setClipboard(url);MirrorClient.notice("OBS address copied. Add it as an OBS Browser Source.");}));addRenderableWidget(new MirrorButton(x,height/2+26,w,"Stop OBS output",()->{CameraOutput.close();minecraft.setScreen(parent);}));addRenderableWidget(new MirrorButton(x,height-28,w,"Back",()->minecraft.setScreen(parent)));}
                public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xf014243c);g.drawCenteredString(font,"Mirror OBS content output",width/2,18,0xffd6bdf7);g.drawWordWrap(font,Component.literal("Add the copied address as an OBS Browser Source at 1280 × 720. It exports the screen content, without the Minecraft world or player. OBS can send it to its Virtual Camera."),(width-Math.min(400,width-24))/2,45,Math.min(400,width-24),0xffa2b1c8);super.render(g,mx,my,delta);}public void onClose(){minecraft.setScreen(parent);}public boolean isPauseScreen(){return false;}
            });
        }catch(IOException e){MirrorClient.notice("The local camera output could not start.");close();}
    }
}
