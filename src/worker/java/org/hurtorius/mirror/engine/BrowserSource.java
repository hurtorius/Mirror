package org.hurtorius.mirror.engine;

import com.google.gson.*;
import org.hurtorius.mirror.core.ScreenSpec;
import org.cef.*;
import org.cef.browser.*;
import org.cef.handler.*;
import org.cef.callback.*;
import org.cef.network.*;
import org.cef.misc.*;
import me.friwi.jcefmaven.*;
import java.awt.event.*;
import java.util.*;

public final class BrowserSource implements EngineMain.Source {
    private static CefApp app;
    private final CefClient client;
    private final MirrorBrowser browser;
    private final ScreenSpec spec;
    private final EngineMain engine;
    public BrowserSource(EngineMain engine,JsonObject m)throws Exception{
        this.engine=engine;spec=ScreenSpec.read(m.get("spec").toString());
        if(app==null){CefAppBuilder builder=new CefAppBuilder();builder.setInstallDir(engine.runtime().resolve("chromium-152.0.6").toFile());builder.getCefSettings().windowless_rendering_enabled=true;
            String profile=System.getenv("MIRROR_BROWSER_ID");if(profile==null||!profile.matches("[a-zA-Z0-9_-]{1,64}"))profile="preview";
            builder.getCefSettings().root_cache_path=engine.runtime().resolve("browser-profiles/"+profile).toString();builder.getCefSettings().cache_path=engine.runtime().resolve("browser-profiles/"+profile+"/default").toString();builder.getCefSettings().persist_session_cookies=false;builder.getCefSettings().log_severity=CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
            builder.addJcefArgs("--disable-gpu","--disable-background-networking","--disable-sync","--disable-features=MediaRouter","--disable-speech-api","--deny-permission-prompts");
            builder.setProgressHandler((state,percent)->engine.event("PREPARING","text","Preparing Mirror's browser","percent",percent));app=builder.build();}
        client=app.createClient();
        client.addRequestHandler(new CefRequestHandlerAdapter(){
            public boolean onBeforeBrowse(CefBrowser b,CefFrame f,CefRequest r,boolean g,boolean redirect){return denied(r.getURL());}
            public boolean onOpenURLFromTab(CefBrowser b,CefFrame f,String url,boolean g){if(!denied(url))b.loadURL(url);return true;}
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b,CefFrame f,CefRequest r,boolean nav,boolean download,String initiator,BoolRef disable){return new CefResourceRequestHandlerAdapter(){public boolean onBeforeResourceLoad(CefBrowser bb,CefFrame ff,CefRequest rr){String url=rr.getURL();return !(url.startsWith("blob:")||url.startsWith("data:"))&&denied(url);}public void onProtocolExecution(CefBrowser b,CefFrame f,CefRequest r,BoolRef allow){allow.set(false);}};}
            public boolean onCertificateError(CefBrowser b,CefLoadHandler.ErrorCode e,String url,CefCallback c){return false;}
            public void onRenderProcessTerminated(CefBrowser b,TerminationStatus status,int code,String message){engine.error("The browser stopped. Reopen the browser source to recover.");}
        });
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter(){public boolean onBeforePopup(CefBrowser b,CefFrame f,String url,String frame){if(!denied(url))b.loadURL(url);return true;}});
        client.addDisplayHandler(new CefDisplayHandlerAdapter(){public void onAddressChange(CefBrowser b,CefFrame f,String url){if(f.isMain())engine.event("ADDRESS","url",url);}public boolean onConsoleMessage(CefBrowser b,CefSettings.LogSeverity l,String msg,String src,int line){return true;}});
        client.addLoadHandler(new CefLoadHandlerAdapter(){public void onLoadError(CefBrowser b,CefFrame f,ErrorCode code,String text,String url){if(f.isMain()&&code!=ErrorCode.ERR_ABORTED)engine.error("This website could not load. Check the connection and the allowed-site list.");}});
        client.addContextMenuHandler(new CefContextMenuHandlerAdapter(){public void onBeforeContextMenu(CefBrowser b,CefFrame f,CefContextMenuParams p,CefMenuModel menu){menu.clear();}});
        client.addDialogHandler((b,mode,title,path,filters,extensions,descriptions,callback)->{callback.Cancel();engine.error("Website access to local files is blocked. Import media from Mirror's library instead.");return true;});
        client.addDownloadHandler(new CefDownloadHandler(){public boolean onBeforeDownload(CefBrowser b,CefDownloadItem i,String n,CefBeforeDownloadCallback cb){engine.error("Website downloads are blocked in Mirror.");return true;}public void onDownloadUpdated(CefBrowser b,CefDownloadItem i,CefDownloadItemCallback cb){cb.cancel();}});
        var viewport=org.hurtorius.mirror.core.BrowserViewport.of(spec);browser=new MirrorBrowser(client,spec.url,viewport.width(),viewport.height(),engine::image);browser.createImmediately();browser.setFocus(true);
    }
    private boolean denied(String url){boolean deny=!spec.siteAllowed(url);if(deny&&!url.equals("about:blank"))engine.event("BLOCKED","text","A website request was blocked by this Initiator's rules.");return deny;}
    public void command(JsonObject m){String type=m.get("type").getAsString();int mods=m.has("mods")?m.get("mods").getAsInt():0;
        switch(type){
            case "RESIZE"->{var viewport=org.hurtorius.mirror.core.BrowserViewport.of(ScreenSpec.read(m.get("spec").toString()));browser.resize(viewport.width(),viewport.height());}
            case "NAVIGATE"->{String url=m.get("url").getAsString();if(!denied(url))browser.loadURL(url);}
            case "TEXT"->{for(char character:m.get("text").getAsString().toCharArray())browser.key(KeyEvent.KEY_TYPED,0,character,0);}
            case "BACK"->browser.goBack();case "FORWARD"->browser.goForward();case "RELOAD"->browser.reload();
            case "POINTER"->browser.pointer(m.get("event").getAsInt(),m.get("x").getAsInt(),m.get("y").getAsInt(),m.get("button").getAsInt(),mods);
            case "WHEEL"->browser.wheel(m.get("x").getAsInt(),m.get("y").getAsInt(),m.get("amount").getAsInt(),mods);
            case "KEY"->browser.key(m.get("event").getAsInt(),m.get("code").getAsInt(),m.has("character")?(char)m.get("character").getAsInt():KeyEvent.CHAR_UNDEFINED,mods);
        }
    }
    public void close(){browser.close(true);client.dispose();}
    public static void shutdown(){if(app!=null){app.dispose();app=null;}}
}
