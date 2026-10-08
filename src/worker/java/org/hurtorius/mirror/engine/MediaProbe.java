package org.hurtorius.mirror.engine;
import com.google.gson.JsonObject;
import org.apache.pdfbox.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import java.nio.file.*;
/** Metadata for imported maps is established before an audience needs to play it. */
final class MediaProbe {
    static void run(EngineMain engine,JsonObject m){
        String id=m.get("media").getAsString();
        try{
            Path path=Path.of(m.get("path").getAsString());
            if(!Files.isRegularFile(path)||Files.size(path)>512L*1024*1024)throw new IllegalArgumentException("Unavailable media");
            double duration=0;int pages=0;
            if(m.get("kind").getAsString().equals("pdf"))try(var pdf=Loader.loadPDF(path.toFile())){pages=pdf.getNumberOfPages();if(pages<1||pages>1000)throw new IllegalArgumentException("PDF page limit");}
            else try(var grabber=new FFmpegFrameGrabber(path.toFile())){LocalMediaDecoder.restrict(grabber,false);grabber.start();duration=grabber.getLengthInTime()/1_000_000.0;}
            engine.event("PROBED_INFO","media",id,"duration",Math.max(0,Math.min(86400,duration)),"pages",pages);
        }catch(Exception e){engine.event("PROBE_FAILED","media",id);}
    }
}
