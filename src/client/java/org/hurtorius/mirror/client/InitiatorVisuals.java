package org.hurtorius.mirror.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.hurtorius.mirror.core.Device;
import java.util.*;

/** Render-thread choreography of every known Initiator, sampled on server time. */
public final class InitiatorVisuals {
    private static ClientLevel level;
    private record Animation(Device device,InitiatorLifecycle life) {}
    private static final Map<String,Animation> animations=new HashMap<>();
    private static final List<Animation> ghosts=new ArrayList<>();
    public record Ghost(Device device,InitiatorLifecycle.Pose pose) {}
    public static double time(){return (System.currentTimeMillis()+MirrorClient.serverOffset)/50.0;}
    public static InitiatorLifecycle.Pose pose(Device device){
        double now=time();var animation=animations.computeIfAbsent(device.id(),id->{double age=now-device.createdMillis()/50.0;return new Animation(device,age>=0&&age<InitiatorLifecycle.PLACE_TICKS?InitiatorLifecycle.placed(now-age):InitiatorLifecycle.loaded(device.active(),now));});
        animation.life.observe(device.active(),now);if(animation.device()!=device)animations.put(device.id(),new Animation(device,animation.life));
        var pose=animation.life.sample(now,MirrorClient.preferences.reducedMotion);
        return pose.withMood(switch(device.mood()){case "preparing"->InitiatorLifecycle.Mood.PREPARING;case "sharing"->InitiatorLifecycle.Mood.SHARING;case "locked"->InitiatorLifecycle.Mood.LOCKED;default->device.active()?InitiatorLifecycle.Mood.ACTIVE:InitiatorLifecycle.Mood.DORMANT;});
    }
    public static List<Ghost> ghosts(){
        if(ghosts.isEmpty())return List.of();
        // A refused break restores the block; its device then settles again instead of shrinking away.
        double now=time();var world=Minecraft.getInstance().level;ghosts.removeIf(g->g.life.expired(now)||world!=null&&world.getBlockState(new BlockPos(g.device.x(),g.device.y(),g.device.z())).is(org.hurtorius.mirror.Mirror.INITIATOR));
        return ghosts.stream().map(g->new Ghost(g.device,g.life.sample(now,MirrorClient.preferences.reducedMotion))).toList();
    }
    /** The block is gone: play the wake-up in reverse at once, without waiting for the next device list. */
    static void vanish(Device device){var old=animations.remove(device.id());if(old!=null&&ghosts.size()<128)ghosts.add(new Animation(device,InitiatorLifecycle.broken(old.life.sample(time(),MirrorClient.preferences.reducedMotion),time())));}
    public static void sync(){
        Minecraft mc=Minecraft.getInstance();
        if(level!=mc.level){animations.clear();ghosts.clear();}
        for(String id:new ArrayList<>(animations.keySet()))if(!MirrorClient.devices.containsKey(id)){
            var d=animations.get(id).device;var pos=new BlockPos(d.x(),d.y(),d.z());
            if(level==mc.level&&level!=null&&level.hasChunkAt(pos)&&!level.getBlockState(pos).is(org.hurtorius.mirror.Mirror.INITIATOR))vanish(d);else animations.remove(id);
        }
        level=mc.level;
    }
    public static void tick(){if(level!=Minecraft.getInstance().level){MirrorClient.devices.clear();sync();}}
}
