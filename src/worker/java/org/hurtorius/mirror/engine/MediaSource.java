package org.hurtorius.mirror.engine;

import com.google.gson.JsonObject;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.ArrayDeque;

/** A local decoder follows the authoritative timeline; decoding never runs on the game thread. */
public final class MediaSource implements EngineMain.Source {
    private final EngineMain engine;
    private volatile boolean alive=true,playing=false,jump;
    private volatile double position;
    private volatile long clock=System.nanoTime();
    private volatile int page=0;
    private final String kind,media;
    private final Path file;
    private final Thread thread;
    private FFmpegFrameGrabber grabber;
    private PDDocument document;
    private long lastVisualizer;
    private record Sound(long at,byte[] pcm){}
    public MediaSource(EngineMain engine,JsonObject m)throws Exception{
        this.engine=engine;file=Path.of(m.get("path").getAsString());kind=m.get("kind").getAsString();media=m.has("media")?m.get("media").getAsString():"";
        // The viewer's picture quality: FFmpeg decodes, and so encodes and sends, nothing wider. A still ImageIO reads is sent once as it is.
        int maxWidth=m.has("maxWidth")?Math.clamp(m.get("maxWidth").getAsInt(),320,1280):1280;
        if(!Files.isRegularFile(file)||Files.size(file)>512L*1024*1024)throw new IOException("Media file is unavailable or too large.");
        if(kind.equals("image")){
            boolean decoded=false;
            try(var stream=ImageIO.createImageInputStream(file.toFile())){var readers=ImageIO.getImageReaders(stream);if(readers.hasNext()){var r=readers.next();try{r.setInput(stream);if((long)r.getWidth(0)*r.getHeight(0)>64*1024*1024)throw new IOException("Image is too large.");engine.image(r.read(0));decoded=true;}finally{r.dispose();}}}
            if(!decoded)try(var imageGrabber=new FFmpegFrameGrabber(file.toFile());var converter=new Java2DFrameConverter()){
                LocalMediaDecoder.restrict(imageGrabber,true);imageGrabber.start();int w=imageGrabber.getImageWidth(),h=imageGrabber.getImageHeight();if(w<1||h<1||(long)w*h>64*1024*1024)throw new IOException("Image is too large or invalid.");double scale=Math.min(1,Math.min(maxWidth/(double)w,720.0/h));imageGrabber.setImageWidth(Math.max(16,(int)(w*scale)));imageGrabber.setImageHeight(Math.max(16,(int)(h*scale)));var frame=imageGrabber.grabImage();if(frame==null)throw new IOException("Unsupported image.");engine.image(converter.getBufferedImage(frame,1.0,false,null));
            }
            thread=null;return;
        }
        if(kind.equals("pdf")){document=Loader.loadPDF(file.toFile());if(document.getNumberOfPages()<1||document.getNumberOfPages()>1000){document.close();document=null;throw new IOException("PDF must contain 1 to 1,000 pages.");}engine.event("MEDIA_INFO","media",media,"pages",document.getNumberOfPages(),"duration",0);}
        else {grabber=new FFmpegFrameGrabber(file.toFile());LocalMediaDecoder.restrict(grabber,false);grabber.setAudioChannels(2);grabber.setSampleRate(48000);grabber.setSampleFormat(org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16);grabber.start();int w=grabber.getImageWidth(),h=grabber.getImageHeight();if(w>0&&h>0){double scale=Math.min(1,Math.min(maxWidth/(double)w,720.0/h));grabber.setImageWidth(Math.max(16,(int)(w*scale)));grabber.setImageHeight(Math.max(16,(int)(h*scale)));}engine.event("MEDIA_INFO","media",media,"duration",grabber.getLengthInTime()/1000000.0,"pages",0);}
        thread=new Thread(this::decode,"Mirror media decoder");thread.setDaemon(true);thread.start();
    }
    private double target(){return position+(playing?(System.nanoTime()-clock)/1000000000.0:0);}
    // TIME arrives about 20 times a second with a continuous position; only a jump of the timeline, or play/pause, is a real seek. jump is set last, after the new position.
    public void command(JsonObject m){switch(m.get("type").getAsString()){case "TIME"->{double at=m.get("position").getAsDouble();if(Double.isFinite(at)&&at>=0&&at<=86400){boolean next=m.get("playing").getAsBoolean(),moved=Math.abs(at-target())>.35||next!=playing;position=at;clock=System.nanoTime();playing=next;if(moved)jump=true;}}case "PAGE"->{page=Math.max(0,m.get("page").getAsInt());}}}
    /** decoded is the furthest point read. Seeking costs a decode from the previous keyframe, so a decoder that is behind seeks at most every 2 s instead of falling further behind each time. */
    static boolean seek(long decoded,long target,boolean wasPlaying,boolean jump,long sinceSeek){return decoded<0||!wasPlaying||jump||target-decoded>350_000&&sinceSeek>2_000_000_000L;}
    /** On-time pictures are shown up to 30 a second; late ones only 4 a second of media, so a slow decoder catches up yet never freezes. */
    static boolean show(long timestamp,long lastPicture,long delay){return lastPicture<0||timestamp<lastPicture||timestamp-lastPicture>=(delay< -50_000?250_000:33_000);}
    private void decode(){
        try(Java2DFrameConverter convert=new Java2DFrameConverter()){
            int renderedPage=-1;boolean wasPlaying=false;long decoded=-1,lastPicture=-1,lastSeek=0;
            // A frame-threaded video decoder hands out each picture several frames after the sound read with it.
            // Sound therefore waits here for its own time instead of holding the pictures back.
            ArrayDeque<Sound> sound=new ArrayDeque<>();
            while(alive){
                if(document!=null){int selected=Math.min(page,document.getNumberOfPages()-1);if(selected!=renderedPage){var box=document.getPage(selected).getCropBox();float scale=Math.min(2,1280/Math.max(box.getWidth(),box.getHeight()));engine.image(new PDFRenderer(document).renderImage(selected,scale));renderedPage=selected;}Thread.sleep(40);continue;}
                long target=(long)(target()*1000000);long duration=grabber.getLengthInTime();
                play(sound);if(duration>0&&target>=duration){Thread.sleep(40);continue;}
                if(!playing){sound.clear();if(decoded<0||Math.abs(decoded-target)>1000||wasPlaying){grabber.setTimestamp(Math.max(0,target));Frame frame=grabber.grabImage();if(frame!=null&&frame.image!=null){BufferedImage image=convert.convert(frame);if(image!=null)picture(image);}decoded=target;}wasPlaying=false;Thread.sleep(40);continue;}
                if(seek(decoded,target,wasPlaying,jump,System.nanoTime()-lastSeek)){jump=false;sound.clear();target=(long)(target()*1000000);grabber.setTimestamp(Math.max(0,target));decoded=target;lastSeek=System.nanoTime();}
                wasPlaying=true;
                // Music, or a very long decoder delay: 2 s of sound read ahead is enough.
                if(!sound.isEmpty()&&sound.peekLast().at()-target>2_000_000){Thread.sleep(10);continue;}
                Frame f=grabber.grab();if(f==null){Thread.sleep(sound.isEmpty()?100:10);continue;}
                decoded=Math.max(decoded,f.timestamp);
                if(f.samples!=null){byte[] pcm=pcm(f.samples);if(pcm.length>0)sound.add(new Sound(f.timestamp,pcm));}
                if(f.image==null)continue;
                // An early picture waits for its time (at most 200 ms, so a seek or pause is noticed), sending the sound that falls due meanwhile.
                long now=(long)(target()*1000000);for(long until=System.nanoTime()+200_000_000L;alive&&playing&&!jump&&f.timestamp>now&&System.nanoTime()<until;now=(long)(target()*1000000)){play(sound);long next=sound.isEmpty()?f.timestamp:Math.min(f.timestamp,sound.peekFirst().at());Thread.sleep(Math.max(1,Math.min(20,(next-now)/1000)));}
                if(!alive)break;
                if(show(f.timestamp,lastPicture,f.timestamp-now)){BufferedImage image=convert.convert(f);if(image!=null){picture(image);lastPicture=f.timestamp;}}
            }
        }catch(Throwable e){if(alive){e.printStackTrace(System.err);engine.event("SOURCE_FAILED","media",media,"text","This media could not be decoded. Try another file or format.");}}
    }
    /** Sends the sound that is due, in chunks the client accepts; music also draws its visualizer from it. */
    private void play(ArrayDeque<Sound> sound){
        long now=(long)(target()*1000000);
        while(playing&&!sound.isEmpty()&&sound.peekFirst().at()<=now){byte[] pcm=sound.pollFirst().pcm();for(int off=0;off<pcm.length;off+=9600)engine.pcm(java.util.Arrays.copyOfRange(pcm,off,Math.min(pcm.length,off+9600)));if(kind.equals("audio")&&System.nanoTime()-lastVisualizer>=66_000_000){engine.image(visualizer(pcm));lastVisualizer=System.nanoTime();}}
    }
    private void picture(BufferedImage image){
        // JavaCV reuses its image on the next decoded frame; the encoder must own an immutable snapshot.
        engine.image(new BufferedImage(image.getColorModel(),image.copyData(null),image.isAlphaPremultiplied(),null));
    }
    static byte[] pcm(Buffer[] samples){if(samples.length==0)return new byte[0];if(samples.length==1&&samples[0] instanceof ShortBuffer s){ShortBuffer b=s.duplicate();byte[] pcm=new byte[b.remaining()*2];for(int i=0;i<pcm.length;i+=2){short n=b.get();pcm[i]=(byte)n;pcm[i+1]=(byte)(n>>8);}return pcm;}return new byte[0];}
    private BufferedImage visualizer(byte[] pcm){BufferedImage image=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.BLACK);g.fillRect(0,0,1280,720);g.setColor(new Color(0x86e1db));g.setStroke(new BasicStroke(3));int last=360;for(int x=0;x<1280;x++){int i=Math.min(pcm.length-2,(int)((long)x*(pcm.length/2)/1280)*2);short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));int y=360+sample*220/32768;g.drawLine(x-1,last,x,y);last=y;}g.dispose();return image;}
    public void close(){alive=false;if(thread!=null){thread.interrupt();try{thread.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}try{if(grabber!=null)grabber.close();if(document!=null)document.close();}catch(IOException e){e.printStackTrace(System.err);}}
}
