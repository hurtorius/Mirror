package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import java.awt.image.*;
import java.io.*;
import java.util.Random;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class FrameEncoderTest {
    @Test void denseTallFramesReduceDetailInsteadOfBreakingTheStream()throws Exception{
        var image=new BufferedImage(1080,1920,BufferedImage.TYPE_INT_RGB);var random=new Random(42);
        int[] pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();for(int i=0;i<pixels.length;i++)pixels[i]=random.nextInt(0x1000000);
        byte[] jpeg=FrameEncoder.encode(image);assertTrue(FrameAssembler.validJpeg(jpeg));assertTrue(jpeg.length<=240000);
        var decoded=ImageIO.read(new ByteArrayInputStream(jpeg));assertEquals(9/16.0,decoded.getWidth()/(double)decoded.getHeight(),.002);
        assertTrue(decoded.getWidth()<1080);assertNotEquals(decoded.getRGB(10,10),decoded.getRGB(100,100));
    }
    @Test void ordinaryPortraitFramesRetainTheirDimensions()throws Exception{
        var image=new BufferedImage(800,960,BufferedImage.TYPE_INT_RGB);byte[] jpeg=FrameEncoder.encode(image,.9f);
        var decoded=ImageIO.read(new ByteArrayInputStream(jpeg));assertEquals(800,decoded.getWidth());assertEquals(960,decoded.getHeight());assertTrue(FrameAssembler.validJpeg(jpeg));
    }
}
