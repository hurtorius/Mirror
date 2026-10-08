package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
final class MotionPathTest {
    private ScreenSpec route(){ScreenSpec s=new ScreenSpec();s.path=List.of(new ScreenSpec.Waypoint(0,3,0,170,0,1),new ScreenSpec.Waypoint(10,5,4,-170,20,2));return s;}
    @Test void nonLoopingFlightStopsAtEndAndUsesShortAngle(){var s=route();s.pathLoop=false;var midpoint=MotionPath.sample(s,1000,3000);assertEquals(5,midpoint.x());assertEquals(180,midpoint.yaw());assertEquals(10,MotionPath.sample(s,1000,9000).x());}
    @Test void loopRepeatsAtSamePhaseAndMediaSeeksCannotMoveIt(){var s=route();var a=new Anchor();a.spec=s;a.motionMillis=1000;var expected=MotionPath.sample(s,a.motionMillis,2500);a.seek(88,2500);assertEquals(expected,MotionPath.sample(s,a.motionMillis,2500));assertEquals(expected,MotionPath.sample(s,a.motionMillis,5500));}
}
