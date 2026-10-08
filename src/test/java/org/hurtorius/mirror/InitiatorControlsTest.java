package org.hurtorius.mirror;

import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitiatorControlsTest {
    @Test void absoluteCenterRoundTripsAtNegativeAndLargeBlockCoordinates(){
        Anchor a=new Anchor();a.x=-12743;a.y=-60;a.z=23000001;
        var c=ScreenCoordinates.at(a);
        assertEquals(-12742.5,c.display("x",0));
        assertEquals(-56.5,c.display("y",3.5));
        assertEquals(23000001.5,c.display("z",0));
        for(String axis:new String[]{"x","y","z"})for(double offset:new double[]{-128,-2.75,0,.25,128})
            assertEquals(offset,c.stored(axis,c.display(axis,offset)),.0000001);
    }
    @Test void snappingUsesTheWorldGridAndStaysWithinTheExistingReach(){
        var c=new ScreenCoordinates(-.5,-60,.5);
        for(String axis:new String[]{"x","y","z"})for(double offset:new double[]{-128,-1.125,.5,127.75,128}){
            double snapped=c.snapped(axis,offset);
            assertTrue(snapped>=-128&&snapped<=128);
            assertEquals(Math.rint(c.display(axis,snapped)),c.display(axis,snapped));
        }
        assertEquals(.5,c.snapped("z",.4));
    }
    @Test void worldCoordinateInputRejectsNonfiniteAndOutOfReachWithoutClamping(){
        var c=new ScreenCoordinates(1000.5,64,-500.5);
        assertThrows(IllegalArgumentException.class,()->c.stored("x",0));
        assertThrows(IllegalArgumentException.class,()->c.stored("z",Double.NaN));
        assertThrows(IllegalArgumentException.class,()->c.stored("y",Double.POSITIVE_INFINITY));
        assertEquals(1.25,c.stored("x",1001.75));
    }
    @Test void movingACopiedLayoutKeepsItsWorldCenter(){
        var from=new ScreenCoordinates(-.5,64,10.5);var to=new ScreenCoordinates(10.5,60,14.5);
        double moved=to.stored("x",from.display("x",3.25));
        assertEquals(2.75,to.display("x",moved));
        assertEquals(3.25,ScreenCoordinates.followedPlayer().display("x",3.25));
    }
    @Test void brightnessSupportsBlackAndNativeTextPreservesOpacity(){
        ScreenSpec s=new ScreenSpec();assertEquals(1,s.brightness);s.brightness=0;s.validate();
        assertEquals(0x80000000,ScreenBrightness.textColor(128,0));
        assertEquals(0xff7f7f7f,ScreenBrightness.textColor(255,.5));
        assertEquals(0xffffffff,ScreenBrightness.textColor(255,2));
        s.brightness=-.01;assertThrows(IllegalArgumentException.class,s::validate);
    }
}
