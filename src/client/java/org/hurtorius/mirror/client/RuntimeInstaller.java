package org.hurtorius.mirror.client;

import com.google.gson.*;
import org.hurtorius.mirror.core.*;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class RuntimeInstaller {
    private RuntimeInstaller(){}
    /** The last status of every engine start: preparation is over, so nothing more is shown for it. */
    public static final String READY="Mirror is ready";
    /** Files hashed this session, keyed by path, size, modification time and expected hash, so engine starts skip re-hashing unchanged files. */
    private static final Set<String> VERIFIED=ConcurrentHashMap.newKeySet();
    /** One media preparation per runtime at a time, on its own thread: an engine closed while it waits (a viewer walking away) never cancels a first-use download, and the next engine joins it. */
    private static final Map<Path,MediaJob> MEDIA=new HashMap<>();
    private static final class MediaJob {
        final CompletableFuture<List<Path>> done=new CompletableFuture<>();final Set<Consumer<String>> watchers=ConcurrentHashMap.newKeySet();volatile String last;
        void status(String text){last=text;for(Consumer<String> watcher:watchers)watcher.accept(text);}
    }
    public static List<Path> prepare(Path root,boolean browser,boolean media,Consumer<String> status)throws Exception{
        List<Path> jars=bundled(root,status);
        // Media and the browser download apart from the bundled jars, so a first-use download never blocks engines that do not need it.
        if(media)jars.addAll(media(root,status));
        if(browser)ManagedBrowser.prepare(root,status);
        status.accept(READY);return jars;
    }
    private static synchronized List<Path> bundled(Path root,Consumer<String> status)throws Exception{
        Files.createDirectories(root);AtomicFile.rejectLinks(root);List<JsonObject> wanted=new ArrayList<>();Set<String> owned=new HashSet<>();
        for(JsonElement e:resource("mirror/engine-manifest.json").getAsJsonArray()){JsonObject entry=e.getAsJsonObject();owned.add(entry.get("name").getAsString());if(!entry.has("url"))wanted.add(entry);}
        for(var platform:resource("mirror/native-lock.json").getAsJsonObject().entrySet())for(JsonElement e:platform.getValue().getAsJsonArray())owned.add(e.getAsJsonObject().get("name").getAsString());
        List<Path> jars=fetch(root,wanted,status);prune(root,owned);return jars;
    }
    /** Joins the running media preparation or starts one; each joiner sees its latest status first. Closing a waiting engine interrupts only its wait. */
    private static List<Path> media(Path root,Consumer<String> status)throws Exception{
        MediaJob job;
        synchronized(MEDIA){Path key=root.toAbsolutePath().normalize();job=MEDIA.get(key);
            if(job==null||job.done.isDone()){MediaJob started=new MediaJob();job=started;MEDIA.put(key,started);Thread worker=new Thread(()->{try{started.done.complete(mediaFiles(root,started::status));}catch(Throwable e){started.done.completeExceptionally(e);}},"Mirror media preparation");worker.setDaemon(true);worker.start();}}
        job.watchers.add(status);String last=job.last;if(last!=null)status.accept(last);
        try{return job.done.get();}catch(ExecutionException e){throw e.getCause() instanceof Exception cause?cause:e;}finally{job.watchers.remove(status);}
    }
    private static List<Path> mediaFiles(Path root,Consumer<String> status)throws Exception{
        List<JsonObject> wanted=new ArrayList<>();for(JsonElement e:resource("mirror/engine-manifest.json").getAsJsonArray())if(e.getAsJsonObject().has("url"))wanted.add(e.getAsJsonObject());
        String platform=RuntimePlatform.current();JsonObject natives=resource("mirror/native-lock.json").getAsJsonObject();
        if(!natives.has(platform))throw new IOException("Mirror's media playback supports Windows x64, Linux x64/ARM64, and macOS Intel/Apple Silicon.");
        for(JsonElement e:natives.getAsJsonArray(platform))wanted.add(e.getAsJsonObject());
        return fetch(root,wanted,status);
    }
    private static List<Path> fetch(Path root,List<JsonObject> wanted,Consumer<String> status)throws Exception{
        List<Path> jars=new ArrayList<>();Map<Path,JsonObject> missing=new LinkedHashMap<>();long download=0;
        for(JsonObject entry:wanted){Path path=AtomicFile.child(root,entry.get("name").getAsString());jars.add(path);if(!valid(path,entry.get("bytes").getAsLong(),entry.get("sha256").getAsString())){missing.put(path,entry);if(entry.has("url"))download+=entry.get("bytes").getAsLong();}}
        String offline="Mirror needs internet once to prepare media playback (about "+(download+1048575)/1048576+" MB). Connect, then reload the media.";
        if(download>0){if(Files.getFileStore(root).getUsableSpace()<download+256L*1024*1024)throw new IOException("Mirror needs more free disk space to prepare media playback.");status.accept("Preparing media · first use downloads "+(download+1048575)/1048576+" MB");}
        for(var item:missing.entrySet()){JsonObject entry=item.getValue();Path path=item.getKey();String name=entry.get("name").getAsString();long size=entry.get("bytes").getAsLong();String digest=entry.get("sha256").getAsString();
            if(entry.has("url")){URI uri=URI.create(entry.get("url").getAsString());if(!"https".equals(uri.getScheme())||!"repo.maven.apache.org".equals(uri.getHost())||!uri.getPath().startsWith("/maven2/"))throw new IOException("Invalid runtime download location.");download(uri,path,size,digest,status,"Preparing media",offline);}
            else try(InputStream in=RuntimeInstaller.class.getClassLoader().getResourceAsStream("mirror/engine/"+name)){if(in==null)throw new IOException("A bundled Mirror component is missing. Reinstall the mod.");install(in,path,size,digest,status,"Preparing Mirror");}
        }
        return jars;
    }
    /** Deletes top-level jars no current manifest names (for any platform), plus the legacy WebView profile folder. Profiles, browsers and caches are kept. */
    static void prune(Path root,Set<String> owned)throws IOException{
        try(var files=Files.list(root)){for(Path p:files.toList()){String name=p.getFileName().toString();if(name.endsWith(".jar")&&!owned.contains(name)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))try{Files.delete(p);}catch(IOException locked){/* A running engine still holds it on Windows; the next start retries. */}}}
        Path legacy=root.resolve("webview-profiles");
        if(Files.isDirectory(legacy,LinkOption.NOFOLLOW_LINKS))try(var tree=Files.walk(legacy)){for(Path p:tree.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException|UncheckedIOException locked){/* A locked or unreadable entry; retried on the next start, never blocking this one. */}
    }
    private static JsonElement resource(String name)throws IOException{try(InputStream in=RuntimeInstaller.class.getClassLoader().getResourceAsStream(name)){if(in==null)throw new IOException("Mirror's runtime manifest is missing. Reinstall the mod.");return JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}
    private static String key(Path path,String hash)throws IOException{return path.toAbsolutePath()+"|"+Files.size(path)+"|"+Files.getLastModifiedTime(path)+"|"+hash;}
    static boolean valid(Path path,long size,String hash)throws Exception{
        AtomicFile.rejectLinks(path);if(!Files.isRegularFile(path)||Files.size(path)!=size)return false;String key=key(path,hash);if(VERIFIED.contains(key))return true;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))return false;VERIFIED.add(key);return true;
    }
    /** Streams a pinned download into place. Callers check the location; redirects are refused and a stalled connection times out. */
    static void download(URI uri,Path path,long size,String hash,Consumer<String> status,String label,String offline)throws Exception{
        HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);
        try{int code=c.getResponseCode();if(code!=200)throw new IOException(offline+" (HTTP "+code+")");try(InputStream in=c.getInputStream()){install(in,path,size,hash,status,label);}}
        catch(SocketException|SocketTimeoutException|UnknownHostException|EOFException|javax.net.ssl.SSLException x){throw new IOException(offline,x);}
        finally{c.disconnect();}
    }
    private static void install(InputStream in,Path path,long size,String hash,Consumer<String> status,String label)throws Exception{
        if(size<1||size>512L*1024*1024||!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid runtime manifest.");
        Path temp=Files.createTempFile(path.getParent(),".prepare-",".part");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0,last=0;
        try{try(OutputStream out=Files.newOutputStream(temp)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Source preparation cancelled.");total+=n;if(total>size)throw new IOException("Runtime download exceeded its expected size.");out.write(b,0,n);digest.update(b,0,n);if(total-last>2*1024*1024){last=total;status.accept(label+" · "+(100*total/size)+"%");}}}
            if(total!=size||!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("The runtime download did not pass verification. Retry to get a fresh copy.");
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
            VERIFIED.add(key(path,hash));
        }finally{Files.deleteIfExists(temp);}
    }
}
