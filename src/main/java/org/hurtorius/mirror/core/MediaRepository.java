package org.hurtorius.mirror.core;

import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** World-owned blobs, never executable imports. A transfer has a fixed byte budget. */
public final class MediaRepository {
    public static final int CHUNK=28000;
    public static final long MAX_FILE=512L*1024*1024;
    private final Path root;
    private final Map<String,Upload> uploads=new HashMap<>();
    private static final class Upload {
        String id,owner,name,folder,kind;Path temp;long expected,received,last;
        OutputStream out;MessageDigest digest;
    }
    public MediaRepository(Path root)throws IOException{this.root=root.toAbsolutePath().normalize();AtomicFile.rejectLinks(this.root);Files.createDirectories(this.root);}
    public Path path(String hash)throws IOException{if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid media fingerprint.");return AtomicFile.child(root,hash);}
    public String begin(String owner,String name,String folder,String kind,long bytes,long quota,long used,long now)throws IOException{
        ScreenSpec.uuidOrEmpty(owner);ScreenSpec.text(name,128,"Media name");ScreenSpec.text(folder,64,"Folder");
        if(bytes<1||bytes>MAX_FILE||!Set.of("image","video","audio","pdf","subtitle").contains(kind)||kind.equals("subtitle")&&bytes>262144)throw new IOException("Choose media under 512 MB, or subtitles under 256 KB.");
        if(uploads.values().stream().anyMatch(u->u.owner.equals(owner)))throw new IOException("Finish the current import first.");
        long reserved=uploads.values().stream().mapToLong(u->u.expected).sum();if(used+reserved+bytes>quota)throw new IOException("This import would exceed the world's media limit.");
        if(Files.getFileStore(root).getUsableSpace()<bytes+64L*1024*1024)throw new IOException("There is not enough disk space for this import.");
        Upload u=new Upload();u.id=UUID.randomUUID().toString();u.owner=owner;u.name=name;u.folder=folder;u.kind=kind;u.expected=bytes;u.last=now;
        u.temp=Files.createTempFile(root,".upload-",".part");u.out=Files.newOutputStream(u.temp);
        try{u.digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        uploads.put(u.id,u);return u.id;
    }
    public WorldStore.Media chunk(String id,String owner,long offset,byte[] bytes,long now)throws IOException{
        Upload u=uploads.get(id);if(u==null||!u.owner.equals(owner))throw new IOException("The import session is no longer available.");
        if(offset!=u.received||bytes.length==0||bytes.length>CHUNK||u.received+bytes.length>u.expected){cancel(id);throw new IOException("The import arrived out of order; select the file again.");}
        try{u.out.write(bytes);u.digest.update(bytes);u.received+=bytes.length;u.last=now;
            if(u.received!=u.expected)return null;
            u.out.close();if(!signature(u.temp,u.kind))throw new IOException("The file's contents do not match this media type.");
            String hash=HexFormat.of().formatHex(u.digest.digest());Path target=path(hash);
            if(!Files.exists(target)){try{Files.move(u.temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(u.temp,target);}}
            else Files.deleteIfExists(u.temp);
            uploads.remove(id);return new WorldStore.Media(UUID.randomUUID().toString(),hash,u.name,u.folder,u.kind,u.expected,owner);
        }catch(IOException e){cancel(id);throw e;}
    }
    public byte[] read(String hash,long offset,int count)throws IOException{
        if(offset<0||count<1||count>CHUNK)throw new IOException("Invalid media request.");Path p=path(hash);
        try(var file=new RandomAccessFile(p.toFile(),"r")){if(offset>=file.length())return new byte[0];file.seek(offset);byte[] data=new byte[(int)Math.min(count,file.length()-offset)];file.readFully(data);return data;}
    }
    public WorldStore.Media storeBoard(String id,String owner,String name,byte[] jpeg,long available)throws IOException{
        if(!FrameAssembler.validJpeg(jpeg)||jpeg.length>240000||jpeg.length>available)throw new IOException("The drawing exceeds the world's available media space.");
        try{
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
            Path target=path(hash);if(!Files.exists(target))AtomicFile.write(target,jpeg);
            return new WorldStore.Media(id.isEmpty()?UUID.randomUUID().toString():id,hash,ScreenSpec.text(name,128,"Drawing name"),"Whiteboards","image",jpeg.length,owner);
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public void expire(long now)throws IOException{for(String id:new ArrayList<>(uploads.keySet()))if(now-uploads.get(id).last>60000)cancel(id);}
    public void disconnect(String owner)throws IOException{for(String id:new ArrayList<>(uploads.keySet()))if(uploads.get(id).owner.equals(owner))cancel(id);}
    public void cancel(String id)throws IOException{Upload u=uploads.remove(id);if(u!=null){try{u.out.close();}finally{Files.deleteIfExists(u.temp);}}}
    public void close()throws IOException{for(String id:new ArrayList<>(uploads.keySet()))cancel(id);}
    public void tidy(Set<String> referenced)throws IOException{
        try(var files=Files.list(root)){for(Path p:files.toList())if(p.getFileName().toString().matches("[a-f0-9]{64}")&&!referenced.contains(p.getFileName().toString())){AtomicFile.rejectLinks(p);Files.delete(p);}}
    }
    static boolean signature(Path file,String kind)throws IOException{
        if(kind.equals("subtitle")){try{Subtitles.parse(Files.readString(file));return true;}catch(RuntimeException e){return false;}}
        byte[] h;try(InputStream in=Files.newInputStream(file)){h=in.readNBytes(32);}
        if(h.length<4)return false;
        boolean png=h.length>=8&&h[0]==(byte)137&&h[1]==80&&h[2]==78&&h[3]==71;
        boolean jpg=h[0]==(byte)255&&h[1]==(byte)216&&h[2]==(byte)255;
        String ascii=new String(h,java.nio.charset.StandardCharsets.ISO_8859_1);
        return switch(kind){case "image"->png||jpg||ascii.startsWith("GIF8")||(ascii.startsWith("RIFF")&&ascii.contains("WEBP"));case "pdf"->ascii.startsWith("%PDF-");case "video"->ascii.contains("ftyp")||(h[0]==26&&h[1]==69&&h[2]==(byte)223&&h[3]==(byte)163)||(ascii.startsWith("RIFF")&&ascii.contains("AVI"))||ascii.startsWith("OggS");case "audio"->ascii.startsWith("ID3")||h[0]==(byte)255&&(h[1]&224)==224||ascii.startsWith("fLaC")||ascii.startsWith("OggS")||(ascii.startsWith("RIFF")&&ascii.contains("WAVE"))||ascii.contains("ftyp");default->false;};
    }
}
