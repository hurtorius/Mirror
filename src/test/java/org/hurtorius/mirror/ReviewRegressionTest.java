package org.hurtorius.mirror;
import org.hurtorius.mirror.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewRegressionTest {
    @Test void unlimitedPlayerTurnsProduceAValidInitialDirection(){for(double yaw:List.of(-100000.0,-1080.0,-1.0,0.0,900.0,100000.0)){var s=new ScreenSpec();s.yaw=ScreenSpec.wrapDegrees(yaw+180);assertDoesNotThrow(s::validate);assertTrue(s.yaw>=-180&&s.yaw<180);}}
    @Test void legacyTextAndPlacementAreRecovered(){var r=LegacyConfig.convert("{\"source\":\"TEXT\",\"text\":\"My old screen\",\"enabled\":true,\"width\":4,\"height\":2.25,\"offsetY\":2,\"yaw\":810,\"border\":false}",id->"");assertEquals("My old screen",r.spec().text);assertEquals(2,r.spec().y);assertEquals(90,r.spec().yaw);assertEquals(ScreenSpec.Edge.NONE,r.spec().edge);assertTrue(r.spec().live);}
    @Test void legacyUnresolvedAudienceAndTriggersStayOff(){var r=LegacyConfig.convert("{\"enabled\":true,\"audience\":\"GROUP\",\"groupId\":\"old-group\",\"redstone\":\"POWERED\"}",id->"");assertFalse(r.spec().live);assertEquals(ScreenSpec.Audience.NOBODY,r.spec().audience);assertFalse(r.notes().isEmpty());}
    @Test void legacyMediaHashesResolveWithoutUsingPathsFromTheConfig(){String hash="a".repeat(64),id=UUID.randomUUID().toString();var r=LegacyConfig.convert("{\"source\":\"VIDEO\",\"mediaId\":\""+hash+"\"}",h->h.equals(hash)?id:"");assertEquals(List.of(id),r.spec().playlist);assertEquals(ScreenSpec.Source.MEDIA,r.spec().source);}
    @Test void privateFramesNeedAnActivePublicationBeforeWorldRendering(){assertFalse(FrameVisibility.publicPicture(ScreenSpec.Source.WEB,""));assertFalse(FrameVisibility.publicPicture(ScreenSpec.Source.SHARE,null));assertTrue(FrameVisibility.publicPicture(ScreenSpec.Source.WEB,UUID.randomUUID().toString()));assertTrue(FrameVisibility.publicPicture(ScreenSpec.Source.WHITEBOARD,""));assertTrue(FrameVisibility.publicPicture(ScreenSpec.Source.MEDIA,""));}
}
