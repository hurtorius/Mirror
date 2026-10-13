package org.hurtorius.mirror.core;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Bounded wire images. Dense pages lose detail rather than freezing the stream. */
public final class FrameEncoder {
    private FrameEncoder(){}
    /** JPEG writers are not thread-safe, so each encoding thread keeps its own writer and buffer, encoding in memory without the image cache's temp files. */
    private static final class Writer {final ImageWriter jpeg=ImageIO.getImageWritersByFormatName("jpeg").next();final ImageWriteParam options=jpeg.getDefaultWriteParam();final ByteArrayOutputStream bytes=new ByteArrayOutputStream(262144);Writer(){options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);}}
    private static final ThreadLocal<Writer> WRITER=ThreadLocal.withInitial(Writer::new);
    public static byte[] encode(BufferedImage source)throws IOException{return encode(source,.8f);}
    public static byte[] encode(BufferedImage source,float quality)throws IOException{
        if(source==null||quality<.2f||quality>1||!Float.isFinite(quality))throw new IOException("Invalid picture.");
        double scale=Math.min(1,Math.min(Math.sqrt(2073600.0/(source.getWidth()*(double)source.getHeight())),Math.min(1920.0/source.getWidth(),1920.0/source.getHeight())));
        int width=Math.max(16,(int)(source.getWidth()*scale)),height=Math.max(16,(int)(source.getHeight()*scale));
        BufferedImage image=source;
        if(width!=source.getWidth()||height!=source.getHeight()||!(source.getType()==BufferedImage.TYPE_INT_RGB||source.getType()==BufferedImage.TYPE_3BYTE_BGR||source.getType()==BufferedImage.TYPE_BYTE_GRAY))image=resize(source,width,height);
        for(int pass=0;pass<20;pass++){
            for(float q=quality;q>=.19f;q-=.15f){byte[] result=write(image,q);if(result.length<=240000)return result;}
            int nextWidth=Math.max(16,image.getWidth()*3/4),nextHeight=Math.max(16,image.getHeight()*3/4);
            if(nextWidth==image.getWidth()&&nextHeight==image.getHeight())break;
            image=resize(image,nextWidth,nextHeight);
        }
        throw new IOException("Picture could not fit the stream limit.");
    }
    private static byte[] write(BufferedImage image,float quality)throws IOException{
        Writer w=WRITER.get();w.bytes.reset();
        try(var stream=new MemoryCacheImageOutputStream(w.bytes)){w.jpeg.setOutput(stream);w.options.setCompressionQuality(quality);w.jpeg.write(null,new IIOImage(image,null,null),w.options);}
        catch(IOException|RuntimeException e){w.jpeg.dispose();WRITER.remove();throw e;}
        return w.bytes.toByteArray();
    }
    private static BufferedImage resize(BufferedImage source,int width,int height){
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,width,height,null);g.dispose();return image;
    }
}
