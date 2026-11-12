package org.hurtorius.mirror.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Optional, exact-executable AppArmor setup. Mirror never changes OS policy or disables its sandbox. */
public final class LinuxBrowserPolicy {
    private LinuxBrowserPolicy(){}
    public static void prepare(Path root,Path executable)throws IOException{
        if(!RuntimePlatform.current().startsWith("linux-"))return;
        String path=executable.toAbsolutePath().normalize().toString(),name=name(path);
        String profile="abi <abi/4.0>,\ninclude <tunables/global>\n\nprofile "+name+" "+apparmorPath(path)+" flags=(unconfined) {\n  userns,\n}\n";
        AtomicFile.write(root.resolve("mirror-browser.apparmor"),profile.getBytes(StandardCharsets.UTF_8));
        String target="/etc/apparmor.d/"+name;
        String command="sudo install -m 644 -- "+shell(root.toAbsolutePath().resolve("mirror-browser.apparmor").toString())+" "+shell(target)+" && sudo apparmor_parser -r "+shell(target);
        AtomicFile.write(root.resolve("browser-setup-command.txt"),command.getBytes(StandardCharsets.UTF_8));
    }
    private static String name(String path)throws IOException{
        try{return "mirror-browser-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    public static String apparmorPath(String value)throws IOException{
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toCharArray()){
            if(c<32||c==127)throw new IOException("This browser folder needs a manual AppArmor profile.");
            if("\\\"*?[]{}^".indexOf(c)>=0)out.append('\\');out.append(c);
        }
        return out.append('"').toString();
    }
    public static String shell(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    private static Path log(Path runtime){
        String id=System.getenv("MIRROR_BROWSER_ID");return id!=null&&id.matches("[A-Za-z0-9_-]{1,100}")?runtime.resolve("engine-browser-"+id+".log"):null;
    }
    public static long logOffset(Path runtime){try{Path path=log(runtime);return path!=null&&Files.isRegularFile(path)?Files.size(path):0;}catch(IOException e){return 0;}}
    public static String failureHint(Path runtime,long offset){
        if(!RuntimePlatform.current().startsWith("linux-"))return "";
        try{
            Path path=log(runtime);if(path==null||!Files.isRegularFile(path))return "";
            try(var file=new java.io.RandomAccessFile(path.toFile(),"r")){
                file.seek(Math.min(file.length(),Math.max(offset,file.length()-16384)));byte[] bytes=new byte[(int)(file.length()-file.getFilePointer())];file.readFully(bytes);
                if(new String(bytes,StandardCharsets.UTF_8).contains("No usable sandbox"))return sandboxHint(Path.of("/.flatpak-info"),Path.of("/proc/sys/kernel/apparmor_restrict_unprivileged_userns"));
            }
        }catch(IOException ignored){}
        return "";
    }
    /** Only AppArmor's user-namespace restriction can be fixed by the per-browser rule; Flatpak and disabled namespaces cannot. */
    public static String sandboxHint(Path flatpakInfo,Path apparmorRestriction){
        if(Files.exists(flatpakInfo))return "Browse cannot start its sandboxed browser inside a Flatpak launcher. Use a non-Flatpak launcher build for Browse; media and mirrors still work.";
        try{if(Files.readString(apparmorRestriction).trim().equals("1"))return "Linux browser setup is needed. Open Reports, choose Browser setup, and apply the one-time per-browser permission. Mirror keeps browser sandboxing enabled.";}catch(IOException missing){}
        return "This system disables the user namespaces the browser sandbox needs. Mirror will not disable the sandbox; an administrator must enable unprivileged user namespaces.";
    }
}
