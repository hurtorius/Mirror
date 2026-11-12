package org.hurtorius.mirror.core;

import java.util.*;

public final class Anchor {
    public static final java.util.regex.Pattern DIMENSION=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    public String id=UUID.randomUUID().toString(), owner="", dimension="minecraft:overworld";
    public int x,y,z;
    public long revision=0;
    public long contentVersion=0;
    public long createdMillis=System.currentTimeMillis();
    public long motionMillis=createdMillis;
    public int[] light=null;
    public int lightLevel=0;
    public boolean finished=false;
    public String boardMedia="";
    public String migrationNote="";
    public ScreenSpec spec=new ScreenSpec();
    public boolean playing=false;
    public double position=0;
    public long timelineMillis=0;
    public Anchor copy(){return ScreenSpec.JSON.fromJson(ScreenSpec.JSON.toJson(this),Anchor.class);}
    /** What every viewer may receive: never the address, site rules, audience or share lists, show script, redstone choices or owned light. A linked screen shows its root's content and timeline. */
    public Anchor view(Anchor root,String nearest){
        Anchor v=copy();ScreenSpec s=v.spec;v.migrationNote="";v.light=null;v.lightLevel=0;s.url="";s.allowedSites.clear();s.blockedSites.clear();s.viewers.clear();s.sharePlayers.clear();s.show.clear();s.branches.clear();
        if(root!=this){ScreenSpec r=root.spec;v.boardMedia=root.boardMedia;s.text=r.text;s.source=r.source;s.playlist=new ArrayList<>(r.playlist);s.item=r.item;s.loop=r.loop;s.slideSeconds=r.slideSeconds;s.subtitle=r.subtitle;s.live=spec.live&&r.live;s.entrance=r.entrance;s.easeSeconds=r.easeSeconds;s.floatAmount=r.floatAmount;s.swayDegrees=r.swayDegrees;s.path=new ArrayList<>(r.path);s.pathLoop=r.pathLoop;v.motionMillis=root.motionMillis;v.position=root.position;v.playing=root.playing;v.timelineMillis=root.timelineMillis;}
        if(s.facing==ScreenSpec.Facing.NEAREST_PLAYER&&!nearest.isEmpty())s.target=nearest;
        return v;
    }
    public double positionAt(long now){return position+(playing?Math.max(0,now-timelineMillis)/1000.0:0);}
    public void seek(double seconds,long now){ScreenSpec.range(seconds,0,86400,"Playback position");position=seconds;timelineMillis=now;finished=false;}
    public void play(boolean play,long now){position=Math.min(86400,positionAt(now));timelineMillis=now;playing=play;}
    public void validate(){ScreenSpec.text(migrationNote,512,"Migration note");ScreenSpec.uuidOrEmpty(boardMedia);ScreenSpec.uuidOrEmpty(id);ScreenSpec.uuidOrEmpty(owner);if(id.isEmpty()||owner.isEmpty()||dimension==null||!DIMENSION.matcher(dimension).matches()||revision<0||contentVersion<0||createdMillis<0||Math.abs((long)x)>30000000||Math.abs((long)z)>30000000||y< -2048||y>4096)throw new IllegalArgumentException("Invalid Initiator identity or position.");if(light!=null&&(light.length!=3||lightLevel<0||lightLevel>15||Math.abs((long)light[0])>30000000||Math.abs((long)light[2])>30000000||light[1]< -2048||light[1]>4096))throw new IllegalArgumentException("Invalid owned light position.");spec.validate();ScreenSpec.range(position,0,86400,"Playback position");}
}
