package org.hurtorius.mirror.core;

import java.io.*;
import java.util.*;

/** Bounded latest-frame assembly. Old or interleaved frames cannot grow a queue. */
public final class FrameAssembler {
    private long number=-1,started;
    private int count,total;
    private byte[][] chunks;
    public byte[] add(long sequence,int index,int parts,byte[] bytes,long now){
        if(sequence<0||parts<1||parts>9||index<0||index>=parts||bytes==null||bytes.length<1||bytes.length>28000)return null;
        if(sequence<number)return null;
        if(sequence!=number){number=sequence;started=now;count=0;total=0;chunks=new byte[parts][];}
        if(now-started>2000||chunks==null||chunks.length!=parts||chunks[index]!=null)return null;
        total+=bytes.length;if(total>240000){chunks=null;return null;}
        chunks[index]=bytes.clone();if(++count!=parts)return null;
        byte[] complete=new byte[total];int offset=0;for(byte[] part:chunks){System.arraycopy(part,0,complete,offset,part.length);offset+=part.length;}
        chunks=null;return complete;
    }
    public static boolean validJpeg(byte[] bytes){
        if(bytes==null||bytes.length<4||bytes.length>240000||bytes[0]!=(byte)255||bytes[1]!=(byte)216)return false;
        // Bound dimensions without creating an ImageIO reader on a game/server thread.
        for(int at=2;at+3<bytes.length;){
            if((bytes[at++]&255)!=255)return false;
            while(at<bytes.length&&(bytes[at]&255)==255)at++;
            if(at>=bytes.length)return false;
            int marker=bytes[at++]&255;
            if(marker==0||marker==0xd9||marker==0xda)return false;
            if(marker==0x01||marker>=0xd0&&marker<=0xd7)continue;
            if(at+2>bytes.length)return false;
            int length=(bytes[at]&255)<<8|(bytes[at+1]&255);
            if(length<2||length>bytes.length-at)return false;
            if(marker>=0xc0&&marker<=0xcf&&marker!=0xc4&&marker!=0xc8&&marker!=0xcc){
                if(length<8||bytes[at+2]!=8)return false;
                int height=(bytes[at+3]&255)<<8|(bytes[at+4]&255),width=(bytes[at+5]&255)<<8|(bytes[at+6]&255);
                return width>=16&&height>=16&&width<=1920&&height<=1920&&(long)width*height<=2073600;
            }
            at+=length;
        }
        return false;
    }
}
