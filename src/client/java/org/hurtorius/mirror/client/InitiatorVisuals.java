package org.hurtorius.mirror.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.hurtorius.mirror.core.Device;
import java.util.*;

/** Immutable lookup also read by terrain-building worker threads. */
public final class InitiatorVisuals {
    private static volatile Set<BlockPos> positions=Set.of();
    private static ClientLevel level;
    private record Animation(Device device,InitiatorLifecycle life) {}
    private static final Map<String,Animation> animations=new HashMap<>();
    private static final List<Animation> ghosts=new ArrayList<>();
    public record Ghost(Device device,InitiatorLifecycle.Pose pose) {}
    public static double time(){return (System.currentTimeMillis()+MirrorClient.serverOffset)/50.0;}
    public static InitiatorLifecycle.Pose pose(Device device){
        double now=time();var animation=animations.computeIfAbsent(device.id(),id->{double age=now-device.createdMillis()/50.0;return new Animation(device,age>=0&&age<InitiatorLifecycle.PLACE_TICKS?InitiatorLifecycle.placed(now-age):InitiatorLifecycle.loaded(device.active(),now));});
        animation.life.observe(device.active(),now);animations.put(device.id(),new Animation(device,animation.life));
        var pose=animation.life.sample(now,MirrorClient.preferences.reducedMotion);
        return pose.withMood(switch(device.mood()){case "preparing"->InitiatorLifecycle.Mood.PREPARING;case "sharing"->InitiatorLifecycle.Mood.SHARING;case "locked"->InitiatorLifecycle.Mood.LOCKED;default->device.active()?InitiatorLifecycle.Mood.ACTIVE:InitiatorLifecycle.Mood.DORMANT;});
    }
    public static List<Ghost> ghosts(){double now=time();ghosts.removeIf(g->g.life.expired(now));return ghosts.stream().map(g->new Ghost(g.device,g.life.sample(now,MirrorClient.preferences.reducedMotion))).toList();}
    public static boolean at(BlockPos pos){return positions.contains(pos);}
    public static void sync(){
        Minecraft mc=Minecraft.getInstance();Set<BlockPos> next=new HashSet<>();
        if(level!=mc.level){animations.clear();ghosts.clear();}
        for(String id:new ArrayList<>(animations.keySet()))if(!MirrorClient.devices.containsKey(id)){
            var old=animations.remove(id);var d=old.device;var pos=new BlockPos(d.x(),d.y(),d.z());
            if(level==mc.level&&level!=null&&level.hasChunkAt(pos)&&!level.getBlockState(pos).is(org.hurtorius.mirror.Mirror.INITIATOR)&&ghosts.size()<128)ghosts.add(new Animation(d,InitiatorLifecycle.broken(old.life.sample(time(),MirrorClient.preferences.reducedMotion),time())));
        }
        for(var d:MirrorClient.devices.values())next.add(new BlockPos(d.x(),d.y(),d.z()));
        positions=Set.copyOf(next);level=mc.level;
    }
    public static void tick(){if(level!=Minecraft.getInstance().level){MirrorClient.devices.clear();sync();}}
}
