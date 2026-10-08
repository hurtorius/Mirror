package org.hurtorius.mirror.client;

import com.google.gson.*;
import org.hurtorius.mirror.core.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public final class RuntimeInstaller {
    private RuntimeInstaller(){}
    public static synchronized List<Path> prepare(Path root,boolean browser,boolean media,Consumer<String> status)throws Exception{
        Files.createDirectories(root);AtomicFile.rejectLinks(root);List<Path> jars=new ArrayList<>();
        JsonArray embedded=resource("mirror/engine-manifest.json").getAsJsonArray();
        for(JsonElement e:embedded){JsonObject entry=e.getAsJsonObject();String name=entry.get("name").getAsString();Path path=AtomicFile.child(root,name);long size=entry.get("bytes").getAsLong();String digest=entry.get("sha256").getAsString();
            if(!valid(path,size,digest)){try(InputStream in=RuntimeInstaller.class.getClassLoader().getResourceAsStream("mirror/engine/"+name)){if(in==null)throw new IOException("A bundled Mirror component is missing. Reinstall the mod.");install(in,path,size,digest,status,name);}}
            if(name.endsWith(".jar"))jars.add(path);
        }
        String platform=platform();JsonObject natives=resource("mirror/native-lock.json").getAsJsonObject();
        if(!natives.has(platform))throw new IOException("Mirror's native sources support Windows x64, Linux x64, and macOS x64/Apple Silicon.");
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
        for(JsonElement e:natives.getAsJsonArray(platform)){JsonObject entry=e.getAsJsonObject();String name=entry.get("name").getAsString();if(name.startsWith("jcef")&&(!browser||platform.startsWith("windows")))continue;if(!name.startsWith("jcef")&&!media)continue;
            Path path=AtomicFile.child(root,name);long size=entry.get("bytes").getAsLong();String digest=entry.get("sha256").getAsString();
            if(!valid(path,size,digest)){if(Files.getFileStore(root).getUsableSpace()<size+256L*1024*1024)throw new IOException("Mirror needs more free disk space to prepare this source.");
                InputStream bundled=RuntimeInstaller.class.getClassLoader().getResourceAsStream("mirror/native-bundles/"+name);if(bundled!=null){try(bundled){status.accept("Preparing Mirror's offline media support");install(bundled,path,size,digest,status,name);}jars.add(path);continue;}
                String url=entry.get("url").getAsString();URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||!"repo.maven.apache.org".equals(uri.getHost()))throw new IOException("Invalid runtime download location.");
                status.accept("Preparing "+(name.startsWith("jcef")?"browser":"media")+" · first use downloads "+(size/1024/1024)+" MB");
                HttpResponse<InputStream> response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(InputStream in=response.body()){if(response.statusCode()!=200)throw new IOException("Mirror's source download failed ("+response.statusCode()+"). Retry when the connection is available.");install(in,path,size,digest,status,name);}
            }
            jars.add(path);
        }
        status.accept("Mirror is ready");return jars;
    }
    private static JsonElement resource(String name)throws IOException{try(InputStream in=RuntimeInstaller.class.getClassLoader().getResourceAsStream(name)){if(in==null)throw new IOException("Mirror's runtime manifest is missing. Reinstall the mod.");return JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}
    private static boolean valid(Path path,long size,String hash)throws Exception{AtomicFile.rejectLinks(path);if(!Files.isRegularFile(path)||Files.size(path)!=size)return false;MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest()).equals(hash);}
    private static void install(InputStream in,Path path,long size,String hash,Consumer<String> status,String label)throws Exception{
        if(size<1||size>512L*1024*1024||!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid runtime manifest.");
        Path temp=Files.createTempFile(path.getParent(),".prepare-",".part");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0,last=0;
        try{try(OutputStream out=Files.newOutputStream(temp)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Source preparation cancelled.");total+=n;if(total>size)throw new IOException("Runtime download exceeded its expected size.");out.write(b,0,n);digest.update(b,0,n);if(total-last>2*1024*1024){last=total;status.accept("Preparing Mirror · "+(100*total/size)+"%");}}}
            if(total!=size||!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("The runtime download did not pass verification. Retry to get a fresh copy.");
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    public static String platform(){String os=System.getProperty("os.name").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);String family=os.contains("win")?"windows":os.contains("mac")?"macosx":"linux";return family+"-"+(arch.contains("aarch64")||arch.contains("arm64")?"arm64":"x86_64");}
}
