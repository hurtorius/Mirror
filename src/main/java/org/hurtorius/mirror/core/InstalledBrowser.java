package org.hurtorius.mirror.core;

import java.nio.file.*;
import java.util.*;

/** The player's own Google Chrome program, when one is installed. Mirror runs it with its own separate profile, never the player's,
 *  because only a real Chrome carries Widevine (protected TV and film sites) and needs no first-use browser download. */
public final class InstalledBrowser {
    private InstalledBrowser(){}
    static List<Path> candidates(String platform,Map<String,String> env){
        List<Path> paths=new ArrayList<>();
        if(platform.startsWith("windows")){
            for(String root:new String[]{env.get("ProgramFiles"),env.get("ProgramFiles(x86)"),env.get("ProgramW6432"),env.get("LOCALAPPDATA")})
                if(root!=null&&!root.isBlank())paths.add(Path.of(root,"Google","Chrome","Application","chrome.exe"));
        }else if(platform.startsWith("macos")){
            paths.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            String home=env.get("HOME");if(home!=null&&!home.isBlank())paths.add(Path.of(home,"Applications","Google Chrome.app","Contents","MacOS","Google Chrome"));
        }else if(platform.startsWith("linux")){
            paths.add(Path.of("/opt/google/chrome/chrome"));
        }
        return paths;
    }
    /** The installed Chrome to use, or null. */
    public static Path find(){
        if("1".equals(System.getenv("MIRROR_MANAGED_BROWSER")))return null;
        for(Path path:candidates(RuntimePlatform.current(),System.getenv()))
            try{Path real=path.toRealPath();if(Files.isRegularFile(real)&&Files.isExecutable(real))return real;}catch(Exception missing){}
        return null;
    }
    /** Whether a path names exactly the installed Chrome found on this computer. */
    public static boolean is(Path path){
        Path found=find();
        try{return found!=null&&path!=null&&path.toRealPath().equals(found);}catch(Exception e){return false;}
    }
}
