package org.hurtorius.mirror.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import static java.nio.file.StandardOpenOption.*;

public final class AtomicFile {
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
    public static void rejectLinks(Path path)throws IOException{for(Path p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Mirror storage cannot contain symbolic links.");}
    public static Path child(Path root,String name)throws IOException{
        if(name==null||!name.matches("[a-zA-Z0-9._+-]{1,128}")||name.equals(".")||name.equals(".."))throw new IOException("Invalid storage name.");
        Path path=root.toAbsolutePath().normalize().resolve(name);rejectLinks(path);return path;
    }
}
