package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserViewportTest {
    @Test void defaultAndPortraitFollowTheScreen(){
        var s=new ScreenSpec();assertEquals(new BrowserViewport(1280,720),BrowserViewport.of(s));
        s.width=5;s.height=6;var v=BrowserViewport.of(s);assertEquals(5.0/6,v.width()/(double)v.height(),.001);
        assertTrue(v.width()*v.height()<923000);assertTrue(v.height()>720);
    }
    @Test void customSizePersistsAndDoesNotFollowLaterGeometry(){
        var s=new ScreenSpec();s.browserAutoSize=false;s.browserWidth=800;s.browserHeight=960;s.width=9;s.height=2;
        var loaded=ScreenSpec.read(ScreenSpec.JSON.toJson(s));assertEquals(new BrowserViewport(800,960),BrowserViewport.of(loaded));
    }
    @Test void oversizedViewportCannotAllocateAnUnboundedFrame(){
        var s=new ScreenSpec();s.browserAutoSize=false;s.browserWidth=1920;s.browserHeight=1920;assertThrows(IllegalArgumentException.class,s::validate);
        s.browserWidth=0;assertThrows(IllegalArgumentException.class,s::validate);s.browserAutoSize=true;assertDoesNotThrow(s::validate);assertEquals(new BrowserViewport(1280,720),BrowserViewport.of(s));
    }
    @Test void portraitJpegIsAcceptedButSquareOverBudgetIsRejected()throws Exception{
        for(int[] size:new int[][]{{800,960},{1080,1920},{1920,1920}}){var b=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(size[0],size[1],1),"jpeg",b);assertEquals(size[0]*size[1]<=2073600,FrameAssembler.validJpeg(b.toByteArray()));}
    }
}
