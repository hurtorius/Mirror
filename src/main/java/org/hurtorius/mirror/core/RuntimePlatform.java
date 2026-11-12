package org.hurtorius.mirror.core;
import java.util.Locale;
public final class RuntimePlatform {
    private RuntimePlatform(){}
    public static String current(){return of(System.getProperty("os.name"),System.getProperty("os.arch"));}
    public static String of(String os,String arch){
        os=os.toLowerCase(Locale.ROOT);arch=arch.toLowerCase(Locale.ROOT);
        String family=os.contains("windows")?"windows":os.contains("mac")||os.contains("darwin")?"macosx":os.contains("linux")?"linux":"unsupported";
        String cpu=arch.equals("amd64")||arch.equals("x86_64")?"x86_64":arch.equals("aarch64")||arch.equals("arm64")?"arm64":"unsupported";
        return family+"-"+cpu;
    }
}
