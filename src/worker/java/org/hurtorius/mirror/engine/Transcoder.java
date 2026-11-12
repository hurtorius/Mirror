package org.hurtorius.mirror.engine;
import com.google.gson.JsonObject;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import java.nio.file.*;
/** Optional map-size reduction, local to the importer and never on the game thread. */
final class Transcoder {
    static void run(EngineMain engine,JsonObject m){Path input=Path.of(m.get("path").getAsString()),output=null;
        try{Path folder=engine.runtime().resolve("imports");Files.createDirectories(folder);output=Files.createTempFile(folder,"Mirror-video-",".mp4");
            try(FFmpegFrameGrabber grabber=new FFmpegFrameGrabber(input.toFile())){grabber.setAudioChannels(2);grabber.setSampleRate(48000);LocalMediaDecoder.restrict(grabber,false);grabber.start();int ow=grabber.getImageWidth(),oh=grabber.getImageHeight();if(ow<1||oh<1||grabber.getLengthInTime()>86400000000L)throw new IllegalArgumentException("Choose a video under 24 hours.");double scale=Math.min(1,Math.min(1280.0/ow,720.0/oh));int w=Math.max(16,((int)(ow*scale))/2*2),h=Math.max(16,((int)(oh*scale))/2*2);grabber.setImageWidth(w);grabber.setImageHeight(h);
                try(FFmpegFrameRecorder recorder=new FFmpegFrameRecorder(output.toFile(),w,h,grabber.getAudioChannels()>0?2:0)){recorder.setFormat("mp4");recorder.setVideoCodecName("libopenh264");recorder.setVideoBitrate(1200000);double fps=fps(grabber.getFrameRate());recorder.setFrameRate(fps);recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC);recorder.setAudioBitrate(128000);recorder.setSampleRate(48000);recorder.start();long last=0,lastSlot=Long.MIN_VALUE;for(Frame frame;(frame=grabber.grab())!=null;){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(frame.image!=null){long slot=slot(frame.timestamp,fps);if(slot<=lastSlot)continue;lastSlot=slot;}recorder.setTimestamp(frame.timestamp);recorder.record(frame);if(System.currentTimeMillis()-last>500){last=System.currentTimeMillis();engine.event("PREPARING","text","Shrinking video · "+(grabber.getLengthInTime()>0?100*frame.timestamp/grabber.getLengthInTime():0)+"%");}}recorder.stop();}
                grabber.stop();
            }
            if(Files.size(output)>=Files.size(input)){Files.delete(output);engine.event("TRANSCODED","path",input.toString(),"reduced",false);}else engine.event("TRANSCODED","path",output.toString(),"name",input.getFileName().toString().replaceFirst("\\.[^.]+$","")+".mp4","reduced",true);
        }catch(Exception e){if(output!=null)try{Files.deleteIfExists(output);}catch(Exception ignored){}engine.error("This video could not be shrunk. You can import the original file instead.");}
    }
    /** Playback shows about 30 pictures a second, so more only cost bits and every viewer's decoding. An unknown rate counts as 30. */
    static double fps(double source){return source>=1&&source<=1000?Math.min(30,source):30;}
    /** The recorder numbers each picture round(timestamp × rate). One picture per number drops the extra ones and never repeats a number, which the muxer rejects. */
    static long slot(long timestamp,double fps){return Math.round(timestamp*fps/1e6);}
}
