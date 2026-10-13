package org.hurtorius.mirror.engine;

import com.google.gson.JsonObject;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import org.hurtorius.mirror.core.*;
import java.io.IOException;
import java.util.*;

/** Explicit manual account setup. No debugger, pixel capture or audio capture exists in this mode. */
final class ChromeSignIn implements EngineMain.Source {
    private Process process;
    private int pid;
    ChromeSignIn(EngineMain engine,JsonObject message)throws Exception{
        ScreenSpec spec=ScreenSpec.read(message.get("spec").toString());
        if(!spec.siteAllowed(spec.url))throw new IOException("That website is blocked by this screen's rules.");
        String id=System.getenv("MIRROR_BROWSER_ID");if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("The browser profile is unavailable.");
        List<String> args=new ArrayList<>(List.of(ChromeBrowser.executable(engine.runtime()).toString(),"--no-first-run","--no-default-browser-check"));args.addAll(ChromeBrowser.PROFILE_FLAGS);
        args.addAll(List.of("--user-data-dir="+ChromeBrowser.profile(engine.runtime(),id),"--profile-directory=Default","--new-window",spec.url));
        ProcessBuilder builder=new ProcessBuilder(args);
        builder.environment().remove("MIRROR_ENGINE_TOKEN");builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        process=builder.start();pid=(int)process.pid();
        engine.event("BROWSER_INFO","text","Chrome account setup opened privately. Return to Mirror after signing in.");
    }
    public void command(JsonObject message){}
    public boolean audio(JsonObject message){return true;}
    public void close(){
        if(process==null)return;
        if(process.isAlive()){
            if(com.sun.jna.Platform.isWindows())User32.INSTANCE.EnumWindows((window,data)->{IntByReference owner=new IntByReference();User32.INSTANCE.GetWindowThreadProcessId(window,owner);if(owner.getValue()==pid)User32.INSTANCE.PostMessage(window,0x0010,new WinDef.WPARAM(0),new WinDef.LPARAM(0));return true;},null);
            else process.destroy();
            try{if(!process.waitFor(2500,java.util.concurrent.TimeUnit.MILLISECONDS))process.destroyForcibly();}catch(InterruptedException e){process.destroy();Thread.currentThread().interrupt();}
        }
        process=null;
    }
}
