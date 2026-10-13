package org.hurtorius.mirror.client;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.hurtorius.mirror.*;
import org.hurtorius.mirror.core.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class MirrorClient implements ClientModInitializer {
    public record View(Anchor anchor,String stream,String epoch,String publisher){}
    public static final Map<String,View> views=new LinkedHashMap<>();
    public static final Map<String,Device> devices=new LinkedHashMap<>();
    public static final Map<String,WorldStore.Media> library=new LinkedHashMap<>();
    public static final Map<String,TextureFrame> frames=new HashMap<>();
    private static final Map<String,TextureFrame> browserFrames=new HashMap<>();
    private static final Map<String,String> browserPolicies=new HashMap<>();
    private static final Map<String,BrowserViewport> browserSizes=new HashMap<>();
    private static final Map<String,Boolean> browserCapture=new HashMap<>();
    private static final Map<String,Integer> browserRates=new HashMap<>();
    private static String browserKey(String id){return "browser-"+id;}
    private static boolean browserKeyed(String key){return key.startsWith("browser-");}
    private static String sourceId(String key){return browserKeyed(key)?key.substring(8):key;}
    public static TextureFrame browserFrame(String id){return browserFrames.get(id);}
    public static TextureFrame worldFrame(View view){return view.anchor.spec.source==ScreenSpec.Source.WEB&&isBrowserPublished(view.stream)&&hasBrowser(view.stream)?browserFrames.get(view.stream):frames.get(view.stream);}
    public static final Map<String,String> players=new LinkedHashMap<>();
    public static final Map<String,List<String>> groups=new LinkedHashMap<>();
    private static final Map<String,List<Subtitles.Cue>> subtitleTracks=new HashMap<>();
    private static final Set<String> subtitleLoading=new HashSet<>();
    public static ClientPreferences preferences;
    public static String world="",status="",notice="",pendingImportFolder="";
    public static long serverOffset=0,worldBytes=0,quota=1024L*1024*1024;
    private static long bestRoundTrip=Long.MAX_VALUE;
    public static boolean operator=false,emergency=false,serverOwner=false,worldSettingsReady=false;
    public static final Map<String,Boolean> worldFeatures=new HashMap<>();
    public static InitiatorScreen editor;
    private static Path root,config,cache,runtime;
    private static final Map<String,EngineProcess> engines=new HashMap<>();
    private static final Map<String,SpatialAudio> sounds=new HashMap<>();
    private static final Map<String,String> textKeys=new HashMap<>(),blockedStreams=new HashMap<>();
    private static final Map<String,String> publishing=new HashMap<>();
    /** A screen's media item that failed. One that failed while Mirror prepared its media runtime (offline at a first use) is tried again after 30 s; any other waits for Reload media. */
    record Failure(String media,long until){
        static Failure of(String media,boolean preparing,long now){return new Failure(media,preparing?now+30000:Long.MAX_VALUE);}
        static boolean blocks(Failure failure,String media,long now){return failure!=null&&failure.media.equals(media)&&now<failure.until;}
    }
    private static final Map<String,Failure> failedMedia=new HashMap<>();
    // A media engine plays one item after another. mediaPlaying is the current item; the engine's reader thread reads it (SOURCE_READY), hence concurrent.
    // The current item's sound counts only from its SOURCE_READY (audioReady), and a switch that is not ready within 10 s gets a fresh engine (mediaSwitches).
    private static final Map<String,String> mediaPlaying=new ConcurrentHashMap<>();
    private static final Map<String,Long> audioReady=new ConcurrentHashMap<>();
    private static final Map<String,Long> mediaSwitches=new HashMap<>(),recentStreams=new HashMap<>();
    private static final Map<String,Integer> mediaWidths=new HashMap<>();
    private static final Map<String,Anchor> nearestAnchors=new HashMap<>();
    private static final Map<String,FrameAssembler> assemblers=new HashMap<>();
    private record RemotePictures(String epoch,FramePlayout queue){}
    private static final Map<String,RemotePictures> remotePictures=new HashMap<>();
    private static final Map<String,FramePlayout> localPictures=new HashMap<>();
    private static final Map<String,FrameRateGate> publicationRates=new HashMap<>();
    private static final Map<String,String> publicationSources=new HashMap<>();
    private static final Map<String,Long> sequences=new HashMap<>();
    private static final Map<String,Long> engineVersions=new HashMap<>();
    private static final ExecutorService files=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Mirror media files");t.setDaemon(true);return t;});
    private static Download download;
    private static Upload upload;
    private static int ticks;
    private static long generation;
    private static Boolean creativePermission;
    private static final PublicationIntent publicationIntent=new PublicationIntent();
    private static final Set<String> browserEngines=new HashSet<>();
    private static final Map<String,String> browserAddresses=new HashMap<>();
    private static final Map<String,Long> publishAt=new HashMap<>();
    private static final Map<String,EngineProcess.Picture> pendingPictures=new HashMap<>();
    private static final Map<String,Boolean> browserShowRequests=new HashMap<>();
    private static final Map<String,Long> browserShowSince=new HashMap<>();
    private static Set<String> wantedStreams=Set.of();
    /** Anchor ids whose streams tick activated, nearest player first; the renderer draws exactly these. */
    public static List<String> liveScreens=List.of();
    private static final Map<String,String> preparing=new HashMap<>();
    public static int publishFps=60;
    private static long shareAttempt;
    public static long shareAttempt(){return shareAttempt;}
    public static boolean sharing(){return !publishing.isEmpty();}
    private record AudioChunk(String id,long generation,long version,long received,byte[] pcm){}
    private static final ArrayBlockingQueue<AudioChunk> incomingAudio=new ArrayBlockingQueue<>(64);
    public static boolean hasBrowser(String id){return browserEngines.contains(id)&&engines.containsKey(browserKey(id))&&engines.get(browserKey(id)).alive();}
    public static EngineProcess browserEngine(String id){return hasBrowser(id)?engines.get(browserKey(id)):null;}
    public static String browserAddress(String id,String fallback){return browserAddresses.getOrDefault(id,fallback);}
    public static EngineProcess openBrowser(String id,ScreenSpec spec){
        EngineProcess current=browserEngine(id);
        String policy=spec.allowedSites.toString()+"|"+spec.blockedSites;
        if(current!=null&&policy.equals(browserPolicies.get(id))){current.send("RATE","fps",spec.browserFps);current.send("RESIZE","spec",spec);browserSizes.put(id,BrowserViewport.of(spec));browserRates.put(id,spec.browserFps);if(!browserAddress(id,spec.url).equals(spec.url))current.send("NAVIGATE","url",spec.url);return current;}
        if(current!=null&&isBrowserPublished(id))privateBrowser(id);
        if(current==null&&browserEngines.size()>=preferences.maxScreens)throw new IllegalArgumentException("Stop another browser before opening more. The screen limit protects game performance.");
        closeBrowser(id);browserPolicies.put(id,policy);EngineProcess opened=engine(id,true,false,false);browserSizes.put(id,BrowserViewport.of(spec));browserRates.put(id,spec.browserFps);opened.send("BROWSER","spec",spec);opened.send("RATE","fps",spec.browserFps);return opened;
    }
    public static void resizePrivatePreview(String id,ScreenSpec spec){
        var engine=browserEngine(id);if(engine==null||isBrowserPublished(id))return;var size=BrowserViewport.of(spec);
        if(!java.util.Objects.equals(browserRates.put(id,spec.browserFps),spec.browserFps))engine.send("RATE","fps",spec.browserFps);
        if(!size.equals(browserSizes.get(id))){engine.send("RESIZE","spec",spec);browserSizes.put(id,size);}
    }
    public static void showBrowserWhenReady(String id,boolean close){browserShowRequests.put(id,close);browserShowSince.put(id,System.currentTimeMillis());}
    public static boolean browserShowPending(String id){return browserShowRequests.containsKey(id);}
    private static boolean editingBrowser(String id){return editor!=null&&editor.anchorId().equals(id)&&editor.contentSource()==ScreenSpec.Source.WEB&&hasBrowser(id);}

    public static void stopCapturesForAccount(){
        for(String id:new ArrayList<>(publishing.keySet())){if("SHARE".equals(publicationSources.get(id))){send("STOP_SHARING","id",id);removePublishing(id);pendingPictures.remove(id);publicationIntent.cancel(id);closeEngine(id);clearPicture(id);}}
    }
    public static void clearBrowserPreview(String id){localPictures.remove(browserKey(id));var process=browserEngine(id);if(process!=null)process.discardPictures();var frame=browserFrames.remove(id);if(frame!=null)frame.close();}
    public static void clearPicture(String id){remotePictures.remove(id);boards.remove(id);TextureFrame frame=frames.remove(id);if(frame!=null)frame.close();MirrorRenderer.clearTransition(id);CameraOutput.invalidate(id);}
    private static long metadataUntil,pickerUntil;
    public static EngineProcess filePicker(boolean media){pickerUntil=System.currentTimeMillis()+300000;return engine("picker",false,media,true);}
    private static final Map<String,Path> metadataPending=new HashMap<>();
    private static final class Download {WorldStore.Media media;Path temp;OutputStream out;long offset,last;MessageDigest hash;long generation;boolean resumed;}
    private static final class Upload {Path file;String id;InputStream in;long offset,total,generation;Path copy;OutputStream copyOut;MessageDigest digest;}
    private static final Map<String,Long> downloadBlocked=new HashMap<>();
    private static final Map<String,Uplink> uplinks=new HashMap<>();
    private static final Map<String,String> boards=new HashMap<>();
    private static final Set<String> boardLoading=new HashSet<>();

    public void onInitializeClient(){
        IrisReflectionScope.initialize();
        net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry.register(context->new DraftPreviewRenderer(context.vertexConsumers()));
        net.minecraft.client.renderer.special.SpecialModelRenderers.ID_MAPPER.put(Identifier.parse("mirror:initiator"),InitiatorItemRenderer.Unbaked.CODEC);
        Minecraft mc=Minecraft.getInstance();root=realGameDirectory(mc);config=root.resolve("config/mirror-client.json");preferences=ClientPreferences.load(config);cache=root.resolve("mirror-cache");runtime=root.resolve("mirror-runtime/1.0.0");
        ClientPlayNetworking.registerGlobalReceiver(MessagePayload.TYPE,(p,c)->{try{message(JsonParser.parseString(p.json()).getAsJsonObject());}catch(RuntimeException invalid){notice("Invalid Mirror data was ignored.");}});
        ClientPlayNetworking.registerGlobalReceiver(DataPayload.TYPE,(p,c)->data(p));
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->c.execute(MirrorClient::reset));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->{reset();files.shutdownNow();});
        var category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mirror","controls"));
        KeyMapping hide=KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mirror.hide",InputConstants.Type.KEYSYM,298,category));
        KeyMapping stop=KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mirror.stop",InputConstants.Type.KEYSYM,299,category));
        ClientTickEvents.END_CLIENT_TICK.register(c->{while(hide.consumeClick()){preferences.hidden=!preferences.hidden;if(preferences.hidden){stopSharing();CameraOutput.close();}preferences.save(config);notice(preferences.hidden?"Mirror screens hidden and muted":"Mirror screens visible");}while(stop.consumeClick())stopSharing();tick();});
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mirror","sharing"),(g,t)->{if(!sharing()||mc.options.hideGui||mc.debugEntries.isOverlayVisible())return;Component text=Component.literal("Your "+(publicationSources.containsValue("SHARE")?"screen":publicationSources.containsValue("WEB")?"browser":"drawing")+" is live · ").append(stop.getTranslatedKeyMessage()).append(" stops it");g.fill(2,2,8+mc.font.width(text),15,0x90000000);g.drawString(mc.font,text,5,5,0xffffffff);});
        WorldRenderEvents.END_EXTRACTION.register(MirrorRenderer::extract);
        WorldRenderEvents.AFTER_ENTITIES.register(MirrorRenderer::render);
    }
    /** Resolved through links once, so a moved .minecraft or instance folder works while links inside Mirror's own folders stay refused. */
    private static Path realGameDirectory(Minecraft mc){Path game=mc.gameDirectory.toPath();try{return game.toRealPath();}catch(IOException e){return game.toAbsolutePath();}}
    public static boolean connected(){return !world.isEmpty()&&ClientPlayNetworking.canSend(MessagePayload.TYPE);}
    public static void send(String action,Object...values){if(world.isEmpty()||!ClientPlayNetworking.canSend(MessagePayload.TYPE))return;JsonObject m=new JsonObject();m.addProperty("action",action);for(int i=0;i<values.length;i+=2)m.add((String)values[i],ScreenSpec.JSON.toJsonTree(values[i+1]));String text=m.toString();if(text.length()>24000){notice("These screen settings are too large to send.");return;}if(action.equals("PUBLISH_WEB")&&m.has("id")&&!browserEngines.contains(m.get("id").getAsString())){notice("Open your browser preview before publishing it.");return;}if(action.equals("WHITEBOARD")&&m.has("id")){String id=m.get("id").getAsString();TextureFrame frame=frames.get(id);if(frame!=null&&frame.browserPixels)clearPicture(id);closeEngine(id);}if(action.equals("PUBLISH_WEB")&&m.has("id"))pendingBrowserPublications.add(m.get("id").getAsString());if((action.equals("PUBLISH_WEB")||action.equals("WHITEBOARD"))&&m.has("id"))publicationIntent.request(m.get("id").getAsString(),action.equals("PUBLISH_WEB")?"WEB":"WHITEBOARD",System.currentTimeMillis());if(action.equals("DISMISS")&&m.has("id"))publicationIntent.cancel(m.get("id").getAsString());ClientPlayNetworking.send(new MessagePayload(text));}
    public static void notice(String text){Minecraft mc=Minecraft.getInstance();mc.execute(()->{Reports.add(text);notice=text;if(editor!=null)editor.message(text);});}
    private static void clock(JsonObject m){if(m.has("serverTime")&&bestRoundTrip==Long.MAX_VALUE)serverOffset=m.get("serverTime").getAsLong()-System.currentTimeMillis();}
    private static Anchor readAnchor(JsonObject message){Anchor anchor=ScreenSpec.JSON.fromJson(message.get("anchor"),Anchor.class);if(anchor==null)throw new IllegalArgumentException("Missing screen data");anchor.validate();return anchor;}
    /** Until a HELLO this client supports, a server (another Mirror version included) is answered by nothing: no editor, consent or viewer screen opens for it. */
    static boolean accepts(String world,String type){return !world.isEmpty()||type.equals("HELLO");}
    private static void message(JsonObject m){Minecraft mc=Minecraft.getInstance();String type=m.get("type").getAsString();if(!accepts(world,type))return;clock(m);
        switch(type){
            case "HELLO"->{reset();if(!NativeCompatibility.supportedClient()){notice("This server runs a different Mirror version, so its screens stay hidden.");break;}world=m.get("world").getAsString();operator=m.get("operator").getAsBoolean();serverOwner=m.has("serverOwner")&&m.get("serverOwner").getAsBoolean();publishFps=m.has("fps")?Math.clamp(m.get("fps").getAsInt(),1,60):24;emergency=m.get("emergency").getAsBoolean();clock(m);}
            case "SYNC"->{long arrived=System.currentTimeMillis(),sent=m.get("sent").getAsLong(),roundTrip=arrived-sent;if(roundTrip>=0&&roundTrip<=2000&&roundTrip<=bestRoundTrip+10){bestRoundTrip=Math.min(bestRoundTrip,roundTrip);serverOffset=m.get("time").getAsLong()-(sent+arrived)/2;}}
            case "OPEN"->{serverOwner=m.has("serverOwner")&&m.get("serverOwner").getAsBoolean();Anchor a=readAnchor(m);worldBytes=m.get("worldBytes").getAsLong();quota=m.get("quota").getAsLong();editor=new InitiatorScreen(a);mc.setScreen(editor);}
            case "VIEWER_SETTINGS"->mc.setScreen(new ViewerSettingsScreen(null));
            case "SAVED"->{if(editor!=null)editor.saved(readAnchor(m));}
            case "CONTROL_ACK"->{if(editor!=null)editor.control(readAnchor(m));}
            case "STATE"->{Anchor a=readAnchor(m);String stream=m.get("stream").getAsString(),epoch=m.get("epoch").getAsString();View old=views.get(a.id);if(old!=null&&old.anchor.spec.source!=a.spec.source){textKeys.remove(old.stream);CameraOutput.invalidate(a.id);}if(old!=null&&old.anchor.spec.source!=a.spec.source&&(mediaPlaying.containsKey(old.stream)||boards.containsKey(old.stream))){closeEngine(old.stream);boards.remove(old.stream);TextureFrame oldFrame=frames.remove(old.stream);if(oldFrame!=null)oldFrame.close();}if(old!=null&&(old.anchor.contentVersion!=a.contentVersion||old.anchor.spec.source!=a.spec.source||!old.anchor.spec.currentMedia().equals(a.spec.currentMedia())))MirrorRenderer.contentChanged(a.id,frames.get(old.stream),old.anchor.spec);if(old!=null&&!old.epoch.equals(epoch)){if(mediaPlaying.containsKey(stream))closeEngine(stream);remotePictures.remove(stream);assemblers.remove(stream);TextureFrame f=frames.remove(stream);if(f!=null)f.close();}if(a.spec.source==ScreenSpec.Source.MEDIA&&(!a.spec.live||a.spec.playlist.isEmpty())&&mediaPlaying.containsKey(stream)){closeEngine(stream);if(a.spec.playlist.isEmpty())clearPicture(stream);}views.put(a.id,new View(a,stream,epoch,m.get("publisher").getAsString()));}
            case "DEVICES"->{devices.clear();for(JsonElement e:m.getAsJsonArray("devices")){Device d=ScreenSpec.JSON.fromJson(e,Device.class);devices.put(d.id(),d);}InitiatorVisuals.sync();}
            case "AUDIENCE"->uplinks.computeIfAbsent(m.get("id").getAsString(),k->new Uplink()).audience(m.get("epoch").getAsString(),m.get("watched").getAsBoolean());
            case "REMOVE"->{String id=m.get("id").getAsString();views.remove(id);CameraOutput.invalidate(id);MirrorRenderer.remove(id);}
            case "MEDIA"->{WorldStore.Media media=WorldStore.validateMedia(ScreenSpec.JSON.fromJson(m.get("media"),WorldStore.Media.class));WorldStore.Media previous=library.put(media.id(),media);if(!media.equals(previous)){if(mc.screen instanceof LibraryScreen list)list.refresh();if(mc.screen instanceof MediaPickerScreen picker)picker.refresh();}}
            case "LIBRARY_CLEAR"->{library.clear();if(mc.screen instanceof LibraryScreen list)list.refresh();}
            case "GROUPS"->{if(editor!=null)editor.groupsUpdated();groups.clear();for(var e:m.getAsJsonObject("groups").entrySet()){List<String> list=new ArrayList<>();for(JsonElement id:e.getValue().getAsJsonArray())list.add(id.getAsString());groups.put(e.getKey(),list);}}
            case "GROUP_CLEAR"->groups.clear();
            case "GROUP"->{List<String> list=new ArrayList<>();for(JsonElement id:m.getAsJsonArray("players"))list.add(id.getAsString());groups.put(m.get("name").getAsString(),list);if(editor!=null&&m.has("saved")&&m.get("saved").getAsBoolean())editor.groupsUpdated();}
            case "PLAYERS"->{players.clear();for(JsonElement e:m.getAsJsonArray("players")){JsonObject p=e.getAsJsonObject();players.put(p.get("id").getAsString(),p.get("name").getAsString());}}
            case "NOTICE"->notice(m.get("text").getAsString());
            case "MEDIA_FAILED"->{Download d=download;if(d!=null&&d.media.id().equals(m.get("media").getAsString())){downloadBlocked.put(d.media.hash(),Long.MAX_VALUE);closeDownload();status="";}notice(m.get("text").getAsString());}
            case "ERROR"->{pendingBrowserPublications.clear();browserShowRequests.clear();browserShowSince.clear();if(upload!=null){send("IMPORT_CANCEL");closeUpload();}notice(m.get("text").getAsString());if(mc.screen instanceof ServerSettingsScreen settings)settings.error(m.get("text").getAsString());if(mc.screen instanceof ShareConsentScreen consent)consent.failed(m.get("text").getAsString());if(editor!=null)editor.saveFailed();}
            case "IMPORT_READY"->{if(upload!=null){upload.id=m.get("id").getAsString();uploadChunk();}}
            case "IMPORT_ACK"->{if(upload!=null&&upload.id.equals(m.get("id").getAsString())&&upload.offset==m.get("offset").getAsLong())uploadChunk();}
            case "IMPORT_DONE"->{WorldStore.Media media=WorldStore.validateMedia(ScreenSpec.JSON.fromJson(m.get("media"),WorldStore.Media.class));library.put(media.id(),media);worldBytes=m.get("worldBytes").getAsLong();if(upload!=null&&Set.of("video","audio","pdf").contains(media.kind())){metadataPending.put(media.id(),upload.file);metadataUntil=System.currentTimeMillis()+60000;engine("metadata",false,true,false).send("PROBE_MEDIA","media",media.id(),"kind",media.kind(),"path",upload.file.toString());}if(upload!=null){Upload done=upload;files.submit(()->keepCopy(done,media.hash()));}closeUpload();notice("Imported "+media.name()+" into this world");if(editor!=null)editor.imported(media);}
            case "MEDIA_REMOVED"->{library.remove(m.get("id").getAsString());worldBytes=m.get("worldBytes").getAsLong();if(mc.screen instanceof LibraryScreen)mc.screen.resize(mc.screen.width,mc.screen.height);}
            case "SERVER_STATUS"->{if(m.has("fps"))publishFps=Math.clamp(m.get("fps").getAsInt(),1,60);for(String feature:List.of("browser","media","sharing"))worldFeatures.put(feature,m.get(feature).getAsBoolean());quota=m.get("quota").getAsLong();worldBytes=m.get("worldBytes").getAsLong();worldSettingsReady=true;}
            case "WORLD_SIZE"->{worldBytes=m.get("worldBytes").getAsLong();quota=m.get("quota").getAsLong();}
            case "BROWSER_POLICY_CHANGED"->{String id=m.get("id").getAsString();publicationIntent.cancel(id);if(browserEngines.contains(id)){removePublishing(id);closeBrowser(id);clearPicture(id);status="Browser rules changed. Reopen the browser privately.";}}
            case "PUBLISH"->{String id=m.get("id").getAsString(),epoch=m.get("epoch").getAsString();if(!epoch.equals(publishing.get(id))&&!publicationIntent.accept(id,m.get("source").getAsString(),System.currentTimeMillis()))break;blockedStreams.remove(id);pendingBrowserPublications.remove(id);rememberPublisher(id,epoch,m.get("source").getAsString());if(editor!=null&&editor.anchorId().equals(id))editor.publishing();SpatialAudio previous=sounds.remove(id);if(previous!=null)previous.close();TextureFrame f=m.get("source").getAsString().equals("WEB")?browserFrames.get(id):frames.get(id);if(f!=null&&f.latest!=null)pendingPictures.put(id,new EngineProcess.Picture(f.latest,System.nanoTime()));Boolean close=browserShowRequests.remove(id);browserShowSince.remove(id);if(Boolean.TRUE.equals(close)&&editor!=null&&editor.anchorId().equals(id))editor.finishBrowserShow();}
            case "SHARE_REQUEST"->mc.setScreen(new ShareConsentScreen(m,mc.screen));
            case "SHARE_START"->{String id=m.get("id").getAsString();if(!(mc.screen instanceof ShareConsentScreen consent)||!consent.mayStart(id))break;blockedStreams.remove(id);rememberPublisher(id,m.get("epoch").getAsString(),"SHARE");consent.accepted(id);}
            case "SHARE_STOP","STREAM_STOP"->{
                if(m.has("id")){
                    String id=m.get("id").getAsString(),stopped=m.has("epoch")?m.get("epoch").getAsString():"",owned=publishing.get(id);
                    View visible=views.get(id);
                    if(!stopped.isEmpty()&&owned!=null&&!stopped.equals(owned))break;
                    boolean publicStream=visible==null||visible.epoch.isEmpty()||stopped.isEmpty()||stopped.equals(visible.epoch);
                    if(owned!=null){
                        removePublishing(id);pendingPictures.remove(id);closeEngine(id);
                        boolean keep=m.has("keepPreview")&&m.get("keepPreview").getAsBoolean()||editingBrowser(id);
                        if(!keep)closeBrowser(id);
                    }
                    if(publicStream){CameraOutput.invalidate(id);MirrorRenderer.clearTransition(id);TextureFrame frame=frames.remove(id);if(frame!=null)frame.close();remotePictures.remove(id);assemblers.remove(id);SpatialAudio sound=sounds.remove(id);if(sound!=null)sound.close();}
                }
                if(m.has("reason")&&!m.get("reason").getAsString().isBlank())notice(m.get("reason").getAsString());
            }
            case "EMERGENCY"->{emergency=m.get("enabled").getAsBoolean();if(emergency){stopSharing();CameraOutput.close();views.clear();for(TextureFrame f:frames.values())f.close();frames.clear();}notice(emergency?"The server owner stopped all Mirror screens":"Mirror is available again");}
        }
    }
    private static void data(DataPayload p){
        if(p.kind()==0){receiveMedia(p);return;}
        boolean authorized=views.values().stream().anyMatch(v->v.stream.equals(p.id())&&v.epoch.equals(p.epoch())&&v.anchor.spec.live);
        if(!wantedStreams.contains(p.id())||publishing.containsKey(p.id())||!authorized||p.epoch().isBlank()||emergency||preferences.hidden||p.epoch().equals(blockedStreams.get(p.id())))return;
        if(p.kind()==1){byte[] jpeg=assemblers.computeIfAbsent(p.id(),k->new FrameAssembler()).add(p.number(),p.part(),p.parts(),p.bytes(),System.currentTimeMillis());if(jpeg!=null&&FrameAssembler.validJpeg(jpeg)){var queued=remotePictures.get(p.id());if(queued==null||!queued.epoch.equals(p.epoch())){queued=new RemotePictures(p.epoch(),new FramePlayout());remotePictures.put(p.id(),queued);}queued.queue.offer(p.number(),jpeg,System.nanoTime());}}
        if(p.kind()==2&&p.bytes().length%4==0&&p.bytes().length<=9600&&!preferences.hidden&&!editingBrowser(p.id()))playLocalAudio(p.id(),p.bytes());
    }
    private static void tick(){InitiatorVisuals.tick();if(world.isEmpty())return;ticks++;var inventoryClient=Minecraft.getInstance();if(inventoryClient.player!=null&&inventoryClient.level!=null){boolean permitted=inventoryClient.player.canUseGameMasterBlocks();if(creativePermission==null||creativePermission!=permitted){boolean vanilla=permitted&&inventoryClient.options.operatorItemsTab().get();net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(inventoryClient.level.enabledFeatures(),!vanilla,inventoryClient.level.registryAccess());net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(inventoryClient.level.enabledFeatures(),vanilla,inventoryClient.level.registryAccess());var connection=inventoryClient.getConnection();if(connection!=null){var items=List.copyOf(net.minecraft.world.item.CreativeModeTabs.searchTab().getDisplayItems());connection.searchTrees().updateCreativeTooltips(inventoryClient.level.registryAccess(),items);connection.searchTrees().updateCreativeTags(items);}creativePermission=permitted;}}if(metadataUntil>0&&System.currentTimeMillis()>metadataUntil)for(String id:new ArrayList<>(metadataPending.keySet()))finishMetadata(id);if(download!=null&&System.currentTimeMillis()-download.last>15000)stalled(download);
        if(ticks%20==0)for(var e:publishing.entrySet())send("HEARTBEAT","id",e.getKey(),"epoch",e.getValue());
        if(ticks%100==0)send("SYNC","sent",System.currentTimeMillis());
        if(!preferences.hidden&&Minecraft.getInstance().level!=null){var mc=Minecraft.getInstance();for(Device d:devices.values())if(mc.player!=null&&mc.player.distanceToSqr(d.x()+.5,d.y()+.7,d.z()+.5)<16*16){if(ticks%100==0&&!d.mood().equals("idle")&&preferences.volume>0)mc.level.playLocalSound(d.x()+.5,d.y()+.7,d.z()+.5,net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,net.minecraft.sounds.SoundSource.BLOCKS,(float)(.04*preferences.volume),.6f,false);}}
        Set<String> active=new HashSet<>();List<View> ordered=views.values().stream().filter(v->v.anchor.spec.live).sorted(Comparator.comparingDouble(v->{var p=Minecraft.getInstance().player;return p==null?0:p.distanceToSqr(v.anchor.x,v.anchor.y,v.anchor.z);})).limit(preferences.maxScreens).toList();liveScreens=ordered.stream().map(v->v.anchor.id).toList();
        if(ticks%20==0&&!preferences.hidden&&preferences.volume>0&&Minecraft.getInstance().player!=null&&ordered.stream().anyMatch(v->{Anchor a=v.anchor;return a.spec.duckMusic&&a.spec.volume>0&&(a.playing||a.spec.source==ScreenSpec.Source.WEB||a.spec.source==ScreenSpec.Source.SHARE)&&Minecraft.getInstance().player.distanceToSqr(a.x+.5+a.spec.x,a.y+a.spec.y,a.z+.5+a.spec.z)<a.spec.soundRange*a.spec.soundRange;}))Minecraft.getInstance().getMusicManager().stopPlaying();
        nearestAnchors.clear();
        for(View view:ordered){String id=view.stream;if(!active.add(id))continue;Anchor a=view.anchor;nearestAnchors.put(id,a);if(a.spec.source==ScreenSpec.Source.BLANK||a.spec.source==ScreenSpec.Source.TEXT){TextureFrame unused=frames.remove(id);if(unused!=null)unused.close();}String sound=isBrowserPublished(id)&&hasBrowser(id)?browserKey(id):id;if(sounds.containsKey(sound))sounds.get(sound).update(a);
            // Memory first: a loaded or loading track, and the item already playing, cost no file check on the tick.
            WorldStore.Media caption=library.get(a.spec.subtitle);if(caption!=null&&caption.kind().equals("subtitle")&&!subtitleTracks.containsKey(caption.id())&&!subtitleLoading.contains(caption.id())){Path path=mediaFile(caption);if(Files.isRegularFile(path)){subtitleLoading.add(caption.id());long expected=generation;files.submit(()->{try{List<Subtitles.Cue> cues=Subtitles.parse(Subtitles.decode(Files.readAllBytes(path)));Minecraft.getInstance().execute(()->{if(generation==expected)subtitleTracks.put(caption.id(),cues);subtitleLoading.remove(caption.id());});}catch(Exception e){Minecraft.getInstance().execute(()->{subtitleLoading.remove(caption.id());notice("This subtitle file could not be read.");});}});}else if(download==null)beginDownload(caption);}
            if(a.spec.source==ScreenSpec.Source.TEXT&&CameraOutput.active(a.id)&&!preferences.hidden){String text=a.spec.text;if(!text.equals(textKeys.get(id))){TextSource.render(id,text);textKeys.put(id,text);}}
            if((a.spec.source==ScreenSpec.Source.MEDIA&&!a.spec.playlist.isEmpty()||a.spec.source==ScreenSpec.Source.WHITEBOARD&&!a.boardMedia.isEmpty()&&view.epoch.isEmpty())&&!preferences.hidden){String mediaId=a.spec.source==ScreenSpec.Source.WHITEBOARD?a.boardMedia:a.spec.playlist.get(a.spec.item);WorldStore.Media item=library.get(mediaId);if(item!=null&&!Failure.blocks(failedMedia.get(id),mediaId,System.currentTimeMillis())){
                // A saved drawing shows without an engine; memory first, so a board already on screen costs no file check.
                if(a.spec.source==ScreenSpec.Source.WHITEBOARD){if(frames.get(id)==null||!item.hash().equals(boards.get(id))){Path path=mediaFile(item);if(Files.isRegularFile(path))showBoard(id,item,path);else if(download==null)beginDownload(item);}}else{
                // The 10 s count from when the worker can take the item: one still preparing its runtime (a first use may download it) is not stuck.
                Long switched=mediaSwitches.get(id);EngineProcess switching=engines.get(id);if(switched!=null&&switching!=null&&switching.starting())mediaSwitches.put(id,System.currentTimeMillis());else if(switched!=null&&System.currentTimeMillis()-switched>10000)closeEngine(id);
                // OBS output asks for 1280 px; otherwise nothing wider than the viewer's picture quality is decoded. A change restarts the item at that width.
                int width=CameraOutput.active(a.id)?1280:preferences.resolution;
                if(!mediaId.equals(mediaPlaying.get(id))||width!=mediaWidths.getOrDefault(id,width)){Path path=mediaFile(item);if(Files.isRegularFile(path))playMedia(id,mediaId,item.kind(),path,width);else if(download==null)beginDownload(item);}
                EngineProcess engine=mediaId.equals(mediaPlaying.get(id))?engines.get(id):null;
                if(engine!=null){double position=a.positionAt(System.currentTimeMillis()+serverOffset);engine.send("TIME","position",position,"playing",a.playing);if(item.kind().equals("pdf"))engine.send("PAGE","page",(int)(position/a.spec.slideSeconds));}}
            }}
        }
        if(editor!=null&&editingBrowser(editor.anchorId())){
            String id=editor.anchorId();active.add(id);
            if(!isBrowserPublished(id)||Minecraft.getInstance().screen instanceof BrowserControlScreen){SpatialAudio audio=sounds.get(browserKey(id));if(audio!=null)audio.preview(editor.previewVolume());}
        }
        keepRecent(recentStreams,active,System.currentTimeMillis());
        Set<String> wanted=new HashSet<>(preferences.hidden?Set.of():active);
        if(!wanted.equals(wantedStreams)||ticks%100==0){wantedStreams=Set.copyOf(wanted);send("SUBSCRIBE","streams",wantedStreams);}
        for(String key:new ArrayList<>(engines.keySet())){
            String id=sourceId(key);
            if(browserKeyed(key)){
                if(!publishing.containsKey(id)&&!editingBrowser(id)){closeEngine(key);continue;}
                var screen=Minecraft.getInstance().screen;
                boolean capture=isBrowserPublished(id)||screen instanceof BrowserControlScreen||screen instanceof InitiatorScreen settings&&settings.previewVisible();
                if(!java.util.Objects.equals(browserCapture.put(id,capture),capture))engines.get(key).send("CAPTURE","enabled",capture);
                continue;
            }
            if(!(key.equals("metadata")&&System.currentTimeMillis()<metadataUntil||key.equals("picker")&&System.currentTimeMillis()<pickerUntil)&&(!recentStreams.containsKey(id)||preferences.hidden)&&!publishing.containsKey(id)&&!(editor!=null&&editor.anchorId().equals(id)&&!mediaPlaying.containsKey(id)))closeEngine(key);
        }
        renderPictures(false);
        for(String key:new ArrayList<>(sounds.keySet())){String id=sourceId(key);if(browserKeyed(key)?!publishing.containsKey(id)&&!editingBrowser(id):!active.contains(id)&&!publishing.containsKey(id))sounds.remove(key).close();}
        for(String id:new ArrayList<>(frames.keySet()))if(!recentStreams.containsKey(id)&&!publishing.containsKey(id)&&!(editor!=null&&editor.anchorId().equals(id))&&!MirrorRenderer.fading(id)){frames.remove(id).close();remotePictures.remove(id);assemblers.remove(id);}
    }
    /** Screens that just left the nearest few keep their engine and picture for 3 s, so walking along that boundary does not restart them at every step. */
    static void keepRecent(Map<String,Long> recent,Set<String> active,long now){for(String id:active)recent.put(id,now);recent.values().removeIf(seen->now-seen>3000);}
    public static void renderPictures(){renderPictures(true);}
    private static void renderPictures(boolean upload){
        if(world.isEmpty())return;
        drainAudio();
        for(var e:engines.entrySet()){
            String key=e.getKey(),id=sourceId(key);EngineProcess.Picture snapshot;
            while((snapshot=e.getValue().takePicture())!=null){
                localPictures.computeIfAbsent(key,k->new FramePlayout(20_000_000)).offer(FrameSequence.next(0,snapshot.received()),snapshot.bytes(),snapshot.received());
                String epoch=publishing.get(id);
                // OBS output gets each picture once: TextureFrame.accept forwards every frames/<id> picture, and a published browser (keyed browser-<id>) is forwarded here.
                if(epoch!=null&&PublicationSource.forwards(browserKeyed(key),publicationSources.get(id))&&publicationRates.computeIfAbsent(id,k->new FrameRateGate()).allow(System.nanoTime()/1_000_000,publishFps)){if(browserKeyed(key))CameraOutput.accept(id,snapshot.bytes());publishPicture(id,epoch,snapshot.bytes(),snapshot.received());}
            }
        }
        for(var e:uplinks.entrySet())uplink(e.getKey(),e.getValue());
        if(upload){
        long renderNow=System.nanoTime();
        for(var it=localPictures.entrySet().iterator();it.hasNext();){var entry=it.next();String key=entry.getKey(),id=sourceId(key);
            if(!engines.containsKey(key)){it.remove();continue;}byte[] jpeg=entry.getValue().poll(renderNow);if(jpeg==null)continue;
            boolean browser=browserKeyed(key);var frame=browser?browserFrames.computeIfAbsent(id,k->new TextureFrame(browserKey(k))):frames.computeIfAbsent(id,TextureFrame::new);
            frame.accept(jpeg);frame.browserPixels=browser;
        }
        for(var it=remotePictures.entrySet().iterator();it.hasNext();){var entry=it.next();String id=entry.getKey();var stream=entry.getValue();
            boolean visible=!emergency&&!preferences.hidden&&wantedStreams.contains(id)&&!stream.epoch.equals(blockedStreams.get(id))&&views.values().stream().anyMatch(v->v.stream.equals(id)&&v.epoch.equals(stream.epoch)&&v.anchor.spec.live);
            if(!visible){it.remove();continue;}byte[] jpeg=stream.queue.poll(renderNow);if(jpeg!=null)frames.computeIfAbsent(id,TextureFrame::new).accept(jpeg);
        }
        // Decode only pictures a pass drew last frame; any open screen (editor, browser, preview) may show any of them.
        boolean gui=Minecraft.getInstance().screen!=null;
        for(TextureFrame frame:frames.values())if(gui||MirrorRenderer.wanted(frame))frame.upload();
        for(var entry:browserFrames.entrySet())if(gui||MirrorRenderer.wanted(entry.getValue())||browserShowRequests.containsKey(entry.getKey()))entry.getValue().upload();
        }
        long now=System.nanoTime(),period=1_000_000_000L/publishFps;
        for(String id:new ArrayList<>(pendingPictures.keySet())){
            String epoch=publishing.get(id);if(epoch==null){pendingPictures.remove(id);continue;}
            long due=publishAt.getOrDefault(id,0L);if(now<due)continue;
            var snapshot=pendingPictures.remove(id);publishPicture(id,epoch,snapshot.bytes(),snapshot.received());publishAt.put(id,Math.max(due+period,now-period));
        }
        for(String id:new ArrayList<>(browserShowRequests.keySet())){
            if(System.currentTimeMillis()-browserShowSince.getOrDefault(id,0L)>20000){browserShowRequests.remove(id);browserShowSince.remove(id);pendingBrowserPublications.remove(id);publicationIntent.cancel(id);notice("The browser did not become ready. Open Browse and use Reload.");continue;}
            if(!publishing.containsKey(id)&&browserPreviewReady(id)&&!publicationIntentPending(id))send("PUBLISH_WEB","id",id);
        }
    }
    private static boolean publicationIntentPending(String id){return pendingBrowserPublications.contains(id);}
    private static final Set<String> pendingBrowserPublications=new HashSet<>();
    private static void drainAudio(){
        if(incomingAudio.isEmpty())return;
        Map<String,java.io.ByteArrayOutputStream> batches=new HashMap<>();AudioChunk chunk;
        while((chunk=incomingAudio.poll())!=null){
            if(chunk.generation!=generation||engineVersions.getOrDefault(chunk.id,0L)!=chunk.version||System.nanoTime()-chunk.received>250_000_000L||chunk.received<audioReady.getOrDefault(chunk.id,0L))continue;
            var bytes=batches.computeIfAbsent(chunk.id,k->new java.io.ByteArrayOutputStream(9600));
            if(bytes.size()+chunk.pcm.length>9600){routeAudio(chunk.id,bytes.toByteArray());bytes.reset();}
            bytes.writeBytes(chunk.pcm);
        }
        batches.forEach((id,bytes)->{if(bytes.size()>0)routeAudio(id,bytes.toByteArray());});
    }
    private static void routeAudio(String key,byte[] pcm){
        String id=sourceId(key),epoch=publishing.get(id);boolean browser=browserKeyed(key);
        if(epoch!=null&&PublicationSource.forwards(browser,publicationSources.get(id))&&pcm.length<=9600&&ClientPlayNetworking.canSend(DataPayload.TYPE))ClientPlayNetworking.send(new DataPayload(2,id,epoch,System.nanoTime(),0,1,pcm));
        if(browser){if(isBrowserPublished(id)||editingBrowser(id))playLocalAudio(key,pcm);}
        else if(epoch==null&&!editingBrowser(id))playLocalAudio(key,pcm);
    }
    private static void playLocalAudio(String key,byte[] pcm){
        String id=sourceId(key);
        boolean preview=browserKeyed(key)&&editingBrowser(id)&&(!isBrowserPublished(id)||Minecraft.getInstance().screen instanceof BrowserControlScreen);
        // Linked screens share a stream; its sound comes from the nearest one, whose gain and pan tick refreshes.
        Anchor nearest=nearestAnchors.get(id);
        if(preferences.hidden||!preview&&nearest==null)return;
        // A new sound drops chunks until it has a gain, so it gets one before the first.
        SpatialAudio audio=sounds.get(key);
        if(audio==null){audio=new SpatialAudio();sounds.put(key,audio);if(preview)audio.preview(editor.previewVolume());else audio.update(nearest);}
        audio.accept(pcm);
    }
    /** This client's own progress toward a screen's first picture: its world-media download or first-time preparation. */
    public static String progress(View view){Anchor a=view.anchor;String media=a.spec.source==ScreenSpec.Source.WHITEBOARD?a.boardMedia:a.spec.playlist.isEmpty()?"":a.spec.playlist.get(a.spec.item);Download d=download;return d!=null&&d.media.id().equals(media)?"Loading "+d.media.name()+" · "+(100*d.offset/Math.max(1,d.media.bytes()))+"%":preparing.get(view.stream);}
    public static String subtitle(Anchor anchor){List<Subtitles.Cue> track=subtitleTracks.get(anchor.spec.subtitle);return track==null?(library.containsKey(anchor.spec.subtitle)?"":anchor.spec.subtitle):Subtitles.at(track,anchor.positionAt(System.currentTimeMillis()+serverOffset));}
    /** The engine for a screen or helper. desktop is true only for the file picker and screen sharing, which show or read the desktop; every other engine runs headless. */
    public static EngineProcess engine(String id,boolean browser,boolean media,boolean desktop){
        String key=browser?browserKey(id):id;
        // A live engine serves later requests, unless one needs the media decoder or the desktop it was started without.
        if(engines.containsKey(key)&&engines.get(key).alive()&&(media&&!engines.get(key).media()||desktop&&!engines.get(key).desktop()))closeEngine(key);
        if(browser)browserEngines.add(id);
        EngineProcess current=engines.get(key);if(current!=null&&current.alive())return current;if(current!=null)current.close();
        long startGeneration=generation,version=engineVersions.merge(key,1L,Long::sum);
        // The reader thread marks the current item's start before passing on any later sound.
        EngineProcess engine=new EngineProcess(runtime,key,browser,media,desktop,m->{if(m.get("type").getAsString().equals("SOURCE_READY")&&m.has("media")&&m.get("media").getAsString().equals(mediaPlaying.get(key)))audioReady.put(key,System.nanoTime());Minecraft.getInstance().execute(()->{if(startGeneration==generation&&engineVersions.getOrDefault(key,0L)==version)engineEvent(key,m);});},pcm->{var chunk=new AudioChunk(key,startGeneration,version,System.nanoTime(),pcm);if(!incomingAudio.offer(chunk)){incomingAudio.poll();incomingAudio.offer(chunk);}});
        engines.put(key,engine);return engine;
    }
    /** Plays an item on the screen's live media engine when there is one, so a new slide or video costs a new decoder, not a new JVM. */
    private static void playMedia(String id,String mediaId,String kind,Path path,int width){
        // PNG, JPEG and GIF pictures decode in Java, so showing one never waits for the media decoder a first use downloads.
        boolean decoder=!kind.equals("image")||!javaPicture(head(path));
        EngineProcess current=engines.get(id);boolean reuse=current!=null&&current.alive()&&mediaPlaying.containsKey(id)&&(current.media()||!decoder);
        if(reuse){current.discardPictures();localPictures.remove(id);SpatialAudio sound=sounds.remove(id);if(sound!=null)sound.close();mediaSwitches.put(id,System.currentTimeMillis());}else closeEngine(id);
        mediaPlaying.put(id,mediaId);mediaWidths.put(id,width);audioReady.put(id,Long.MAX_VALUE);
        (reuse?current:engine(id,false,decoder,false)).send("MEDIA","path",path.toString(),"kind",kind,"media",mediaId,"maxWidth",width);
    }
    /** A PNG, JPEG or GIF header: what the worker reads with ImageIO. Anything else (WebP, an unknown picture) needs FFmpeg. */
    static boolean javaPicture(byte[] h){return h.length>=4&&(h[0]==(byte)137&&h[1]==80&&h[2]==78&&h[3]==71||h[0]==(byte)255&&h[1]==(byte)216&&h[2]==(byte)255||h[0]=='G'&&h[1]=='I'&&h[2]=='F'&&h[3]=='8');}
    private static byte[] head(Path path){try(InputStream in=Files.newInputStream(path)){return in.readNBytes(8);}catch(IOException e){return new byte[0];}}
    /** A screen waiting for its first picture shows only preparation still under way: the closing READY ends it, so a paused song with no cover stays blank. */
    static void showPreparation(Map<String,String> shown,String key,String text){if(text.equals(RuntimeInstaller.READY))shown.remove(key);else shown.put(key,text);}
    /** Whether an engine event is about the item playing now: a reused media engine can still report on the one before, which must not fail or time the new one. */
    static boolean currentItem(JsonObject event,String playing){return !event.has("media")||playing==null||event.get("media").getAsString().equals(playing);}
    private static void engineEvent(String key,JsonObject m){String id=sourceId(key);String type=m.get("type").getAsString();
        if(!currentItem(m,mediaPlaying.get(key)))return;
        if(type.equals("SOURCE_READY")||type.equals("SOURCE_FAILED"))mediaSwitches.remove(key);
        if(type.equals("SOURCE_READY")&&browserKeyed(key)){EngineProcess browser=engines.get(key);if(browser!=null)browser.send("AUDIO","enabled",true);status="";}if(type.equals("BROWSER_INFO"))Reports.add(m.get("text").getAsString());if(type.equals("ADDRESS"))browserAddresses.put(id,m.get("url").getAsString());if(type.equals("BROWSER_ERROR")||type.equals("BROWSER_AUDIO_LIMITED"))notice(m.get("text").getAsString());if(type.equals("PROBED_INFO")){send("MEDIA_INFO","media",m.get("media").getAsString(),"duration",m.get("duration").getAsDouble(),"pages",m.get("pages").getAsInt());finishMetadata(m.get("media").getAsString());}if(type.equals("PROBE_FAILED")){finishMetadata(m.get("media").getAsString());notice("Imported media metadata could not be read. Check the file before using it in a show.");}if(type.equals("ADDRESS")&&isBrowserPublished(id))send("PAGE_CHANGED","id",id);if(type.equals("PREPARING")){status=m.get("text").getAsString();showPreparation(preparing,key,status);if(key.equals("picker"))pickerUntil=Math.max(pickerUntil,System.currentTimeMillis()+60000);}if(type.equals("ERROR")||type.equals("SOURCE_FAILED")){String item=mediaPlaying.get(key),text=m.has("text")?m.get("text").getAsString():"";
            // Offline at a first use the worker says so itself and the screen tries again; a repeat of the notice already shown is not added again.
            if(item!=null){boolean preparing=m.has("preparing")&&m.get("preparing").getAsBoolean();failedMedia.put(key,Failure.of(item,preparing,System.currentTimeMillis()));closeEngine(key);clearPicture(key);String shown=preparing?text:text.isEmpty()?"Media could not open. Choose another file or use Reload media in Playback options.":"Media could not open: "+text;if(!shown.equals(notice))notice(shown);}
            else if(key.equals("picker")){closeEngine(key);pickerUntil=0;status="";notice(text);}
            else if(key.equals("metadata")&&type.equals("SOURCE_FAILED")){for(String pending:new ArrayList<>(metadataPending.keySet()))finishMetadata(pending);notice("Imported media metadata could not be read. "+text);}
            else{if(type.equals("SOURCE_FAILED")){if(browserKeyed(key)&&!isBrowserPublished(id))closeBrowser(id);else{send("STOP_SHARING","id",id);publicationIntent.cancel(id);if(publishing.containsKey(id))stopScreenSharing(id);else{closeEngine(key);if(!browserKeyed(key))clearPicture(id);}}}notice(m.get("text").getAsString());}}if(type.equals("CAPTURE_ENDED")){send("STOP_SHARING");removePublishing(id);closeEngine(id);notice("The selected window closed.");}if(type.equals("FILE")){Path selected=Path.of(m.get("path").getAsString());if(editor!=null&&ImportScreen.video(selected))Minecraft.getInstance().setScreen(new ImportScreen(Minecraft.getInstance().screen,selected,id));else{importFile(selected,"");closeEngine("picker");pickerUntil=0;}}if(type.equals("FILE_CANCEL")){closeEngine("picker");pickerUntil=0;if(editor!=null)editor.importCancelled();}if(type.equals("TRANSCODED")){importFile(Path.of(m.get("path").getAsString()),pendingImportFolder);closeEngine("picker");pickerUntil=0;}
        if(type.equals("MEDIA_INFO")&&operator){String item=mediaPlaying.get(id);if(item!=null&&m.has("duration"))send("MEDIA_INFO","media",item,"duration",m.get("duration").getAsDouble(),"pages",m.get("pages").getAsInt());}
        if(Minecraft.getInstance().screen instanceof BrowserControlScreen browserScreen&&type.equals("ADDRESS"))browserScreen.addressChanged(id,m.get("url").getAsString());if(editor!=null&&editor.anchorId().equals(id))editor.engineEvent(m);if(Minecraft.getInstance().screen instanceof ShareConsentScreen consent)consent.engineEvent(m);
    }
    public static void closeBrowser(String id){closeEngine(browserKey(id));}
    public static void closeEngine(String key){localPictures.remove(key);
        String id=sourceId(key);
        if(browserKeyed(key)){browserEngines.remove(id);browserAddresses.remove(id);browserPolicies.remove(id);browserSizes.remove(id);browserCapture.remove(id);browserRates.remove(id);TextureFrame preview=browserFrames.remove(id);if(preview!=null)preview.close();pendingPictures.remove(id);publishAt.remove(id);publicationRates.remove(id);pendingBrowserPublications.remove(id);browserShowRequests.remove(id);browserShowSince.remove(id);}
        preparing.remove(key);engineVersions.merge(key,1L,Long::sum);EngineProcess process=engines.remove(key);if(process!=null)process.close();mediaPlaying.remove(key);mediaWidths.remove(key);audioReady.remove(key);mediaSwitches.remove(key);SpatialAudio sound=sounds.remove(key);if(sound!=null)sound.close();
    }
    /** A drawing goes out on every change so the saved board is never behind; engine pictures wait in an uplink that keeps at most two frames on the link. */
    private static void publishPicture(String id,String epoch,byte[] picture){sendPicture(id,epoch,picture,System.nanoTime(),null);}
    private static void publishPicture(String id,String epoch,byte[] picture,long received){Uplink u=uplinks.computeIfAbsent(id,k->new Uplink());u.offer(new Uplink.Frame(epoch,picture,received));uplink(id,u);}
    private static void uplink(String id,Uplink u){Uplink.Frame f=u.take(publishing.get(id),System.currentTimeMillis());if(f!=null)sendPicture(id,f.epoch(),f.picture(),f.received(),u);}
    private static void sendPicture(String id,String epoch,byte[] picture,long received,Uplink u){long n=FrameSequence.next(sequences.getOrDefault(id,0L),received);sequences.put(id,n);var parts=DataPayload.picture(id,epoch,n,picture);var sender=ClientPlayNetworking.getSender();for(int i=0;i<parts.size();i++)sender.sendPacket(parts.get(i),u!=null&&i==parts.size()-1?f->u.delivered():null);}
    public static void submitDrawingFrame(String id,byte[] jpeg){if(!FrameAssembler.validJpeg(jpeg))return;frames.computeIfAbsent(id,TextureFrame::new).accept(jpeg);String epoch=publishing.get(id);if(epoch!=null)publishPicture(id,epoch,jpeg);}
    private static void rememberPublisher(String id,String epoch,String source){publishing.put(id,epoch);publicationSources.put(id,source);}
    public static boolean isPublishing(String id){return publishing.containsKey(id);}
    public static boolean isBrowserPublished(String id){return publishing.containsKey(id)&&"WEB".equals(publicationSources.get(id));}
    private static String removePublishing(String id){publicationSources.remove(id);publicationRates.remove(id);uplinks.remove(id);return publishing.remove(id);}
    public static java.awt.image.BufferedImage loadDrawing(String id){WorldStore.Media item=library.get(id);if(item==null||!item.kind().equals("image")||item.bytes()>240000)return null;try{Path path=mediaFile(item);if(!Files.isRegularFile(path)){if(download==null)beginDownload(item);return null;}byte[] jpeg=Files.readAllBytes(path);if(!FrameAssembler.validJpeg(jpeg))return null;return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));}catch(IOException e){return null;}}
    public static void stopScreenSharing(String id){pendingPictures.remove(id);browserShowRequests.remove(id);browserShowSince.remove(id);pendingBrowserPublications.remove(id);publicationIntent.cancel(id);send("STOP_SHARING","id",id);String epoch=removePublishing(id);if(epoch!=null)blockedStreams.put(id,epoch);closeEngine(id);closeBrowser(id);clearPicture(id);}
    public static void stopSharing(){shareAttempt++;browserShowRequests.clear();browserShowSince.clear();pendingBrowserPublications.clear();pendingPictures.clear();publicationIntent.clear();send("STOP_SHARING");for(String id:new ArrayList<>(publishing.keySet())){blockedStreams.put(id,publishing.get(id));closeEngine(id);closeBrowser(id);TextureFrame frame=frames.remove(id);if(frame!=null)frame.close();CameraOutput.invalidate(id);MirrorRenderer.clearTransition(id);}publishing.clear();publicationSources.clear();uplinks.clear();}
    public static void retryMedia(String id){var view=views.get(id);String stream=view==null?id:view.stream();failedMedia.remove(stream);downloadBlocked.clear();closeEngine(stream);clearPicture(stream);notice("Reloading the selected media");}
    public static boolean browserPreviewReady(String id){var frame=browserFrames.get(id);return browserEngines.contains(id)&&frame!=null&&frame.available()&&frame.browserPixels;}
    public static void privateBrowser(String id){if(publishing.containsKey(id)&&!isBrowserPublished(id)){browserShowRequests.remove(id);browserShowSince.remove(id);pendingBrowserPublications.remove(id);return;}browserShowRequests.remove(id);browserShowSince.remove(id);pendingBrowserPublications.remove(id);pendingPictures.remove(id);publicationIntent.cancel(id);CameraOutput.invalidate(id);send("PRIVATE_BROWSER","id",id);removePublishing(id);EngineProcess process=browserEngine(id);if(process!=null)process.send("AUDIO","enabled",true);}
    /** In singleplayer and for a LAN host the world's own file plays directly; everyone else plays the verified cache copy. */
    private static Path mediaFile(WorldStore.Media m){if(Minecraft.getInstance().hasSingleplayerServer()){Path local=Mirror.SERVER.localMedia(m);if(local!=null)return local;}return cache.resolve(m.hash());}
    /** A saved drawing is a small validated JPEG, decoded straight into the screen's texture without starting a media engine. */
    private static void showBoard(String id,WorldStore.Media item,Path path){
        if(!boardLoading.add(id))return;long expected=generation;
        files.submit(()->{byte[] jpeg;try{jpeg=Files.size(path)<=240000?Files.readAllBytes(path):null;}catch(IOException e){jpeg=null;}byte[] read=jpeg;
            Minecraft.getInstance().execute(()->{if(generation!=expected)return;boardLoading.remove(id);if(views.values().stream().noneMatch(v->v.stream.equals(id)&&v.epoch.isEmpty()&&v.anchor.boardMedia.equals(item.id())))return;
                if(read!=null&&FrameAssembler.validJpeg(read)){frames.computeIfAbsent(id,TextureFrame::new).accept(read);boards.put(id,item.hash());}else{failedMedia.put(id,Failure.of(item.id(),false,System.currentTimeMillis()));notice("This drawing could not be shown.");}});});
    }
    private static void beginDownload(WorldStore.Media item){if(System.currentTimeMillis()<downloadBlocked.getOrDefault(item.hash(),0L))return;try{Files.createDirectories(cache);AtomicFile.rejectLinks(cache);if(Files.getFileStore(cache).getUsableSpace()<item.bytes()+64L*1024*1024){downloadBlocked.put(item.hash(),System.currentTimeMillis()+60000);notice("Mirror needs more disk space to load world media.");return;}Download d=new Download();d.media=item;d.temp=Files.createTempFile(cache,".download-",".part");d.out=Files.newOutputStream(d.temp);d.hash=MessageDigest.getInstance("SHA-256");d.generation=generation;d.last=System.currentTimeMillis();download=d;send("GET_MEDIA","media",item.id(),"offset",0,"start",true);status="Loading "+item.name();}catch(Exception e){downloadBlocked.put(item.hash(),System.currentTimeMillis()+60000);notice("World media could not be saved in your local cache.");}}
    /** The server keeps a window of chunks in flight; every few chunks confirm the position so the window slides on. */
    private static void receiveMedia(DataPayload p){Download d=download;if(d==null||!p.id().equals(d.media.id())||!p.epoch().equals(world)||p.number()!=d.offset||d.generation!=generation||d.offset+p.bytes().length>d.media.bytes())return;
        try{d.last=System.currentTimeMillis();d.resumed=false;d.out.write(p.bytes());d.hash.update(p.bytes());d.offset+=p.bytes().length;status="Loading "+d.media.name()+" · "+(100*d.offset/d.media.bytes())+"%";
            if(d.offset==d.media.bytes()){d.out.close();if(!HexFormat.of().formatHex(d.hash.digest()).equals(d.media.hash())){downloadBlocked.put(d.media.hash(),Long.MAX_VALUE);closeDownload();status="";notice("World media failed verification and was discarded.");return;}Path target=AtomicFile.child(cache,d.media.hash());Files.move(d.temp,target,StandardCopyOption.REPLACE_EXISTING);download=null;status="";}
            else if(d.offset%((long)MediaWindow.ACK*MediaRepository.CHUNK)==0)send("GET_MEDIA","media",d.media.id(),"offset",d.offset);
        }catch(IOException e){downloadBlocked.put(d.media.hash(),System.currentTimeMillis()+60000);closeDownload();status="";notice("World media could not be saved in your local cache.");}
    }
    /** A silent download resumes once where it stopped; if that stalls too it is dropped and retried after a minute. One nobody needs any more ends quietly. */
    private static void stalled(Download d){
        String id=d.media.id();boolean wanted=editor!=null||views.values().stream().anyMatch(v->v.anchor.spec.live&&(v.anchor.spec.currentMedia().equals(id)||v.anchor.boardMedia.equals(id)||v.anchor.spec.subtitle.equals(id)));
        if(wanted&&!d.resumed){d.resumed=true;d.last=System.currentTimeMillis();send("GET_MEDIA","media",id,"offset",d.offset,"start",true);return;}
        closeDownload();status="";if(wanted){downloadBlocked.put(d.media.hash(),System.currentTimeMillis()+60000);notice("World media stopped downloading. Mirror will try again in a minute.");}
    }
    public static void importFile(Path path,String folder){if(upload!=null){notice("Finish the current import first.");return;}try{if(!Files.isRegularFile(path)||Files.size(path)>MediaRepository.MAX_FILE)throw new IOException("Choose a file under 512 MB.");String name=path.getFileName().toString();String lower=name.toLowerCase(Locale.ROOT);String kind=lower.matches(".*\\.(png|jpg|jpeg|gif|webp)$")?"image":lower.matches(".*\\.(mp4|mkv|webm|mov|avi)$")?"video":lower.matches(".*\\.(mp3|wav|ogg|flac|m4a)$")?"audio":lower.endsWith(".pdf")?"pdf":lower.matches(".*\\.(srt|vtt)$")?"subtitle":"";if(kind.isEmpty())throw new IOException("Choose an image, video, music file or PDF.");Upload u=new Upload();u.file=path;u.in=Files.newInputStream(path);u.total=Files.size(path);u.generation=generation;if(!Minecraft.getInstance().hasSingleplayerServer())try{Files.createDirectories(cache);AtomicFile.rejectLinks(cache);u.digest=MessageDigest.getInstance("SHA-256");u.copy=Files.createTempFile(cache,".import-",".part");u.copyOut=Files.newOutputStream(u.copy);}catch(Exception e){dropCopy(u);}upload=u;send("IMPORT","name",name,"folder",folder,"kind",kind,"bytes",u.total);status="Importing "+name;}catch(IOException e){notice(e.getMessage());}}
    private static void uploadChunk(){
        Upload transfer=upload;if(transfer==null||transfer.id==null)return;long expected=generation;
        files.submit(()->{
            try{byte[] batch=transfer.in.readNBytes(MediaRepository.CHUNK*MediaRepository.BATCH);if(transfer.copyOut!=null)try{transfer.digest.update(batch);transfer.copyOut.write(batch);}catch(IOException e){dropCopy(transfer);}
                Minecraft.getInstance().execute(()->{
                    if(upload!=transfer||generation!=expected)return;
                    for(int start=0;start<batch.length;start+=MediaRepository.CHUNK){byte[] bytes=Arrays.copyOfRange(batch,start,Math.min(batch.length,start+MediaRepository.CHUNK));long offset=transfer.offset;transfer.offset+=bytes.length;ClientPlayNetworking.send(new DataPayload(0,transfer.id,"",offset,0,1,bytes));}
                    status="Importing · "+(100*transfer.offset/transfer.total)+"%";
                });
            }catch(IOException e){Minecraft.getInstance().execute(()->{if(upload==transfer&&generation==expected){closeUpload();notice("The selected file could not be read.");}});}
        });
    }

    private static void closeDownload(){Download d=download;download=null;if(d!=null)try{d.out.close();Files.deleteIfExists(d.temp);}catch(IOException ignored){}}
    /** Upload copies are touched only on the file thread, after every read already queued there. */
    private static void dropCopy(Upload u){OutputStream out=u.copyOut;Path copy=u.copy;u.copyOut=null;u.copy=null;try{if(out!=null)out.close();if(copy!=null)Files.deleteIfExists(copy);}catch(IOException ignored){}}
    /** The importer's own verified bytes become its cache entry, so a multiplayer import plays without being downloaded back. */
    private static void keepCopy(Upload u,String hash){OutputStream out=u.copyOut;Path copy=u.copy;if(out==null)return;u.copyOut=null;u.copy=null;try{out.close();if(HexFormat.of().formatHex(u.digest.digest()).equals(hash))Files.move(copy,AtomicFile.child(cache,hash),StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(copy);}catch(IOException e){try{Files.deleteIfExists(copy);}catch(IOException ignored){}}}
    private static void closeUpload(){Upload u=upload;upload=null;if(u!=null)try{files.submit(()->dropCopy(u));u.in.close();Path imports=runtime.resolve("imports").toAbsolutePath().normalize();Path owned=u.file.toAbsolutePath().normalize();if(!metadataPending.containsValue(u.file)&&owned.startsWith(imports)&&owned.getFileName().toString().startsWith("Mirror-video-")){AtomicFile.rejectLinks(owned);Files.deleteIfExists(owned);}}catch(IOException ignored){}status="";}
    public static void clearCache(){closeDownload();downloadBlocked.clear();for(String id:new ArrayList<>(mediaPlaying.keySet()))closeEngine(id);files.submit(()->{try{if(Files.isDirectory(cache)){AtomicFile.rejectLinks(cache);try(var entries=Files.list(cache)){for(Path p:entries.toList())if(p.getFileName().toString().matches("[a-f0-9]{64}")){AtomicFile.rejectLinks(p);Files.delete(p);}}}notice("Saved world media cleared");}catch(IOException e){notice("The media cache could not be cleared.");}});}
    public static void savePreferences(){if(preferences.hidden){stopSharing();CameraOutput.close();}preferences.save(config);}
    private static void finishMetadata(String id){Path file=metadataPending.remove(id);if(file!=null)try{Path owned=file.toAbsolutePath().normalize();if(owned.startsWith(runtime.resolve("imports").toAbsolutePath().normalize())&&owned.getFileName().toString().startsWith("Mirror-video-")){AtomicFile.rejectLinks(owned);Files.deleteIfExists(owned);}}catch(IOException ignored){}if(metadataPending.isEmpty()){metadataUntil=0;closeEngine("metadata");}}
    private static void reset(){shareAttempt++;for(String id:new ArrayList<>(metadataPending.keySet()))finishMetadata(id);generation++;bestRoundTrip=Long.MAX_VALUE;CameraOutput.close();for(EngineProcess e:engines.values())e.close();engines.clear();engineVersions.clear();preparing.clear();liveScreens=List.of();for(SpatialAudio s:sounds.values())s.close();sounds.clear();for(TextureFrame f:frames.values())f.close();frames.clear();for(TextureFrame frame:browserFrames.values())frame.close();browserFrames.clear();browserPolicies.clear();browserSizes.clear();browserCapture.clear();browserRates.clear();views.clear();devices.clear();InitiatorVisuals.sync();library.clear();subtitleTracks.clear();subtitleLoading.clear();players.clear();groups.clear();publishing.clear();publicationSources.clear();pendingPictures.clear();publishAt.clear();browserAddresses.clear();incomingAudio.clear();wantedStreams=Set.of();pendingBrowserPublications.clear();browserShowRequests.clear();browserShowSince.clear();mediaPlaying.clear();mediaWidths.clear();audioReady.clear();mediaSwitches.clear();recentStreams.clear();nearestAnchors.clear();failedMedia.clear();localPictures.clear();publicationRates.clear();remotePictures.clear();assemblers.clear();sequences.clear();closeDownload();closeUpload();downloadBlocked.clear();uplinks.clear();boards.clear();boardLoading.clear();creativePermission=null;publicationIntent.clear();browserEngines.clear();textKeys.clear();blockedStreams.clear();world="";operator=false;serverOwner=false;worldSettingsReady=false;worldFeatures.clear();emergency=false;editor=null;status="";MirrorRenderer.reset();}
}
