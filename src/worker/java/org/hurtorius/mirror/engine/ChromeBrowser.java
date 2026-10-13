package org.hurtorius.mirror.engine;

import com.google.gson.*;
import org.hurtorius.mirror.core.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Mirror-managed Chromium, headless, with private inherited pipes (no debugging port).
 * The dedicated persistent Mirror profile never opens the player's normal Chrome data. */
final class ChromeBrowser implements EngineMain.Source {
    /** Shared with ChromeSignIn, which opens the same profile: a bounded cache and no component or model downloads. */
    static final List<String> PROFILE_FLAGS=List.of("--disk-cache-size=67108864","--disable-component-update","--disable-features=OptimizationHints,OptimizationGuideModelDownloading,OptimizationHintsFetching","--metrics-recording-only");
    private final EngineMain engine;
    private final ScreenSpec spec;
    private final BrowserAudioMixer audioMixer;
    private final String token=UUID.randomUUID().toString(),binding="mirrorAudio"+UUID.randomUUID().toString().replace("-","");
    private final AtomicInteger sequence=new AtomicInteger();
    private final ConcurrentMap<Integer,CompletableFuture<JsonObject>> pending=new ConcurrentHashMap<>();
    private final Set<String> popups=new HashSet<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Mirror Chrome frames");t.setDaemon(true);return t;});
    private BrowserPipe pipe;
    private volatile boolean alive=true,capturing=true,pageError;
    private volatile String session="",target="",script="";
    private BrowserViewport viewport;
    private volatile int frameMillis;
    private int buttons;
    private boolean audioRestricted,failedPage;
    private byte[] lastPicture;
    ChromeBrowser(EngineMain engine,JsonObject m)throws Exception{
        this.engine=engine;spec=ScreenSpec.read(m.get("spec").toString());viewport=BrowserViewport.of(spec);audioMixer=new BrowserAudioMixer(engine::pcm);
        long launchLogOffset=LinuxBrowserPolicy.logOffset(engine.runtime());
        try{
            launch();
            Thread reader=new Thread(this::read,"Mirror Chrome pipe");reader.setDaemon(true);reader.start();
            call("Browser.setDownloadBehavior",params("behavior","deny"),false);
            target=call("Target.createTarget",params("url","about:blank"),false).get("targetId").getAsString();
            for(var entry:call("Target.getTargets",params(),false).getAsJsonArray("targetInfos")){var other=entry.getAsJsonObject();if(other.get("type").getAsString().equals("page")&&!other.get("targetId").getAsString().equals(target))call("Target.closeTarget",params("targetId",other.get("targetId")),false);}
            session=call("Target.attachToTarget",params("targetId",target,"flatten",true),false).get("sessionId").getAsString();
            call("Page.enable",params(),true);call("Runtime.enable",params(),true);
            call("Runtime.addBinding",params("name",binding),true);
            call("Page.setInterceptFileChooserDialog",params("enabled",true),true);
            call("Emulation.setFocusEmulationEnabled",params("enabled",true),true);
            call("Emulation.setDeviceMetricsOverride",metrics(),true);
            try(var in=ChromeBrowser.class.getResourceAsStream("/mirror/browser-audio.js")){
                if(in==null)throw new IOException("Browser audio is missing.");
                script=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("__MIRROR_AUDIO_TOKEN__",token).replace("__MIRROR_POST__","window['"+binding+"']")
                    +";if(window===window.top){window.open=(url)=>{if(url)location.href=url;return null};document.addEventListener('click',e=>{const a=e.target.closest?.('a[target]');if(a){e.preventDefault();location.href=a.href}},true);}";
            }
            for(var command:frameSetup())call(command.getKey(),command.getValue(),true);
            // Every other tab or popup is shown on the screen instead (see handle).
            call("Target.setDiscoverTargets",params("discover",true,"filter",List.of(Map.of("type","page"))),false);
            call("Page.startScreencast",captureOptions(),true);
            navigate(spec.url);
            engine.event("BROWSER_INFO","text","Mirror browser · "+viewport.width()+" × "+viewport.height());
        }catch(Exception e){close();String hint=LinuxBrowserPolicy.failureHint(engine.runtime(),launchLogOffset);if(!hint.isEmpty())throw new IOException(hint,e);throw e;}
    }
    static Path executable(Path root)throws IOException{
        String configured=System.getenv("MIRROR_BROWSER_EXECUTABLE");
        if(configured==null)throw new IOException("Mirror's browser is not prepared. Reopen Browse to retry.");
        Path path=Path.of(configured).toAbsolutePath().normalize();
        if(!path.startsWith(root.toAbsolutePath().normalize())||!Files.isExecutable(path))throw new IOException("Mirror's browser installation is unavailable.");
        return path;
    }
    private void launch()throws Exception{
        String id=System.getenv("MIRROR_BROWSER_ID");if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))id="local";
        List<String> args=new ArrayList<>(List.of(executable(engine.runtime()).toString(),"--headless=new","--force-color-profile=srgb","--remote-debugging-pipe","--no-first-run","--no-default-browser-check","--disable-background-networking","--safebrowsing-disable-auto-update","--disable-sync","--disable-extensions","--disable-background-timer-throttling","--disable-renderer-backgrounding","--mute-audio","--autoplay-policy=no-user-gesture-required","--deny-permission-prompts"));
        args.addAll(PROFILE_FLAGS);args.addAll(List.of("--user-data-dir="+profile(engine.runtime(),id),"--profile-directory=Default","about:blank"));pipe=BrowserPipe.launch(args);
    }
    /** One profile per screen, because Chrome locks it. Opening one marks it used; others unused for 30 days are removed, under a lock every engine shares. */
    static Path profile(Path runtime,String id)throws IOException{
        Path profiles=runtime.resolve("chrome-profiles").toAbsolutePath(),profile=profiles.resolve(id);Files.createDirectories(profiles);AtomicFile.rejectLinks(profiles);
        try(var lock=FileChannel.open(profiles.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var exclusive=lock.lock();var list=Files.newDirectoryStream(profiles)){
            long now=use(profile);
            for(Path old:list)if(!old.equals(profile)&&Files.isDirectory(old,LinkOption.NOFOLLOW_LINKS)&&now-Files.getLastModifiedTime(old,LinkOption.NOFOLLOW_LINKS).toMillis()>30L*24*60*60*1000)
                try(var entries=Files.walk(old)){for(Path entry:entries.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(entry);}catch(IOException|UncheckedIOException e){System.err.println("An unused browser profile could not be removed: "+e);}
        }catch(IOException|DirectoryIteratorException e){use(profile);System.err.println("Unused browser profiles were not checked: "+e);}
        // A Chrome that is closing holds the profile a moment longer; one started on it now would hand over to the dying one and exit. Its Windows lockfile goes when it does.
        Path lockfile=profile.resolve("lockfile");long start=System.nanoTime();
        while(true)try{Files.deleteIfExists(lockfile);break;}catch(IOException busy){if(System.nanoTime()-start>10_000_000_000L)break;try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        // On Linux and macOS it is a SingletonLock link to "host-pid" instead, which Chrome itself clears once that process is gone.
        Path singleton=profile.resolve("SingletonLock");
        while(System.nanoTime()-start<10_000_000_000L)try{String owner=Files.readSymbolicLink(singleton).toString();if(!ProcessHandle.of(Long.parseLong(owner.substring(owner.lastIndexOf('-')+1))).map(ProcessHandle::isAlive).orElse(false))break;Thread.sleep(50);}
            catch(IOException|RuntimeException released){break;}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        return profile;
    }
    private static long use(Path profile)throws IOException{long now=System.currentTimeMillis();Files.createDirectories(profile);AtomicFile.rejectLinks(profile);Files.setLastModifiedTime(profile,FileTime.fromMillis(now));return now;}
    static int quality(BrowserViewport viewport){return (long)viewport.width()*viewport.height()>921600?62:78;}
    private JsonObject captureOptions(){return params("format","jpeg","quality",quality(viewport),"maxWidth",1920,"maxHeight",1920,"everyNthFrame",1);}
    private JsonObject metrics(){return params("width",viewport.width(),"height",viewport.height(),"deviceScaleFactor",1,"mobile",false);}
    /** What each frame session, the page's and every out-of-process frame's, needs before its document runs. */
    private List<Map.Entry<String,JsonObject>> frameSetup(){
        // No type filter: Chrome still pauses the targets a filter skips (the page's audio worklet never started), so every attached target is resumed in handle.
        return List.of(Map.entry("Page.addScriptToEvaluateOnNewDocument",params("source",script)),Map.entry("Fetch.enable",params("patterns",fetchPatterns(spec))),
            Map.entry("Target.setAutoAttach",params("autoAttach",true,"waitForDebuggerOnStart",true,"flatten",true)));
    }
    /** Without block rules only navigations (top, frames, redirect hops) need checking, so subresources are never paused. */
    static List<Map<String,String>> fetchPatterns(ScreenSpec spec){return spec.blockedSites.isEmpty()?List.of(Map.of("urlPattern","*","resourceType","Document")):List.of(Map.of("urlPattern","*"));}
    static boolean allowed(ScreenSpec spec,String url,boolean document){
        if(document)return spec.siteAllowed(url);
        if(url.startsWith("data:")||url.startsWith("blob:"))return true;
        // The allow-list names websites, not the hosts their pages load from.
        try{String host=ScreenSpec.webUri(url).getHost().toLowerCase(Locale.ROOT);return spec.blockedSites.stream().noneMatch(d->ScreenSpec.matchesDomain(host,d));}catch(IllegalArgumentException e){return false;}
    }
    static String loadError(JsonObject navigation){
        String error=navigation!=null&&navigation.has("errorText")?navigation.get("errorText").getAsString():"";
        return error.isEmpty()||error.equals("net::ERR_ABORTED")||error.equals("net::ERR_BLOCKED_BY_CLIENT")?null:"Chrome could not load the page: "+error;
    }
    /** The address of a new tab or popup that should open on the screen instead, or null. */
    static String popup(JsonObject info,String main){
        String url=info.get("url").getAsString();
        return info.get("type").getAsString().equals("page")&&!info.has("subtype")&&!info.get("targetId").getAsString().equals(main)&&(url.startsWith("http://")||url.startsWith("https://"))?url:null;
    }
    private synchronized int send(String method,JsonObject args,String to,CompletableFuture<JsonObject> reply)throws IOException{
        if(!alive||pipe==null)throw new IOException("Chrome is closed.");int id=sequence.incrementAndGet();JsonObject m=params("id",id,"method",method,"params",args);if(to!=null)m.addProperty("sessionId",to);
        if(reply!=null)pending.put(id,reply);byte[] bytes=(m.toString()+"\0").getBytes(StandardCharsets.UTF_8);
        try{pipe.write(bytes);}catch(IOException e){pending.remove(id);throw e;}return id;
    }
    private JsonObject call(String method,JsonObject args,boolean page)throws Exception{return call(method,args,page,15000);}
    private JsonObject call(String method,JsonObject args,boolean page,long timeout)throws Exception{
        CompletableFuture<JsonObject> reply=new CompletableFuture<>();int id=send(method,args,page?session:null,reply);
        try{return reply.get(timeout,TimeUnit.MILLISECONDS);}finally{pending.remove(id);}
    }
    private void post(String method,JsonObject args){post(method,args,session);}
    private void post(String method,JsonObject args,String to){try{send(method,args,to,null);}catch(IOException e){if(alive)engine.event("BROWSER_ERROR","text",e.getMessage());}}
    private void read(){
        byte[] bytes=new byte[65536];ByteArrayOutputStream message=new ByteArrayOutputStream();int length;
        try{while(alive&&(length=pipe.read(bytes))>=0){
            int start=0;
            for(int i=0;i<length;i++)if(bytes[i]==0){
                message.write(bytes,start,i-start);if(message.size()>8*1024*1024)throw new IOException("Chrome message exceeded its limit.");
                if(message.size()>0){handle(JsonParser.parseString(message.toString(StandardCharsets.UTF_8)).getAsJsonObject());message.reset();}start=i+1;
            }
            if(start<length){message.write(bytes,start,length-start);if(message.size()>8*1024*1024)throw new IOException("Chrome message exceeded its limit.");}
        }}catch(Exception e){if(alive)e.printStackTrace(System.err);}finally{
            pending.values().forEach(f->f.completeExceptionally(new IOException("Chrome closed.")));
            if(alive)engine.event("SOURCE_FAILED","text","Chrome stopped. Reopen Browse to recover.");
        }
    }
    private void handle(JsonObject m){
        if(m.has("id")){var f=pending.remove(m.get("id").getAsInt());if(f!=null){if(m.has("error"))f.completeExceptionally(new IOException(m.get("error").toString()));else f.complete(m.getAsJsonObject("result"));}return;}
        if(!m.has("method"))return;JsonObject p=m.has("params")?m.getAsJsonObject("params"):new JsonObject();String method=m.get("method").getAsString(),from=m.has("sessionId")?m.get("sessionId").getAsString():null;
        // Frame sessions enable Page only so their script runs; the address, picture and errors belong to the page itself.
        if(method.startsWith("Page.")&&!session.equals(from))return;
        switch(method){
            case "Page.screencastFrame"->{
                byte[] jpeg=Base64.getDecoder().decode(p.get("data").getAsString());
                if(capturing&&!failedPage&&!Arrays.equals(lastPicture,jpeg)){lastPicture=jpeg;engine.encodedImage(jpeg);}
                int frame=p.get("sessionId").getAsInt();if(frameMillis==0)post("Page.screencastFrameAck",params("sessionId",frame));else timer.schedule(()->post("Page.screencastFrameAck",params("sessionId",frame)),frameMillis,TimeUnit.MILLISECONDS);
            }
            case "Page.frameNavigated"->{JsonObject f=p.getAsJsonObject("frame");if(!f.has("parentId")){String url=f.get("url").getAsString();failedPage=url.startsWith("chrome-error:");
                // Link clicks, history and reloads that fail land here; typed addresses and blocked pages were already explained.
                if(failedPage){lastPicture=null;engine.image(new java.awt.image.BufferedImage(viewport.width(),viewport.height(),java.awt.image.BufferedImage.TYPE_INT_RGB));if(!pageError)engine.event("BROWSER_ERROR","text","Chrome could not load the page.");}else if(spec.siteAllowed(url))engine.event("ADDRESS","url",url);pageError=failedPage;}}
            // Every frame's dialog arrives here and nobody can answer it on a shared screen: dismiss it, but let a page that asks before leaving be left.
            case "Page.javascriptDialogOpening"->post("Page.handleJavaScriptDialog",params("accept",p.get("type").getAsString().equals("beforeunload")));
            case "Page.frameStartedNavigating"->{if(target.equals(p.get("frameId").getAsString()))pageError=false;}
            case "Page.navigatedWithinDocument"->{String url=p.get("url").getAsString();if(spec.siteAllowed(url))engine.event("ADDRESS","url",url);}
            case "Fetch.requestPaused"->{String url=p.getAsJsonObject("request").get("url").getAsString();boolean document=p.get("resourceType").getAsString().equals("Document"),allow=allowed(spec,url,document);
                post(allow?"Fetch.continueRequest":"Fetch.failRequest",allow?params("requestId",p.get("requestId")):params("requestId",p.get("requestId"),"errorReason","BlockedByClient"),from);
                if(!allow&&document){if(target.equals(p.get("frameId").getAsString()))pageError=true;engine.event("BROWSER_ERROR","text","Navigation blocked by this screen's website rules.");}
            }
            // Cross-site frames run in their own process and session: give them the audio hook and the site rules before they start. Workers only resume.
            case "Target.attachedToTarget"->{String child=p.get("sessionId").getAsString();
                if(p.getAsJsonObject("targetInfo").get("type").getAsString().equals("iframe")){post("Page.enable",params(),child);for(var command:frameSetup())post(command.getKey(),command.getValue(),child);}
                if(p.get("waitingForDebugger").getAsBoolean())post("Runtime.runIfWaitingForDebugger",params(),child);
            }
            case "Target.targetCreated","Target.targetInfoChanged"->{JsonObject info=p.getAsJsonObject("targetInfo");String url=popup(info,target);
                if(url!=null&&popups.add(info.get("targetId").getAsString())){navigate(url);post("Target.closeTarget",params("targetId",info.get("targetId")),null);}
            }
            case "Runtime.bindingCalled"->{if(!binding.equals(p.get("name").getAsString()))return;String value=p.get("payload").getAsString(),prefix=token+"|";if(value.length()>12000||!value.startsWith(prefix))return;int divider=value.indexOf('|',prefix.length());if(divider<0)return;String frame=value.substring(prefix.length(),divider),body=value.substring(divider+1);
                if(body.equals("!restricted")){if(!audioRestricted){audioRestricted=true;engine.event("BROWSER_AUDIO_LIMITED","text","This website restricts embedded audio. Try the video's own page.");}}
                else if(!body.startsWith("!"))try{audioMixer.accept(frame,Base64.getDecoder().decode(body));}catch(IllegalArgumentException ignored){}
            }
        }
    }
    private void navigate(String url){
        if(!spec.siteAllowed(url)){engine.event("BROWSER_ERROR","text","That address is blocked by this screen's website rules.");return;}
        // The reply arrives on the pipe reader; nothing here waits for it, so commands keep their order.
        CompletableFuture<JsonObject> reply=new CompletableFuture<>();
        try{send("Page.navigate",params("url",url),session,reply);}catch(IOException e){if(alive)engine.event("BROWSER_ERROR","text",e.getMessage());return;}
        reply.thenAccept(navigation->{String error=loadError(navigation);if(error!=null){pageError=true;engine.event("BROWSER_ERROR","text",error);}});
    }
    public void command(JsonObject m)throws Exception{
        switch(m.get("type").getAsString()){
            case "NAVIGATE"->navigate(m.get("url").getAsString());
            case "BACK","FORWARD"->{JsonObject h=call("Page.getNavigationHistory",params(),true);int index=h.get("currentIndex").getAsInt()+(m.get("type").getAsString().equals("BACK")?-1:1);JsonArray list=h.getAsJsonArray("entries");if(index>=0&&index<list.size())post("Page.navigateToHistoryEntry",params("entryId",list.get(index).getAsJsonObject().get("id")));}
            case "RELOAD"->post("Page.reload",params());
            case "RATE"->{int fps=Math.clamp(m.get("fps").getAsInt(),1,60);frameMillis=fps>=60?0:(int)Math.ceil(2000.0/fps);}
            case "CAPTURE"->{boolean on=m.get("enabled").getAsBoolean();if(on!=capturing){capturing=on;if(on){lastPicture=null;post("Page.startScreencast",captureOptions());}else post("Page.stopScreencast",params());}}
            case "RESIZE"->{var next=BrowserViewport.of(ScreenSpec.read(m.get("spec").toString()));if(!next.equals(viewport)){
                call("Page.stopScreencast",params(),true);
                try{viewport=next;lastPicture=null;call("Emulation.setDeviceMetricsOverride",metrics(),true);
                    try{call("Runtime.evaluate",params("expression","new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))","awaitPromise",true),true,1000);}catch(TimeoutException slow){engine.event("BROWSER_INFO","text","The page is slow to redraw after resizing.");}
                }finally{if(capturing)post("Page.startScreencast",captureOptions());}
            }}
            case "TEXT"->post("Input.insertText",params("text",m.get("text")));
            case "POINTER","WHEEL","KEY"->input(m);
        }
    }
    private void input(JsonObject m){
        String type=m.get("type").getAsString();int modifiers=m.has("mods")?m.get("mods").getAsInt():0;
        int mods=((modifiers&512)!=0?1:0)|((modifiers&128)!=0?2:0)|((modifiers&256)!=0?4:0)|((modifiers&64)!=0?8:0);
        if(type.equals("POINTER")){
            int event=m.get("event").getAsInt(),button=m.get("button").getAsInt(),mask=button==0?1:button==1?2:4;
            if(event==501)buttons|=mask;else if(event==502)buttons&=~mask;
            post("Input.dispatchMouseEvent",params("type",event==501?"mousePressed":event==502?"mouseReleased":"mouseMoved","x",m.get("x"),"y",m.get("y"),"button",event==503?"none":button==0?"left":button==1?"right":"middle","buttons",buttons,"clickCount",event==503?0:1,"modifiers",mods));
        }else if(type.equals("WHEEL"))post("Input.dispatchMouseEvent",params("type","mouseWheel","x",m.get("x"),"y",m.get("y"),"deltaX",0,"deltaY",m.get("amount").getAsDouble()*100,"modifiers",mods));
        else{
            int event=m.get("event").getAsInt();if(event==400){if((mods&3)==0)post("Input.insertText",params("text",new String(Character.toChars(m.get("character").getAsInt()))));return;}
            int code=m.get("code").getAsInt();if(code==10)code=13;else if(code==127)code=46;
            String key=switch(code){case 13->"Enter";case 8->"Backspace";case 9->"Tab";case 27->"Escape";case 33->"PageUp";case 34->"PageDown";case 35->"End";case 36->"Home";case 37->"ArrowLeft";case 38->"ArrowUp";case 39->"ArrowRight";case 40->"ArrowDown";case 46->"Delete";default->Character.toString((char)code);};
            JsonObject args=params("type",event==401?"keyDown":"keyUp","key",key,"windowsVirtualKeyCode",code,"modifiers",mods);if(event==401&&code==13){args.addProperty("text","\r");args.addProperty("unmodifiedText","\r");}post("Input.dispatchKeyEvent",args);
        }
    }
    private static JsonObject params(Object...values){JsonObject o=new JsonObject();for(int i=0;i<values.length;i+=2)o.add((String)values[i],ScreenSpec.JSON.toJsonTree(values[i+1]));return o;}
    public boolean audio(JsonObject m){audioMixer.enabled(m.get("enabled").getAsBoolean());return true;}
    public void close(){
        if(!alive)return;alive=false;timer.shutdownNow();audioMixer.close();if(pipe!=null){pipe.close();pipe=null;}
        pending.values().forEach(f->f.completeExceptionally(new IOException("Browser closed.")));pending.clear();
    }
}
