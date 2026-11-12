package org.hurtorius.mirror.core;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** World-owned blobs, never executable imports. A transfer has a fixed byte budget. */
public final class MediaRepository {
    public static final int CHUNK=28000;
    /** Chunks an importer sends per acknowledgement; the server acknowledges only batch ends. */
    public static final int BATCH=4;
    public static final long MAX_FILE=512L*1024*1024;
    public static final Set<String> KINDS=Set.of("image","video","audio","pdf","subtitle");
    public static final Pattern HASH=Pattern.compile("[a-f0-9]{64}");
    private final Path root;
    // Written on the server thread; a complete upload is sealed on the media thread, so the map is concurrent.
    private final Map<String,Upload> uploads=new ConcurrentHashMap<>();
    private final Set<String> superseded=new HashSet<>();
    private static final class Upload {
        String id,owner,name,folder,kind,hash;Path temp;long expected,received,last;boolean complete;
        FileChannel channel;OutputStream out;MessageDigest digest;
    }
    public MediaRepository(Path root)throws IOException{this.root=root.toAbsolutePath().normalize();AtomicFile.rejectLinks(this.root);Files.createDirectories(this.root);}
    public Path path(String hash)throws IOException{if(hash==null||!HASH.matcher(hash).matches())throw new IOException("Invalid media fingerprint.");return AtomicFile.child(root,hash);}
    /** A complete stored file, found with one attribute read and no ancestor walk because this folder was checked when it opened; a link in the file's place is refused. A singleplayer host asks every tick. */
    public Path stored(String hash,long bytes){if(hash==null||!HASH.matcher(hash).matches())return null;Path p=root.resolve(hash);try{var file=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return file.isRegularFile()&&file.size()==bytes?p:null;}catch(IOException e){return null;}}
    public String begin(String owner,String name,String folder,String kind,long bytes,long quota,long used,long now)throws IOException{
        ScreenSpec.uuidOrEmpty(owner);ScreenSpec.text(name,128,"Media name");ScreenSpec.text(folder,64,"Folder");
        if(bytes<1||bytes>MAX_FILE||!KINDS.contains(kind)||kind.equals("subtitle")&&bytes>262144)throw new IOException("Choose media under 512 MB, or subtitles under 256 KB.");
        if(uploads.values().stream().anyMatch(u->u.owner.equals(owner)))throw new IOException("Finish the current import first.");
        long reserved=uploads.values().stream().mapToLong(u->u.expected).sum();if(used+reserved+bytes>quota)throw new IOException("This import would exceed the world's media limit.");
        if(Files.getFileStore(root).getUsableSpace()<bytes+64L*1024*1024)throw new IOException("There is not enough disk space for this import.");
        Upload u=new Upload();u.id=UUID.randomUUID().toString();u.owner=owner;u.name=name;u.folder=folder;u.kind=kind;u.expected=bytes;u.last=now;
        u.temp=Files.createTempFile(root,".upload-",".part");u.channel=FileChannel.open(u.temp,StandardOpenOption.WRITE);u.out=Channels.newOutputStream(u.channel);
        try{u.digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        uploads.put(u.id,u);return u.id;
    }
    /** Writes one chunk in order; true once the last byte is in. The upload then belongs to seal() and store(): its owner leaving or a timeout no longer discards it. */
    public boolean write(String id,String owner,long offset,byte[] bytes,long now)throws IOException{
        Upload u=uploads.get(id);if(u==null||!u.owner.equals(owner)||u.complete)throw new IOException("The import session is no longer available.");
        if(offset!=u.received||bytes.length==0||bytes.length>CHUNK||u.received+bytes.length>u.expected){cancel(id);throw new IOException("The import arrived out of order; select the file again.");}
        try{u.out.write(bytes);u.digest.update(bytes);u.received+=bytes.length;u.last=now;}catch(IOException e){cancel(id);throw e;}
        u.complete=u.received==u.expected;return u.complete;
    }
    /** Forces a complete upload to disk and checks its contents. This is the slow part (up to 512 MB of fsync), so the server runs it off its own thread; a failure discards the upload. */
    public void seal(String id)throws IOException{
        Upload u=uploads.get(id);if(u==null||!u.complete)throw new IOException("The import session is no longer available.");
        try{u.channel.force(true);u.out.close();if(!signature(u.temp,u.kind))throw new IOException("The file's contents do not match this media type.");u.hash=HexFormat.of().formatHex(u.digest.digest());}catch(IOException e){cancel(id);throw e;}
    }
    /** Back on the server thread: a sealed upload takes its fingerprint name and becomes a library item. Done here, so a tidy can never delete a file the library is about to name. */
    public WorldStore.Media store(String id)throws IOException{
        Upload u=uploads.get(id);if(u==null||u.hash==null)throw new IOException("The import session is no longer available.");
        try{Path target=path(u.hash);
            if(!Files.exists(target)){try{Files.move(u.temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(u.temp,target);}}
            else Files.deleteIfExists(u.temp);
            uploads.remove(id);return new WorldStore.Media(UUID.randomUUID().toString(),u.hash,u.name,u.folder,u.kind,u.expected,u.owner);
        }catch(IOException e){cancel(id);throw e;}
    }
    /** Chunks of one stored file, read through a single open handle; a file shorter than a requested position is incomplete, never an empty chunk. */
    public List<byte[]> read(String hash,List<Long> offsets)throws IOException{
        List<byte[]> chunks=new ArrayList<>(offsets.size());
        try(var file=FileChannel.open(path(hash),StandardOpenOption.READ)){long length=file.size();
            for(long offset:offsets){if(offset<0)throw new IOException("Invalid media request.");if(offset>=length)throw new IOException("World media is incomplete.");ByteBuffer data=ByteBuffer.allocate((int)Math.min(CHUNK,length-offset));while(data.hasRemaining())if(file.read(data,offset+data.position())<0)throw new IOException("World media is incomplete.");chunks.add(data.array());}}
        return chunks;
    }
    public WorldStore.Media storeBoard(String id,String owner,String name,byte[] jpeg,long available)throws IOException{
        if(!FrameAssembler.validJpeg(jpeg)||jpeg.length>240000||jpeg.length>available)throw new IOException("The drawing exceeds the world's available media space.");
        try{
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
            Path target=path(hash);if(!Files.exists(target))AtomicFile.write(target,jpeg);
            return new WorldStore.Media(id.isEmpty()?UUID.randomUUID().toString():id,hash,ScreenSpec.text(name,128,"Drawing name"),"Whiteboards","image",jpeg.length,owner);
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    /** A file the live world stopped using, such as a redrawn board's previous drawing. It is deleted only after a saved world.json stops needing it. */
    public void supersede(String hash){superseded.add(hash);}
    /** The superseded files that a world.json saved with this library would not reference. */
    public Set<String> retiring(Collection<WorldStore.Media> library){Set<String> retired=new HashSet<>(superseded);for(var m:library)retired.remove(m.hash());return retired;}
    /** Once that world.json is on disk: deletes those files, unless the library has started using one again. */
    public void retire(Set<String> hashes,Collection<WorldStore.Media> library)throws IOException{
        Set<String> used=new HashSet<>();for(var m:library)used.add(m.hash());
        for(String hash:hashes)if(superseded.remove(hash)&&!used.contains(hash))Files.deleteIfExists(path(hash));
    }
    /** Imports still arriving; each will add one library item. */
    public int pendingCount(){return uploads.size();}
    public void expire(long now)throws IOException{for(var e:new ArrayList<>(uploads.entrySet()))if(!e.getValue().complete&&now-e.getValue().last>60000)cancel(e.getKey());}
    public void disconnect(String owner)throws IOException{for(var e:new ArrayList<>(uploads.entrySet()))if(!e.getValue().complete&&e.getValue().owner.equals(owner))cancel(e.getKey());}
    public void cancel(String id)throws IOException{Upload u=uploads.remove(id);if(u!=null){try{u.out.close();}finally{Files.deleteIfExists(u.temp);}}}
    public void close()throws IOException{for(String id:new ArrayList<>(uploads.keySet()))cancel(id);}
    public void tidy(Set<String> referenced)throws IOException{
        try(var files=Files.list(root)){for(Path p:files.toList())if(HASH.matcher(p.getFileName().toString()).matches()&&!referenced.contains(p.getFileName().toString())){AtomicFile.rejectLinks(p);Files.delete(p);}}
    }
    static boolean signature(Path file,String kind)throws IOException{
        if(kind.equals("subtitle")){try{Subtitles.parse(Subtitles.decode(Files.readAllBytes(file)));return true;}catch(RuntimeException e){return false;}}
        byte[] h;try(InputStream in=Files.newInputStream(file)){h=in.readNBytes(32);}
        if(h.length<4)return false;
        boolean png=h.length>=8&&h[0]==(byte)137&&h[1]==80&&h[2]==78&&h[3]==71;
        boolean jpg=h[0]==(byte)255&&h[1]==(byte)216&&h[2]==(byte)255;
        String ascii=new String(h,java.nio.charset.StandardCharsets.ISO_8859_1);
        return switch(kind){case "image"->png||jpg||ascii.startsWith("GIF8")||(ascii.startsWith("RIFF")&&ascii.contains("WEBP"));case "pdf"->ascii.startsWith("%PDF-");case "video"->ascii.contains("ftyp")||(h[0]==26&&h[1]==69&&h[2]==(byte)223&&h[3]==(byte)163)||(ascii.startsWith("RIFF")&&ascii.contains("AVI"))||ascii.startsWith("OggS");case "audio"->ascii.startsWith("ID3")||h[0]==(byte)255&&(h[1]&224)==224||ascii.startsWith("fLaC")||ascii.startsWith("OggS")||(ascii.startsWith("RIFF")&&ascii.contains("WAVE"))||ascii.contains("ftyp");default->false;};
    }
}
