package org.hurtorius.mirror.client;

import org.hurtorius.mirror.core.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One motion/facing calculation for the world and the editor preview. */
final class ScreenPose {
    record Pose(double x,double y,double z,double yaw,double pitch,double roll){}
    static Pose sample(Anchor a,ScreenSpec s,ClientLevel world,Vec3 camera,long millis,boolean reducedMotion){
        double time=millis/1000.0,x=a.x+.5+s.x,y=a.y+s.y,z=a.z+.5+s.z,yaw=s.yaw,pitch=s.pitch,roll=s.roll;
        if(world!=null&&!s.target.isEmpty()){
            var player=world.getPlayerByUUID(UUID.fromString(s.target));
            if(player!=null){if(s.followPlayer){x=player.getX()+s.x;y=player.getY()+s.y;z=player.getZ()+s.z;}
                if(s.facing==ScreenSpec.Facing.SPECIFIC_PLAYER||s.facing==ScreenSpec.Facing.NEAREST_PLAYER){yaw=Math.toDegrees(Math.atan2(player.getX()-x,player.getZ()-z));pitch=-Math.toDegrees(Math.atan2(player.getEyeY()-y,Math.hypot(player.getX()-x,player.getZ()-z)));}}
        }
        if(s.facing==ScreenSpec.Facing.EACH_VIEWER||s.facing==ScreenSpec.Facing.BILLBOARD){yaw=Math.toDegrees(Math.atan2(camera.x-x,camera.z-z));if(s.facing==ScreenSpec.Facing.EACH_VIEWER)pitch=-Math.toDegrees(Math.atan2(camera.y-y,Math.hypot(camera.x-x,camera.z-z)));}
        if(world!=null&&s.facing==ScreenSpec.Facing.NEAREST_PLAYER&&s.target.isEmpty()){final double px=x,py=y,pz=z;var nearest=world.players().stream().min(Comparator.comparingDouble(p->p.distanceToSqr(px,py,pz))).orElse(null);if(nearest!=null)yaw=Math.toDegrees(Math.atan2(nearest.getX()-x,nearest.getZ()-z));}
        if(s.facing==ScreenSpec.Facing.ORBIT){double angle=(time%s.orbitSeconds)*2*Math.PI/s.orbitSeconds;x+=Math.sin(angle)*s.orbitRadius;z+=Math.cos(angle)*s.orbitRadius;yaw=ScreenSpec.wrapDegrees(180+Math.toDegrees(angle));}
        if(!s.path.isEmpty()){var path=MotionPath.sample(s,a.motionMillis,millis);x=a.x+.5+path.x();y=a.y+path.y();z=a.z+.5+path.z();yaw=path.yaw();pitch=path.pitch();}
        if(!reducedMotion){y+=Math.sin(time*1.4)*s.floatAmount;roll+=Math.sin(time*.9)*s.swayDegrees;}
        return new Pose(x,y,z,yaw,pitch,roll);
    }
}
