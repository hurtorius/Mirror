package org.hurtorius.mirror.core;

import java.nio.file.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorldStore {
    public int schema=1;
    public String world=UUID.randomUUID().toString();
    public Map<String,Anchor> anchors=new LinkedHashMap<>();
    public Map<String,Media> media=new LinkedHashMap<>();
    public Map<String,List<String>> groups=new LinkedHashMap<>();
    public Map<String,Double> durations=new LinkedHashMap<>();
    public Map<String,Integer> pages=new LinkedHashMap<>();
    public List<Light> pendingLights=new ArrayList<>();
    public List<String> audit=new ArrayList<>();
    public boolean emergency=false, browserEnabled=true, mediaEnabled=true, sharingEnabled=true;
    public long mediaQuota=1024L*1024*1024;
    public int maxScreens=128, maxPublishFps=60, maxFrameBytes=240000;
    public record Media(String id,String hash,String name,String folder,String kind,long bytes,String addedBy){}
    public static Media validateMedia(Media m){
        if(m==null||ScreenSpec.uuidOrEmpty(m.id).isEmpty()||m.hash==null||!m.hash.matches("[a-f0-9]{64}")||m.bytes<1||m.bytes>512L*1024*1024||!Set.of("image","video","audio","pdf","subtitle").contains(m.kind))throw new IllegalArgumentException("Invalid world media metadata.");
        ScreenSpec.text(m.name,128,"Media name");ScreenSpec.text(m.folder,64,"Media folder");ScreenSpec.uuidOrEmpty(m.addedBy);return m;
    }
    public record Light(String dimension,int x,int y,int z,int level){}

    public static WorldStore load(Path file)throws IOException{
        if(!Files.exists(file))return new WorldStore();
        AtomicFile.rejectLinks(file);
        if(Files.size(file)>8*1024*1024)throw new IOException("Mirror world settings are too large; original file preserved.");
        try{WorldStore s=ScreenSpec.JSON.fromJson(Files.readString(file),WorldStore.class);if(s!=null&&s.anchors!=null)for(Anchor a:s.anchors.values())if(a!=null&&a.spec!=null&&Double.isFinite(a.spec.yaw)&&Math.abs(a.spec.yaw)>360)a.spec.yaw=ScreenSpec.wrapDegrees(a.spec.yaw);s.validate();
            for(Anchor a:s.anchors.values()){a.timelineMillis=System.currentTimeMillis();a.motionMillis=a.timelineMillis;if(a.spec.source==ScreenSpec.Source.WEB||a.spec.source==ScreenSpec.Source.SHARE){a.spec.live=false;a.playing=false;}}
            return s;
        }catch(RuntimeException e){throw new IOException("Cannot read Mirror world settings; original file preserved.",e);}
    }
    public void save(Path file)throws IOException{validate();AtomicFile.write(file,ScreenSpec.JSON.toJson(this).getBytes(StandardCharsets.UTF_8));}
    public void validate(){
        if(schema!=1||world==null||ScreenSpec.uuidOrEmpty(world).isEmpty()||anchors==null||anchors.size()>128||media==null||media.size()>4096||groups==null||groups.size()>128||audit==null||audit.size()>1000||durations==null||durations.size()>4096)throw new IllegalArgumentException("Unsupported or invalid Mirror world settings.");
        for(var e:durations.entrySet()){if(!media.containsKey(e.getKey()))throw new IllegalArgumentException("Missing duration media.");ScreenSpec.range(e.getValue(),0,86400,"Media duration");}
        if(pages==null||pages.size()>4096)throw new IllegalArgumentException("Invalid PDF page information.");for(var e:pages.entrySet())if(!media.containsKey(e.getKey())||e.getValue()<0||e.getValue()>1000)throw new IllegalArgumentException("Invalid PDF page information.");
        if(pendingLights==null||pendingLights.size()>4096)throw new IllegalArgumentException("Invalid pending light cleanup.");for(Light l:pendingLights)if(l==null||l.dimension==null||!l.dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")||Math.abs((long)l.x)>30000000||Math.abs((long)l.z)>30000000||l.y< -2048||l.y>4096||l.level<0||l.level>15)throw new IllegalArgumentException("Invalid pending light cleanup.");
        if(mediaQuota<1024*1024||mediaQuota>100L*1024*1024*1024||maxScreens<1||maxScreens>128||maxPublishFps<1||maxPublishFps>60||maxFrameBytes<10000||maxFrameBytes>240000)throw new IllegalArgumentException("Invalid server limits.");
        for(var e:anchors.entrySet()){e.getValue().validate();if(!e.getKey().equals(e.getValue().id))throw new IllegalArgumentException("Initiator identity mismatch.");}
        for(var e:media.entrySet()){Media m=e.getValue();if(m==null||!e.getKey().equals(m.id)||ScreenSpec.uuidOrEmpty(m.id).isEmpty()||!m.hash.matches("[a-f0-9]{64}")||m.bytes<1||m.bytes>512L*1024*1024||!Set.of("image","video","audio","pdf","subtitle").contains(m.kind))throw new IllegalArgumentException("Invalid world media.");ScreenSpec.text(m.name,128,"Media name");ScreenSpec.text(m.folder,64,"Media folder");ScreenSpec.uuidOrEmpty(m.addedBy);}
        for(var e:groups.entrySet()){ScreenSpec.text(e.getKey(),32,"Group");if(e.getValue()==null||e.getValue().size()>128)throw new IllegalArgumentException("Invalid group.");for(String id:e.getValue())ScreenSpec.uuidOrEmpty(id);}
    }
    public void log(String actor,String action){audit.add(System.currentTimeMillis()+" "+actor+" "+action);while(audit.size()>1000)audit.remove(0);}
    public long bytes(){return media.values().stream().mapToLong(Media::bytes).sum();}
}
