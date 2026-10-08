package org.hurtorius.mirror.client;
import org.hurtorius.mirror.core.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenPoseTest {
    @Test void orbitAnglesRemainPreciseAtRealCalendarTimes(){
        var a=new Anchor();var s=new ScreenSpec();s.facing=ScreenSpec.Facing.ORBIT;
        var p=ScreenPose.sample(a,s,null,Vec3.ZERO,1791434342371L,false);
        assertTrue(Math.abs(p.yaw())<=180);assertEquals(s.orbitRadius,Math.hypot(p.x()-(a.x+.5+s.x),p.z()-(a.z+.5+s.z)),.000001);
        var next=ScreenPose.sample(a,s,null,Vec3.ZERO,1791434342387L,false);assertTrue(Math.abs(ScreenSpec.wrapDegrees(next.yaw()-p.yaw()))<1);
    }
    @Test void eachViewerFacesEitherSideWithANormalFront(){
        var a=new Anchor();var s=new ScreenSpec();s.facing=ScreenSpec.Facing.EACH_VIEWER;
        assertEquals(180,ScreenPose.sample(a,s,null,new Vec3(.5,3.5,-5),1000,true).yaw());
        assertEquals(0,ScreenPose.sample(a,s,null,new Vec3(.5,3.5,5),1000,true).yaw());
    }
    @Test void reducedMotionDisablesIdleMovementWithoutChangingPlacement(){
        var a=new Anchor();var s=new ScreenSpec();s.floatAmount=1;s.swayDegrees=5;
        var moving=ScreenPose.sample(a,s,null,Vec3.ZERO,1000,false);var still=ScreenPose.sample(a,s,null,Vec3.ZERO,1000,true);
        assertEquals(3.5,still.y());assertEquals(0,still.roll());assertNotEquals(still.y(),moving.y());assertNotEquals(still.roll(),moving.roll());
    }
}
