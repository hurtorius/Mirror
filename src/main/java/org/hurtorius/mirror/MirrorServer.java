package org.hurtorius.mirror;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.hurtorius.mirror.core.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

public final class MirrorServer {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("mirror");
    private MinecraftServer server;
    private WorldStore world;
    private Path save;
    private MediaRepository media;
    private final Consent consent=new Consent();
    private final Map<String,Publisher> publishers=new HashMap<>();
    private final Map<String,Set<String>> visible=new HashMap<>(),subscriptions=new HashMap<>();
    private final Map<String,Long> messageWindow=new HashMap<>();
    private final Map<String,Integer> messageCount=new HashMap<>();
    private final Map<String,Boolean> redstone=new HashMap<>(),area=new HashMap<>();
    private final Map<String,Show> shows=new HashMap<>();
    private final Map<String,Integer> finishPulses=new HashMap<>();
    private final Map<String,Download> downloads=new HashMap<>();
    private final Set<String> editors=new HashSet<>();
    private boolean dirty=false;
    private int ticks;
    private static final class Publisher {String player,epoch;long last,lastFrame,sequence=-1,budgetWindow,bytes,audioBytes;FrameAssembler frames=new FrameAssembler();FrameRateGate rate=new FrameRateGate();byte[] latest;}
    private static final class Show {int cue;long next;ScreenSpec base;}
    private record Download(String player,String media,long offset){}

    public void start(MinecraftServer server){
        this.server=server;Path root=server.getWorldPath(LevelResource.ROOT).resolve("mirror");save=root.resolve("world.json");
        try{world=WorldStore.load(save);media=new MediaRepository(root.resolve("media"));}
        catch(IOException e){throw new IllegalStateException("Mirror could not load this world's data. The original files were preserved.",e);}
        consent.clear();publishers.clear();visible.clear();subscriptions.clear();editors.clear();redstone.clear();area.clear();shows.clear();finishPulses.clear();downloads.clear();ticks=0;
        LOG.info("Mirror ready: {} Initiators; {} media items",world.anchors.size(),world.media.size());
    }
    public void stop(MinecraftServer ignored){
        try{checkpoint();if(media!=null)media.close();}catch(IOException e){LOG.error("Mirror data could not be saved",e);}
        for(String id:new ArrayList<>(publishers.keySet()))revoke(id,"World closed");
        publishers.clear();visible.clear();subscriptions.clear();editors.clear();shows.clear();downloads.clear();messageCount.clear();messageWindow.clear();world=null;server=null;
    }
    private void checkpoint()throws IOException{
        if(world==null)return;long now=now();
        for(Anchor a:world.anchors.values())if(a.playing){a.position=Math.min(86400,a.positionAt(now));a.timelineMillis=now;}
        snapshotBoards();
        Map<String,ScreenSpec> overlays=new HashMap<>();
        for(var e:shows.entrySet()){Anchor a=world.anchors.get(e.getKey());if(a!=null){overlays.put(a.id,a.spec);a.spec=e.getValue().base;}}
        try{world.save(save);dirty=false;}finally{for(var e:overlays.entrySet())world.anchors.get(e.getKey()).spec=e.getValue();}
    }
    public static boolean operator(ServerPlayer p){return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}
    public boolean mayEdit(ServerPlayer p,Anchor a){return operator(p)&&(!a.spec.locked||a.owner.equals(p.getUUID().toString())||p.permissions().hasPermission(Permissions.COMMANDS_OWNER));}
    private boolean maySee(ServerPlayer p,Anchor a){if(world.emergency||!p.level().dimension().identifier().toString().equals(a.dimension))return false;double[] center=center(a);return a.spec.canView(p.getUUID().toString(),Math.sqrt(p.distanceToSqr(center[0],center[1],center[2])));}
    private double[] center(Anchor a){ScreenSpec s=a.spec;double x=a.x+.5+s.x,y=a.y+s.y,z=a.z+.5+s.z;if(s.followPlayer&&!s.target.isEmpty()){var target=server.getPlayerList().getPlayer(UUID.fromString(s.target));if(target!=null&&target.level().dimension().identifier().toString().equals(a.dimension)){x=target.getX()+s.x;y=target.getY()+s.y;z=target.getZ()+s.z;}}if(s.facing==ScreenSpec.Facing.ORBIT){double angle=now()/1000.0*2*Math.PI/s.orbitSeconds;x+=Math.sin(angle)*s.orbitRadius;z+=Math.cos(angle)*s.orbitRadius;}if(!s.path.isEmpty()){var path=MotionPath.sample(s,a.motionMillis,now());x=a.x+.5+path.x();y=a.y+path.y();z=a.z+.5+path.z();}return new double[]{x,y,z};}
    private boolean enabled(Anchor a){return switch(a.spec.source){case WEB->world.browserEnabled;case MEDIA->world.mediaEnabled;case SHARE->world.sharingEnabled;default->true;};}
    private boolean maySeeContent(ServerPlayer p,Anchor a){Anchor root=effective(a);return enabled(a)&&enabled(root)&&maySee(p,a)&&(root==a||maySee(p,root));}
    private boolean nearby(ServerPlayer p,Anchor a){return p.level().dimension().identifier().toString().equals(a.dimension)&&p.distanceToSqr(a.x+.5,a.y+.5,a.z+.5)<=128*128;}
    public Anchor at(String dimension,BlockPos pos){if(world==null)return null;for(Anchor a:world.anchors.values())if(a.dimension.equals(dimension)&&a.x==pos.getX()&&a.y==pos.getY()&&a.z==pos.getZ())return a;return null;}
    public static boolean isInitiator(ItemStack stack){CustomData data=stack.get(DataComponents.CUSTOM_DATA);return stack.is(Mirror.INITIATOR_ITEM)||stack.is(Items.LODESTONE)&&data!=null&&data.copyTag().getBooleanOr("mirror_initiator",false);}
    public static ItemStack initiator(){return new ItemStack(Mirror.INITIATOR_ITEM);}
    public void adopt(net.minecraft.server.level.ServerLevel level,InitiatorBlockEntity entity){
        if(world==null)return;BlockPos pos=entity.getBlockPos();String dimension=level.dimension().identifier().toString();Anchor a=at(dimension,pos);
        if(a==null){
            if(world.anchors.size()>=world.maxScreens)return;
            a=new Anchor();a.x=pos.getX();a.y=pos.getY();a.z=pos.getZ();a.dimension=dimension;a.createdMillis=0;a.owner="00000000-0000-0000-0000-000000000000";
            try{String owner=entity.legacyOwner();if(!ScreenSpec.uuidOrEmpty(owner).isEmpty())a.owner=owner;
                if(!entity.legacyConfig().isEmpty()){var imported=LegacyConfig.convert(entity.legacyConfig(),hash->legacyMedia(hash,aOwner(entity)));a.spec=imported.spec();a.migrationNote=String.join("; ",imported.notes());}
            }catch(Exception error){LOG.warn("Legacy Initiator at {} needs review; its original NBT is retained",pos,error);a.spec=new ScreenSpec();a.migrationNote="Legacy settings could not be converted; original block NBT is retained.";}
            a.timelineMillis=now();world.anchors.put(a.id,a);dirty=true;world.log(a.owner,"registered native Initiator "+a.id);
        }
        entity.bind(a.id);
    }
    private String aOwner(InitiatorBlockEntity entity){try{return ScreenSpec.uuidOrEmpty(entity.legacyOwner());}catch(IllegalArgumentException e){return "";}}
    private String legacyMedia(String hash,String owner){
        if(hash==null||!hash.matches("[a-f0-9]{64}"))return "";
        for(var m:world.media.values())if(m.hash().equals(hash))return m.id();
        Path root=save.getParent();
        try{for(String folder:List.of("media","assets")){Path dir=root.resolve(folder);if(!Files.isDirectory(dir))continue;try(var files=Files.list(dir)){
            for(Path source:files.filter(f->f.getFileName().toString().startsWith(hash+".")).toList()){
                String name=source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);String kind=name.endsWith(".png")||name.endsWith(".jpg")||name.endsWith(".webp")?"image":name.endsWith(".mp4")||name.endsWith(".webm")?"video":name.endsWith(".wav")||name.endsWith(".mp3")||name.endsWith(".ogg")?"audio":name.endsWith(".pdf")?"pdf":name.endsWith(".srt")||name.endsWith(".vtt")?"subtitle":"";
                JsonObject legacyMetadata=null;Path metadata=dir.resolve(hash+".json");if(Files.isRegularFile(metadata)&&Files.size(metadata)<=16384){AtomicFile.rejectLinks(metadata);legacyMetadata=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();}
                if(name.endsWith(".bin")&&legacyMetadata!=null&&legacyMetadata.has("kind")){String oldKind=legacyMetadata.get("kind").getAsString();kind=oldKind.startsWith("VIDEO_")?"video":oldKind.startsWith("AUDIO_")?"audio":oldKind.equals("DOCUMENT_PDF")?"pdf":oldKind.startsWith("SUBTITLE_")?"subtitle":"";}
                if(kind.isEmpty())continue;AtomicFile.rejectLinks(source);long size=Files.size(source);if(size<1||size>MediaRepository.MAX_FILE)continue;
                var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(source)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)digest.update(bytes,0,n);}if(!HexFormat.of().formatHex(digest.digest()).equals(hash))continue;
                Path target=root.resolve("media").resolve(hash);if(!Files.exists(target))Files.copy(source,target);
                String id=UUID.nameUUIDFromBytes(("mirror-legacy:"+hash).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();String title=legacyMetadata!=null&&legacyMetadata.has("name")?legacyMetadata.get("name").getAsString():"Recovered "+kind;if(title.length()>128)title=title.substring(0,Character.isHighSurrogate(title.charAt(127))?127:128);world.media.put(id,WorldStore.validateMedia(new WorldStore.Media(id,hash,title,"Recovered dev media",kind,size,owner)));if(legacyMetadata!=null&&legacyMetadata.has("durationMicros")){double duration=legacyMetadata.get("durationMicros").getAsDouble()/1_000_000.0;if(Double.isFinite(duration)&&duration>=0&&duration<=86400)world.durations.put(id,duration);}dirty=true;return id;
            }
        }}}catch(Exception error){LOG.warn("Legacy media could not be imported; original retained",error);}return "";
    }
    public void interact(ServerPlayer p,InteractionHand hand,BlockHitResult hit,boolean item){
        if(world==null)return;if(p.level().getBlockEntity(hit.getBlockPos()) instanceof InitiatorBlockEntity nativeBlock)adopt(p.level(),nativeBlock);Anchor existing=at(p.level().dimension().identifier().toString(),hit.getBlockPos());
        if(existing!=null){if(mayEdit(p,existing))open(p,existing);else send(p,"VIEWER_SETTINGS");return;}
        if(!item){if(p.level().getBlockState(hit.getBlockPos()).is(Mirror.INITIATOR))error(p,"This Initiator needs recovery or the world has reached its screen limit.");return;}
        if(!canPlace(p))return;
        if(!p.getItemInHand(hand).is(Mirror.INITIATOR_ITEM))p.setItemInHand(hand,new ItemStack(Mirror.INITIATOR_ITEM,p.getItemInHand(hand).getCount()));
        Mirror.INITIATOR_ITEM.place(new net.minecraft.world.item.context.BlockPlaceContext(p,hand,p.getItemInHand(hand),hit));
    }
    public boolean canPlace(ServerPlayer p){if(world==null)return false;if(!operator(p)){error(p,"Only operators can place an Initiator.");return false;}if(world.anchors.size()>=world.maxScreens){error(p,"This world has reached its Initiator limit.");return false;}return true;}
    public void placed(ServerPlayer p,BlockPos pos){
        if(world==null)return;Anchor a=new Anchor();a.owner=p.getUUID().toString();a.dimension=p.level().dimension().identifier().toString();a.x=pos.getX();a.y=pos.getY();a.z=pos.getZ();a.spec.yaw=ScreenSpec.wrapDegrees(p.getYRot()+180);a.timelineMillis=now();world.anchors.put(a.id,a);
        if(p.level().getBlockEntity(pos) instanceof InitiatorBlockEntity block)block.fresh(a.id);
        world.log(a.owner,"placed Initiator "+a.id);dirty=true;broadcast(a);
    }
    public void broken(String dimension,BlockPos pos){Anchor a=at(dimension,pos);if(a!=null){revoke(a.id,"");clearLight(a);world.anchors.remove(a.id);shows.remove(a.id);dirty=true;for(ServerPlayer p:server.getPlayerList().getPlayers())send(p,"REMOVE","id",a.id);}}
    public void join(ServerPlayer p){if(world==null)return;send(p,"HELLO","world",world.world,"serverTime",now(),"operator",operator(p),"serverOwner",p.permissions().hasPermission(Permissions.COMMANDS_OWNER),"emergency",world.emergency,"fps",world.maxPublishFps);visible.put(p.getUUID().toString(),new HashSet<>());}
    public void leave(ServerPlayer p){String id=p.getUUID().toString();consent.disconnect(id);visible.remove(id);subscriptions.remove(id);editors.remove(id);downloads.remove(id);messageWindow.remove(id);messageCount.remove(id);for(var e:new ArrayList<>(publishers.entrySet()))if(e.getValue().player.equals(id))revoke(e.getKey(),"Publisher disconnected");try{media.disconnect(id);}catch(IOException e){LOG.warn("Could not close an interrupted import",e);}}
    private void open(ServerPlayer p,Anchor a){if(!mayEdit(p,a)||!nearby(p,a))throw new IllegalArgumentException("You cannot edit this Initiator here.");editors.add(p.getUUID().toString());Anchor editing=a.copy();if(shows.containsKey(a.id))editing.spec=shows.get(a.id).base.copy();send(p,"OPEN","anchor",editing,"serverOwner",p.permissions().hasPermission(Permissions.COMMANDS_OWNER),"serverTime",now(),"worldBytes",world.bytes(),"quota",world.mediaQuota);library(p);if(!a.migrationNote.isEmpty())send(p,"NOTICE","text",a.migrationNote);}
    private void library(ServerPlayer p){send(p,"LIBRARY_CLEAR");for(WorldStore.Media m:world.media.values())send(p,"MEDIA","media",m);send(p,"GROUP_CLEAR");for(var e:world.groups.entrySet())send(p,"GROUP","name",e.getKey(),"players",e.getValue());send(p,"PLAYERS","players",server.getPlayerList().getPlayers().stream().map(v->Map.of("id",v.getUUID().toString(),"name",v.getGameProfile().name())).toList());}
    public void message(ServerPlayer p,String text){
        if(world==null||text.length()>24000)return;String actor=p.getUUID().toString();long now=now();
        if(now-messageWindow.getOrDefault(actor,0L)>=1000){messageWindow.put(actor,now);messageCount.put(actor,0);}
        int count=messageCount.merge(actor,1,Integer::sum);if(count>40)return;
        try{
            JsonObject m=JsonParser.parseString(text).getAsJsonObject();String action=str(m,"action",32);
            if(action.equals("SYNC")){send(p,"SYNC","sent",m.get("sent").getAsLong(),"time",now);return;}
            if(action.equals("STOP_SHARING")){String target=m.has("id")?str(m,"id",36):"";for(var e:new ArrayList<>(publishers.entrySet()))if(e.getValue().player.equals(actor)&&(target.isEmpty()||target.equals(e.getKey())))revoke(e.getKey(),"");return;}
            if(action.equals("CONSENT_DECLINE")){consent.decline(str(m,"request",36),actor);send(p,"SHARE_STOP","reason","Request declined");return;}
            if(action.equals("PRIVATE_BROWSER")){String id=str(m,"id",36);Publisher pub=publishers.get(id);Anchor a=world.anchors.get(id);if(a==null||pub==null||!pub.player.equals(actor)||a.spec.source!=ScreenSpec.Source.WEB)return;publishers.remove(id);consent.revoke(id);a.spec.live=false;changed(a);for(ServerPlayer viewer:server.getPlayerList().getPlayers())send(viewer,"STREAM_STOP","id",id,"epoch",pub.epoch,"keepPreview",viewer==p);send(p,"CONTROL_ACK","anchor",a);return;}
            if(action.equals("HEARTBEAT")){Publisher pub=publishers.get(str(m,"id",36));if(pub!=null&&pub.player.equals(actor)&&pub.epoch.equals(str(m,"epoch",36)))pub.last=now;return;}
            if(action.equals("SUBSCRIBE")){
                var list=m.getAsJsonArray("streams");if(list==null||list.size()>10)throw new IllegalArgumentException("Too many requested streams");
                Set<String> requested=new HashSet<>();for(var value:list){String id=ScreenSpec.uuidOrEmpty(value.getAsString());if(!id.isEmpty())requested.add(id);}
                Set<String> previous=subscriptions.put(actor,requested);
                for(String id:requested)if(previous==null||!previous.contains(id)){Anchor screen=world.anchors.get(id);Publisher pub=publishers.get(id);if(screen!=null&&pub!=null&&pub.latest!=null&&receives(p,screen,pub))sendPicture(p,screen,pub,pub.latest);}
                return;
            }
            if(action.equals("GET_MEDIA")){download(p,m);return;}
            if(action.equals("CONSENT_ACCEPT")){accept(p,m);return;}
            if(!operator(p))throw new IllegalArgumentException("Only operators can change Mirror.");
            if(action.equals("MEDIA_INFO")){String id=str(m,"media",36);if(!world.media.containsKey(id))return;double duration=m.get("duration").getAsDouble();ScreenSpec.range(duration,0,86400,"Media duration");int pages=m.has("pages")?m.get("pages").getAsInt():0;if(pages<0||pages>1000)throw new IllegalArgumentException("PDFs support up to 1,000 pages.");world.durations.put(id,duration);world.pages.put(id,pages);dirty=true;return;}
            if(action.equals("IMPORT")){if(!world.mediaEnabled)throw new IOException("The server owner disabled media imports.");if(world.media.size()>=4096)throw new IOException("The world's media library is full. Remove unused items before importing more.");String id=media.begin(actor,str(m,"name",128),str(m,"folder",64),str(m,"kind",8),m.get("bytes").getAsLong(),world.mediaQuota,world.bytes(),now);send(p,"IMPORT_READY","id",id);return;}
            if(action.equals("IMPORT_CANCEL")){media.disconnect(actor);return;}
            if(action.equals("GROUP_SAVE")){String name=str(m,"name",32);if(name.isBlank())throw new IllegalArgumentException("Name the audience group.");List<String> ids=new ArrayList<>();for(JsonElement e:m.getAsJsonArray("players")){String id=e.getAsString();if(ScreenSpec.uuidOrEmpty(id).isEmpty()||ids.size()>=128)throw new IllegalArgumentException("Invalid audience group.");ids.add(id);}if(world.groups.size()>=128&&!world.groups.containsKey(name))throw new IllegalArgumentException("The group limit has been reached.");world.groups.put(name,ids);for(Anchor grouped:world.anchors.values())if(grouped.spec.group.equals(name)){revoke(grouped.id,"Audience group changed; sharing needs fresh consent");grouped.spec.viewers=new ArrayList<>(ids);grouped.spec.audience=ScreenSpec.Audience.SELECTED;grouped.revision++;changed(grouped);}dirty=true;send(p,"GROUP","name",name,"players",ids,"saved",true);return;}
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
            WorldStore.Media saved=media.storeBoard(a.boardMedia,a.owner,a.spec.name+" drawing",pub.latest,world.mediaQuota-world.bytes()+(previous==null?0:previous.bytes()));
            a.boardMedia=saved.id();world.media.put(saved.id(),saved);dirty=true;return true;
        }catch(IOException e){LOG.warn("Mirror drawing could not be saved",e);ServerPlayer player=server.getPlayerList().getPlayer(UUID.fromString(pub.player));if(player!=null)error(player,e.getMessage());return false;}
    }
    private void serverStatus(ServerPlayer p){send(p,"SERVER_STATUS","browser",world.browserEnabled,"media",world.mediaEnabled,"sharing",world.sharingEnabled,"quota",world.mediaQuota,"worldBytes",world.bytes(),"fps",world.maxPublishFps);}
    private void serverControl(ServerPlayer p,JsonObject m)throws IOException{
        if(!p.permissions().hasPermission(Permissions.COMMANDS_OWNER))throw new IllegalArgumentException("Only the server owner can change these world controls.");
        String setting=str(m,"setting",20);
        if(setting.equals("fps")){int fps=m.get("fps").getAsInt();if(fps!=30&&fps!=60)throw new IllegalArgumentException("Choose 30 or 60 shared frames per second.");world.maxPublishFps=fps;for(var viewer:server.getPlayerList().getPlayers())serverStatus(viewer);}
        else if(setting.equals("quota")){
            long mb=m.get("megabytes").getAsLong();if(mb<1||mb>102400)throw new IllegalArgumentException("Choose a quota from 1 to 102400 MB.");world.mediaQuota=mb*1024*1024;
        }else{
            boolean on=m.get("enabled").getAsBoolean();
            switch(setting){case "emergency"->world.emergency=on;case "browser"->world.browserEnabled=on;case "media"->world.mediaEnabled=on;case "sharing"->world.sharingEnabled=on;default->throw new IllegalArgumentException("Unknown world control.");}
            if(world.emergency||!on&&!setting.equals("emergency"))for(Anchor a:world.anchors.values())if(world.emergency||!enabled(a)){stopShow(a);a.play(false,now());a.spec.live=false;revoke(a.id,"Source stopped by server owner");changed(a);}
            if(setting.equals("emergency"))for(ServerPlayer viewer:server.getPlayerList().getPlayers())send(viewer,"EMERGENCY","enabled",on);
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
        if(!spec.group.isEmpty()){List<String> group=world.groups.get(spec.group);if(group==null)throw new IllegalArgumentException("Choose a saved audience group.");spec.audience=ScreenSpec.Audience.SELECTED;spec.viewers=new ArrayList<>(group);}
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
        ServerPlayer target=server.getPlayerList().getPlayer(UUID.fromString(player));if(target==null||!ServerPlayNetworking.canSend(target,MessagePayload.TYPE))throw new IllegalArgumentException("Choose a connected player with Mirror.");
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
        if(world==null||packet.bytes().length>28000)return;String actor=p.getUUID().toString();
        try{
            if(packet.kind()==0){if(!operator(p))return;WorldStore.Media result=media.chunk(packet.id(),actor,packet.number(),packet.bytes(),now());if(result==null)send(p,"IMPORT_ACK","id",packet.id(),"offset",packet.number()+packet.bytes().length);else{world.media.put(result.id(),result);world.log(actor,"imported "+result.id()+" "+result.bytes()+" bytes");dirty=true;checkpoint();send(p,"IMPORT_DONE","media",result,"worldBytes",world.bytes());}return;}
            Anchor a=world.anchors.get(packet.id());Publisher pub=publishers.get(packet.id());if(a==null||pub==null||world.emergency||!a.spec.live||!pub.player.equals(actor)||!pub.epoch.equals(packet.epoch()))return;
            if(a.spec.source==ScreenSpec.Source.SHARE&&!consent.allows(a.id,actor,Consent.audience(a.spec),pub.epoch))return;
            if(a.spec.source!=ScreenSpec.Source.SHARE&&!mayEdit(p,a))return;
            if(now()-pub.budgetWindow>=1000){pub.budgetWindow=now();pub.bytes=0;pub.audioBytes=0;}
            pub.bytes+=packet.bytes().length;if(pub.bytes>world.maxPublishFps*(long)world.maxFrameBytes+240000)return;
            pub.last=now();
            if(packet.kind()==1){byte[] complete=pub.frames.add(packet.number(),packet.part(),packet.parts(),packet.bytes(),now());if(complete!=null&&FrameAssembler.validJpeg(complete)&&pub.rate.allow(now(),world.maxPublishFps)){pub.latest=complete;pub.sequence=packet.number();pub.lastFrame=now();relay(a,pub,complete);}}
            else if(packet.kind()==2&&packet.bytes().length<=9600&&packet.bytes().length%4==0){pub.audioBytes+=packet.bytes().length;if(pub.audioBytes>211200)return;for(ServerPlayer viewer:server.getPlayerList().getPlayers())if(receives(viewer,a,pub))ServerPlayNetworking.send(viewer,packet);}
        }catch(Exception e){error(p,e.getMessage()==null?"The transfer failed.":e.getMessage());}
    }
    private boolean receives(ServerPlayer player,Anchor root,Publisher publisher){
        if(player.getUUID().toString().equals(publisher.player)||!ServerPlayNetworking.canSend(player,DataPayload.TYPE))return false;
        Set<String> wanted=subscriptions.get(player.getUUID().toString());if(wanted!=null&&!wanted.contains(root.id))return false;
        return world.anchors.values().stream().anyMatch(v->effective(v)==root&&v.spec.live&&maySeeContent(player,v));
    }
    private void sendPicture(ServerPlayer player,Anchor screen,Publisher pub,byte[] bytes){
        int parts=(bytes.length+27999)/28000;
        for(int i=0;i<parts;i++)ServerPlayNetworking.send(player,new DataPayload(1,screen.id,pub.epoch,pub.sequence,i,parts,Arrays.copyOfRange(bytes,i*28000,Math.min(bytes.length,(i+1)*28000))));
    }
    private void relay(Anchor screen,Publisher pub,byte[] bytes){for(ServerPlayer player:server.getPlayerList().getPlayers())if(receives(player,screen,pub))sendPicture(player,screen,pub,bytes);}
    private void download(ServerPlayer p,JsonObject m)throws IOException{
        String id=str(m,"media",36);WorldStore.Media item=world.media.get(id);if(item==null||!allowedMedia(p,id))throw new IOException("This media is not available to you.");long offset=m.get("offset").getAsLong();if(offset<0||offset>=item.bytes())throw new IOException("Invalid media position.");downloads.put(p.getUUID().toString(),new Download(p.getUUID().toString(),id,offset));
    }
    private boolean allowedMedia(ServerPlayer p,String id){if(operator(p))return true;for(Anchor a:world.anchors.values())if(a.spec.live&&maySeeContent(p,a)&&(effective(a).spec.playlist.contains(id)||effective(a).spec.subtitle.equals(id)||effective(a).boardMedia.equals(id)))return true;return false;}
    private Anchor effective(Anchor a){Anchor root=world.anchors.get(a.spec.link);return root==null?a:root;}
    private void clearLight(Anchor a){if(a.light==null||server==null)return;var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,Identifier.parse(a.dimension)));if(level!=null){BlockPos pos=new BlockPos(a.light[0],a.light[1],a.light[2]);if(!level.hasChunkAt(pos)){world.pendingLights.add(new WorldStore.Light(a.dimension,a.light[0],a.light[1],a.light[2],a.lightLevel));dirty=true;}else if(level.getBlockState(pos).is(Blocks.LIGHT)&&level.getBlockState(pos).getValue(net.minecraft.world.level.block.LightBlock.LEVEL)==a.lightLevel)level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());}a.light=null;a.lightLevel=0;}
    private void light(Anchor a){int brightness=a.spec.live&&!world.emergency?(int)Math.round(a.spec.glow*15):0;double[] center=center(a);BlockPos target=BlockPos.containing(center[0],center[1],center[2]);if(a.light!=null&&(a.light[0]!=target.getX()||a.light[1]!=target.getY()||a.light[2]!=target.getZ()||brightness!=a.lightLevel))clearLight(a);if(brightness==0||a.light!=null)return;var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,Identifier.parse(a.dimension)));if(level!=null&&level.hasChunkAt(target)&&level.getBlockState(target).isAir()){level.setBlockAndUpdate(target,Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL,brightness));a.light=new int[]{target.getX(),target.getY(),target.getZ()};a.lightLevel=brightness;dirty=true;}}
    private void deleteMedia(ServerPlayer p,String id)throws IOException{if(world.anchors.values().stream().anyMatch(a->a.boardMedia.equals(id)||a.spec.playlist.contains(id)||a.spec.subtitle.equals(id)||a.spec.show.stream().anyMatch(c->c.media().equals(id))))throw new IOException("Remove this item from playlists and shows before deleting it.");WorldStore.Media old=world.media.remove(id);if(old==null)return;world.durations.remove(id);world.pages.remove(id);dirty=true;checkpoint();media.tidy(world.media.values().stream().map(WorldStore.Media::hash).collect(java.util.stream.Collectors.toSet()));send(p,"MEDIA_REMOVED","id",id,"worldBytes",world.bytes());}
    private void tidy(ServerPlayer p)throws IOException{Set<String> used=new HashSet<>();for(Anchor a:world.anchors.values()){used.addAll(a.spec.playlist);used.add(a.spec.subtitle);used.add(a.boardMedia);for(ScreenSpec.Cue c:a.spec.show)used.add(c.media());}int before=world.media.size();world.media.keySet().removeIf(id->!used.contains(id));world.durations.keySet().removeIf(id->!world.media.containsKey(id));world.pages.keySet().removeIf(id->!world.media.containsKey(id));dirty=true;checkpoint();media.tidy(world.media.values().stream().map(WorldStore.Media::hash).collect(java.util.stream.Collectors.toSet()));send(p,"WORLD_SIZE","worldBytes",world.bytes(),"quota",world.mediaQuota);library(p);send(p,"NOTICE","text","Removed "+(before-world.media.size())+" unused library items.");}
    private void next(Anchor a){if(a.spec.playlist.isEmpty())return;a.spec.item=(a.spec.item+1)%a.spec.playlist.size();a.seek(0,now());changed(a);}
    private void changed(Anchor a){dirty=true;var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,Identifier.parse(a.dimension)));if(level!=null)level.updateNeighbourForOutputSignal(new BlockPos(a.x,a.y,a.z),Mirror.INITIATOR);broadcast(a);}
    private void broadcast(Anchor a){if(server==null)return;for(Anchor screen:world.anchors.values())if(screen==a||effective(screen)==a)for(ServerPlayer p:server.getPlayerList().getPlayers())if(maySeeContent(p,screen))state(p,screen);}
    private void state(ServerPlayer p,Anchor a){
        Anchor view=a.copy(),root=effective(a);view.migrationNote="";view.spec.url="";view.spec.allowedSites.clear();view.spec.blockedSites.clear();view.spec.viewers.clear();view.spec.sharePlayers.clear();view.spec.show.clear();view.spec.branches.clear();
        if(root!=a){view.boardMedia=root.boardMedia;view.spec.text=root.spec.text;view.spec.source=root.spec.source;view.spec.playlist=new ArrayList<>(root.spec.playlist);view.spec.item=root.spec.item;view.spec.loop=root.spec.loop;view.spec.live=a.spec.live&&root.spec.live;view.spec.entrance=root.spec.entrance;view.spec.easeSeconds=root.spec.easeSeconds;view.spec.floatAmount=root.spec.floatAmount;view.spec.swayDegrees=root.spec.swayDegrees;view.spec.path=new ArrayList<>(root.spec.path);view.spec.pathLoop=root.spec.pathLoop;view.motionMillis=root.motionMillis;view.position=root.position;view.playing=root.playing;view.timelineMillis=root.timelineMillis;}
        if(view.spec.facing==ScreenSpec.Facing.NEAREST_PLAYER){double[] center=center(a);ServerPlayer nearest=server.getPlayerList().getPlayers().stream().filter(v->v.level().dimension().identifier().toString().equals(a.dimension)).min(Comparator.comparingDouble(v->v.distanceToSqr(center[0],center[1],center[2]))).orElse(null);if(nearest!=null)view.spec.target=nearest.getUUID().toString();}
        Publisher pub=publishers.get(root.id);String publisher="";if(pub!=null){ServerPlayer operator=server.getPlayerList().getPlayer(UUID.fromString(pub.player));publisher=operator==null?"":operator.getGameProfile().name();}
        send(p,"STATE","anchor",view,"serverTime",now(),"stream",root.id,"epoch",pub==null?"":pub.epoch,"publisher",publisher);
        if(!view.spec.playlist.isEmpty()){WorldStore.Media m=world.media.get(view.spec.playlist.get(view.spec.item));if(m!=null)send(p,"MEDIA","media",m);}
        if(pub!=null&&pub.latest!=null&&!visible.getOrDefault(p.getUUID().toString(),Set.of()).contains(a.id)&&view.spec.live&&receives(p,root,pub)){int parts=(pub.latest.length+27999)/28000;for(int i=0;i<parts;i++)ServerPlayNetworking.send(p,new DataPayload(1,root.id,pub.epoch,pub.sequence,i,parts,Arrays.copyOfRange(pub.latest,i*28000,Math.min(pub.latest.length,(i+1)*28000))));}
        WorldStore.Media board=world.media.get(view.boardMedia);if(board!=null)send(p,"MEDIA","media",board);
        WorldStore.Media subtitles=world.media.get(view.spec.subtitle);if(subtitles!=null)send(p,"MEDIA","media",subtitles);
    }
    private void stopShow(Anchor a){Show previous=shows.remove(a.id);if(previous!=null)a.spec=previous.base;}
    private void dismiss(Anchor a){stopShow(a);a.spec.live=false;a.spec.triggersArmed=false;a.play(false,now());revoke(a.id,"");changed(a);}
    private void show(Anchor a){if(world.emergency||!enabled(a))return;stopShow(a);a.motionMillis=now();Show s=new Show();s.base=a.spec.copy();a.spec=a.spec.copy();s.next=now();shows.put(a.id,s);a.seek(0,now());}
    public void tick(MinecraftServer ignored){
        if(world==null)return;ticks++;long now=now();
        for(var e:new ArrayList<>(publishers.entrySet()))if(now-e.getValue().last>5000)revoke(e.getKey(),"Publisher stopped responding");
        for(Anchor a:new ArrayList<>(world.anchors.values())){
            if(ticks%5==0)light(a);Integer pulse=finishPulses.get(a.id);if(pulse!=null){if(pulse<=0){finishPulses.remove(a.id);a.finished=false;changed(a);}else finishPulses.put(a.id,pulse-1);}
            if(a.playing&&a.spec.source==ScreenSpec.Source.MEDIA&&!a.spec.playlist.isEmpty()&&a.spec.link.isEmpty()&&!world.emergency){String id=a.spec.playlist.get(a.spec.item);WorldStore.Media item=world.media.get(id);double duration=item==null?0:item.kind().equals("image")?a.spec.slideSeconds:item.kind().equals("pdf")?a.spec.slideSeconds*Math.max(1,world.pages.getOrDefault(id,1)):world.durations.getOrDefault(id,0.0);if(duration>0&&a.positionAt(now)>=duration){if(a.spec.item+1<a.spec.playlist.size()||a.spec.loop){next(a);finishPulses.put(a.id,3);}else{a.play(false,now);a.seek(duration,now);}a.finished=true;changed(a);}}
            var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,Identifier.parse(a.dimension)));if(level==null)continue;
            BlockPos pos=new BlockPos(a.x,a.y,a.z);
            if(level.hasChunkAt(pos)){
                if(level.getBlockState(pos).is(Blocks.LODESTONE)){level.setBlockAndUpdate(pos,Mirror.INITIATOR.defaultBlockState());if(level.getBlockEntity(pos) instanceof InitiatorBlockEntity nativeBlock)nativeBlock.bind(a.id);dirty=true;}
                if(!level.getBlockState(pos).is(Mirror.INITIATOR)){broken(a.dimension,pos);continue;}
                boolean active=a.spec.live&&!world.emergency;if(level.getBlockState(pos).getValue(InitiatorBlock.ACTIVE)!=active)level.setBlockAndUpdate(pos,level.getBlockState(pos).setValue(InitiatorBlock.ACTIVE,active));
            }
            if(!world.emergency&&enabled(a)&&a.spec.triggersArmed&&ticks%5==0&&level.hasChunkAt(pos)){
                boolean powered=level.hasNeighborSignal(pos),was=redstone.getOrDefault(a.id,false);redstone.put(a.id,powered);
                if(a.spec.redstone==ScreenSpec.Redstone.POWER&&powered!=was){a.spec.live=powered;if(powered)a.motionMillis=now();if(!powered)revoke(a.id,"Redstone dismissed screen");changed(a);}
                if(powered&&!was){if(a.spec.redstone==ScreenSpec.Redstone.PULSE_NEXT)next(a);if(a.spec.trigger==ScreenSpec.Trigger.REDSTONE)show(a);}
                if(world.mediaEnabled)for(ScreenSpec.Branch choice:a.spec.branches){var direction=net.minecraft.core.Direction.valueOf(choice.side());boolean input=level.getSignal(pos.relative(direction),direction)>0;String key=a.id+":"+choice.side();boolean previous=redstone.getOrDefault(key,false);redstone.put(key,input);if(input&&!previous){stopShow(a);revoke(a.id,"Authored film choice");a.spec.source=ScreenSpec.Source.MEDIA;a.spec.item=a.spec.playlist.indexOf(choice.media());if(a.spec.item<0)continue;a.spec.live=true;a.finished=false;a.seek(0,now);a.play(true,now);changed(a);}}
                boolean inside=server.getPlayerList().getPlayers().stream().anyMatch(p->p.level()==level&&p.distanceToSqr(a.x+.5,a.y+.5,a.z+.5)<=a.spec.areaRadius*a.spec.areaRadius);
                if(inside&&!area.getOrDefault(a.id,false)&&a.spec.trigger==ScreenSpec.Trigger.AREA)show(a);area.put(a.id,inside);
            }
            Show s=shows.get(a.id);if(s!=null&&!world.emergency&&now>=s.next){if(s.cue>=a.spec.show.size()){boolean reset=a.spec.resetShow;if(reset){stopShow(a);a.play(false,now);a.seek(0,now);changed(a);}else{s.next=Long.MAX_VALUE;}}else{ScreenSpec.Cue c=a.spec.show.get(s.cue++);applyCue(a,c);s.next=now+(long)(c.seconds()*1000);}}
        }
        if(ticks%10==0){
            for(var light:new ArrayList<>(world.pendingLights)){var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,Identifier.parse(light.dimension())));BlockPos pos=new BlockPos(light.x(),light.y(),light.z());if(level!=null&&level.hasChunkAt(pos)){if(level.getBlockState(pos).is(Blocks.LIGHT)&&level.getBlockState(pos).getValue(net.minecraft.world.level.block.LightBlock.LEVEL)==light.level())level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());world.pendingLights.remove(light);dirty=true;}}
            for(ServerPlayer p:server.getPlayerList().getPlayers()){
                if(!ServerPlayNetworking.canSend(p,MessagePayload.TYPE))continue;String id=p.getUUID().toString();Set<String> previous=visible.computeIfAbsent(id,k->new HashSet<>()),current=new HashSet<>();
                List<Device> devices=new ArrayList<>();for(Anchor a:world.anchors.values())if(p.level().dimension().identifier().toString().equals(a.dimension)&&p.distanceToSqr(a.x+.5,a.y+.5,a.z+.5)<128*128){Publisher pub=publishers.get(a.id);String mood=a.spec.locked?"locked":pub!=null?(pub.latest==null?"preparing":a.spec.source==ScreenSpec.Source.SHARE?"sharing":"live"):a.spec.live?"live":"idle";devices.add(new Device(a.id,a.x,a.y,a.z,mood,a.createdMillis,a.spec.live&&!world.emergency));}send(p,"DEVICES","devices",devices);
                for(Anchor a:world.anchors.values())if(maySeeContent(p,a)){current.add(a.id);state(p,a);}
                for(String gone:previous)if(!current.contains(gone))send(p,"REMOVE","id",gone);visible.put(id,current);
            }
        }
        for(Download d:new ArrayList<>(downloads.values())){ServerPlayer p=server.getPlayerList().getPlayer(UUID.fromString(d.player));WorldStore.Media m=world.media.get(d.media);downloads.remove(d.player);if(p==null||m==null||!allowedMedia(p,d.media))continue;try{byte[] bytes=media.read(m.hash(),d.offset,MediaRepository.CHUNK);ServerPlayNetworking.send(p,new DataPayload(0,m.id(),world.world,d.offset,0,1,bytes));}catch(IOException e){error(p,"World media could not be read. Ask the map owner to restore the missing file.");}}
        if(ticks%200==0){try{media.expire(now);if(dirty)checkpoint();}catch(IOException e){LOG.error("Mirror checkpoint failed",e);for(ServerPlayer p:server.getPlayerList().getPlayers())if(operator(p))error(p,"Mirror could not save. Check disk space and the server log before closing this world.");}}
    }
    private void applyCue(Anchor a,ScreenSpec.Cue c){switch(c.action()){case "summon"->{a.motionMillis=now();a.spec.live=true;}case "dismiss"->{a.spec.live=false;revoke(a.id,"Show dismissed screen");}case "play"->{if(a.finished)a.seek(0,now());a.spec.source=ScreenSpec.Source.MEDIA;if(!c.media().isEmpty()){a.spec.item=a.spec.playlist.indexOf(c.media());if(a.spec.item<0){a.spec.playlist.add(c.media());a.spec.item=a.spec.playlist.size()-1;}a.seek(0,now());}a.spec.live=true;a.play(true,now());}case "pause"->a.play(false,now());case "next"->next(a);case "seek"->a.seek(c.value(),now());case "width"->a.spec.width=c.value();case "height"->a.spec.height=c.value();case "yaw"->a.spec.yaw=c.value();case "x"->a.spec.x=c.value();case "y"->a.spec.y=c.value();case "z"->a.spec.z=c.value();case "pitch"->a.spec.pitch=c.value();case "roll"->a.spec.roll=c.value();default->{}}changed(a);}
    private static long now(){return System.currentTimeMillis();}
    private static String str(JsonObject m,String key,int max){if(!m.has(key)||!m.get(key).isJsonPrimitive())throw new IllegalArgumentException("Missing "+key+".");return ScreenSpec.text(m.get(key).getAsString(),max,key);}
    private static void error(ServerPlayer p,String text){send(p,"ERROR","text",text.length()>256?text.substring(0,256):text);}
    private static void send(ServerPlayer p,String type,Object...values){if(!ServerPlayNetworking.canSend(p,MessagePayload.TYPE))return;JsonObject m=new JsonObject();m.addProperty("type",type);for(int i=0;i<values.length;i+=2)m.add((String)values[i],ScreenSpec.JSON.toJsonTree(values[i+1]));String text=m.toString();if(text.length()<=24000)ServerPlayNetworking.send(p,new MessagePayload(text));}
}
