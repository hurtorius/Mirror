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
    public int maxScreens=128, maxPublishFps=60;
    /** What loading had to reset or drop; never saved. */
    public transient List<String> repairs=new ArrayList<>();
    public record Media(String id,String hash,String name,String folder,String kind,long bytes,String addedBy){}
    public static Media validateMedia(Media m){
        if(m==null||ScreenSpec.uuidOrEmpty(m.id).isEmpty()||m.hash==null||!MediaRepository.HASH.matcher(m.hash).matches()||m.bytes<1||m.bytes>MediaRepository.MAX_FILE||!MediaRepository.KINDS.contains(m.kind))throw new IllegalArgumentException("Invalid world media metadata.");
        ScreenSpec.text(m.name,128,"Media name");ScreenSpec.text(m.folder,64,"Media folder");ScreenSpec.uuidOrEmpty(m.addedBy);return m;
    }
    public record Light(String dimension,int x,int y,int z,int level){}

    /** Unreadable files fail closed, but one bad entry does not close the world (see repair); a repaired file is first copied to a dated backup beside it. */
    public static WorldStore load(Path file)throws IOException{
        if(!Files.exists(file))return new WorldStore();
        AtomicFile.rejectLinks(file);
        if(Files.size(file)>8*1024*1024)throw new IOException("Mirror world settings are too large; original file preserved.");
        WorldStore s;
        try{s=ScreenSpec.JSON.fromJson(Files.readString(file),WorldStore.class);s.limits();s.repair();s.validate();
            for(Anchor a:s.anchors.values()){a.timelineMillis=System.currentTimeMillis();a.motionMillis=a.timelineMillis;if(a.spec.source==ScreenSpec.Source.WEB||a.spec.source==ScreenSpec.Source.SHARE){a.spec.live=false;a.playing=false;}}
        }catch(RuntimeException e){throw new IOException("Cannot read Mirror world settings; original file preserved.",e);}
        if(!s.repairs.isEmpty())Files.copy(file,file.resolveSibling(file.getFileName()+"."+System.currentTimeMillis()+".bak"));
        return s;
    }
    /** The validated settings exactly as world.json stores them. */
    public byte[] json(){validate();return ScreenSpec.JSON.toJson(this).getBytes(StandardCharsets.UTF_8);}
    public void validate(){
        limits();
        for(var e:durations.entrySet())duration(e.getKey(),e.getValue());
        for(var e:pages.entrySet())pages(e.getKey(),e.getValue());
        for(Light l:pendingLights)light(l);
        for(var e:anchors.entrySet())anchor(e.getKey(),e.getValue());
        for(var e:media.entrySet())if(!e.getKey().equals(validateMedia(e.getValue()).id))throw new IllegalArgumentException("Invalid world media.");
        for(var e:groups.entrySet())group(e.getKey(),e.getValue());
    }
    private void limits(){
        if(schema!=1||world==null||ScreenSpec.uuidOrEmpty(world).isEmpty()||anchors==null||anchors.size()>128||media==null||media.size()>4096||groups==null||groups.size()>128||audit==null||audit.size()>1000||durations==null||durations.size()>4096||pages==null||pages.size()>4096||pendingLights==null||pendingLights.size()>4096)throw new IllegalArgumentException("Unsupported or invalid Mirror world settings.");
        if(mediaQuota<1024*1024||mediaQuota>100L*1024*1024*1024||maxScreens<1||maxScreens>128||maxPublishFps<1||maxPublishFps>60)throw new IllegalArgumentException("Invalid server limits.");
    }
    /** Entries a newer rule or a hand edit made invalid: an Initiator keeps its identity and place with default settings, any other bad entry is dropped. */
    private void repair(){
        for(var it=anchors.entrySet().iterator();it.hasNext();){var e=it.next();Anchor a=e.getValue();
            if(a!=null&&a.spec!=null&&Double.isFinite(a.spec.yaw)&&Math.abs(a.spec.yaw)>360)a.spec.yaw=ScreenSpec.wrapDegrees(a.spec.yaw);
            try{anchor(e.getKey(),a);ScreenSpec.budget(a.spec);continue;}catch(RuntimeException invalid){}
            if(a!=null)try{a.spec=new ScreenSpec();a.playing=false;a.position=0;a.migrationNote="Settings from another Mirror version were reset.";anchor(e.getKey(),a);repairs.add("reset the settings of Initiator "+a.id);continue;}catch(RuntimeException invalid){}
            it.remove();repairs.add("dropped an unreadable Initiator");
        }
        drop(media.entrySet(),e->{if(!e.getKey().equals(validateMedia(e.getValue()).id))throw new IllegalArgumentException("Invalid world media.");},"media entry");
        drop(durations.entrySet(),e->duration(e.getKey(),e.getValue()),"media duration");
        drop(pages.entrySet(),e->pages(e.getKey(),e.getValue()),"PDF page count");
        groups.replaceAll((name,players)->players==null?null:new ArrayList<>(new LinkedHashSet<>(players)));drop(groups.entrySet(),e->group(e.getKey(),e.getValue()),"audience group");
        drop(pendingLights,WorldStore::light,"light cleanup");
    }
    private <T> void drop(Collection<T> entries,java.util.function.Consumer<T> check,String label){for(var it=entries.iterator();it.hasNext();){T entry=it.next();try{check.accept(entry);}catch(RuntimeException invalid){it.remove();repairs.add("dropped an invalid "+label);}}}
    private static void anchor(String key,Anchor a){if(a==null||!key.equals(a.id))throw new IllegalArgumentException("Initiator identity mismatch.");a.validate();}
    private void duration(String id,Double seconds){if(!media.containsKey(id)||seconds==null)throw new IllegalArgumentException("Missing duration media.");ScreenSpec.range(seconds,0,86400,"Media duration");}
    private void pages(String id,Integer count){if(!media.containsKey(id)||count==null||count<0||count>1000)throw new IllegalArgumentException("Invalid PDF page information.");}
    private static void group(String name,List<String> players){ScreenSpec.text(name,32,"Group");if(players==null||players.size()>128||new HashSet<>(players).size()!=players.size())throw new IllegalArgumentException("Invalid group.");for(String id:players)if(ScreenSpec.uuidOrEmpty(id).isEmpty())throw new IllegalArgumentException("Invalid group.");}
    private static void light(Light l){if(l==null||l.dimension==null||!Anchor.DIMENSION.matcher(l.dimension).matches()||Math.abs((long)l.x)>30000000||Math.abs((long)l.z)>30000000||l.y< -2048||l.y>4096||l.level<0||l.level>15)throw new IllegalArgumentException("Invalid pending light cleanup.");}
    public void log(String actor,String action){audit.add(System.currentTimeMillis()+" "+actor+" "+action);while(audit.size()>1000)audit.remove(0);}
    public long bytes(){return media.values().stream().mapToLong(Media::bytes).sum();}
}
