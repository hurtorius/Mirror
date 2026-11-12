package org.hurtorius.mirror.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Extracts a hash-verified browser ZIP, preserving macOS framework links without following them. */
public final class BrowserArchive {
    private BrowserArchive(){}
    public static void extract(Path archive,Path root)throws IOException{
        root=root.toAbsolutePath().normalize();Files.createDirectories(root);AtomicFile.rejectLinks(root);
        Map<String,Integer> modes=modes(archive);Map<Path,String> links=new LinkedHashMap<>();long total=0;int count=0;
        try(ZipFile zip=new ZipFile(archive.toFile())){
            var entries=zip.entries();
            while(entries.hasMoreElements()){
                var entry=entries.nextElement();if(++count>20000)throw new IOException("Browser archive has too many files.");
                Path path=safe(root,entry.getName());int mode=modes.getOrDefault(entry.getName(),0);
                if(entry.isDirectory()){Files.createDirectories(path);AtomicFile.rejectLinks(path);continue;}
                Files.createDirectories(path.getParent());AtomicFile.rejectLinks(path);
                if((mode&0170000)==0120000){
                    try(var in=zip.getInputStream(entry)){byte[] data=in.readNBytes(4097);if(data.length>4096)throw new IOException("Invalid browser link.");links.put(path,new String(data,StandardCharsets.UTF_8));}
                    continue;
                }
                try(var in=zip.getInputStream(entry);var out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){
                    byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;){total+=n;if(total>2L*1024*1024*1024)throw new IOException("Browser archive exceeds its size limit.");out.write(bytes,0,n);}
                }
                if((mode&0111)!=0&&!path.toFile().setExecutable(true,false)&&!RuntimePlatform.current().startsWith("windows"))throw new IOException("Cannot enable the browser executable.");
            }
        }
        for(var link:links.entrySet()){
            Path value=Path.of(link.getValue());if(value.isAbsolute()||!link.getKey().getParent().resolve(value).normalize().startsWith(root))throw new IOException("Invalid browser link destination.");
            AtomicFile.rejectLinks(link.getKey().getParent());Files.createSymbolicLink(link.getKey(),value);
        }
        // The text check above cannot see a chain through another archive link; check where each link really leads.
        Path real=root.toRealPath();
        for(Path link:links.keySet())try{if(!link.toRealPath().startsWith(real))throw new IOException("Invalid browser link destination.");}catch(NoSuchFileException dangling){/* A dangling link cannot be followed out of the folder. */}
    }
    public static Path safe(Path root,String name)throws IOException{
        if(name.indexOf('\\')>=0||name.indexOf(':')>=0)throw new IOException("Invalid browser archive path.");
        Path entry=Path.of(name);Path path=root.resolve(entry).normalize();if(entry.isAbsolute()||!path.startsWith(root)||path.equals(root))throw new IOException("Browser archive escaped its folder.");return path;
    }
    private static Map<String,Integer> modes(Path archive)throws IOException{
        Map<String,Integer> result=new HashMap<>();
        try(RandomAccessFile file=new RandomAccessFile(archive.toFile(),"r")){
            long end=file.length()-22,min=Math.max(0,end-65535);boolean found=false;
            for(;end>=min;end--){file.seek(end);if(Integer.reverseBytes(file.readInt())==0x06054b50){found=true;break;}}
            if(!found)throw new IOException("Invalid browser archive directory.");
            file.seek(end+10);int count=Short.toUnsignedInt(Short.reverseBytes(file.readShort()));file.seek(end+16);long offset=Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
            if(count==65535||offset==0xffffffffL)throw new IOException("Unsupported browser archive directory.");file.seek(offset);
            for(int i=0;i<count;i++){
                long start=file.getFilePointer();if(Integer.reverseBytes(file.readInt())!=0x02014b50)throw new IOException("Invalid browser archive entry.");
                file.seek(start+28);int name=Short.toUnsignedInt(Short.reverseBytes(file.readShort())),extra=Short.toUnsignedInt(Short.reverseBytes(file.readShort())),comment=Short.toUnsignedInt(Short.reverseBytes(file.readShort()));
                file.seek(start+38);int attrs=Integer.reverseBytes(file.readInt());file.seek(start+46);byte[] bytes=new byte[name];file.readFully(bytes);result.put(new String(bytes,StandardCharsets.UTF_8),attrs>>>16);file.seek(start+46L+name+extra+comment);
            }
        }
        return result;
    }
}
