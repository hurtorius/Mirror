package org.hurtorius.mirror.engine;

import com.google.gson.JsonObject;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import org.hurtorius.mirror.core.*;
import java.io.IOException;
import java.nio.file.*;

/** Explicit manual account setup. No debugger, pixel capture or audio capture exists in this mode. */
final class ChromeSignIn implements EngineMain.Source {
    private WinNT.HANDLE process;
    private int pid;
    ChromeSignIn(EngineMain engine,JsonObject message)throws Exception{
        ScreenSpec spec=ScreenSpec.read(message.get("spec").toString());
        if(!spec.siteAllowed(spec.url))throw new IOException("That website is blocked by this screen's rules.");
        String id=System.getenv("MIRROR_BROWSER_ID");if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("The browser profile is unavailable.");
        Path profile=engine.runtime().resolve("chrome-profiles").resolve(id).toAbsolutePath();Files.createDirectories(profile);AtomicFile.rejectLinks(profile);
        var startup=new WinBase.STARTUPINFO();startup.dwFlags=1;startup.wShowWindow=new WinDef.WORD(1);
        var info=new WinBase.PROCESS_INFORMATION();
        String command=quote(ChromeBrowser.executable().toString())+" --no-first-run --no-default-browser-check --user-data-dir="+quote(profile.toString())+" --profile-directory=Default --new-window "+quote(spec.url);
        if(!Kernel32.INSTANCE.CreateProcess(null,command,null,null,false,new WinDef.DWORD(0),null,null,startup,info))throw new IOException("Chrome's sign-in window could not open.");
        process=info.hProcess;pid=info.dwProcessId.intValue();Kernel32.INSTANCE.CloseHandle(info.hThread);
        engine.event("BROWSER_INFO","text","Chrome account setup opened privately. Return to Mirror after signing in.");
    }
    private static String quote(String value){return "\""+value.replace("\"","%22")+"\"";}
    public void command(JsonObject message){}
    public boolean audio(JsonObject message){return true;}
    public void close(){
        if(process==null)return;
        if(Kernel32.INSTANCE.WaitForSingleObject(process,0)==258){
            User32.INSTANCE.EnumWindows((window,data)->{IntByReference owner=new IntByReference();User32.INSTANCE.GetWindowThreadProcessId(window,owner);if(owner.getValue()==pid)User32.INSTANCE.PostMessage(window,0x0010,new WinDef.WPARAM(0),new WinDef.LPARAM(0));return true;},null);
            if(Kernel32.INSTANCE.WaitForSingleObject(process,2500)==258)Kernel32.INSTANCE.TerminateProcess(process,0);
        }
        Kernel32.INSTANCE.CloseHandle(process);process=null;
    }
}
