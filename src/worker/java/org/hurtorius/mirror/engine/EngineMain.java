package org.hurtorius.mirror.engine;

import com.google.gson.*;
import org.hurtorius.mirror.core.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Local authenticated child JVM. No web server, remote control or user telemetry. */
public final class EngineMain {
    private DataOutputStream out;
    private volatile boolean running=true;
    private volatile Source source;
    private volatile JsonObject pendingTime;
    private final AtomicReference<Object> picture=new AtomicReference<>();
    private final Semaphore pictureReady=new Semaphore(0);
    private volatile int frameFps=60;
    private volatile long framesWritten;
    public long framesWritten(){return framesWritten;}
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private Path runtime;
    private WindowsAudio audio;
    private String audioRequest="";
    public interface Source extends AutoCloseable {void command(JsonObject m)throws Exception;default boolean audio(JsonObject m){return false;}default Map<String,Object> audioStatus(){return Map.of();}default void close()throws Exception{}}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Mirror engine needs its local port and runtime directory.");
        EngineMain engine=new EngineMain();engine.runtime=Path.of(args[1]);
        try(Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),Integer.parseInt(args[0]))){socket.setTcpNoDelay(true);
            engine.out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            engine.event("AUTH","token",System.getenv("MIRROR_ENGINE_TOKEN"));
            Thread encoder=new Thread(engine::encode,"Mirror image encoder");encoder.setDaemon(true);encoder.start();
            engine.event("READY");
            while(engine.running){int length=in.readInt();if(length<1||length>24000)throw new IOException("Invalid engine message.");byte[] bytes=in.readNBytes(length);if(bytes.length!=length)break;JsonObject m=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();String type=m.get("type").getAsString();
                if(type.equals("STOP")){engine.running=false;break;}
                if(type.equals("PICK_FILE")){engine.work.submit(()->engine.pick(m));continue;}
                if(type.equals("PROBE_MEDIA")){engine.work.submit(()->MediaProbe.run(engine,m));continue;}
                if(type.equals("TRANSCODE")){engine.work.submit(()->Transcoder.run(engine,m));continue;}
                if(type.equals("LICENSE_INFO")){engine.work.submit(()->{try{org.bytedeco.javacv.FFmpegFrameGrabber.tryLoad();engine.event("LICENSE_INFO","license",org.bytedeco.ffmpeg.global.avutil.avutil_license().getString(),"configuration",org.bytedeco.ffmpeg.global.avutil.avutil_configuration().getString());}catch(Exception e){engine.error("Media runtime information could not be read.");}});continue;}
                if(Set.of("BROWSER","SIGN_IN","MEDIA","SCREEN").contains(type)){engine.pendingTime=null;engine.work.submit(()->{try{engine.closeSource();engine.source=switch(type){case "BROWSER"->com.sun.jna.Platform.isWindows()?new ChromeBrowser(engine,m):new BrowserSource(engine,m);case "SIGN_IN"->new ChromeSignIn(engine,m);case "MEDIA"->new MediaSource(engine,m);default->new CaptureSource(engine,m);};if(engine.pendingTime!=null)engine.source.command(engine.pendingTime);engine.event("SOURCE_READY");}catch(Throwable e){engine.event("SOURCE_FAILED","text",e instanceof IOException&&e.getMessage()!=null?e.getMessage():"The source could not open: "+e.getClass().getSimpleName()+". Check the selected source and retry.");e.printStackTrace(System.err);}});}
                else if(type.equals("SOURCES")){engine.work.submit(()->CaptureSource.sources(engine));}
                else if(type.equals("AUDIO")){engine.work.submit(()->engine.audio(m));}
                else if(type.equals("AUDIO_STATUS")){engine.event("AUDIO_STATUS","quietedSessions",engine.audio==null?0:engine.audio.quietedSessions(),"source",engine.source==null?Map.of():engine.source.audioStatus());}
                else if(type.equals("RATE")){engine.work.submit(()->{engine.frameFps=Math.clamp(m.get("fps").getAsInt(),1,60);engine.command(m);});}
                else if(type.equals("TIME")){engine.pendingTime=m;engine.command(m);}
                // Source creation also runs on this executor. Navigation/input/rate commands
                // must run after it, otherwise actions sent during startup silently disappear.
                else engine.work.submit(()->engine.command(m));
            }
        }catch(EOFException|java.net.SocketException ignored){}finally{engine.running=false;engine.closeSource();engine.work.shutdownNow();BrowserSource.shutdown();}
    }
    public void image(BufferedImage image){offerPicture(image);}
    public void encodedImage(byte[] jpeg){offerPicture(jpeg);}
    private void offerPicture(Object image){if(picture.getAndSet(image)==null)pictureReady.release();}
    public synchronized void event(String type,Object...fields){JsonObject m=new JsonObject();m.addProperty("type",type);for(int i=0;i<fields.length;i+=2)m.add((String)fields[i],ScreenSpec.JSON.toJsonTree(fields[i+1]));try{byte[] bytes=m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeByte(0);out.writeInt(bytes.length);out.write(bytes);out.flush();}catch(IOException e){running=false;}}
    public void error(String message){event("ERROR","text",message);}
    public synchronized void pcm(byte[] bytes){try{out.writeByte(2);out.writeInt(bytes.length);out.write(bytes);out.flush();}catch(IOException e){running=false;}}
    private void encode(){
        FrameRateGate rate=new FrameRateGate();
        while(running)try{
            pictureReady.acquire();Object image=picture.getAndSet(null);if(image==null||!rate.allow(System.nanoTime()/1_000_000,frameFps))continue;
            byte[] jpeg=image instanceof byte[] encoded&&FrameAssembler.validJpeg(encoded)?encoded:jpeg(image instanceof byte[] encoded?ImageIO.read(new ByteArrayInputStream(encoded)):(BufferedImage)image);
            synchronized(this){out.writeByte(1);out.writeInt(jpeg.length);out.write(jpeg);out.flush();framesWritten++;}
        }catch(Exception e){if(running)error("The source picture could not be prepared.");}
    }
    public static byte[] jpeg(BufferedImage source)throws IOException{return FrameEncoder.encode(source);}
    private void pick(JsonObject m){try{AtomicReference<Path> selected=new AtomicReference<>();javax.swing.SwingUtilities.invokeAndWait(()->{javax.swing.JFileChooser chooser=new javax.swing.JFileChooser(){protected javax.swing.JDialog createDialog(java.awt.Component parent){var dialog=super.createDialog(parent);dialog.setAlwaysOnTop(true);return dialog;}};chooser.setDialogTitle("Import into this Minecraft world");chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images, videos, music and PDF","png","jpg","jpeg","gif","webp","mp4","mkv","webm","mov","avi","mp3","wav","ogg","flac","m4a","pdf","srt","vtt"));if(chooser.showOpenDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION)selected.set(chooser.getSelectedFile().toPath());});if(selected.get()!=null)event("FILE","path",selected.get().toAbsolutePath().toString());else event("FILE_CANCEL");}catch(Exception e){error("The file chooser could not open. You can paste a local file path in Import.");}}
    public Path runtime(){return runtime;}
    private void audio(JsonObject m){
        if(source!=null&&source.audio(m))return;
        if(audio!=null&&m.toString().equals(audioRequest))return;audioRequest=m.toString();if(audio!=null){audio.close();audio=null;}if(!m.get("enabled").getAsBoolean())return;
        if(!com.sun.jna.Platform.isWindows()){error("Live source audio is available on Windows 11. Imported media sound works on all supported systems.");return;}
        long pid=m.has("pid")?m.get("pid").getAsLong():ProcessHandle.current().pid();
        audio=new WindowsAudio(pid,m.has("exclude")&&m.get("exclude").getAsBoolean(),m.has("quietLocal")&&m.get("quietLocal").getAsBoolean(),this::pcm,this::error);
    }
    private void command(JsonObject message){Source current=source;if(current!=null)try{current.command(message);}catch(Exception e){error("The source could not complete this action.");e.printStackTrace(System.err);}}
    private void closeSource(){audioRequest="";if(audio!=null){audio.close();audio=null;}Source old=source;source=null;if(old!=null)try{old.close();}catch(Exception e){e.printStackTrace(System.err);}picture.set(null);}
}
