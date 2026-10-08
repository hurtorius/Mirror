package org.hurtorius.mirror.core;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;

/** Bounded wire images. Dense pages lose detail rather than freezing the stream. */
public final class FrameEncoder {
    private FrameEncoder(){}
    public static byte[] encode(BufferedImage source)throws IOException{return encode(source,.8f);}
    public static byte[] encode(BufferedImage source,float quality)throws IOException{
        if(source==null||quality<.2f||quality>1||!Float.isFinite(quality))throw new IOException("Invalid picture.");
        double scale=Math.min(1,Math.min(Math.sqrt(2073600.0/(source.getWidth()*(double)source.getHeight())),Math.min(1920.0/source.getWidth(),1920.0/source.getHeight())));
        int width=Math.max(16,(int)(source.getWidth()*scale)),height=Math.max(16,(int)(source.getHeight()*scale));
        BufferedImage image=source;
        if(width!=source.getWidth()||height!=source.getHeight()||!(source.getType()==BufferedImage.TYPE_INT_RGB||source.getType()==BufferedImage.TYPE_3BYTE_BGR||source.getType()==BufferedImage.TYPE_BYTE_GRAY))image=resize(source,width,height);
        for(int pass=0;pass<20;pass++){
            for(float q=quality;q>=.19f;q-=.15f){
                var bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
                try(var stream=ImageIO.createImageOutputStream(bytes)){
                    writer.setOutput(stream);var options=writer.getDefaultWriteParam();options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);options.setCompressionQuality(q);
                    writer.write(null,new IIOImage(image,null,null),options);
                }finally{writer.dispose();}
                byte[] result=bytes.toByteArray();if(result.length<=240000)return result;
            }
            int nextWidth=Math.max(16,image.getWidth()*3/4),nextHeight=Math.max(16,image.getHeight()*3/4);
            if(nextWidth==image.getWidth()&&nextHeight==image.getHeight())break;
            image=resize(image,nextWidth,nextHeight);
        }
        throw new IOException("Picture could not fit the stream limit.");
    }
    private static BufferedImage resize(BufferedImage source,int width,int height){
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,width,height,null);g.dispose();return image;
    }
}
