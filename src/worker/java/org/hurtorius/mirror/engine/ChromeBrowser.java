package org.hurtorius.mirror.engine;

import com.google.gson.*;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.platform.win32.*;
import org.hurtorius.mirror.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed Google Chrome, headless, with private inherited pipes (no debugging port).
 * The dedicated persistent Mirror profile never opens the player's normal Chrome data. */
final class ChromeBrowser implements EngineMain.Source {
    private final EngineMain engine;
    private final ScreenSpec spec;
    private final BrowserAudioMixer audioMixer;
    private final String token=UUID.randomUUID().toString(),binding="mirrorAudio"+UUID.randomUUID().toString().replace("-","");
    private final AtomicInteger sequence=new AtomicInteger();
    private final ConcurrentMap<Integer,CompletableFuture<JsonObject>> pending=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Mirror Chrome frames");t.setDaemon(true);return t;});
    private WinNT.HANDLE input,output,process;
    private volatile boolean alive=true,capturing=true;
    private String session="";
    private BrowserViewport viewport;
    private volatile int frameMillis;
    private int buttons;
    private boolean audioRestricted,failedPage;
    private byte[] lastPicture;
    private volatile long capturedFrames,changedFrames,captureBytes;
    ChromeBrowser(EngineMain engine,JsonObject m)throws Exception{
        this.engine=engine;spec=ScreenSpec.read(m.get("spec").toString());viewport=BrowserViewport.of(spec);audioMixer=new BrowserAudioMixer(engine::pcm);
        try{
            launch();
            Thread reader=new Thread(this::read,"Mirror Chrome pipe");reader.setDaemon(true);reader.start();
            call("Browser.setDownloadBehavior",params("behavior","deny"),false);
            String target=call("Target.createTarget",params("url","about:blank"),false).get("targetId").getAsString();
            for(var entry:call("Target.getTargets",params(),false).getAsJsonArray("targetInfos")){var other=entry.getAsJsonObject();if(other.get("type").getAsString().equals("page")&&!other.get("targetId").getAsString().equals(target))call("Target.closeTarget",params("targetId",other.get("targetId")),false);}
            session=call("Target.attachToTarget",params("targetId",target,"flatten",true),false).get("sessionId").getAsString();
            call("Page.enable",params(),true);call("Runtime.enable",params(),true);call("Network.enable",params(),true);
            call("Runtime.addBinding",params("name",binding),true);
            call("Page.setInterceptFileChooserDialog",params("enabled",true),true);
            call("Emulation.setFocusEmulationEnabled",params("enabled",true),true);
            call("Emulation.setDeviceMetricsOverride",metrics(),true);
            // Interception also prevents redirects and subframes bypassing the screen's site policy.
            call("Fetch.enable",params("patterns",List.of(Map.of("urlPattern","*"))),true);
            String script;
            try(var in=ChromeBrowser.class.getResourceAsStream("/mirror/browser-audio.js")){
                if(in==null)throw new IOException("Browser audio is missing.");
                script=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("__MIRROR_AUDIO_TOKEN__",token)
                    .replace("chrome.webview.postMessage.bind(chrome.webview)","window['"+binding+"']")
                    .replace("chrome.webview.postMessage(message)","window['"+binding+"'](message)");
            }
            script+=";window.open=(url)=>{if(url)location.href=url;return null};document.addEventListener('click',e=>{const a=e.target.closest?.('a[target]');if(a){e.preventDefault();location.href=a.href}},true);";
            call("Page.addScriptToEvaluateOnNewDocument",params("source",script),true);
            call("Page.startScreencast",params("format","jpeg","quality",78,"maxWidth",1920,"maxHeight",1920,"everyNthFrame",1),true);
            navigate(spec.url);
            engine.event("BROWSER_INFO","text","Google Chrome · "+viewport.width()+" × "+viewport.height());
        }catch(Exception e){close();throw e;}
    }
    private static String env(String key){return System.getenv().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse("");}
    static Path executable()throws IOException{
        for(String base:List.of(env("PROGRAMFILES"),env("PROGRAMFILES(X86)"),env("LOCALAPPDATA"))){
            if(base.isBlank())continue;Path p=Path.of(base,"Google","Chrome","Application","chrome.exe");if(Files.isRegularFile(p))return p;
        }
        throw new IOException("Google Chrome is required for browsing. Install Chrome, then open Browse again.");
    }
    private static void check(boolean ok)throws IOException{if(!ok)throw new IOException("Chrome pipe failed (Windows "+Kernel32.INSTANCE.GetLastError()+").");}
    private void launch()throws Exception{
        String id=System.getenv("MIRROR_BROWSER_ID");if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))id="local";
        Path profile=engine.runtime().resolve("chrome-profiles").resolve(id).toAbsolutePath();Files.createDirectories(profile);AtomicFile.rejectLinks(profile);
        var security=new WinBase.SECURITY_ATTRIBUTES();security.bInheritHandle=true;
        var readChild=new WinNT.HANDLEByReference();var writeParent=new WinNT.HANDLEByReference();
        var readParent=new WinNT.HANDLEByReference();var writeChild=new WinNT.HANDLEByReference();
        try{
            check(Kernel32.INSTANCE.CreatePipe(readChild,writeParent,security,65536));input=writeParent.getValue();
            check(Kernel32.INSTANCE.CreatePipe(readParent,writeChild,security,65536));output=readParent.getValue();
            check(Kernel32.INSTANCE.SetHandleInformation(input,1,0));check(Kernel32.INSTANCE.SetHandleInformation(output,1,0));
            // Windows CRT's inherited descriptor table: stdin/out/err closed, fd 3 reads, fd 4 writes.
            int count=5;Memory descriptors=new Memory(4+count+(long)count*Native.POINTER_SIZE);descriptors.clear();descriptors.setInt(0,count);
            for(int i=0;i<count;i++)descriptors.setPointer(4+count+(long)i*Native.POINTER_SIZE,Pointer.createConstant(-1));
            descriptors.setByte(7,(byte)9);descriptors.setByte(8,(byte)9);
            descriptors.setPointer(4+count+3L*Native.POINTER_SIZE,readChild.getValue().getPointer());descriptors.setPointer(4+count+4L*Native.POINTER_SIZE,writeChild.getValue().getPointer());
            var startup=new WinBase.STARTUPINFO();startup.cbReserved2=new WinDef.WORD((int)descriptors.size());startup.lpReserved2=new com.sun.jna.ptr.ByteByReference();startup.lpReserved2.setPointer(descriptors);startup.dwFlags=1;startup.wShowWindow=new WinDef.WORD(0);
            var info=new WinBase.PROCESS_INFORMATION();
            String command=quote(executable().toString())+" --headless=new --remote-debugging-pipe --no-first-run --no-default-browser-check --disable-background-networking --disable-sync --disable-extensions --disable-background-timer-throttling --disable-renderer-backgrounding --mute-audio --autoplay-policy=no-user-gesture-required --deny-permission-prompts --user-data-dir="+quote(profile.toString())+" --profile-directory=Default about:blank";
            check(Kernel32.INSTANCE.CreateProcess(null,command,null,null,true,new WinDef.DWORD(0x08000000),null,null,startup,info));process=info.hProcess;Kernel32.INSTANCE.CloseHandle(info.hThread);
        }finally{closeHandle(readChild.getValue());closeHandle(writeChild.getValue());}
    }
    private static String quote(String s){return "\""+s.replace("\"","\\\"")+"\"";}
    private static JsonObject captureOptions(){return params("format","jpeg","quality",78,"maxWidth",1920,"maxHeight",1920,"everyNthFrame",1);}
    private JsonObject metrics(){return params("width",viewport.width(),"height",viewport.height(),"deviceScaleFactor",1,"mobile",false);}
    private synchronized int send(String method,JsonObject args,boolean page,CompletableFuture<JsonObject> reply)throws IOException{
        if(!alive||input==null)throw new IOException("Chrome is closed.");int id=sequence.incrementAndGet();JsonObject m=params("id",id,"method",method,"params",args);if(page)m.addProperty("sessionId",session);
        if(reply!=null)pending.put(id,reply);byte[] bytes=(m.toString()+"\0").getBytes(StandardCharsets.UTF_8);IntByReference written=new IntByReference();
        if(!Kernel32.INSTANCE.WriteFile(input,bytes,bytes.length,written,null)||written.getValue()!=bytes.length){pending.remove(id);throw new IOException("Chrome stopped responding.");}return id;
    }
    private JsonObject call(String method,JsonObject args,boolean page)throws Exception{return call(method,args,page,15000);}
    private JsonObject call(String method,JsonObject args,boolean page,long timeout)throws Exception{
        CompletableFuture<JsonObject> reply=new CompletableFuture<>();int id=send(method,args,page,reply);
        try{return reply.get(timeout,TimeUnit.MILLISECONDS);}finally{pending.remove(id);}
    }
    private void post(String method,JsonObject args){try{send(method,args,true,null);}catch(IOException e){if(alive)engine.event("BROWSER_ERROR","text",e.getMessage());}}
    private void read(){
        byte[] bytes=new byte[65536];ByteArrayOutputStream message=new ByteArrayOutputStream();IntByReference received=new IntByReference();
        try{while(alive&&Kernel32.INSTANCE.ReadFile(output,bytes,bytes.length,received,null)){
            int start=0,length=received.getValue();
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
        if(!m.has("method"))return;JsonObject p=m.has("params")?m.getAsJsonObject("params"):new JsonObject();
        switch(m.get("method").getAsString()){
            case "Page.screencastFrame"->{
                byte[] jpeg=Base64.getDecoder().decode(p.get("data").getAsString());capturedFrames++;captureBytes+=jpeg.length;
                if(capturing&&!failedPage&&!Arrays.equals(lastPicture,jpeg)){lastPicture=jpeg;changedFrames++;engine.encodedImage(jpeg);}
                int frame=p.get("sessionId").getAsInt();if(frameMillis==0)post("Page.screencastFrameAck",params("sessionId",frame));else timer.schedule(()->post("Page.screencastFrameAck",params("sessionId",frame)),frameMillis,TimeUnit.MILLISECONDS);
            }
            case "Page.frameNavigated"->{JsonObject f=p.getAsJsonObject("frame");if(!f.has("parentId")){String url=f.get("url").getAsString();failedPage=url.startsWith("chrome-error:");if(failedPage){lastPicture=null;engine.image(new java.awt.image.BufferedImage(viewport.width(),viewport.height(),java.awt.image.BufferedImage.TYPE_INT_RGB));}else if(spec.siteAllowed(url))engine.event("ADDRESS","url",url);}}
            case "Page.navigatedWithinDocument"->{String url=p.get("url").getAsString();if(spec.siteAllowed(url))engine.event("ADDRESS","url",url);}
            case "Fetch.requestPaused"->{String url=p.getAsJsonObject("request").get("url").getAsString();boolean document=p.get("resourceType").getAsString().equals("Document");
                boolean allow=spec.siteAllowed(url)||!document&&(url.startsWith("data:")||url.startsWith("blob:"));
                post(allow?"Fetch.continueRequest":"Fetch.failRequest",allow?params("requestId",p.get("requestId")):params("requestId",p.get("requestId"),"errorReason","BlockedByClient"));
                if(!allow&&document)engine.event("BROWSER_ERROR","text","Navigation blocked by this screen's website rules.");
            }
            case "Network.loadingFailed"->{if(p.has("type")&&p.get("type").getAsString().equals("Document")&&(!p.has("canceled")||!p.get("canceled").getAsBoolean()))engine.event("BROWSER_ERROR","text","Chrome could not load the page: "+p.get("errorText").getAsString());}
            case "Runtime.bindingCalled"->{if(!binding.equals(p.get("name").getAsString()))return;String value=p.get("payload").getAsString(),prefix=token+"|";if(value.length()>12000||!value.startsWith(prefix))return;int divider=value.indexOf('|',prefix.length());if(divider<0)return;String frame=value.substring(prefix.length(),divider),body=value.substring(divider+1);
                if(body.equals("!restricted")){if(!audioRestricted){audioRestricted=true;engine.event("BROWSER_AUDIO_LIMITED","text","This website restricts embedded audio. Try the video's own page.");}}
                else if(!body.startsWith("!"))try{audioMixer.accept(frame,Base64.getDecoder().decode(body));}catch(IllegalArgumentException ignored){}
            }
        }
    }
    private void navigate(String url){if(spec.siteAllowed(url))post("Page.navigate",params("url",url));else engine.event("BROWSER_ERROR","text","That address is blocked by this screen's website rules.");}
    public void command(JsonObject m)throws Exception{
        switch(m.get("type").getAsString()){
            case "NAVIGATE"->navigate(m.get("url").getAsString());
            case "BACK","FORWARD"->{JsonObject h=call("Page.getNavigationHistory",params(),true);int index=h.get("currentIndex").getAsInt()+(m.get("type").getAsString().equals("BACK")?-1:1);JsonArray list=h.getAsJsonArray("entries");if(index>=0&&index<list.size())post("Page.navigateToHistoryEntry",params("entryId",list.get(index).getAsJsonObject().get("id")));}
            case "RELOAD"->post("Page.reload",params());
            case "AUDIO"->audioMixer.enabled(m.get("enabled").getAsBoolean());
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
            case "DIAGNOSTICS"->{JsonObject result=call("Runtime.evaluate",params("expression","JSON.stringify({browser:'Google Chrome',visibility:document.visibilityState,width:innerWidth,height:innerHeight,scale:devicePixelRatio,videos:Array.from(document.querySelectorAll('video')).map(v=>({time:v.currentTime,paused:v.paused,muted:v.muted,volume:v.volume,ready:v.readyState,error:v.error?.code||0,total:v.getVideoPlaybackQuality().totalVideoFrames,dropped:v.getVideoPlaybackQuality().droppedVideoFrames}))})","returnByValue",true),true);result.addProperty("capturedFrames",capturedFrames);result.addProperty("changedFrames",changedFrames);result.addProperty("captureBytes",captureBytes);result.addProperty("writtenFrames",engine.framesWritten());engine.event("BROWSER_DIAGNOSTICS","result",result.toString());}
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
    public Map<String,Object> audioStatus(){return audioMixer.status();}
    private static void closeHandle(WinNT.HANDLE h){if(h!=null)Kernel32.INSTANCE.CloseHandle(h);}
    public void close(){
        if(!alive)return;alive=false;timer.shutdownNow();audioMixer.close();closeHandle(input);input=null;
        if(process!=null){if(Kernel32.INSTANCE.WaitForSingleObject(process,1500)==258)Kernel32.INSTANCE.TerminateProcess(process,0);closeHandle(process);process=null;}
        closeHandle(output);output=null;pending.values().forEach(f->f.completeExceptionally(new IOException("Chrome closed.")));pending.clear();
    }
}
