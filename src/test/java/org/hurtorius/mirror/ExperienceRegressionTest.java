package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import javax.imageio.*;
import java.awt.image.BufferedImage;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceRegressionTest {
    @Test void submittedWebAddressesDoNotNeedAScheme(){assertEquals("https://youtube.com/watch?v=test",BrowserAddress.resolve(" youtube.com/watch?v=test "));assertEquals("http://localhost:25580/video",BrowserAddress.resolve("localhost:25580/video"));}
    @Test void submittedSearchesAreEncodedAndUnsafeSchemesAreRejected(){assertTrue(BrowserAddress.resolve("a film & music").endsWith("a+film+%26+music"));assertThrows(IllegalArgumentException.class,()->BrowserAddress.resolve("file:///private.txt"));assertThrows(IllegalArgumentException.class,()->BrowserAddress.resolve("https://user:password@example.com"));assertThrows(IllegalArgumentException.class,()->BrowserAddress.resolve(" "));}
    @Test void serverTickQuantizationDoesNotThrowAwayHalfTheVideoFrames(){var gate=new FrameRateGate();int accepted=0;for(int i=0;i<240;i++){long tick=Math.round((i*1000.0/24)/50)*50;if(gate.allow(tick,24))accepted++;}assertTrue(accepted>=235,"20 Hz packet delivery should still carry almost all 24 Hz frames");}
    @Test void sustainedFloodsStayBounded(){var gate=new FrameRateGate();int accepted=0;for(int ms=0;ms<10000;ms++)if(gate.allow(ms,24))accepted++;assertTrue(accepted<=242);assertTrue(accepted>=239);}
    @Test void baselineAndProgressiveJpegHeadersAreAccepted()throws Exception{
        for(boolean progressive:new boolean[]{false,true}){
            var image=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
            try(var output=ImageIO.createImageOutputStream(bytes)){writer.setOutput(output);var options=writer.getDefaultWriteParam();options.setProgressiveMode(progressive?ImageWriteParam.MODE_DEFAULT:ImageWriteParam.MODE_DISABLED);writer.write(null,new IIOImage(image,null,null),options);}finally{writer.dispose();}
            assertTrue(FrameAssembler.validJpeg(bytes.toByteArray()));
        }
    }
    @Test void jpegHeaderLimitsRejectTruncatedAndOversizedFrames(){
        byte[] small={(byte)255,(byte)216,(byte)255,(byte)192,0,8,8,2,(byte)208,5,0,3};assertTrue(FrameAssembler.validJpeg(small));
        small[9]=127;assertFalse(FrameAssembler.validJpeg(small));
        assertFalse(FrameAssembler.validJpeg(new byte[]{(byte)255,(byte)216,(byte)255,(byte)224,127,127}));
        assertFalse(FrameAssembler.validJpeg(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}));
    }
}
