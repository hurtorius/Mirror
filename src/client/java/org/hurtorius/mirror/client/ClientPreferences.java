package org.hurtorius.mirror.client;
import org.hurtorius.mirror.core.*;
import java.nio.file.*;
public final class ClientPreferences {
    public boolean hidden=false,reducedMotion=false,glow=true,positional=true;
    public double volume=.8;
    public int maxScreens=4,resolution=1280;
    public static ClientPreferences load(Path file){try{if(Files.isRegularFile(file)&&Files.size(file)<8192){ClientPreferences p=ScreenSpec.JSON.fromJson(Files.readString(file),ClientPreferences.class);if(p!=null&&Double.isFinite(p.volume)&&p.volume>=0&&p.volume<=1&&p.maxScreens>=1&&p.maxScreens<=8&&p.resolution>=320&&p.resolution<=1280)return p;}}catch(Exception ignored){}return new ClientPreferences();}
    public void save(Path file){try{AtomicFile.write(file,ScreenSpec.JSON.toJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){MirrorClient.notice("Your Mirror settings could not be saved.");}}
}
