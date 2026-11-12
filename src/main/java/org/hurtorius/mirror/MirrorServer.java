package org.hurtorius.mirror;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.hurtorius.mirror.core.*;
import xyz.nucleoid.packettweaker.PacketContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

public final class MirrorServer {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("mirror");
    private static final Executor READER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Mirror media reader");t.setDaemon(true);return t;});
    private MinecraftServer server;
    private WorldStore world;
    private Path save;
    private volatile MediaRepository media;
    private final Consent consent=new Consent();
    private final Map<String,Publisher> publishers=new HashMap<>();
    /** Players running this Mirror protocol, each with the STATE and MEDIA text it last received per screen it holds; "" asks for a resend. */
    private final Map<String,Map<String,String>> clients=new HashMap<>();
    private final Map<String,Set<String>> subscriptions=new HashMap<>();
    private final Map<String,Sight> sights=new HashMap<>();
    private final Map<String,Viewer> viewers=new HashMap<>();
    private final Map<String,String> nearest=new HashMap<>();
    private final Map<String,Long> messageWindow=new HashMap<>();
    private final Map<String,Integer> messageCount=new HashMap<>();
    private final Map<String,Boolean> redstone=new HashMap<>(),area=new HashMap<>();
    private final Map<String,Show> shows=new HashMap<>();
    private final Map<String,Integer> finishPulses=new HashMap<>();
    private final Map<String,MediaWindow> downloads=new HashMap<>();
    private final WorldSaver saver=new WorldSaver();
    private final Map<String,ResourceKey<Level>> levels=new HashMap<>();
    /** Screens whose tick threw, with when they may run again: a broken screen backs off for 5 s at a time and is logged once until it recovers. */
    private final Map<String,Long> failing=new HashMap<>();
    private boolean dirty=false;
    private int ticks;
    private long saves;
    private static final class Publisher {String player,epoch;long last,sequence=-1,budgetWindow,bytes,audioBytes;boolean watched=true;FrameAssembler frames=new FrameAssembler();FrameRateGate rate=new FrameRateGate();byte[] latest;List<DataPayload> parts=List.of();}
    private static final class Show {int cue;long next;ScreenSpec base;}
    /** Frames still queued on one viewer's connection, and the streams whose newest frame waits for room. */
    private static final class Viewer {final java.util.concurrent.atomic.AtomicInteger flight=new java.util.concurrent.atomic.AtomicInteger();final Set<String> missed=new HashSet<>();}
    /** The Initiators last shown to one player object in one level; a respawn or a dimension change makes the client forget them. */
    private record Sight(ServerPlayer player,Object level,List<Device> devices){boolean same(Sight o){return o!=null&&o.player==player&&o.level==level&&o.devices.equals(devices);}}
    private record View(String key,List<MessagePayload> messages){}
    private record Transfer(String player,ServerPlayer target,WorldStore.Media item,MediaWindow window,List<Long> offsets){}
    private record Snapshot(long number,Set<String> retired,CompletableFuture<Void> done){}

    public void start(MinecraftServer server){
        this.server=server;
        // The world folder is resolved through links once, so a moved saves folder works while links inside Mirror's own folders stay refused.
        try{Path root=server.getWorldPath(LevelResource.ROOT).toRealPath().resolve("mirror");save=root.resolve("world.json");WorldStore loaded=WorldStore.load(save);media=new MediaRepository(root.resolve("media"));world=loaded;}
        catch(IOException e){throw new IllegalStateException("Mirror could not load this world's data. The original files were preserved.",e);}
        consent.clear();publishers.clear();clients.clear();subscriptions.clear();sights.clear();viewers.clear();nearest.clear();redstone.clear();area.clear();shows.clear();finishPulses.clear();downloads.clear();levels.clear();failing.clear();ticks=0;
        dirty=!world.repairs.isEmpty();if(dirty)LOG.warn("Mirror repaired this world's settings and kept the original beside it as world.json.*.bak: {}",String.join("; ",world.repairs));
        LOG.info("Mirror ready: {} Initiators; {} media items",world.anchors.size(),world.media.size());
    }
    public void stop(MinecraftServer ignored){
        try{checkpointNow();}catch(IOException|RuntimeException e){LOG.error("Mirror data could not be saved",e);}
        finally{
            try{saver.flush();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            try{if(media!=null)media.close();}catch(IOException e){LOG.warn("Interrupted imports could not be removed",e);}
            for(String id:new ArrayList<>(publishers.keySet()))revoke(id,"World closed");
            publishers.clear();clients.clear();subscriptions.clear();sights.clear();viewers.clear();nearest.clear();shows.clear();downloads.clear();messageCount.clear();messageWindow.clear();world=null;server=null;
        }
    }
    /** Snapshots the world on the server thread and leaves the fsync'd write to the saver, so a slow disk never stalls a tick. */
    private void checkpoint(){Snapshot s=snapshot();if(s==null)return;MinecraftServer host=server;MediaRepository repository=media;s.done().whenComplete((ok,error)->host.execute(()->{if(host.isSameThread())saved(s,repository,error);}));}
    /** The same, returning only once world.json is on disk: before media files are deleted, after an import and when the world closes. */
    private void checkpointNow()throws IOException{
        Snapshot s=snapshot();if(s==null)return;
        try{s.done().get();}catch(InterruptedException e){Thread.currentThread().interrupt();dirty=true;throw new IOException("Mirror stopped before this world was saved.",e);}catch(ExecutionException e){dirty=true;throw e.getCause() instanceof IOException io?io:new IOException("Mirror could not save this world.",e.getCause());}
        saved(s,media,null);
    }
    private Snapshot snapshot(){
        if(world==null)return null;long now=now();
        for(Anchor a:world.anchors.values())if(a.playing){a.position=Math.min(86400,a.positionAt(now));a.timelineMillis=now;}
        snapshotBoards();
        Map<String,ScreenSpec> overlays=new HashMap<>();
        for(var e:shows.entrySet()){Anchor a=world.anchors.get(e.getKey());if(a!=null){overlays.put(a.id,a.spec);a.spec=e.getValue().base;}}
        byte[] json;try{json=world.json();}finally{for(var e:overlays.entrySet())world.anchors.get(e.getKey()).spec=e.getValue();}
        Snapshot s=new Snapshot(++saves,media.retiring(world.media.values()),saver.write(save,json));dirty=false;return s;
    }
    /** Only the newest snapshot of the open world reports: a failed write is retried at the next checkpoint, and a written one lets replaced drawings it no longer references be deleted. */
    private void saved(Snapshot s,MediaRepository repository,Throwable error){
        if(world==null||repository!=media||s.number()!=saves)return;
        if(error!=null){LOG.error("Mirror checkpoint failed",error);dirty=true;for(ServerPlayer p:server.getPlayerList().getPlayers())if(operator(p))error(p,"Mirror could not save. Check disk space and the server log before closing this world.");return;}
        try{media.retire(s.retired(),world.media.values());}catch(IOException e){LOG.warn("A replaced drawing could not be removed",e);}
    }
    public static boolean operator(ServerPlayer p){return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}
    public boolean mayEdit(ServerPlayer p,Anchor a){return operator(p)&&(!a.spec.locked||a.owner.equals(p.getUUID().toString())||p.permissions().hasPermission(Permissions.COMMANDS_OWNER));}
    private boolean maySee(ServerPlayer p,Anchor a){if(world.emergency||p.level().dimension()!=key(a.dimension))return false;double[] center=center(a);return a.spec.canView(p.getUUID().toString(),Math.sqrt(p.distanceToSqr(center[0],center[1],center[2])));}
    private double[] center(Anchor a){ScreenSpec s=a.spec;double x=a.x+.5+s.x,y=a.y+s.y,z=a.z+.5+s.z;if(s.followPlayer&&!s.target.isEmpty()){var target=server.getPlayerList().getPlayer(UUID.fromString(s.target));if(target!=null&&target.level().dimension()==key(a.dimension)){x=target.getX()+s.x;y=target.getY()+s.y;z=target.getZ()+s.z;}}if(s.facing==ScreenSpec.Facing.ORBIT){double angle=now()/1000.0*2*Math.PI/s.orbitSeconds;x+=Math.sin(angle)*s.orbitRadius;z+=Math.cos(angle)*s.orbitRadius;}if(!s.path.isEmpty()){var path=MotionPath.sample(s,a.motionMillis,now());x=a.x+.5+path.x();y=a.y+path.y();z=a.z+.5+path.z();}return new double[]{x,y,z};}
    private boolean enabled(Anchor a){return switch(a.spec.source){case WEB->world.browserEnabled;case MEDIA->world.mediaEnabled;case SHARE->world.sharingEnabled;default->true;};}
    private boolean maySeeContent(ServerPlayer p,Anchor a){Anchor root=effective(a);return enabled(a)&&enabled(root)&&maySee(p,a)&&(root==a||maySee(p,root));}
    private boolean nearby(ServerPlayer p,Anchor a){return p.level().dimension()==key(a.dimension)&&p.distanceToSqr(a.x+.5,a.y+.5,a.z+.5)<=128*128;}
    public Anchor at(String dimension,BlockPos pos){if(world==null)return null;for(Anchor a:world.anchors.values())if(a.dimension.equals(dimension)&&a.x==pos.getX()&&a.y==pos.getY()&&a.z==pos.getZ())return a;return null;}
    public static ItemStack initiator(){return new ItemStack(Mirror.INITIATOR_ITEM);}
    /** Registers a loaded Initiator block under its position; true once it has a screen, false while the world is closed or full. */
    public boolean adopt(ServerLevel level,InitiatorBlockEntity entity){
        if(world==null)return false;BlockPos pos=entity.getBlockPos();String dimension=level.dimension().identifier().toString();Anchor a=at(dimension,pos);
        if(a==null){
            if(world.anchors.size()>=world.maxScreens)return false;
            a=new Anchor();a.x=pos.getX();a.y=pos.getY();a.z=pos.getZ();a.dimension=dimension;a.createdMillis=0;a.owner="00000000-0000-0000-0000-000000000000";
            a.timelineMillis=now();world.anchors.put(a.id,a);dirty=true;world.log(a.owner,"registered native Initiator "+a.id);
        }
        return true;
    }
    /** A right-click on an Initiator block: editors get the editor, everyone else their viewer settings. Placing goes through InitiatorItem.place. */
    public void interact(ServerPlayer p,BlockPos pos){
        if(world==null)return;if(p.level().getBlockEntity(pos) instanceof InitiatorBlockEntity nativeBlock)adopt(p.level(),nativeBlock);Anchor existing=at(p.level().dimension().identifier().toString(),pos);
        if(existing==null){error(p,"This Initiator needs recovery or the world has reached its screen limit.");return;}
        if(!modded(p))p.displayClientMessage(Component.literal(mayEdit(p,existing)?"Install this server's Mirror version to see and edit this screen.":"Install this server's Mirror version to see this screen."),true);else if(mayEdit(p,existing))open(p,existing);else send(p,"VIEWER_SETTINGS");
    }
    public boolean canPlace(ServerPlayer p){if(world==null)return false;if(!operator(p)){error(p,"Only operators can place an Initiator.");return false;}if(world.anchors.size()>=world.maxScreens){error(p,"This world has reached its Initiator limit.");return false;}return true;}
    public void placed(ServerPlayer p,BlockPos pos){
        if(world==null)return;Anchor a=new Anchor();a.owner=p.getUUID().toString();a.dimension=p.level().dimension().identifier().toString();a.x=pos.getX();a.y=pos.getY();a.z=pos.getZ();a.spec.yaw=ScreenSpec.wrapDegrees(p.getYRot()+180);a.timelineMillis=now();world.anchors.put(a.id,a);
        world.log(a.owner,"placed Initiator "+a.id);dirty=true;broadcast(a);
    }
    public void broken(String dimension,BlockPos pos){Anchor a=at(dimension,pos);if(a!=null){revoke(a.id,"");clearLight(a);world.anchors.remove(a.id);shows.remove(a.id);nearest.remove(a.id);dirty=true;for(ServerPlayer p:server.getPlayerList().getPlayers())send(p,"REMOVE","id",a.id);for(var held:clients.values())held.remove(a.id);for(Anchor linked:world.anchors.values())if(linked.spec.link.equals(a.id))broadcast(linked);}}
    public void join(ServerPlayer p){
        if(world==null||!ServerPlayNetworking.canSend(p,MessagePayload.TYPE))return;
        if(!NativeCompatibility.supported(PacketContext.create(p))){p.sendSystemMessage(Component.literal("This server runs a different Mirror version, so its screens stay hidden. Install the server's Mirror version to see them."));return;}
        String id=p.getUUID().toString();clients.put(id,new HashMap<>());sights.remove(id);send(p,"HELLO","world",world.world,"serverTime",now(),"operator",operator(p),"serverOwner",p.permissions().hasPermission(Permissions.COMMANDS_OWNER),"emergency",world.emergency,"fps",world.maxPublishFps);
    }
    public void leave(ServerPlayer p){String id=p.getUUID().toString();consent.disconnect(id);clients.remove(id);sights.remove(id);viewers.remove(id);subscriptions.remove(id);downloads.remove(id);messageWindow.remove(id);messageCount.remove(id);for(var e:new ArrayList<>(publishers.entrySet()))if(e.getValue().player.equals(id))stopped(e.getKey(),"Publisher disconnected");try{media.disconnect(id);}catch(IOException e){LOG.warn("Could not close an interrupted import",e);}}
    private void open(ServerPlayer p,Anchor a){if(!mayEdit(p,a)||!nearby(p,a))throw new IllegalArgumentException("You cannot edit this Initiator here.");Anchor editing=a.copy();if(shows.containsKey(a.id))editing.spec=shows.get(a.id).base.copy();send(p,"OPEN","anchor",editing,"serverOwner",p.permissions().hasPermission(Permissions.COMMANDS_OWNER),"serverTime",now(),"worldBytes",world.bytes(),"quota",world.mediaQuota);library(p);if(!a.migrationNote.isEmpty())send(p,"NOTICE","text",a.migrationNote);}
    private void library(ServerPlayer p){send(p,"LIBRARY_CLEAR");for(WorldStore.Media m:world.media.values())send(p,"MEDIA","media",m);send(p,"GROUP_CLEAR");for(var e:world.groups.entrySet())send(p,"GROUP","name",e.getKey(),"players",e.getValue());send(p,"PLAYERS","players",server.getPlayerList().getPlayers().stream().map(v->Map.of("id",v.getUUID().toString(),"name",v.getGameProfile().name())).toList());}
    public void message(ServerPlayer p,String text){
        if(world==null||text.length()>24000)return;String actor=p.getUUID().toString();if(!clients.containsKey(actor))return;long now=now();
        if(now-messageWindow.getOrDefault(actor,0L)>=1000){messageWindow.put(actor,now);messageCount.put(actor,0);}
        int count=messageCount.merge(actor,1,Integer::sum);if(count>40)return;
        try{
            JsonObject m=JsonParser.parseString(text).getAsJsonObject();String action=str(m,"action",32);
            if(action.equals("SYNC")){send(p,"SYNC","sent",m.get("sent").getAsLong(),"time",now);return;}
            if(action.equals("STOP_SHARING")){String target=m.has("id")?str(m,"id",36):"";for(var e:new ArrayList<>(publishers.entrySet()))if(e.getValue().player.equals(actor)&&(target.isEmpty()||target.equals(e.getKey())))stopped(e.getKey(),"");return;}
            if(action.equals("CONSENT_DECLINE")){consent.decline(str(m,"request",36),actor);send(p,"SHARE_STOP","reason","Request declined");return;}
            if(action.equals("PRIVATE_BROWSER")){String id=str(m,"id",36);Publisher pub=publishers.get(id);Anchor a=world.anchors.get(id);if(a==null||pub==null||!pub.player.equals(actor)||a.spec.source!=ScreenSpec.Source.WEB)return;publishers.remove(id);consent.revoke(id);a.spec.live=false;changed(a);for(ServerPlayer viewer:server.getPlayerList().getPlayers())send(viewer,"STREAM_STOP","id",id,"epoch",pub.epoch,"keepPreview",viewer==p);send(p,"CONTROL_ACK","anchor",a);return;}
            if(action.equals("HEARTBEAT")){Publisher pub=publishers.get(str(m,"id",36));if(pub!=null&&pub.player.equals(actor)&&pub.epoch.equals(str(m,"epoch",36)))pub.last=now;return;}
            if(action.equals("SUBSCRIBE")){
                var list=m.getAsJsonArray("streams");if(list==null||list.size()>10)throw new IllegalArgumentException("Too many requested streams");
                Set<String> requested=new HashSet<>();for(var value:list){String id=ScreenSpec.uuidOrEmpty(value.getAsString());if(!id.isEmpty())requested.add(id);}
                Set<String> previous=subscriptions.put(actor,requested);
                for(String id:requested)if(previous==null||!previous.contains(id)){Anchor screen=world.anchors.get(id);Publisher pub=publishers.get(id);if(screen!=null&&pub!=null&&receives(p,screen,pub)){watched(id,pub,true);sendPicture(p,pub,id);}}
                return;
            }
            if(action.equals("GET_MEDIA")){download(p,m);return;}
            if(action.equals("CONSENT_ACCEPT")){accept(p,m);return;}
            if(!operator(p))throw new IllegalArgumentException("Only operators can change Mirror.");
            if(action.equals("MEDIA_INFO")){String id=str(m,"media",36);if(!world.media.containsKey(id))return;double duration=m.get("duration").getAsDouble();ScreenSpec.range(duration,0,86400,"Media duration");int pages=m.has("pages")?m.get("pages").getAsInt():0;if(pages<0||pages>1000)throw new IllegalArgumentException("PDFs support up to 1,000 pages.");world.durations.put(id,duration);world.pages.put(id,pages);dirty=true;return;}
            if(action.equals("IMPORT")){if(!world.mediaEnabled)throw new IOException("The server owner disabled media imports.");if(world.media.size()+media.pendingCount()>=4096)throw new IOException("The world's media library is full. Remove unused items before importing more.");String id=media.begin(actor,str(m,"name",128),str(m,"folder",64),str(m,"kind",8),m.get("bytes").getAsLong(),world.mediaQuota,world.bytes(),now);send(p,"IMPORT_READY","id",id);return;}
            if(action.equals("IMPORT_CANCEL")){media.disconnect(actor);return;}
            if(action.equals("GROUP_SAVE")){String name=str(m,"name",32);if(name.isBlank())throw new IllegalArgumentException("Name the audience group.");List<String> ids=new ArrayList<>();for(JsonElement e:m.getAsJsonArray("players")){String id=e.getAsString();if(ScreenSpec.uuidOrEmpty(id).isEmpty()||ids.size()>=128||ids.contains(id))throw new IllegalArgumentException("Invalid audience group.");ids.add(id);}if(world.groups.size()>=128&&!world.groups.containsKey(name))throw new IllegalArgumentException("The group limit has been reached.");
                // Every screen using the group must still be valid, and fit its message, with the new audience before anything changes.
                List<Anchor> grouped=world.anchors.values().stream().filter(a->a.spec.group.equals(name)).toList();for(Anchor a:grouped){ScreenSpec check=a.spec.copy();check.viewers=new ArrayList<>(ids);check.audience=ScreenSpec.Audience.SELECTED;check.validate();ScreenSpec.budget(check);}
                world.groups.put(name,ids);for(Anchor a:grouped){revoke(a.id,"Audience group changed; sharing needs fresh consent");a.spec.viewers=new ArrayList<>(ids);a.spec.audience=ScreenSpec.Audience.SELECTED;a.revision++;changed(a);}dirty=true;send(p,"GROUP","name",name,"players",ids,"saved",true);return;}
            if(action.equals("SERVER_STATUS")){if(!p.permissions().hasPermission(Permissions.COMMANDS_OWNER))throw new IllegalArgumentException("Only the server owner can change these world controls.");serverStatus(p);return;}
            if(action.equals("SERVER_CONTROL")){serverControl(p,m);return;}
            if(action.equals("MEDIA_EDIT")){String id=str(m,"media",36);WorldStore.Media old=world.media.get(id);if(old==null)throw new IllegalArgumentException("This media was removed.");WorldStore.Media updated=new WorldStore.Media(old.id(),old.hash(),str(m,"name",128),str(m,"folder",64),old.kind(),old.bytes(),old.addedBy());world.media.put(id,updated);world.log(actor,"renamed media "+id);dirty=true;for(ServerPlayer viewer:server.getPlayerList().getPlayers())if(operator(viewer))send(viewer,"MEDIA","media",updated);return;}
            if(action.equals("TIDY")){tidy(p);return;}
            if(action.equals("DELETE_MEDIA")){deleteMedia(p,str(m,"media",36));return;}
            Anchor a=world.anchors.get(str(m,"id",36));if(a==null||!mayEdit(p,a)||!nearby(p,a))throw new IllegalArgumentException("This Initiator is unavailable, locked or too far away.");
            switch(action){
                case "OPEN"->open(p,a);
                case "SAVE"->save(p,a,m);
                case "SUMMON"->{a.motionMillis=now();a.spec.live=true;changed(a);}
                case "DISMISS"->dismiss(a);
                case "PLAY"->{if(a.finished)a.seek(0,now);a.finished=false;a.play(true,now);changed(a);}
                case "PAUSE"->{a.play(false,now);changed(a);}
                case "SEEK"->{a.seek(m.get("seconds").getAsDouble(),now);changed(a);}
                case "NEXT"->next(a);
                case "SHOW"->show(a);
                case "BOARD_SAVE"->{Publisher pub=publishers.get(a.id);if(pub!=null&&aWhiteboard(a.id)&&!snapshotBoard(a,pub))return;if(a.boardMedia.isEmpty())throw new IllegalArgumentException("Wait for the drawing to arrive before saving.");checkpoint();send(p,"NOTICE","text","Drawing saved into this world.");}
                case "PUBLISH_WEB"->publish(p,a);
                case "PAGE_CHANGED"->{if(a.spec.source==ScreenSpec.Source.WEB&&publishers.containsKey(a.id)&&publishers.get(a.id).player.equals(actor)){a.contentVersion++;changed(a);}}
                case "REQUEST_SHARE"->request(p,a,str(m,"player",36));
                case "WHITEBOARD"->{if(a.spec.source!=ScreenSpec.Source.WHITEBOARD)throw new IllegalArgumentException("Choose the whiteboard source first.");Publisher pub=publishers.get(a.id);if(pub==null||!pub.player.equals(actor))pub=newPublisher(p,a);send(p,"PUBLISH","id",a.id,"epoch",pub.epoch,"source","WHITEBOARD");}
                default->throw new IllegalArgumentException("This Mirror action is not supported.");
            }
            send(p,"CONTROL_ACK","anchor",a);
        }catch(Exception e){if(e instanceof IOException)LOG.warn("Mirror storage action failed",e);error(p,e.getMessage()==null?"Mirror could not complete this action.":e.getMessage());}
    }
    private boolean aWhiteboard(String id){Anchor a=world.anchors.get(id);return a!=null&&a.spec.source==ScreenSpec.Source.WHITEBOARD;}
    private void snapshotBoards(){for(var e:publishers.entrySet())if(aWhiteboard(e.getKey()))snapshotBoard(world.anchors.get(e.getKey()),e.getValue());}
    private boolean snapshotBoard(Anchor a,Publisher pub){
        if(pub.latest==null)return false;
        try{
            WorldStore.Media previous=world.media.get(a.boardMedia);
            if(previous==null&&world.media.size()+media.pendingCount()>=4096)throw new IOException("The world's media library is full. Remove unused items before saving a drawing.");
            WorldStore.Media saved=media.storeBoard(a.boardMedia,a.owner,a.spec.name+" drawing",pub.latest,world.mediaQuota-world.bytes()+(previous==null?0:previous.bytes()));
            if(previous!=null&&!previous.hash().equals(saved.hash()))media.supersede(previous.hash());
            a.boardMedia=saved.id();world.media.put(saved.id(),saved);dirty=true;return true;
        }catch(IOException e){LOG.warn("Mirror drawing could not be saved",e);ServerPlayer player=server.getPlayerList().getPlayer(UUID.fromString(pub.player));if(player!=null)error(player,e.getMessage());return false;}
    }
    private void serverStatus(ServerPlayer p){send(p,"SERVER_STATUS","browser",world.browserEnabled,"media",world.mediaEnabled,"sharing",world.sharingEnabled,"quota",world.mediaQuota,"worldBytes",world.bytes(),"fps",world.maxPublishFps);}
    private void serverControl(ServerPlayer p,JsonObject m){
        if(!p.permissions().hasPermission(Permissions.COMMANDS_OWNER))throw new IllegalArgumentException("Only the server owner can change these world controls.");
        String setting=str(m,"setting",20);
        if(setting.equals("fps")){int fps=m.get("fps").getAsInt();if(fps!=30&&fps!=60)throw new IllegalArgumentException("Choose 30 or 60 shared frames per second.");world.maxPublishFps=fps;for(var viewer:server.getPlayerList().getPlayers())serverStatus(viewer);}
        else if(setting.equals("quota")){
            long mb=m.get("megabytes").getAsLong();if(mb<1||mb>102400)throw new IllegalArgumentException("Choose a quota from 1 to 102400 MB.");world.mediaQuota=mb*1024*1024;
        }else{
            boolean on=m.get("enabled").getAsBoolean();
            switch(setting){case "emergency"->world.emergency=on;case "browser"->world.browserEnabled=on;case "media"->world.mediaEnabled=on;case "sharing"->world.sharingEnabled=on;default->throw new IllegalArgumentException("Unknown world control.");}
            if(world.emergency||!on&&!setting.equals("emergency"))for(Anchor a:world.anchors.values())if(world.emergency||!enabled(a)){stopShow(a);a.play(false,now());a.spec.live=false;revoke(a.id,"Source stopped by server owner");changed(a);}
            if(setting.equals("emergency")){for(var held:clients.values())held.replaceAll((k,v)->"");for(ServerPlayer viewer:server.getPlayerList().getPlayers())send(viewer,"EMERGENCY","enabled",on);}
        }
        if(m.has("id")){Anchor edited=world.anchors.get(str(m,"id",36));if(edited!=null&&mayEdit(p,edited))send(p,"CONTROL_ACK","anchor",edited);}
        world.log(p.getUUID().toString(),"changed world control "+setting);dirty=true;checkpoint();send(p,"WORLD_SIZE","worldBytes",world.bytes(),"quota",world.mediaQuota);serverStatus(p);
    }
    private void save(ServerPlayer p,Anchor a,JsonObject m){
        if(m.get("revision").getAsLong()!=a.revision)throw new IllegalArgumentException("Another operator changed this screen. Reopen it before saving.");
        ScreenSpec spec=ScreenSpec.read(m.get("spec").toString());
        if(spec.source==ScreenSpec.Source.WEB&&!world.browserEnabled||spec.source==ScreenSpec.Source.MEDIA&&!world.mediaEnabled||spec.source==ScreenSpec.Source.SHARE&&!world.sharingEnabled)throw new IllegalArgumentException("The server owner disabled this source.");
        for(String id:spec.playlist)if(!world.media.containsKey(id)||world.media.get(id).kind().equals("subtitle"))throw new IllegalArgumentException("Choose playable media for the playlist.");
        if(world.media.containsKey(spec.subtitle)&&!world.media.get(spec.subtitle).kind().equals("subtitle"))throw new IllegalArgumentException("Choose a subtitle file or enter plain subtitle text.");
        for(ScreenSpec.Cue cue:spec.show)if(!cue.media().isEmpty()&&!world.media.containsKey(cue.media()))throw new IllegalArgumentException("A show cue uses missing media.");
        if(!spec.group.isEmpty()){List<String> group=world.groups.get(spec.group);if(group==null)throw new IllegalArgumentException("Choose a saved audience group.");spec.audience=ScreenSpec.Audience.SELECTED;spec.viewers=new ArrayList<>(group);spec.validate();ScreenSpec.budget(spec);}
        if(!spec.link.isEmpty()){Anchor linked=world.anchors.get(spec.link);if(linked==null||linked==a||!linked.dimension.equals(a.dimension)||!linked.spec.link.isEmpty()||!mayEdit(p,linked))throw new IllegalArgumentException("Link to an unlinked Initiator you can edit in this world.");}
        boolean browserPolicyChanged=(spec.source==ScreenSpec.Source.WEB||a.spec.source==ScreenSpec.Source.WEB)&&(!spec.allowedSites.equals(a.spec.allowedSites)||!spec.blockedSites.equals(a.spec.blockedSites));
        boolean sourceChanged=browserPolicyChanged||spec.source!=a.spec.source||spec.source==ScreenSpec.Source.MEDIA&&!spec.currentMedia().equals(a.spec.currentMedia())||!spec.link.equals(a.spec.link);
        if(browserPolicyChanged)for(ServerPlayer viewer:server.getPlayerList().getPlayers())send(viewer,"BROWSER_POLICY_CHANGED","id",a.id);
        boolean privacyChanged=!Consent.audience(spec).equals(Consent.audience(a.spec))||(spec.source==ScreenSpec.Source.SHARE||a.spec.source==ScreenSpec.Source.SHARE)&&(spec.allowSharing!=a.spec.allowSharing||!spec.sharePlayers.equals(a.spec.sharePlayers));
        if(sourceChanged||privacyChanged||!spec.live){revoke(a.id,privacyChanged?"Audience changed; choose the source again":"");if(sourceChanged){a.seek(0,now());a.playing=false;}}
        if(spec.locked&&!a.spec.locked)a.owner=p.getUUID().toString();
        if(!a.spec.live&&spec.live||!a.spec.path.equals(spec.path))a.motionMillis=now();stopShow(a);a.spec=spec;a.migrationNote="";a.revision++;world.log(p.getUUID().toString(),"changed Initiator "+a.id);changed(a);send(p,"SAVED","anchor",a);
    }
    private Publisher newPublisher(ServerPlayer p,Anchor a){
        if(world.emergency||!enabled(a))throw new IllegalArgumentException("This source is stopped by the server owner.");
        Publisher existing=publishers.get(a.id);if(existing!=null&&existing.player.equals(p.getUUID().toString())){existing.last=now();send(p,"PUBLISH","id",a.id,"epoch",existing.epoch,"source",a.spec.source.name());return existing;}
        if(publishers.size()>=8&&!publishers.containsKey(a.id))throw new IllegalArgumentException("Up to eight live sources can broadcast at once.");
        revoke(a.id,"Publisher replaced");Publisher pub=new Publisher();pub.player=p.getUUID().toString();pub.epoch=UUID.randomUUID().toString();pub.last=now();publishers.put(a.id,pub);a.spec.live=true;changed(a);send(p,"PUBLISH","id",a.id,"epoch",pub.epoch,"source",a.spec.source.name());return pub;
    }
    private void publish(ServerPlayer p,Anchor a){if(!world.browserEnabled||a.spec.source!=ScreenSpec.Source.WEB||!a.spec.siteAllowed(a.spec.url))throw new IllegalArgumentException("Choose an allowed website in Source first.");newPublisher(p,a);}
    private void request(ServerPlayer p,Anchor a,String player){
        if(world.emergency||!world.sharingEnabled||!a.spec.allowSharing||a.spec.source!=ScreenSpec.Source.SHARE||(!a.spec.sharePlayers.isEmpty()&&!a.spec.sharePlayers.contains(player)))throw new IllegalArgumentException("Sharing is not allowed for this player and screen.");
        ServerPlayer target=server.getPlayerList().getPlayer(UUID.fromString(player));if(target==null||!modded(target))throw new IllegalArgumentException("Choose a connected player with Mirror.");
        Consent.Request request=consent.request(a.id,p.getUUID().toString(),player,Consent.audience(a.spec),now());
        send(target,"SHARE_REQUEST","request",request,"operatorName",p.getGameProfile().name(),"screenName",a.spec.name,"audienceText",audienceText(a.spec));
    }
    private String audienceText(ScreenSpec spec){if(spec.audience==ScreenSpec.Audience.NOBODY)return "Nobody";if(spec.audience==ScreenSpec.Audience.NEARBY)return "Everyone within "+(int)spec.viewRange+" blocks";return "Selected players: "+spec.viewers.stream().map(id->{var p=server.getPlayerList().getPlayer(UUID.fromString(id));return p==null?id:p.getGameProfile().name();}).reduce((a,b)->a+", "+b).orElse("nobody");}
    private void accept(ServerPlayer p,JsonObject m){
        Anchor a=world.anchors.get(str(m,"id",36));if(a==null||world.emergency||!world.sharingEnabled||!a.spec.allowSharing||a.spec.source!=ScreenSpec.Source.SHARE)throw new IllegalArgumentException("Sharing is no longer available for this screen.");
        Consent.Grant grant=consent.accept(str(m,"request",36),a.id,p.getUUID().toString(),Consent.audience(a.spec),now());
        Publisher old=publishers.remove(a.id);if(old!=null){ServerPlayer previous=server.getPlayerList().getPlayer(UUID.fromString(old.player));if(previous!=null)send(previous,"SHARE_STOP","id",a.id,"epoch",old.epoch,"reason","Sharing replaced");}
        Publisher pub=new Publisher();pub.player=grant.publisher();pub.epoch=grant.epoch();pub.last=now();publishers.put(a.id,pub);a.spec.live=true;changed(a);send(p,"SHARE_START","id",a.id,"epoch",pub.epoch);
    }
    private void revoke(String id,String reason){consent.revoke(id);Publisher pub=publishers.remove(id);if(pub!=null&&server!=null){if(aWhiteboard(id))snapshotBoard(world.anchors.get(id),pub);ServerPlayer player=server.getPlayerList().getPlayer(UUID.fromString(pub.player));if(player!=null)send(player,"SHARE_STOP","id",id,"epoch",pub.epoch,"reason",reason);for(ServerPlayer p:server.getPlayerList().getPlayers())send(p,"STREAM_STOP","id",id,"epoch",pub.epoch);}}
    public void data(ServerPlayer p,DataPayload packet){
        String actor=p.getUUID().toString();if(world==null||packet.bytes().length>28000||!clients.containsKey(actor))return;
        try{
            if(packet.kind()==0){if(!operator(p))return;String upload=packet.id();long end=packet.number()+packet.bytes().length;
                if(!media.write(upload,actor,packet.number(),packet.bytes(),now())){if(end%((long)MediaRepository.CHUNK*MediaRepository.BATCH)==0)send(p,"IMPORT_ACK","id",upload,"offset",end);return;}
                // The last byte is in: its fsync and type check run on the media thread, so a large import never stalls the tick.
                MediaRepository repository=media;MinecraftServer host=server;READER.execute(()->{String failure=null;try{repository.seal(upload);}catch(IOException e){failure=e.getMessage()==null?"The import could not be saved.":e.getMessage();}String error=failure;host.execute(()->imported(p,actor,repository,upload,error));});return;}
            Anchor a=world.anchors.get(packet.id());Publisher pub=publishers.get(packet.id());if(a==null||pub==null||world.emergency||!a.spec.live||!pub.player.equals(actor)||!pub.epoch.equals(packet.epoch()))return;
            if(a.spec.source!=ScreenSpec.Source.SHARE&&!mayEdit(p,a))return;
            if(now()-pub.budgetWindow>=1000){pub.budgetWindow=now();pub.bytes=0;pub.audioBytes=0;}
            pub.bytes+=packet.bytes().length;if(pub.bytes>world.maxPublishFps*240000L+240000)return;
            pub.last=now();
            if(packet.kind()==1){byte[] complete=pub.frames.add(packet.number(),packet.part(),packet.parts(),packet.bytes(),now());if(complete!=null&&FrameAssembler.validJpeg(complete)&&consented(a,pub)&&pub.rate.allow(now(),world.maxPublishFps)){pub.latest=complete;pub.sequence=packet.number();pub.parts=DataPayload.picture(a.id,pub.epoch,pub.sequence,complete);for(ServerPlayer viewer:server.getPlayerList().getPlayers())if(receives(viewer,a,pub))sendPicture(viewer,pub,a.id);}}
            else if(packet.kind()==2&&packet.bytes().length<=9600&&packet.bytes().length%4==0&&consented(a,pub)){pub.audioBytes+=packet.bytes().length;if(pub.audioBytes>211200)return;for(ServerPlayer viewer:server.getPlayerList().getPlayers())if(receives(viewer,a,pub))ServerPlayNetworking.send(viewer,packet);}
        }catch(Exception e){error(p,e.getMessage()==null?"The transfer failed.":e.getMessage());}
    }
    /** A sealed import joins the library and the importer hears of it; a failed one, or one the closed world no longer has, reports the problem instead. */
    private void imported(ServerPlayer p,String actor,MediaRepository repository,String upload,String failure){
        if(world==null||media!=repository)return;
        try{if(failure!=null)throw new IOException(failure);WorldStore.Media result=repository.store(upload);world.media.put(result.id(),result);world.log(actor,"imported "+result.id()+" "+result.bytes()+" bytes");dirty=true;checkpointNow();send(p,"IMPORT_DONE","media",result,"worldBytes",world.bytes());}
        catch(IOException|RuntimeException e){error(p,e.getMessage()==null?"The transfer failed.":e.getMessage());}
    }
    /** A shared screen relays only while its grant still matches the publisher, audience and epoch; checked per frame and per sound packet, not per part. */
    private boolean consented(Anchor a,Publisher pub){return a.spec.source!=ScreenSpec.Source.SHARE||consent.allows(a.id,pub.player,Consent.audience(a.spec),pub.epoch);}
    /** Pictures reach only subscribed clients of this protocol that can see a live screen of the stream; a missing subscription receives nothing. */
    private boolean receives(ServerPlayer player,Anchor root,Publisher publisher){
        String id=player.getUUID().toString();if(id.equals(publisher.player)||!clients.containsKey(id))return false;
        Set<String> wanted=subscriptions.get(id);if(wanted==null||!wanted.contains(root.id))return false;
        return world.anchors.values().stream().anyMatch(v->effective(v)==root&&v.spec.live&&maySeeContent(player,v));
    }
    /** At most two frames queue per viewer; a newer one waits as missed and goes out, checked again, once that link drains. */
    private void sendPicture(ServerPlayer player,Publisher pub,String stream){
        if(pub.parts.isEmpty())return;Viewer v=viewers.computeIfAbsent(player.getUUID().toString(),k->new Viewer());
        if(v.flight.get()>=2){v.missed.add(stream);return;}
        v.missed.remove(stream);v.flight.incrementAndGet();var sender=ServerPlayNetworking.getSender(player);
        for(int i=0;i<pub.parts.size();i++)sender.sendPacket(pub.parts.get(i),i==pub.parts.size()-1?f->v.flight.decrementAndGet():null);
    }
    private void resendMissed(){
        for(var e:viewers.entrySet()){Viewer v=e.getValue();if(v.missed.isEmpty()||v.flight.get()>=2)continue;ServerPlayer p=server.getPlayerList().getPlayer(UUID.fromString(e.getKey()));
            for(String id:new ArrayList<>(v.missed)){v.missed.remove(id);Anchor a=world.anchors.get(id);Publisher pub=publishers.get(id);if(p!=null&&a!=null&&pub!=null&&receives(p,a,pub))sendPicture(p,pub,id);}}
    }
    /** Tells a publisher whether anyone receives its pictures, so an unwatched stream uploads one frame a second. */
    private void watched(String id,Publisher pub,boolean watched){if(pub.watched==watched)return;pub.watched=watched;ServerPlayer owner=server.getPlayerList().getPlayer(UUID.fromString(pub.player));if(owner!=null)send(owner,"AUDIENCE","id",id,"epoch",pub.epoch,"watched",watched);}
    private void download(ServerPlayer p,JsonObject m)throws IOException{
        String id=str(m,"media",36),player=p.getUUID().toString();boolean start=m.has("start")&&m.get("start").getAsBoolean();MediaWindow current=downloads.get(player);
        if(!start&&(current==null||!current.media.equals(id)))return;
        WorldStore.Media item=world.media.get(id);if(item==null||!allowedMedia(p,id)){downloads.remove(player);if(start)throw new IOException("This media is not available to you.");return;}
        long offset=m.get("offset").getAsLong();if(offset<0||offset>=item.bytes())throw new IOException("Invalid media position.");
        downloads.put(player,MediaWindow.request(current,id,item.bytes(),offset,start));
    }
    /** Up to four chunks per player and sixteen in all each tick, read and sent off the server thread. The first player served moves on each tick, so more than sixteen downloads all progress. */
    private void sendMedia(){
        if(downloads.isEmpty())return;List<Transfer> active=new ArrayList<>();
        for(var e:new ArrayList<>(downloads.entrySet())){MediaWindow d=e.getValue();if(d.done())continue;ServerPlayer p=server.getPlayerList().getPlayer(UUID.fromString(e.getKey()));WorldStore.Media m=world.media.get(d.media);if(p==null||m==null||!allowedMedia(p,d.media))downloads.remove(e.getKey());else active.add(new Transfer(e.getKey(),p,m,d,new ArrayList<>()));}
        if(active.isEmpty())return;List<List<Long>> shares=MediaWindow.share(active.stream().map(Transfer::window).toList(),ticks,16,4);for(int i=0;i<active.size();i++)active.get(i).offsets().addAll(shares.get(i));
        MediaRepository repository=media;MinecraftServer host=server;String worldId=world.world;
        for(Transfer t:active)if(!t.offsets().isEmpty())READER.execute(()->{try{List<byte[]> chunks=repository.read(t.item().hash(),t.offsets());for(int i=0;i<chunks.size();i++)ServerPlayNetworking.send(t.target(),new DataPayload(0,t.item().id(),worldId,t.offsets().get(i),0,1,chunks.get(i)));}catch(IOException e){host.execute(()->{if(downloads.remove(t.player(),t.window()))send(t.target(),"MEDIA_FAILED","media",t.item().id(),"text","World media could not be read. Ask the map owner to restore the missing file.");});}});
    }
    /** This integrated server's own copy of a media file, so its host plays world media without downloading and storing it again. */
    public Path localMedia(WorldStore.Media m){MediaRepository repository=media;return repository==null?null:repository.stored(m.hash(),m.bytes());}
    private boolean allowedMedia(ServerPlayer p,String id){if(operator(p))return true;for(Anchor a:world.anchors.values())if(a.spec.live&&maySeeContent(p,a)&&(effective(a).spec.playlist.contains(id)||effective(a).spec.subtitle.equals(id)||effective(a).boardMedia.equals(id)))return true;return false;}
    private Anchor effective(Anchor a){Anchor root=world.anchors.get(a.spec.link);return root==null?a:root;}
    private void clearLight(Anchor a){if(a.light==null||server==null)return;var level=level(a.dimension);if(level!=null){BlockPos pos=new BlockPos(a.light[0],a.light[1],a.light[2]);if(!level.hasChunkAt(pos)){world.pendingLights.add(new WorldStore.Light(a.dimension,a.light[0],a.light[1],a.light[2],a.lightLevel));dirty=true;}else clearOwnedLight(level,pos,a.lightLevel);}a.light=null;a.lightLevel=0;}
    /** Only a light block this screen placed, at the level it placed, is removed; a player's own light block stays. */
    private static void clearOwnedLight(ServerLevel level,BlockPos pos,int lightLevel){BlockState state=level.getBlockState(pos);if(state.is(Blocks.LIGHT)&&state.getValue(net.minecraft.world.level.block.LightBlock.LEVEL)==lightLevel)level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());}
    private void light(Anchor a){if(a.light==null&&(a.spec.glow==0||!a.spec.live||world.emergency))return;int brightness=a.spec.live&&!world.emergency?(int)Math.round(a.spec.glow*15):0;double[] center=center(a);BlockPos target=BlockPos.containing(center[0],center[1],center[2]);if(a.light!=null&&(a.light[0]!=target.getX()||a.light[1]!=target.getY()||a.light[2]!=target.getZ()||brightness!=a.lightLevel))clearLight(a);if(brightness==0||a.light!=null)return;var level=level(a.dimension);if(level!=null&&level.hasChunkAt(target)&&level.getBlockState(target).isAir()){level.setBlockAndUpdate(target,Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL,brightness));a.light=new int[]{target.getX(),target.getY(),target.getZ()};a.lightLevel=brightness;dirty=true;}}
    private void deleteMedia(ServerPlayer p,String id)throws IOException{if(world.anchors.values().stream().anyMatch(a->a.boardMedia.equals(id)||a.spec.playlist.contains(id)||a.spec.subtitle.equals(id)||a.spec.show.stream().anyMatch(c->c.media().equals(id))))throw new IOException("Remove this item from playlists and shows before deleting it.");WorldStore.Media old=world.media.remove(id);if(old==null)return;world.durations.remove(id);world.pages.remove(id);dirty=true;checkpointNow();media.tidy(world.media.values().stream().map(WorldStore.Media::hash).collect(java.util.stream.Collectors.toSet()));send(p,"MEDIA_REMOVED","id",id,"worldBytes",world.bytes());}
    private void tidy(ServerPlayer p)throws IOException{Set<String> used=new HashSet<>();for(Anchor a:world.anchors.values()){used.addAll(a.spec.playlist);used.add(a.spec.subtitle);used.add(a.boardMedia);for(ScreenSpec.Cue c:a.spec.show)used.add(c.media());}int before=world.media.size();world.media.keySet().removeIf(id->!used.contains(id));world.durations.keySet().removeIf(id->!world.media.containsKey(id));world.pages.keySet().removeIf(id->!world.media.containsKey(id));dirty=true;checkpointNow();media.tidy(world.media.values().stream().map(WorldStore.Media::hash).collect(java.util.stream.Collectors.toSet()));send(p,"WORLD_SIZE","worldBytes",world.bytes(),"quota",world.mediaQuota);library(p);send(p,"NOTICE","text","Removed "+(before-world.media.size())+" unused library items.");}
    private void next(Anchor a){if(a.spec.playlist.isEmpty())return;a.spec.item=(a.spec.item+1)%a.spec.playlist.size();a.seek(0,now());changed(a);}
    private void changed(Anchor a){dirty=true;var level=level(a.dimension);if(level!=null)level.updateNeighbourForOutputSignal(new BlockPos(a.x,a.y,a.z),Mirror.INITIATOR);broadcast(a);}
    private void broadcast(Anchor a){if(server==null)return;for(Anchor screen:world.anchors.values())if(screen==a||effective(screen)==a)push(screen);}
    /** Sends a screen's view to every client that may see it and does not already hold exactly that view; the view is built once for all of them. */
    private void push(Anchor screen){View v=null;for(ServerPlayer p:server.getPlayerList().getPlayers())if(modded(p)&&maySeeContent(p,screen)){if(v==null)v=view(screen);deliver(p,screen,v);}}
    private void deliver(ServerPlayer p,Anchor a,View v){Map<String,String> held=clients.get(p.getUUID().toString());if(held!=null&&!v.key().equals(held.put(a.id,v.key())))for(MessagePayload m:v.messages())ServerPlayNetworking.send(p,m);}
    private View view(Anchor a){
        Anchor root=effective(a);String target="";if(a.spec.facing==ScreenSpec.Facing.NEAREST_PLAYER){target=nearestPlayer(a);nearest.put(a.id,target);}
        Anchor view=a.view(root,target);Publisher pub=publishers.get(root.id);String publisher="";if(pub!=null){ServerPlayer operator=server.getPlayerList().getPlayer(UUID.fromString(pub.player));publisher=operator==null?"":operator.getGameProfile().name();}
        List<MessagePayload> messages=new ArrayList<>();messages.add(payload("STATE","anchor",view,"stream",root.id,"epoch",pub==null?"":pub.epoch,"publisher",publisher));
        if(!view.spec.playlist.isEmpty())messages.add(mediaMessage(view.spec.playlist.get(view.spec.item)));messages.add(mediaMessage(view.boardMedia));messages.add(mediaMessage(view.spec.subtitle));messages.removeIf(Objects::isNull);
        StringBuilder key=new StringBuilder(root.id);for(MessagePayload m:messages)key.append('\n').append(m.json());return new View(key.toString(),messages);
    }
    private MessagePayload mediaMessage(String id){WorldStore.Media m=world.media.get(id);return m==null?null:payload("MEDIA","media",m);}
    private String nearestPlayer(Anchor a){double[] center=center(a);return server.getPlayerList().getPlayers().stream().filter(v->v.level().dimension()==key(a.dimension)).min(Comparator.comparingDouble(v->v.distanceToSqr(center[0],center[1],center[2]))).map(v->v.getUUID().toString()).orElse("");}
    private void stopped(String id,String reason){revoke(id,reason);Anchor a=world==null?null:world.anchors.get(id);if(a!=null)broadcast(a);}
    private void stopShow(Anchor a){Show previous=shows.remove(a.id);if(previous!=null)a.spec=previous.base;}
    private void dismiss(Anchor a){stopShow(a);a.spec.live=false;a.spec.triggersArmed=false;a.play(false,now());revoke(a.id,"");changed(a);}
    private void show(Anchor a){if(world.emergency||!enabled(a))return;stopShow(a);a.motionMillis=now();Show s=new Show();s.base=a.spec.copy();a.spec=a.spec.copy();s.next=now();shows.put(a.id,s);a.seek(0,now());changed(a);}
    public void tick(MinecraftServer ignored){
        if(world==null)return;ticks++;long now=now();
        if(!publishers.isEmpty())for(var e:new ArrayList<>(publishers.entrySet()))if(now-e.getValue().last>5000)stopped(e.getKey(),"Publisher stopped responding");
        for(Anchor a:new ArrayList<>(world.anchors.values())){Long retry=failing.get(a.id);if(retry!=null&&now<retry)continue;try{tick(a,now);if(retry!=null)failing.remove(a.id);}catch(RuntimeException e){if(failing.put(a.id,now+5000)==null)LOG.error("Mirror screen {} failed; it is retried every 5 s until it recovers",a.id,e);}}
        if(ticks%10==0){
            for(var light:new ArrayList<>(world.pendingLights)){var level=level(light.dimension());BlockPos pos=new BlockPos(light.x(),light.y(),light.z());if(level!=null&&level.hasChunkAt(pos)){clearOwnedLight(level,pos,light.level());world.pendingLights.remove(light);dirty=true;}}
            for(var e:publishers.entrySet()){Anchor a=world.anchors.get(e.getKey());Publisher pub=e.getValue();if(a!=null)watched(e.getKey(),pub,server.getPlayerList().getPlayers().stream().anyMatch(v->receives(v,a,pub)));}
            for(Anchor a:world.anchors.values())if(a.spec.facing==ScreenSpec.Facing.NEAREST_PLAYER&&!nearestPlayer(a).equals(nearest.get(a.id)))push(a);
            // Changes are pushed as they happen; this pass sends newly visible screens, removes the rest, and every 5 s resends only views that differ from what a client holds.
            Map<String,View> built=new HashMap<>();boolean refresh=ticks%100==0;
            for(ServerPlayer p:server.getPlayerList().getPlayers()){
                String id=p.getUUID().toString();Map<String,String> held=clients.get(id);if(held==null)continue;Set<String> current=new HashSet<>();
                List<Device> devices=new ArrayList<>();var here=p.level().dimension();for(Anchor a:world.anchors.values())if(here==key(a.dimension)&&p.distanceToSqr(a.x+.5,a.y+.5,a.z+.5)<128*128){Publisher pub=publishers.get(a.id);String mood=a.spec.locked?"locked":pub!=null?(pub.latest==null?"preparing":a.spec.source==ScreenSpec.Source.SHARE?"sharing":"live"):a.spec.live?"live":"idle";devices.add(new Device(a.id,a.x,a.y,a.z,mood,a.createdMillis,a.spec.live&&!world.emergency));}Sight sight=new Sight(p,p.level(),devices);if(!sight.same(sights.put(id,sight)))send(p,"DEVICES","devices",devices);
                for(Anchor a:world.anchors.values())if(maySeeContent(p,a)){current.add(a.id);String key=held.get(a.id);if(refresh||key==null||key.isEmpty())deliver(p,a,built.computeIfAbsent(a.id,k->view(a)));}
                for(var it=held.keySet().iterator();it.hasNext();){String gone=it.next();if(!current.contains(gone)){it.remove();send(p,"REMOVE","id",gone);}}
            }
        }
        sendMedia();resendMissed();
        if(ticks%200==0){try{media.expire(now);if(dirty)checkpoint();}catch(IOException|RuntimeException e){LOG.error("Mirror checkpoint failed",e);for(ServerPlayer p:server.getPlayerList().getPlayers())if(operator(p))error(p,"Mirror could not save. Check disk space and the server log before closing this world.");}}
    }
    private void tick(Anchor a,long now){
        if(ticks%5==0)light(a);Integer pulse=finishPulses.get(a.id);if(pulse!=null){if(pulse<=0){finishPulses.remove(a.id);a.finished=false;changed(a);}else finishPulses.put(a.id,pulse-1);}
        if(a.playing&&a.spec.source==ScreenSpec.Source.MEDIA&&!a.spec.playlist.isEmpty()&&a.spec.link.isEmpty()&&!world.emergency){String id=a.spec.playlist.get(a.spec.item);WorldStore.Media item=world.media.get(id);double duration=item==null?0:item.kind().equals("image")?a.spec.slideSeconds:item.kind().equals("pdf")?a.spec.slideSeconds*Math.max(1,world.pages.getOrDefault(id,1)):world.durations.getOrDefault(id,0.0);if(duration>0&&a.positionAt(now)>=duration){if(a.spec.item+1<a.spec.playlist.size()||a.spec.loop){next(a);finishPulses.put(a.id,3);}else{a.play(false,now);a.seek(Math.min(86400,duration),now);}a.finished=true;changed(a);}}
        var level=level(a.dimension);if(level==null)return;
        BlockPos pos=new BlockPos(a.x,a.y,a.z);boolean loaded=level.hasChunkAt(pos);
        if(loaded){
            BlockState state=level.getBlockState(pos);
            if(!state.is(Mirror.INITIATOR)){broken(a.dimension,pos);return;}
            boolean active=a.spec.live&&!world.emergency;if(state.getValue(InitiatorBlock.ACTIVE)!=active)level.setBlockAndUpdate(pos,state.setValue(InitiatorBlock.ACTIVE,active));
        }
        if(!world.emergency&&enabled(a)&&a.spec.triggersArmed&&ticks%5==0&&loaded){
            boolean powered=level.hasNeighborSignal(pos),was=redstone.getOrDefault(a.id,false);redstone.put(a.id,powered);
            if(a.spec.redstone==ScreenSpec.Redstone.POWER&&powered!=was){a.spec.live=powered;if(powered)a.motionMillis=now();if(!powered)revoke(a.id,"Redstone dismissed screen");changed(a);}
            if(powered&&!was){if(a.spec.redstone==ScreenSpec.Redstone.PULSE_NEXT)next(a);if(a.spec.trigger==ScreenSpec.Trigger.REDSTONE)show(a);}
            if(world.mediaEnabled)for(ScreenSpec.Branch choice:a.spec.branches){var direction=net.minecraft.core.Direction.valueOf(choice.side());boolean input=level.getSignal(pos.relative(direction),direction)>0;String key=a.id+":"+choice.side();boolean previous=redstone.getOrDefault(key,false);redstone.put(key,input);if(input&&!previous){stopShow(a);revoke(a.id,"Authored film choice");a.spec.source=ScreenSpec.Source.MEDIA;a.spec.item=a.spec.playlist.indexOf(choice.media());if(a.spec.item<0)continue;a.spec.live=true;a.finished=false;a.seek(0,now);a.play(true,now);changed(a);}}
            boolean inside=server.getPlayerList().getPlayers().stream().anyMatch(p->p.level()==level&&p.distanceToSqr(a.x+.5,a.y+.5,a.z+.5)<=a.spec.areaRadius*a.spec.areaRadius);
            if(inside&&!area.getOrDefault(a.id,false)&&a.spec.trigger==ScreenSpec.Trigger.AREA)show(a);area.put(a.id,inside);
        }
        Show s=shows.get(a.id);if(s!=null&&!world.emergency&&now>=s.next){if(s.cue>=a.spec.show.size()){boolean reset=a.spec.resetShow;if(reset){stopShow(a);a.play(false,now);a.seek(0,now);changed(a);}else{s.next=Long.MAX_VALUE;}}else{ScreenSpec.Cue c=a.spec.show.get(s.cue++);applyCue(a,c);s.next=now+(long)(c.seconds()*1000);}}
    }
    private void applyCue(Anchor a,ScreenSpec.Cue c){switch(c.action()){case "summon"->{a.motionMillis=now();a.spec.live=true;}case "dismiss"->{a.spec.live=false;revoke(a.id,"Show dismissed screen");}case "play"->{if(a.finished)a.seek(0,now());a.spec.source=ScreenSpec.Source.MEDIA;if(!c.media().isEmpty()){a.spec.item=a.spec.playlist.indexOf(c.media());if(a.spec.item<0){a.spec.playlist.add(c.media());a.spec.item=a.spec.playlist.size()-1;}a.seek(0,now());}a.spec.live=true;a.play(true,now());}case "pause"->a.play(false,now());case "next"->next(a);case "seek"->a.seek(c.value(),now());case "width"->a.spec.width=c.value();case "height"->a.spec.height=c.value();case "yaw"->a.spec.yaw=c.value();case "x"->a.spec.x=c.value();case "y"->a.spec.y=c.value();case "z"->a.spec.z=c.value();case "pitch"->a.spec.pitch=c.value();case "roll"->a.spec.roll=c.value();default->{}}changed(a);}
    /** Dimension keys are parsed once per name; getLevel still answers null for a dimension that no longer exists. */
    private ResourceKey<Level> key(String dimension){return levels.computeIfAbsent(dimension,d->ResourceKey.create(Registries.DIMENSION,Identifier.parse(d)));}
    private ServerLevel level(String dimension){return server.getLevel(key(dimension));}
    private static long now(){return System.currentTimeMillis();}
    private static String str(JsonObject m,String key,int max){if(!m.has(key)||!m.get(key).isJsonPrimitive())throw new IllegalArgumentException("Missing "+key+".");return ScreenSpec.text(m.get(key).getAsString(),max,key);}
    private boolean modded(ServerPlayer p){return clients.containsKey(p.getUUID().toString());}
    /** Players without this Mirror version still learn why nothing happened, on the action bar. */
    private void error(ServerPlayer p,String text){String shown=text.length()>256?text.substring(0,256):text;if(modded(p))send(p,"ERROR","text",shown);else p.displayClientMessage(Component.literal(shown),true);}
    private void send(ServerPlayer p,String type,Object...values){if(!modded(p))return;MessagePayload m=payload(type,values);if(m!=null)ServerPlayNetworking.send(p,m);}
    private static MessagePayload payload(String type,Object...values){JsonObject m=new JsonObject();m.addProperty("type",type);for(int i=0;i<values.length;i+=2)m.add((String)values[i],ScreenSpec.JSON.toJsonTree(values[i+1]));String text=m.toString();return text.length()<=24000?new MessagePayload(text):null;}
}
