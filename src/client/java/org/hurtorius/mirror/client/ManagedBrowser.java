package org.hurtorius.mirror.client;

import com.google.gson.*;
import org.hurtorius.mirror.core.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** The browser a screen runs: the player's installed Google Chrome when there is one (protected video works, nothing to download),
 *  otherwise this pinned installation that Mirror owns. Either way it runs on Mirror's own profile; personal browser data is never used. */
public final class ManagedBrowser {
    private ManagedBrowser(){}
    /** The browser program engines start. */
    public static Path program(Path root)throws IOException{Path installed=InstalledBrowser.find();return installed!=null?installed:executable(root);}
    /** Readies the browser engines start: nothing to do when Chrome is installed. */
    public static void prepareProgram(Path root,Consumer<String> status)throws Exception{if(InstalledBrowser.find()==null)prepare(root,status);}
    private static JsonObject manifest()throws IOException{
        try(var in=ManagedBrowser.class.getResourceAsStream("/mirror/browser-lock.json")){
            if(in==null)throw new IOException("Mirror's browser manifest is missing.");return JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    public static Path executable(Path root)throws IOException{
        var m=manifest();String platform=RuntimePlatform.current();var entry=m.getAsJsonObject("packages").getAsJsonObject(platform);
        if(entry==null)throw new IOException("This browser supports Windows x64, Linux x64/ARM64 and macOS Intel/Apple Silicon.");
        return BrowserArchive.safe(root.toAbsolutePath().resolve("browser-"+platform),entry.get("executable").getAsString());
    }
    public static synchronized void prepare(Path root,Consumer<String> status)throws Exception{
        root=root.toAbsolutePath().normalize();AtomicFile.rejectLinks(root);var m=manifest();String platform=RuntimePlatform.current();var entry=m.getAsJsonObject("packages").getAsJsonObject(platform);
        if(entry==null)throw new IOException("This browser supports Windows x64, Linux x64/ARM64 and macOS Intel/Apple Silicon.");
        String version=m.get("version").getAsString();if(!version.matches("[0-9.]{1,40}"))throw new IOException("Invalid browser version.");
        Path folder=root.resolve("browser-"+platform),ready=folder.resolve(".complete"),executable=executable(root);
        String hash=entry.get("sha256").getAsString();long size=entry.get("bytes").getAsLong();
        if(!hash.matches("[0-9a-f]{64}")||size<1||size>512L*1024*1024)throw new IOException("Invalid browser package.");
        AtomicFile.rejectLinks(folder);
        // A game closed while unpacking leaves its staging folder behind; nothing else uses them once this lock is held.
        try(var old=Files.list(root)){for(Path p:old.toList())if(p.getFileName().toString().startsWith(".browser-prepare-")&&Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))deleteOwned(root,p);}
        if(Files.isRegularFile(ready)&&Files.readString(ready).equals(hash)&&Files.isExecutable(executable)){LinuxBrowserPolicy.prepare(root,executable);return;}
        if(Files.getFileStore(root).getUsableSpace()<size*5+128L*1024*1024)throw new IOException("Mirror needs more free disk space to prepare its browser.");
        Path archive=root.resolve(".browser-download.zip"),staging=null;
        try{
            URI uri=URI.create(entry.get("url").getAsString());if(!uri.getScheme().equals("https")||!uri.getHost().equals("storage.googleapis.com")||!uri.getPath().startsWith("/chrome-for-testing-public/"))throw new IOException("Invalid browser download location.");
            AtomicFile.rejectLinks(archive);status.accept("Preparing Mirror's browser · first use downloads "+size/1048576+" MB");
            RuntimeInstaller.download(uri,archive,size,hash,status,"Preparing Mirror's browser","Mirror needs internet once to prepare its browser (about "+size/1048576+" MB). Connect, then choose Reload.");
            staging=Files.createTempDirectory(root,".browser-prepare-");status.accept("Preparing Mirror's browser · unpacking");BrowserArchive.extract(archive,staging);
            Path binary=BrowserArchive.safe(staging,entry.get("executable").getAsString());if(!Files.isRegularFile(binary))throw new IOException("The browser package is incomplete.");
            if(!RuntimePlatform.current().startsWith("windows")&&!binary.toFile().setExecutable(true,false))throw new IOException("The browser cannot run from this folder.");
            Files.writeString(staging.resolve(".complete"),hash);
            if(Files.exists(folder))deleteOwned(root,folder);
            Files.move(staging,folder);LinuxBrowserPolicy.prepare(root,executable);status.accept("Mirror's browser is ready");
        }finally{if(staging!=null)deleteOwned(root,staging);Files.deleteIfExists(archive);}
    }
    private static void deleteOwned(Path root,Path path)throws IOException{
        path=path.toAbsolutePath().normalize();if(!path.getParent().equals(root)||!path.getFileName().toString().startsWith("browser-")&&!path.getFileName().toString().startsWith(".browser-prepare-"))throw new IOException("Invalid browser cleanup path.");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;AtomicFile.rejectLinks(path);
        try(var entries=Files.walk(path)){for(Path entry:entries.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(entry);}
    }
}
