package org.hurtorius.mirror.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import static java.nio.file.StandardOpenOption.*;

public final class AtomicFile {
    private static final java.util.regex.Pattern NAME=java.util.regex.Pattern.compile("[a-zA-Z0-9._+-]{1,128}");
    private AtomicFile(){}
    public static void write(Path path,byte[] data)throws IOException{
        Path parent=path.toAbsolutePath().normalize().getParent();Files.createDirectories(parent);
        rejectLinks(parent);rejectLinks(path);
        Path temp=Files.createTempFile(parent,".mirror-",".tmp");
        try{try(FileChannel c=FileChannel.open(temp,WRITE)){var bytes=java.nio.ByteBuffer.wrap(data);while(bytes.hasRemaining())c.write(bytes);c.force(true);}
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    public static void rejectLinks(Path path)throws IOException{for(Path p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())if(Files.isSymbolicLink(p)&&!systemAlias(p))throw new IOException("Mirror storage cannot contain symbolic links.");}
    private static boolean systemAlias(Path path)throws IOException{
        // macOS supplies these root-owned aliases; links inside user storage remain forbidden.
        if(!RuntimePlatform.current().startsWith("macosx-")||path.getNameCount()!=1||path.getRoot()==null)return false;
        String name=path.getFileName().toString();if(!java.util.Set.of("var","tmp","etc").contains(name))return false;
        Path target=path.getParent().resolve(Files.readSymbolicLink(path)).normalize();
        return Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).getName().equals("root")&&target.equals(Path.of("/private",name))&&!Files.isSymbolicLink(target)&&!Files.isSymbolicLink(target.getParent());
    }
    public static Path child(Path root,String name)throws IOException{
        if(name==null||!NAME.matcher(name).matches()||name.equals(".")||name.equals(".."))throw new IOException("Invalid storage name.");
        Path path=root.toAbsolutePath().normalize().resolve(name);rejectLinks(path);return path;
    }
}
